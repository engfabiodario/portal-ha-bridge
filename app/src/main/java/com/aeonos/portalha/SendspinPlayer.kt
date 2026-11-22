package com.aeonos.portalha

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.util.Log
import com.sendspin.protocol.AudioFormat
import com.sendspin.protocol.ArtworkChannel
import com.sendspin.protocol.ClientPreferences
import com.sendspin.protocol.DiscoveryService
import com.sendspin.protocol.GroupPlaybackState
import com.sendspin.protocol.JsonOptional
import com.sendspin.protocol.JsonOptionalAdapterFactory
import com.sendspin.protocol.OptionalRole
import com.sendspin.protocol.SendSpinClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * server/state carries only what CHANGED: an absent field means "unchanged", while a present
 * null means "cleared". Treating absent as empty blanks the title/artist on any partial update,
 * which churns the track identity and throws away the lyrics — so merge against what we had.
 */
private fun JsonOptional<String>.merge(previous: String): String = when (this) {
    is JsonOptional.Present -> value.orEmpty()
    else -> previous
}

/**
 * Sendspin player — the Portal as a synchronised multi-room speaker.
 *
 * Where DLNA makes each Portal an island, Sendspin (Open Home Foundation) is a push-based PCM
 * protocol built for group playback: Music Assistant keeps every member in step via a shared
 * clock. It also pushes track metadata and album artwork down the same connection, so the
 * now-playing overlay no longer needs to poll Home Assistant to find out what's playing.
 *
 * We take the client-initiated path: browse mDNS for a Sendspin server and connect to it.
 */
private class PrefsSettingsStore(ctx: Context) : com.sendspin.protocol.ClientSettingsStore {
    private val sp = ctx.getSharedPreferences("sendspin_settings", Context.MODE_PRIVATE)
    override fun getInt(key: String, default: Int): Int = sp.getInt(key, default)
    override fun putInt(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
    override fun getString(key: String, default: String?): String? = sp.getString(key, default)
    override fun putString(key: String, value: String) { sp.edit().putString(key, value).apply() }
}

class SendspinPlayer(
    private val context: Context,
    private val deviceName: () -> String,
    private val serverUrl: () -> String = { "" },
    // Stable Sendspin client id. Without it the library picks a random UUID on every start, so each
    // app restart or reboot shows up in Music Assistant as a NEW player (stale duplicates, and HA's
    // media_player points at a dead one). Blank = library default (random).
    private val clientId: () -> String = { "" },
    // fleet (2026-11-15, net diet): ask for album art only when something shows it (the now-playing overlay). Off =
    // no ARTWORK role in the hello, so Music Assistant sends no JPEG per track to this Portal.
    private val wantArtwork: () -> Boolean = { true },
) {
    private val settingsStore: com.sendspin.protocol.ClientSettingsStore by lazy { PrefsSettingsStore(context) }

    private companion object { const val TAG = "PortalHA" }

    private var scope: CoroutineScope? = null
    private var client: SendSpinClient? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectJob: Job? = null
    private var idleJob: Job? = null
    @Volatile private var audioPlayer: SendspinAudioPlayer? = null
    @Volatile private var idle: IdleThrottle? = null

    /**
     * fleet (2026-11-15, net diet): sendspin-jvm 0.3.4 sends a clock burst (8 x client/time, 200 ms apart) every 10 s and
     * a client/state every 5 s for as long as it is connected - playing or not (~2 messages/s each way per Portal, all
     * day). While NO stream runs this throttle stops the library's two loops (its private clockJob / stateJob, by
     * reflection - minify is off and the jar is pinned) and drives them itself: one library burst every 60 s, one
     * client/state every 60 s (state changes - volume, mute - are still sent by the library at once).
     * Whenever a stream is active the library's OWN loops run untouched (startClockSync / startPeriodicStateReports are
     * re-invoked: a burst goes out at once, then the stock 10 s / 5 s cadence), so sync while playing is identical.
     * Stock cadence also for the first 60 s after each server hello and 30 s after a stream ends. Any reflection
     * failure = the throttle switches itself off for good (library default behaviour).
     */
    private inner class IdleThrottle(private val c: SendSpinClient, private val scope: CoroutineScope) {
        private val cls = SendSpinClient::class.java
        @Volatile private var failed = false
        @Volatile private var throttled = false
        @Volatile private var streaming = false
        @Volatile private var lastActiveMs = android.os.SystemClock.elapsedRealtime()
        @Volatile private var lastHelloMs = android.os.SystemClock.elapsedRealtime()
        private var lastBurstMs = 0L
        private var lastStateMs = 0L
        private var loop: Job? = null

        private fun now() = android.os.SystemClock.elapsedRealtime()

        private fun call(name: String) {
            cls.getDeclaredMethod(name).apply { isAccessible = true }.invoke(c)
        }
        private fun cancelJob(field: String) {
            (cls.getDeclaredField(field).apply { isAccessible = true }.get(c) as? Job)?.cancel()
        }
        private fun jobActive(field: String): Boolean =
            (cls.getDeclaredField(field).apply { isAccessible = true }.get(c) as? Job)?.isActive == true
        private inline fun guarded(what: String, block: () -> Unit) {
            if (failed) return
            runCatching(block).onFailure {
                failed = true; throttled = false
                Log.w(TAG, "sendspin: idle throttle off ($what: ${it.javaClass.simpleName}: ${it.message}) - library cadence")
                runCatching { call("startClockSync"); call("startPeriodicStateReports") }
            }
        }

        fun start() {
            loop = scope.launch {
                while (true) {
                    delay(2_000)
                    tick()
                }
            }
        }

        /** Library state changes: CLOCK_SYNCING = a fresh server hello (the library restarted its loops itself). */
        fun onState(st: Any?) {
            if (st?.toString() == "CLOCK_SYNCING") { lastHelloMs = now(); throttled = false }
        }

        /** stream/start (true) / stream/end (false), from the audio player - called on the library's thread. */
        fun onStream(on: Boolean) {
            streaming = on
            lastActiveMs = now()
            if (on && throttled) restore("stream start")
            // fleet (2026-11-16, review): a cancel that raced a reconnect (tick / burst end vs the library's fresh hello)
            // can leave throttled=false with the library's clock job dead - a stream would then run with no clock sync.
            else if (on && !failed) synchronized(this) {
                guarded("stream check") {
                    if (!jobActive("clockJob")) { call("startClockSync"); Log.i(TAG, "sendspin: clock sync was not running at stream start - restarted") }
                    if (!jobActive("stateJob")) call("startPeriodicStateReports")
                }
            }
        }

        @Synchronized private fun restore(why: String) {
            if (!throttled) return
            guarded("restore") {
                call("startClockSync")            // cancels any job of ours' burst, sends a burst now, then every 10 s
                call("startPeriodicStateReports")
                throttled = false
                Log.i(TAG, "sendspin: library clock sync + state reports back to 10 s / 5 s ($why)")
            }
        }

        @Synchronized private fun tick() {
            if (failed || client !== c) return
            val t = now()
            val active = streaming || t - lastActiveMs < 30_000L || t - lastHelloMs < 60_000L
            if (active) { if (throttled) restore("active"); return }
            if (!throttled) {
                guarded("throttle") {
                    cancelJob("clockJob"); cancelJob("stateJob")
                    throttled = true; lastBurstMs = t; lastStateMs = t
                    Log.i(TAG, "sendspin: idle - clock burst + client/state every 60 s (library: 10 s / 5 s)")
                }
                return
            }
            if (t - lastStateMs >= 60_000L) { lastStateMs = t; guarded("state") { call("sendClientState") } }
            if (t - lastBurstMs >= 60_000L) {
                lastBurstMs = t
                guarded("burst") { call("startClockSync") }   // one stock burst (8 probes in 1.6 s) ...
                scope.launch {
                    delay(2_500)
                    synchronized(this@IdleThrottle) { if (throttled && !failed) guarded("burst end") { cancelJob("clockJob") } }
                }
            }
        }

        fun stop() { loop?.cancel(); loop = null }
    }
    private var focusRequest: AudioFocusRequest? = null

    // fleet (2026-11-22, review b6-08): two mute sources - the Bridge's own hooks (call, intercom, assistant turn) and
    // audio focus (a transient loss) - combined, so one can not lift the other's mute; plus a watchdog for a stale one.
    @Volatile private var hookMuted = false
    @Volatile private var focusMuted = false
    @Volatile private var mutedSinceMs = 0L
    @Volatile private var focusHeld = false

    private fun applyMute() {
        val m = hookMuted || focusMuted
        mutedSinceMs = if (!m) 0L else if (mutedSinceMs == 0L) android.os.SystemClock.elapsedRealtime() else mutedSinceMs
        audioPlayer?.muteForSystem(m)
    }

    /**
     * Silence playback while a call / Alexa turn / the intercom needs the speaker, without
     * dropping our place in the group stream. See SendspinAudioPlayer.muteForSystem.
     */
    fun muteForSystem(muted: Boolean) { hookMuted = muted; applyMute() }

    /**
     * fleet (2026-11-22, review b6-08): a mute older than 2 min while nothing that mutes us is active ([busy] = call,
     * ringing, assistant turn, intercom) is stale (a missed GAIN / end hook): lifted and logged. BridgeService ticks it.
     */
    fun muteWatchdog(busy: Boolean) {
        val since = mutedSinceMs
        if (since == 0L || busy || android.os.SystemClock.elapsedRealtime() - since < 120_000L) return
        Log.w(TAG, "sendspin: mute ${(android.os.SystemClock.elapsedRealtime() - since) / 1000} s old with no call / intercom / assistant turn - lifted (hook=$hookMuted focus=$focusMuted)")
        hookMuted = false; focusMuted = false
        applyMute()
    }

    /** fleet (2026-11-22, review b6-08): stream / track start - take the focus again if something took it for good. */
    private fun takeFocus(why: String) {
        if (focusHeld) return
        val req = focusRequest ?: return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val r = runCatching { audio.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "sendspin: audio focus ${if (focusHeld) "taken" else "refused ($r)"} ($why)")
    }

    private fun releaseFocus(why: String) {
        if (!focusHeld) return
        val req = focusRequest ?: return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { audio.abandonAudioFocusRequest(req) }
        focusHeld = false
        if (focusMuted) { focusMuted = false; applyMute() }
        Log.i(TAG, "sendspin: audio focus given up ($why)")
    }

    // -- Music link health (fleet 2026-11-22, review b6-06): sensor.<slug>_music_link + self-heal 'music' --
    @Volatile var libState = "IDLE"
        private set
    @Volatile private var libStateSinceMs = android.os.SystemClock.elapsedRealtime()
    /** elapsedRealtime since the link is NOT connected (0 = connected). */
    @Volatile private var downSinceMs = android.os.SystemClock.elapsedRealtime()
    @Volatile var drops = 0
        private set
    @Volatile private var everConnected = false
    /** Called (library thread) when the link state changes. */
    @Volatile var onLinkChange: (() -> Unit)? = null

    private fun connectedState(s: String) = s == "STREAMING" || s == "CLOCK_SYNCING"

    /** ms the link has not been connected (0 = connected now). */
    fun downForMs(): Long { val d = downSinceMs; return if (d == 0L) 0L else android.os.SystemClock.elapsedRealtime() - d }

    /** streaming (audio playing) / idle (connected, nothing playing) / connecting / down. */
    fun linkClass(): String = when {
        downSinceMs == 0L && audioPlayer?.isPlaying == true -> "streaming"
        downSinceMs == 0L -> "idle"
        libState == "CONNECTING" || libState == "HANDSHAKING" -> "connecting"
        else -> "down"
    }

    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

    fun linkAttributesJson(): String {
        val c = client
        val cs = runCatching { c?.clockSync }.getOrNull()
        val rtt = runCatching { cs?.lastRttMicros }.getOrNull() ?: 0L
        val off = runCatching { cs?.lastOffsetMicros }.getOrNull() ?: 0.0
        val buf = runCatching { c?.audioBuffer?.size }.getOrNull() ?: 0
        val fmt = runCatching { c?.streamFormat?.value }.getOrNull()
        val sinceS = (android.os.SystemClock.elapsedRealtime() - libStateSinceMs) / 1000
        val rep = SendspinAudioPlayer.lastReportWallMs
        return "{\"library_state\":\"$libState\",\"state_for_s\":$sinceS,\"drops\":$drops," +
            "\"sync_err_ms\":${f1(SendspinAudioPlayer.lastErrMs)},\"avg_err_ms\":${f1(SendspinAudioPlayer.avgErrMs)}," +
            "\"stats_age_s\":${if (rep == 0L) -1 else (System.currentTimeMillis() - rep) / 1000}," +
            "\"underruns\":${SendspinAudioPlayer.totUnderruns},\"trims\":${SendspinAudioPlayer.totTrims}," +
            "\"pads\":${SendspinAudioPlayer.totPads},\"soft\":${SendspinAudioPlayer.totSoft}," +
            "\"buffered_chunks\":$buf,\"out_ms\":${SendspinAudioPlayer.outMs},\"hw_ms\":${SendspinAudioPlayer.hwMs}," +
            "\"rtt_ms\":${f1(rtt / 1000.0)},\"offset_ms\":${f1(off / 1000.0)}," +
            "\"codec\":\"${fmt?.codec ?: ""}\",\"muted\":${hookMuted || focusMuted},\"focus\":$focusHeld}"
    }

    fun statusLine(): String = "music ${linkClass()} lib=$libState drops=$drops down=${downForMs() / 1000}s " +
        "err=${f1(SendspinAudioPlayer.avgErrMs)}ms underruns=${SendspinAudioPlayer.totUnderruns} " +
        "trims=${SendspinAudioPlayer.totTrims} pads=${SendspinAudioPlayer.totPads} muted=${hookMuted || focusMuted} focus=$focusHeld"

    // A phone call or another media app takes focus transiently and hands it back, so those are
    // safe to follow. A PERMANENT loss is not: Android never sends a matching GAIN afterwards, so
    // muting on it strands us silent forever — which is exactly what happened when falcon took
    // focus for an Alexa turn. BridgeService's explicit yield/reclaim hooks own that case.
    // fleet (2026-11-22, review b6-08): a permanent loss lifts a focus mute at once (transient, then permanent in one
    // turn = muted for good before) and marks the focus as lost; it is asked for again at the next stream / track start,
    // so the Bridge is back in the focus stack (voice turns duck it again) after YouTube or a live view took the focus.
    // The Bridge's own hook mutes (call, intercom, assistant turn) are kept apart (hookMuted) - focus never lifts them.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { focusHeld = true; focusMuted = false; applyMute() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { focusMuted = true; applyMute() }
            else -> {
                focusHeld = false
                val was = focusMuted
                focusMuted = false; applyMute()
                Log.i(TAG, "sendspin: permanent focus loss${if (was) " - focus mute lifted" else ""}; asked again at the next stream start")
            }
        }
    }

    // Running view of the track, since server/state only sends what changed.
    @Volatile private var curTitle = ""
    @Volatile private var curArtist = ""
    @Volatile private var curAlbum = ""
    @Volatile private var curPosMs = 0
    @Volatile private var curDurMs = 0
    @Volatile private var curCommands: List<String> = emptyList()
    @Volatile private var progressSeq = 0

    /** What Sendspin says is playing. Pushed to us — no polling, no separate artwork fetch. */
    data class Track(
        val title: String, val artist: String, val album: String,
        val playing: Boolean, val positionMs: Int, val durationMs: Int,
        /**
         * Bumped only when the server actually sent a new progress snapshot. Most state messages
         * carry none, and re-anchoring the position clock to a stale [positionMs] on those drags
         * playback backwards — which is what put the lyrics behind the music.
         */
        val progressSeq: Int,
    )

    /** Fired whenever the track details change. Null means nothing is playing. */
    var onTrack: ((Track?) -> Unit)? = null

    /** Fired when new album artwork arrives, as raw image bytes. */
    var onArtwork: ((ByteArray?) -> Unit)? = null

    /** Ask the server to change this player's volume (0–100). */
    fun setVolume(pct: Int) {
        runCatching { client?.sendControllerCommand("volume", volume = pct.coerceIn(0, 100)) }
    }

    // Transport goes over the same controller channel as volume — Music Assistant owns the queue
    // either way, but routing it here keeps it aimed at THIS player rather than having to resolve
    // the right media_player entity through Home Assistant.
    fun next() = sendCommand("next", "next_track", "skip_next")
    fun previous() = sendCommand("previous", "previous_track", "skip_previous")
    fun stopPlayback() = sendCommand("stop", "pause")

    /** Jump to [positionMs] within the current track (server advertises "seek"). */
    fun seekTo(positionMs: Int) {
        Log.i(TAG, "sendspin: seek to ${positionMs}ms")
        runCatching { client?.sendSeek(positionMs.toLong()) }
            .onFailure { Log.w(TAG, "sendspin: seek failed: ${it.message}") }
    }
    fun playPause(currentlyPlaying: Boolean) =
        if (currentlyPlaying) sendCommand("pause", "play_pause") else sendCommand("play", "play_pause")

    private fun emitTrack(playing: Boolean) = onTrack?.invoke(Track(
        title = curTitle, artist = curArtist, album = curAlbum,
        playing = playing, positionMs = curPosMs, durationMs = curDurMs, progressSeq = progressSeq))

    /** Send the first candidate the server says it supports (falling back to the first). */
    private fun sendCommand(vararg candidates: String) {
        val supported = curCommands
        val cmd = candidates.firstOrNull { supported.isEmpty() || it in supported } ?: candidates.first()
        Log.i(TAG, "sendspin: controller command '$cmd' (server supports $supported)")
        runCatching { client?.sendControllerCommand(cmd) }
            .onFailure { Log.w(TAG, "sendspin: command '$cmd' failed: ${it.message}") }
    }

    val isConnected: Boolean get() = client != null

    /** Whether this session's hello asked for artwork (a pref change needs a Sendspin restart to apply). */
    @Volatile var artworkRequested = true
        private set

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s

        // fleet (2026-11-15, net diet): the multicast lock is taken further down ONLY when mDNS discovery runs (no fixed
        // server URL). Held, it makes the Wi-Fi chip hand every multicast frame on the LAN to the CPU, screen off too.
        val fixedUrl = normalizeServerUrl(serverUrl())
        if (fixedUrl == null) {
            // mDNS needs multicast to survive Wi-Fi power saving, same as the DLNA/DIAL servers.
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("portalha-sendspin").apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
        val artwork = wantArtwork()
        artworkRequested = artwork

        val okHttp = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // long-lived streaming socket
            .build()
        // The protocol's optional fields are a sealed JsonOptional (absent vs present-null), which
        // needs its own factory — registered ahead of the reflective Kotlin adapter, per the
        // library's documented setup. A bare Moshi.Builder() fails on the first server/state.
        val moshi = Moshi.Builder()
            .add(JsonOptionalAdapterFactory())
            .addLast(KotlinJsonAdapterFactory())
            .build()

        // PCM only: the Portal has no spare headroom for decoding, and on a LAN the bandwidth
        // is irrelevant. Artwork is requested at a size that suits the now-playing overlay.
        val prefs = ClientPreferences(
            supportedFormats = listOf(
                AudioFormat(codec = "pcm", channels = 2, sampleRate = 44100, bitDepth = 16),
                AudioFormat(codec = "pcm", channels = 2, sampleRate = 48000, bitDepth = 16),
            ),
            artworkChannels = if (artwork) listOf(ArtworkChannel(source = "album", format = "jpeg")) else emptyList(),
            // NOT "set_static_delay": Music Assistant 2.10.5 (aiosendspin 10.0.0) rejects the whole
            // client/hello as malformed when it is listed (2026-10-10 pilot: endless reconnect loop,
            // player unavailable). The static-delay handler stays for a server that accepts it.
            playerSupportedCommands = listOf("volume", "mute"),
            // fleet (2026-11-22, review b6-02, PILOT): player buffer in KB from control/set 'sendspin_buffer_kb:<n>'
            // (0 = the library default 256 KB = 1.5 s PCM). Applies at the next Sendspin start (restart_service).
            playerBufferCapacity = context.getSharedPreferences("portal_ha", Context.MODE_PRIVATE)
                .getInt("sendspin_buffer_kb", 0).let { kb -> if (kb in 64..4096) kb * 1024 else 1024 * 1024 },   // fleet default 1 MB (6 s) since 11-22 (Dining Room pilot: 0 underruns, sync 1.4 ms)
            supportedOptionalRoles = if (artwork)
                setOf(OptionalRole.PLAYER, OptionalRole.METADATA, OptionalRole.ARTWORK, OptionalRole.CONTROLLER)
            else setOf(OptionalRole.PLAYER, OptionalRole.METADATA, OptionalRole.CONTROLLER),
        )

        val c = SendSpinClient(
            okHttpClient = okHttp,
            moshi = moshi,
            preferences = prefs,
            clientId = clientId().ifBlank { java.util.UUID.randomUUID().toString() },
            clientName = deviceName(),
            manufacturer = "Meta",
            productName = "Portal HA Bridge",
            softwareVersion = BuildConfig.VERSION_NAME,
            audioPlayerFactory = { buffer, clock ->
                SendspinAudioPlayer(buffer, clock).also { ap ->
                    audioPlayer = ap
                    ap.onActive = { on ->
                        idle?.onStream(on)
                        // fleet (2026-11-22, review b6-08): the focus is given up when the stream ends (asked again at the next).
                        if (!on) releaseFocus("stream end")
                    }
                    ap.onStreamBegin = { takeFocus("stream start") }
                    if (hookMuted || focusMuted) ap.muteForSystem(true)   // a new player keeps a mute that is on
                }
            },
            // fleet (2026-11-21, review b6-03): the library keeps the volume / mute Music Assistant set in this store and
            // restores + reports it at the next start. Without one (NoOp) every Bridge restart / install said 100 %.
            settingsStore = settingsStore,
        )
        client = c
        idle = IdleThrottle(c, s)
        // Building an AudioTrack takes 50-300 ms; ask the server to schedule a stream's first
        // chunk (start / join / seek) at least this far ahead so nothing is late at start.
        c.setRequiredLeadTimeMs(400)

        // Announce ourselves as media playback so the system ducks/notifies us appropriately.
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
            .also { focusHeld = runCatching { audio.requestAudioFocus(it) }.getOrDefault(0) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }

        // A fixed server URL skips discovery; the client reconnects on its own after drops.
        val fixed = fixedUrl
        if (fixed != null) {
            connectJob = s.launch {
                Log.i(TAG, "sendspin: connecting to fixed server $fixed")
                runCatching { c.connect(fixed) }
                    .onFailure { Log.w(TAG, "sendspin: connect failed: ${it.message}") }
            }
        } else
        // Follow the first server we find and keep following whichever is current.
        connectJob = s.launch {
            DiscoveryService(AndroidNsdBrowser(context)).discover().collectLatest { servers ->
                val server = servers.firstOrNull() ?: return@collectLatest
                Log.i(TAG, "sendspin: connecting to $server")
                runCatching { c.connect(server.wsUrl) }
                    .onFailure { Log.w(TAG, "sendspin: connect failed: ${it.message}") }
            }
        }

        downSinceMs = android.os.SystemClock.elapsedRealtime()
        s.launch { c.state.collect {
            Log.i(TAG, "sendspin: state $it"); idle?.onState(it)
            // fleet (2026-11-22, review b6-06): link health.
            val name = it.toString()
            val now = android.os.SystemClock.elapsedRealtime()
            if (name != libState) { libState = name; libStateSinceMs = now }
            if (connectedState(name)) {
                if (downSinceMs != 0L) { downSinceMs = 0L; everConnected = true }
            } else if (downSinceMs == 0L) {
                downSinceMs = now
                if (everConnected) { drops++; Log.i(TAG, "sendspin: link lost ($name, drop $drops)") }
            }
            runCatching { onLinkChange?.invoke() }
        } }
        idle?.start()
        s.launch { c.albumArtwork.collect { art ->
            Log.i(TAG, "sendspin: artwork ${art?.size ?: 0} bytes")
            if (art != null && art.isNotEmpty()) onArtwork?.invoke(art)
        } }

        // Track details ride the same connection as the audio, so there's nothing to poll.
        s.launch {
            c.serverState.collect { st ->
                st.controller?.supportedCommands?.let {
                    if (it != curCommands) { curCommands = it; Log.i(TAG, "sendspin: controller supports $it") }
                }
                val md = st.metadata ?: return@collect
                curTitle = md.title.merge(curTitle)
                curArtist = md.artist.merge(curArtist)
                curAlbum = md.album.merge(curAlbum)
                // progress is a plain nullable: null here also means "unchanged". Only a genuinely
                // new snapshot may re-anchor the position clock.
                md.progress?.let {
                    curPosMs = it.trackProgress.toInt()
                    curDurMs = it.trackDuration.toInt()
                    progressSeq++
                }
                if (curTitle.isBlank() && curArtist.isBlank()) { onTrack?.invoke(null); return@collect }
                emitTrack(playing = c.groupPlaybackState.value == GroupPlaybackState.PLAYING)
            }
        }
        s.launch {
            c.groupPlaybackState.collect { gs ->
                Log.i(TAG, "sendspin: playback $gs")
                idleJob?.cancel()
                if (curTitle.isBlank() && curArtist.isBlank()) return@collect
                // Re-emit the same track with the new playing state. Music Assistant reports
                // STOPPED when it ends the stream on a pause, so treating that as "nothing is
                // playing" tore the whole now-playing screen down the moment you hit pause.
                emitTrack(playing = gs == GroupPlaybackState.PLAYING)
                if (gs == GroupPlaybackState.STOPPED) {
                    // Genuinely finished rather than paused? Only then clear the screen.
                    idleJob = s.launch {
                        delay(90_000)
                        curTitle = ""; curArtist = ""; curAlbum = ""; curPosMs = 0; curDurMs = 0
                        onTrack?.invoke(null)
                    }
                }
            }
        }
    }

    fun stop() {
        idle?.stop(); idle = null
        connectJob?.cancel(); connectJob = null
        idleJob?.cancel(); idleJob = null
        focusRequest?.let { req ->
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { audio.abandonAudioFocusRequest(req) }
        }
        focusRequest = null
        audioPlayer = null
        runCatching { client?.disconnect("shutting_down") }
        client = null
        scope?.cancel(); scope = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}

/** "host", "host:port" or a full ws:// URL -> ws://host:port/sendspin; blank or invalid -> null. */
internal fun normalizeServerUrl(raw: String): String? {
    var u = raw.trim()
    if (u.isEmpty()) return null
    if (!u.contains("://")) u = "ws://$u"
    val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
    if (uri.scheme != "ws" && uri.scheme != "wss") return null
    val host = uri.host ?: return null
    val port = if (uri.port > 0) uri.port else 8927
    val path = if (uri.path.isNullOrEmpty() || uri.path == "/") "/sendspin" else uri.path
    return "${uri.scheme}://$host:$port$path"
}
