package com.aeonos.portalha

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Soft self-heal: every [TICK_MS] (30 s since 2026-10-12, was 5 min; first check 2 min after start) the app checks its OWN components and restarts the ones that
 * stopped working, through the entry points the app already uses for them:
 *
 *  - mqtt      the broker client is disconnected on 2 ticks         -> the app's MQTT reconnect
 *  - stream    RTSP wanted but the encoder produced no frame for 2 ticks -> the streamer's own restart
 *  - rtspclients  RTSP clients are PLAYing but none of them received data in the last 30 s, on 2
 *              ticks (a dead client starving the rest, 2026-10-01) -> evict the stalled clients
 *              (the server's own 12 s watchdog normally does this first)
 *  - webview   the dashboard doesn't answer a trivial evaluateJavascript in 10 s on 2 ticks
 *              -> reload the current start page
 *  - keepalive the Ava keep-alive is on but not parked             -> the keep-alive's own re-park
 *  - wifi      the Wi-Fi link is down for 2 ticks -> Android 9: WifiManager.reconnect(), repeated with the
 *              backoff while the link stays down (2026-10-23; was once per outage). Android 10 (wifi recovery
 *              2026-10-29): a ladder by outage age - reconnect(), >= 5 min enableNetwork(last nid, true),
 *              >= 10 min disconnect() + reconnect(), >= 15 min the target-28 helper's radio toggle (every 30 min,
 *              max 4/day). Wi-Fi itself OFF (the framework's SelfRecovery can shut it down while the toggle stays
 *              on) is now 'bad' too -> setWifiEnabled(true): Android 9 itself, Android 10 via the helper.
 *              The Bridge's reconnect()/disconnect()/enableNetwork() are ACCEPTED on Android 10 (its
 *              SYSTEM_ALERT_WINDOW app-op passes AOSP 10 isTargetSdkLessThanQOrPrivileged); only
 *              setWifiEnabled() needs targetSdk < 29 = the helper (com.aeonos.portalha.wifihelper).
 *  - lan       (fleet 2026-10-08) Wi-Fi says connected but neither the MQTT broker (host:port) nor the
 *              LAN probe (default the broker's /24 .1 = main Deco, :80) accepts a TCP connect in 3 s
 *              (a refused connect counts as reachable), on 2 consecutive checks -> Android 9:
 *              WifiManager.reconnect() (wifi recovery 2026-10-29: the 2nd-action disconnect() + reconnect() is
 *              GONE - on 10-26 it turned a LAN glitch into 7 min (Bar) and 15 min (Coffee Area) offline);
 *              Android 10: report only - state 'failing' (reason 'lan unreachable'). NEVER disconnect()
 *              there (2026-10-13): it does work on the Portal 10", but Android 10 then holds the network
 *              NETWORK_SELECTION_DISABLED_NO_INTERNET_TEMPORARY and doesn't rejoin it for ~13 min (Upstairs
 *              Hallway Guest 07:24 -> 07:37: a 2-min LAN glitch became a 13-min outage). While the self-heal state is not ok, or lan is bad,
 *              the lan check alone runs every [LAN_FAST_MS] (30 s).
 *              QUIET PROBES (fleet 2026-10-22): the targets are tried one by one and the check stops at the first
 *              one that answers. While the MQTT session is connected the broker is NOT probed first (the live
 *              session already proves it; the old every-30-s connect was ~120 log lines/h per Portal in
 *              Mosquitto) - the LAN probe goes first and the broker is only tried when that fails. With MQTT
 *              down the order is the old one (broker, then the LAN probe), so healing is unchanged.
 *
 * It never reboots, never toggles the Wi-Fi radio, never changes a camera switch or preference,
 * never drives another app's UI. A tick is skipped while a call / ringing / alarm / cast / intercom
 * / wake hand-off / navigate page / one of our settings screens is up. Each action at most
 * [MAX_PER_HOUR] per rolling hour, with an exponential backoff between repeats of the same action
 * (reset when its check is ok again). One log line (tag SelfHeal) per check result change and per action.
 *
 * HA: switch.<slug>_self_heal (on/off, persisted) and sensor.<slug>_self_heal
 * (ok / fixing / failing, plus off while switched off; attributes last_check, last_report, last_action,
 * last_reason, actions_today). 'failing' = a check still broken on 3+ consecutive ticks.
 * Published only when the state or an attribute other than the two times changed, plus a heartbeat every
 * [HEARTBEAT_MS] (10 min; the Homelab Watcher's bridge_alive treats a report < 12 min old as 'HA reaches
 * the Bridge') and on every MQTT (re)connect / HA birth / switch change (fleet 2026-10-22: it was every tick
 * = ~720 HA DB rows/h fleet-wide just for last_check).
 *
 * Test hooks (adb DEBUG_CONFIG): selfHealTest mqtt|stream|webview|none fakes that check failing
 * until its action has fired once; selfHealTick runs a check now; selfHealStatus logs a status line.
 * The same over MQTT (wall Portals have no adb): portal/<id>/selfheal/set payload 'test:<check>', 'tick' or
 * 'status' (the status line also goes to portal/<id>/selfheal/status_line, not retained).
 */
class SelfHeal(private val ctx: Context, private val host: Host) {

    interface Host {
        /** Why a check must not run now (call, cast, alarm, intercom, settings screen...), or null. */
        fun busyReason(): String?
        /** null = the app has no broker configured (n/a). */
        fun mqttConnected(): Boolean?
        fun mqttReconnect(): String
        /** null = stream not wanted (camera/stream off by the user) or not judgeable yet; else ms since the last encoded frame. */
        fun streamFrameAgeMs(): Long?
        fun streamRestart(): String
        /** null = no PLAYing RTSP client (n/a); true = at least one received data recently; false = none did. */
        fun rtspClientsReceiving(): Boolean? = null
        fun rtspEvictStalled(): String = ""
        /** null = no dashboard to probe (not in front, screen off); else true when it answered in [timeoutMs]. Blocking. */
        fun webviewProbe(timeoutMs: Long): Boolean?
        fun webviewReload(): String
        /** null = n/a (keep-alive off, paused, blocked, unsupported); true = parked; false = not parked. */
        fun keepAliveParked(): Boolean?
        fun keepAliveRepark(): String
        /** true = handed to the broker (false: no MQTT session - the next tick tries again). */
        fun publish(state: String, attributesJson: String): Boolean
        /** lan check: TCP targets (host to port), the MQTT broker first when one is configured. Empty = n/a. */
        fun lanTargets(): List<Pair<String, Int>> = emptyList()
    }

    enum class Check(val id: String) { MQTT("mqtt"), STREAM("stream"), RTSPCLIENTS("rtspclients"), WEBVIEW("webview"), KEEPALIVE("keepalive"), WIFI("wifi"), LAN("lan") }

    private class CheckState {
        var last = "unknown"     // ok / bad: <why> / n/a: <why>
        var bad = 0              // consecutive bad ticks
        val actions = ArrayDeque<Long>()   // elapsedRealtime of actions in the last hour
        var streak = 0           // actions since the check was last ok (backoff exponent)
        var lastActionMs = 0L
        var wifiReconnected = false        // wifi: reconnect() already used for this outage
        var downSinceMs = 0L               // wifi: elapsedRealtime the outage started (0 = not down)
    }

    private val thread = HandlerThread("portal-ha-selfheal").also { it.start() }
    private val handler = Handler(thread.looper)
    private val checks = Check.values().associateWith { CheckState() }

    @Volatile var enabled = true
        private set
    @Volatile private var started = false
    @Volatile private var fake: Check? = null
    @Volatile private var state = "ok"
    @Volatile private var lastCheckWall = 0L
    @Volatile private var lastAction = ""
    @Volatile private var lastReason = ""
    @Volatile private var lastSkip = ""
    @Volatile private var ticks = 0
    @Volatile private var lanTargetsText = ""
    @Volatile private var lanProbedText = ""      // what the last lan check actually connected to (status only)
    @Volatile private var lanBadSinceWall = 0L
    private var lastLanRunMs = 0L
    @Volatile private var lanFastScheduled = false
    private var actionsToday = 0
    private var actionsDay = -1
    // Publish dedup (handler thread only): the last payload sent without its times, and when.
    private var lastPublishedKey = ""
    private var lastPublishMs = 0L
    @Volatile private var publishes = 0

    private val tick = object : Runnable {
        override fun run() {
            runCheck("tick")
            handler.postDelayed(this, TICK_MS)
        }
    }

    // The lan check alone, every LAN_FAST_MS while anything is unhealthy (stops itself once all is ok).
    private val lanFast = object : Runnable {
        override fun run() {
            lanFastScheduled = false
            if (!started || !enabled || !unhealthy()) return
            runLanOnly("lan 60 s")
            scheduleLanFast()
        }
    }

    private fun unhealthy() = state != "ok" || checks.getValue(Check.LAN).bad > 0

    private fun scheduleLanFast() {
        if (lanFastScheduled || !started || !enabled || !unhealthy()) return
        lanFastScheduled = true
        handler.postDelayed(lanFast, LAN_FAST_MS)
    }

    fun start(on: Boolean) {
        enabled = on
        if (started) return
        started = true
        handler.postDelayed(tick, START_DELAY_MS)
        Log.i(TAG, "service up (enabled=$on, first check in ${START_DELAY_MS / 1000} s, then every ${TICK_MS / 1000} s)")
        handler.post { publish(force = true) }
    }

    fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    fun setEnabled(on: Boolean) {
        if (on == enabled) { handler.post { publish(force = true) }; return }
        enabled = on
        Log.i(TAG, "switch ${if (on) "ON" else "OFF"}")
        handler.post {
            if (!on) { checks.values.forEach { it.bad = 0; it.streak = 0; it.last = "unknown" }; lanBadSinceWall = 0L }
            publish(force = true)
        }
    }

    /** adb selfHealTest: fake [name] failing until its action has fired once ("none" clears). */
    fun setTest(name: String) {
        val c = Check.values().firstOrNull { it.id.equals(name.trim(), true) }
        fake = c
        Log.i(TAG, if (c == null) "test: cleared" else "test: faking '${c.id}' failing until its action fires once")
    }

    /** adb selfHealTick: run one check now (counts as a tick). */
    fun tickNow() { handler.post { runCheck("adb") } }

    /** Re-send the HA state (MQTT just connected, HA birth, the 20 s re-publish). */
    fun republish() { handler.post { publish(force = true) } }

    fun status(): String {
        val parts = checks.entries.joinToString(" ") { (c, s) -> "${c.id}=${s.last.substringBefore(':')}/${s.bad}" }
        return "status enabled=$enabled state=${stateNow()} ticks=$ticks $parts fake=${fake?.id ?: "none"} " +
            "actionsToday=$actionsToday lastAction='$lastAction' lastReason='$lastReason' " +
            "lan='${checks.getValue(Check.LAN).last}' lanTargets='$lanTargetsText' lanProbed='$lanProbedText' " +
            "lanFast=$lanFastScheduled publishes=$publishes"
    }

    private fun stateNow() = if (!enabled) "off" else state

    // ── The check ────────────────────────────────────────────────────────────────

    private fun runCheck(why: String) {
        if (!started) return
        if (!enabled) { publish(); return }
        val busy = runCatching { host.busyReason() }.getOrNull()
        if (busy != null) {
            if (busy != lastSkip) { lastSkip = busy; Log.i(TAG, "check skipped: $busy") }
            publish()   // the heartbeat only (nothing changed)
            return
        }
        if (lastSkip.isNotEmpty()) { Log.i(TAG, "checks resumed (was: $lastSkip)"); lastSkip = "" }
        ticks++
        lastCheckWall = System.currentTimeMillis()

        val wifi = evalWifi()
        val wifiDown = wifi.startsWith("bad")
        judge(Check.WIFI, wifi)
        // lan: skipped here when the 60 s lan run did it moments ago (2 checks must be ~a minute apart).
        if (SystemClock.elapsedRealtime() - lastLanRunMs >= LAN_FAST_MS - 10_000L) judgeLan(wifi)
        val lanDown = checks.getValue(Check.LAN).last.startsWith("bad")
        val linkWhy = when {
            wifiDown -> "the Wi-Fi link is down"
            lanDown -> "the LAN is unreachable"
            else -> null
        }
        judge(Check.MQTT, evalMqtt(), skipActionWhy = linkWhy)
        judge(Check.STREAM, evalStream())
        judge(Check.RTSPCLIENTS, evalRtspClients())
        judge(Check.WEBVIEW, evalWebview(), skipActionWhy = linkWhy)
        judge(Check.KEEPALIVE, evalKeepAlive())

        updateState(why)
        publish()
        scheduleLanFast()
    }

    /** The lan check on its own (the 60 s cadence while unhealthy). */
    private fun runLanOnly(why: String) {
        if (!started || !enabled) return
        val busy = runCatching { host.busyReason() }.getOrNull()
        if (busy != null) {
            if (busy != lastSkip) { lastSkip = busy; Log.i(TAG, "check skipped: $busy") }
            return
        }
        lastCheckWall = System.currentTimeMillis()
        judgeLan(evalWifi())
        updateState(why)
        publish()
    }

    private fun judgeLan(wifi: String) {
        lastLanRunMs = SystemClock.elapsedRealtime()
        judge(Check.LAN, evalLan(wifi))
        val s = checks.getValue(Check.LAN)
        if (s.bad > 0) {
            if (lanBadSinceWall == 0L) lanBadSinceWall = System.currentTimeMillis()
            if (s.bad >= BAD_TICKS_FOR_ACTION) lastReason = "lan unreachable"
        } else if (lanBadSinceWall != 0L) {
            Log.i(TAG, "lan: reachable again after ${(System.currentTimeMillis() - lanBadSinceWall) / 1000}s")
            lanBadSinceWall = 0L
        }
    }

    private fun updateState(why: String) {
        val worst = checks.values.maxOf { it.bad }
        // Android 10 has no API to bring an associated-but-dead Wi-Fi link back: say so at once.
        val lanStuck = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checks.getValue(Check.LAN).bad >= BAD_TICKS_FOR_ACTION
        val newState = when {
            worst >= FAILING_TICKS || lanStuck -> "failing"
            worst > 0 -> "fixing"
            else -> "ok"
        }
        if (newState != state) Log.i(TAG, "state $state -> $newState ($why)")
        state = newState
    }

    private fun faked(c: Check) = fake == c

    private fun evalWifi(): String = runCatching {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return "n/a: no Wi-Fi"
        // A wall Portal has no other network: Wi-Fi off is never 'n/a'. It was, so a Wi-Fi the framework shut down
        // (SelfRecovery) was never acted on. 'wifi_on' = the persisted user toggle (0 off, 1 on, 2/3 airplane).
        if (!wm.isWifiEnabled) {
            val on = runCatching { Settings.Global.getInt(ctx.contentResolver, "wifi_on") }.getOrDefault(-1)
            return "bad: Wi-Fi is off (state ${wm.wifiState}, wifi_on=$on)"
        }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val up = cm?.allNetworks?.any { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } == true
        if (up) "ok" else "bad: Wi-Fi link down"
    }.getOrElse { "n/a: ${it.message}" }

    private fun evalLan(wifi: String): String {
        if (faked(Check.LAN)) return "bad: test (selfHealTest lan)"
        if (wifi != "ok") return "n/a: Wi-Fi not connected"
        val targets = runCatching { host.lanTargets() }.getOrDefault(emptyList())
        lanTargetsText = targets.joinToString(",") { "${it.first}:${it.second}" }
        if (targets.isEmpty()) return "n/a: no LAN target"
        // MQTT session up (and not faked down): the broker goes LAST - it is only connected to when every other
        // target failed. No other target = the live session is the proof (no probe at all).
        val mqttUp = !faked(Check.MQTT) && runCatching { host.mqttConnected() }.getOrNull() == true
        if (mqttUp && targets.size == 1) { lanProbedText = "none (MQTT session up)"; return "ok" }
        val order = if (mqttUp) targets.drop(1) + targets.first() else targets
        val results = mutableListOf<Pair<String, String?>>()
        for ((h, port) in order) {
            val r = probe(h, port)
            results += "$h:$port" to r
            if (r == null) break                       // the first target that answers is enough
        }
        lanProbedText = results.joinToString(",") { it.first } + if (mqttUp) " (MQTT up)" else ""
        if (results.last().second == null) return "ok"
        return "bad: lan unreachable (" + results.joinToString(", ") { "${it.first} ${it.second}" } + ")"
    }

    /** null = reachable (connected, or actively refused = the host answered); else why not. */
    private fun probe(host: String, port: Int): String? {
        val sock = java.net.Socket()
        return try {
            sock.connect(java.net.InetSocketAddress(host, port), LAN_PROBE_TIMEOUT_MS)
            null
        } catch (e: java.net.SocketTimeoutException) {
            "timeout ${LAN_PROBE_TIMEOUT_MS / 1000}s"
        } catch (e: java.net.ConnectException) {
            if (e.message?.contains("refused", ignoreCase = true) == true) null else "failed: ${e.message}"
        } catch (e: java.net.UnknownHostException) {
            "unresolved"
        } catch (e: Exception) {
            "failed: ${e.message}"
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun evalMqtt(): String {
        if (faked(Check.MQTT)) return "bad: test (selfHealTest mqtt)"
        return when (runCatching { host.mqttConnected() }.getOrNull()) {
            null -> "n/a: no broker"
            true -> "ok"
            false -> "bad: broker disconnected"
        }
    }

    private fun evalStream(): String {
        if (faked(Check.STREAM)) return "bad: test (selfHealTest stream)"
        val age = runCatching { host.streamFrameAgeMs() }.getOrNull() ?: return "n/a: stream off or just started"
        return if (age <= STREAM_STALE_MS) "ok" else "bad: no camera frames for ${if (age == Long.MAX_VALUE) "ever" else "${age / 1000}s"}"
    }

    private fun evalRtspClients(): String {
        if (faked(Check.RTSPCLIENTS)) return "bad: test (selfHealTest rtspclients)"
        return when (runCatching { host.rtspClientsReceiving() }.getOrNull()) {
            null -> "n/a: no RTSP client playing"
            true -> "ok"
            false -> "bad: RTSP clients connected but none receiving"
        }
    }

    private fun evalWebview(): String {
        if (faked(Check.WEBVIEW)) return "bad: test (selfHealTest webview)"
        return when (runCatching { host.webviewProbe(WEBVIEW_TIMEOUT_MS) }.getOrNull()) {
            null -> "n/a: dashboard not in front"
            true -> "ok"
            false -> "bad: dashboard didn't answer in ${WEBVIEW_TIMEOUT_MS / 1000}s"
        }
    }

    private fun evalKeepAlive(): String = when (runCatching { host.keepAliveParked() }.getOrNull()) {
        null -> "n/a: keep-alive off/paused/blocked"
        true -> "ok"
        false -> "bad: keep-alive on but Ava not parked"
    }

    private fun judge(c: Check, result: String, skipActionWhy: String? = null) {
        val s = checks.getValue(c)
        val bad = result.startsWith("bad")
        if (result.substringBefore(':') != s.last.substringBefore(':') || (bad && result != s.last)) {
            Log.i(TAG, "${c.id}: ${s.last} -> $result")
        }
        s.last = result
        if (!bad) {
            s.bad = 0; s.streak = 0; s.wifiReconnected = false; s.downSinceMs = 0L
            return
        }
        s.bad++
        if (s.bad < BAD_TICKS_FOR_ACTION) return
        if (skipActionWhy != null) { Log.i(TAG, "${c.id}: no action ($skipActionWhy)"); return }
        act(c, s, result.removePrefix("bad: "))
    }

    private fun act(c: Check, s: CheckState, reason: String) {
        val now = SystemClock.elapsedRealtime()
        while (s.actions.isNotEmpty() && now - s.actions.first() > HOUR_MS) s.actions.removeFirst()
        if (s.actions.size >= MAX_PER_HOUR) {
            Log.w(TAG, "${c.id}: no action - ${s.actions.size} in the last hour (limit $MAX_PER_HOUR)"); return
        }
        if (s.streak > 0) {
            // Near-real-time (Fabio 2026-10-12): checks every 30 s; repeats back off from 1 min (1, 2, 4 ... min).
            val base = if (c == Check.LAN) LAN_BACKOFF_BASE_MS else ACTION_BACKOFF_BASE_MS
            val wait = (base shl (s.streak - 1)).coerceAtMost(HOUR_MS) - 5_000L
            if (now - s.lastActionMs < wait) {
                Log.i(TAG, "${c.id}: backing off (${(wait - (now - s.lastActionMs)) / 1000}s left)"); return
            }
        }
        val what: String = runCatching {
            when (c) {
                Check.MQTT -> host.mqttReconnect()
                Check.STREAM -> host.streamRestart()
                Check.RTSPCLIENTS -> host.rtspEvictStalled()
                Check.WEBVIEW -> host.webviewReload()
                Check.KEEPALIVE -> host.keepAliveRepark()
                Check.WIFI -> wifiAction(s)
                Check.LAN -> lanAction(s)
            }
        }.getOrElse { "failed: ${it.message}" }
        if (what.isEmpty()) {        // nothing done (wifi / lan on Android 10: report only; already used)
            if (fake == c) { fake = null; Log.i(TAG, "test: '${c.id}' report-only - fake cleared") }
            return
        }
        s.actions.addLast(now); s.streak++; s.lastActionMs = now
        if (fake == c) { fake = null; Log.i(TAG, "test: '${c.id}' action fired - fake cleared") }
        countToday()
        lastAction = "${c.id}: $what"
        lastReason = reason
        Log.w(TAG, "action ${c.id}: $what (reason: $reason; ${s.actions.size} in the last hour, today $actionsToday)")
    }

    private fun wifiAction(s: CheckState): String {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return ""
        val now = SystemClock.elapsedRealtime()
        if (s.downSinceMs == 0L) s.downSinceMs = now - BAD_TICKS_FOR_ACTION * TICK_MS
        val downMin = (now - s.downSinceMs) / 60_000L
        @Suppress("DEPRECATION")
        if (!wm.isWifiEnabled) {
            // Android 9 may switch it on itself; Android 10 refuses setWifiEnabled() to a target-35 app -> the helper
            // (a no-op there when Wi-Fi is already on; it never turns Wi-Fi off for this command).
            return if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P)
                "WifiManager.setWifiEnabled(true) = ${wm.setWifiEnabled(true)} (Wi-Fi was off $downMin min)"
            else helper("assert_on")
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            // fleet (2026-10-23): repeated with the normal backoff (1, 2, 4, 8 ... min, max MAX_PER_HOUR), no longer once per
            // outage. Coffee Area at the 03:00 Deco reboot: the one reconnect() (03:03) scanned while the Decos were still
            // booting (only neighbours' APs seen), then Android 9 sat in DisconnectedState with no scan for 25 min (rejoined
            // 03:27:51 on a PNO result). reconnect() on Android 9 starts a fresh scan + network selection; it is only sent
            // while the link is down, so a live connection is never touched.
            @Suppress("DEPRECATION")
            val ok = wm.reconnect()
            s.wifiReconnected = true
            return "WifiManager.reconnect() = $ok (attempt ${s.streak + 1} of this outage)"
        }
        // Android 10 ladder (wifi recovery 2026-10-29). The link is DOWN when we get here (2 bad ticks), so none of
        // these can cut a working connection. Nightly Deco reboots rejoin in ~100 s on step 1 alone (10-26..10-29).
        val nid = WifiTrace.lastNetId
        @Suppress("DEPRECATION")
        return when {
            downMin >= 15 && canToggle(now) -> {
                lastToggleMs = now; togglesToday++
                helper("toggle")
            }
            downMin >= 10 && now - lastKickMs >= 15 * 60_000L -> {
                lastKickMs = now
                "WifiManager.disconnect() = ${wm.disconnect()} + reconnect() = ${wm.reconnect()} (down $downMin min)"
            }
            downMin >= 5 && nid >= 0 && now - lastEnableNetMs >= 10 * 60_000L -> {
                // connectToUserSelectNetwork(): without modify rights it logs 'Allowing uid ... with insufficient
                // permissions to connect' and still starts the connect - also when the network's selection status is
                // disabled (DISABLED_NO_INTERNET_*, ...BY_WRONG_PASSWORD, auth/assoc/DHCP temp disables).
                lastEnableNetMs = now
                "WifiManager.enableNetwork($nid, true) = ${wm.enableNetwork(nid, true)} (down $downMin min)"
            }
            else -> {
                val ok = runCatching { wm.reconnect() }.getOrDefault(false)
                if (!ok) {
                    if (s.bad == BAD_TICKS_FOR_ACTION) Log.w(TAG, "wifi: reconnect() refused on Android ${Build.VERSION.RELEASE}")
                    ""
                } else "WifiManager.reconnect() = true on Android ${Build.VERSION.RELEASE} (attempt ${s.streak + 1}, down $downMin min)"
            }
        }
    }

    // -- Wi-Fi helper (target 28; the only way to call setWifiEnabled() on Android 10 without root) --
    private var lastToggleMs = 0L
    private var lastKickMs = 0L
    private var lastEnableNetMs = 0L
    private var togglesToday = 0
    private var togglesDay = -1

    private fun helperInstalled(): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(HELPER_PKG, 0); true }.getOrDefault(false)

    private fun canToggle(now: Long): Boolean {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (day != togglesDay) { togglesDay = day; togglesToday = 0 }
        return togglesToday < MAX_TOGGLES_PER_DAY &&
            (lastToggleMs == 0L || now - lastToggleMs >= 30 * 60_000L) &&
            lastKickMs != 0L && now - lastKickMs >= 5 * 60_000L &&     // the gentler steps first
            helperInstalled()
    }

    /** Explicit ordered broadcast to the helper; its answer (code 1 done / 0 refused + one line) is logged when it
     *  arrives. "" = helper not installed (report only). [onResult] gets that line (the MQTT 'helper:' hook). */
    fun helper(cmd: String, onResult: ((String) -> Unit)? = null): String {
        if (!helperInstalled()) {
            Log.w(TAG, "wifi helper not installed - '$cmd' not possible on Android ${Build.VERSION.RELEASE}")
            onResult?.invoke("helper not installed"); return ""
        }
        val i = Intent("$HELPER_PKG.CMD").setComponent(ComponentName(HELPER_PKG, "$HELPER_PKG.WifiRecoveryReceiver"))
            .putExtra("cmd", cmd).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        return runCatching {
            ctx.sendOrderedBroadcast(i, null, object : BroadcastReceiver() {
                override fun onReceive(c: Context, r: Intent) {
                    val line = "helper $cmd -> code=$resultCode ${resultData ?: "(no answer: helper missing or crashed)"}"
                    Log.w(TAG, line); onResult?.invoke(line)
                }
            }, handler, -1, null, null)
            "helper($cmd) sent"
        }.getOrElse { "helper($cmd) failed: ${it.message}" }
    }

    // Android 9: reconnect() only (wifi recovery 2026-10-29: disconnect() + reconnect() removed, see the class doc).
    // Android 10+: report only (see the class doc).
    @Suppress("DEPRECATION")
    private fun lanAction(s: CheckState): String {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P) {
            if (s.bad == BAD_TICKS_FOR_ACTION) Log.w(TAG, "lan: unreachable for ${s.bad} checks - report only on Android ${Build.VERSION.RELEASE}")
            return ""
        }
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return ""
        return "WifiManager.reconnect() = ${wm.reconnect()}"
    }

    private fun countToday() {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (day != actionsDay) { actionsDay = day; actionsToday = 0 }
        actionsToday++
    }

    // ── HA ──────────────────────────────────────────────────────────────────────

    // Only a real change (state / any attribute except the two times), the heartbeat every HEARTBEAT_MS, or
    // [force] (MQTT connect, HA birth, switch) reaches HA. Runs on the handler thread.
    private fun publish(force: Boolean = false) {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (day != actionsDay) { actionsDay = day; actionsToday = 0 }
        val checksJson = checks.entries.joinToString(",") { (c, s) -> "\"${c.id}\":\"${esc(s.last)}\"" }
        val core = "\"last_action\":\"${esc(lastAction)}\",\"last_reason\":\"${esc(lastReason)}\"," +
            "\"actions_today\":$actionsToday,\"checks\":{$checksJson}," +
            "\"lan\":\"${esc(checks.getValue(Check.LAN).last)}\",\"lan_targets\":\"${esc(lanTargetsText)}\"," +
            "\"lan_unreachable_since\":${if (lanBadSinceWall == 0L) "null" else "\"${isoOf(lanBadSinceWall)}\""}"
        val state = stateNow()
        val key = "$state|$core"
        val now = SystemClock.elapsedRealtime()
        if (!force && key == lastPublishedKey && lastPublishMs != 0L && now - lastPublishMs < HEARTBEAT_MS - 15_000L) return
        val iso = if (lastCheckWall == 0L) "" else isoOf(lastCheckWall)
        val attrs = "{\"last_check\":${if (iso.isEmpty()) "null" else "\"$iso\""}," +
            "\"last_report\":\"${isoOf(System.currentTimeMillis())}\",$core}"
        val sent = runCatching { host.publish(state, attrs) }.getOrDefault(false)
        if (sent) { lastPublishedKey = key; lastPublishMs = now; publishes++ }
    }

    private fun isoOf(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(ms))
            .let { it.substring(0, it.length - 2) + ":" + it.substring(it.length - 2) }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "SelfHeal"
        const val TICK_MS = 30_000L
        const val START_DELAY_MS = 2 * 60_000L
        private const val WEBVIEW_TIMEOUT_MS = 10_000L
        // No encoded frame for longer than this = stale (a tick is 5 min; 2 such ticks = action).
        private const val STREAM_STALE_MS = 60_000L
        private const val BAD_TICKS_FOR_ACTION = 2
        private const val FAILING_TICKS = 3
        private const val MAX_PER_HOUR = 6
        private const val HOUR_MS = 60 * 60_000L
        const val LAN_FAST_MS = 30_000L
        /** Unchanged state is re-sent this often (HA last_reported; the watcher's freshness window is 12 min). */
        const val HEARTBEAT_MS = 10 * 60_000L
        private const val LAN_BACKOFF_BASE_MS = 60_000L
        /** Repeats of the same (non-lan) action back off 1, 2, 4, 8 ... min (the tick is only 30 s). */
        private const val ACTION_BACKOFF_BASE_MS = 60_000L
        private const val LAN_PROBE_TIMEOUT_MS = 3_000
        const val HELPER_PKG = "com.aeonos.portalha.wifihelper"
        private const val MAX_TOGGLES_PER_DAY = 4
    }
}
