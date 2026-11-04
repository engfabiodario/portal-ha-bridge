package com.aeonos.portalha

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Android 11+ (the Echo Show 8 "crown" on LineageOS 18.1, fleet model show8): keeps the external voice assistant (Ava)
 * able to use the MICROPHONE.
 *
 * Android 11's while-in-use rule: a foreground service started while its app is in the background gets no microphone
 * (logged by ActivityManager: "Foreground service started from background can not have location/camera/microphone
 * access: service com.example.ava/.services.VoiceSatelliteService"). Ava starts that service itself at boot and on its
 * own service restart - always from the background - so its AudioRecord fails ("Failed to initialize AudioRecord
 * (permission denied or device busy)") and it hears nothing (measured 2026-11-04 on the first Show:
 * allowWhileInUsePermissionInFgs=false). A start from an app that is TOP carries the exemption, and the Bridge's
 * dashboard is the top app: the Bridge starts Ava's (exported) VoiceSatelliteService, then cycles Ava's mic
 * (ACTION_MUTE_MIC -> ACTION_UNMUTE_MIC) so Ava reopens its AudioRecord with the access it now has (proven by hand
 * the same night: adb start + the mute switch off/on = capture).
 *
 * When: (1) the first time the dashboard is in front after the Bridge starts (service start only - nothing is muted),
 * (2) whenever the log shows that refusal or Ava's AudioRecord failure (Ava tried to open its mic = it is not muted on
 * purpose, so the cycle never unmutes a mic someone muted). Only while the dashboard is in front (else when it comes
 * back), at most once a minute and 20 times an hour. Android 9 / 10 (the Meta Portals) never run this.
 */
class AvaMicStarter(private val ctx: Context, private val dashboardInFront: () -> Boolean) {
    companion object {
        private const val TAG = "PortalHA"
        const val PKG = AvaKeepAlive.DEFAULT_PACKAGE
        private const val SERVICE = "$PKG.services.VoiceSatelliteService"
        private const val RECEIVER = "$PKG.receivers.AvaControlReceiver"
        private const val MIN_GAP_MS = 60_000L
        private const val MAX_PER_HOUR = 20
        val needed: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        private val refusedRe = Regex("""can not have [a-z/]*microphone[a-z/]* access: service ${Regex.escape(PKG)}/""")
        private val failedRe = Regex("""MicrophoneInput.*Failed to (initialize|create) AudioRecord""")
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    @Volatile private var logThread: Thread? = null
    @Volatile private var logProc: Process? = null
    @Volatile private var pendingCycle = false
    @Volatile private var pendingStart = true            // (1): once per Bridge start
    private var lastFixMs = 0L
    private val recent = ArrayDeque<Long>()

    fun start() {
        if (!needed || started) return
        if (runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.isFailure) { Log.i(TAG, "avamic: $PKG not installed - off"); return }
        started = true
        logThread = Thread({ readLoop() }, "portal-ha-avamic-log").also { it.isDaemon = true; it.start() }
        Log.i(TAG, "avamic: on (Android ${Build.VERSION.RELEASE}: a background-started assistant service gets no microphone)")
    }

    fun stop() {
        started = false
        logThread = null
        runCatching { logProc?.destroy() }
        logProc = null
        main.removeCallbacksAndMessages(null)
    }

    /** The dashboard came to the front: do what waited for it. */
    fun onDashboardResumed() {
        if (!started) return
        if (pendingStart || pendingCycle) main.postDelayed({ fix(if (pendingCycle) "deferred refusal" else "dashboard in front") }, 1_500L)
    }

    fun status(): String = "avamic: status started=$started pendingStart=$pendingStart pendingCycle=$pendingCycle fixes/h=${recent.size}"

    @Synchronized private fun fix(why: String) {
        if (!started) return
        val cycle = pendingCycle
        if (!dashboardInFront()) { Log.i(TAG, "avamic: $why - waiting for the dashboard (top app needed)"); return }
        val now = SystemClock.elapsedRealtime()
        while (recent.isNotEmpty() && now - recent.first() > 3_600_000L) recent.removeFirst()
        if (lastFixMs != 0L && now - lastFixMs < MIN_GAP_MS) {
            main.postDelayed({ fix(why) }, MIN_GAP_MS - (now - lastFixMs) + 500L); return
        }
        if (recent.size >= MAX_PER_HOUR) { Log.w(TAG, "avamic: $why - $MAX_PER_HOUR fixes this hour, holding"); return }
        lastFixMs = now; recent.addLast(now)
        pendingStart = false; pendingCycle = false
        try {
            ctx.startForegroundService(Intent().setComponent(ComponentName(PKG, SERVICE)))
            Log.i(TAG, "avamic: started $SERVICE from the dashboard ($why)")
        } catch (e: Exception) {
            Log.w(TAG, "avamic: start failed ($why): ${e.message}"); return
        }
        if (!cycle) return
        main.postDelayed({ send("ACTION_MUTE_MIC") }, 2_500L)
        main.postDelayed({ send("ACTION_UNMUTE_MIC"); Log.i(TAG, "avamic: mic cycled - Ava reopens its AudioRecord") }, 4_500L)
    }

    private fun send(action: String) {
        runCatching { ctx.sendBroadcast(Intent("$PKG.$action").setComponent(ComponentName(PKG, RECEIVER))) }
            .onFailure { Log.w(TAG, "avamic: $action failed: ${it.message}") }
    }

    private fun onLogLine(line: String) {
        if (!refusedRe.containsMatchIn(line) && !failedRe.containsMatchIn(line)) return
        if (pendingCycle) return
        pendingCycle = true
        Log.i(TAG, "avamic: assistant has no microphone (${if (refusedRe.containsMatchIn(line)) "background start refused" else "AudioRecord failed"})")
        main.postDelayed({ fix("no microphone") }, 3_000L)       // let Ava's own retries finish first
    }

    private fun readLoop() {
        var failures = 0
        val me = Thread.currentThread()
        // This boot's earlier lines first (Ava may have failed before the Bridge started), then live ones.
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "brief", "-s", "ActivityManager:W", "MicrophoneInput:E").redirectErrorStream(true).start()
            val last = p.inputStream.bufferedReader().use { r -> r.readLines().lastOrNull { refusedRe.containsMatchIn(it) || failedRe.containsMatchIn(it) } }
            p.destroy()
            if (last != null) onLogLine(last)
        }
        while (started && me === logThread) {
            try {
                val p = ProcessBuilder("logcat", "-v", "brief", "-T", "1", "-s", "ActivityManager:W", "MicrophoneInput:E")
                    .redirectErrorStream(true).start()
                logProc = p
                p.inputStream.bufferedReader().use { r ->
                    while (started && me === logThread) {
                        val line = r.readLine() ?: break
                        onLogLine(line)
                    }
                }
                failures = 0
            } catch (e: Exception) {
                if (me === logThread) { failures++; Log.w(TAG, "avamic: log reader: ${e.message}") }
            } finally {
                runCatching { logProc?.destroy() }
            }
            if (!started || me !== logThread) break
            runCatching { Thread.sleep((2_000L shl failures.coerceAtMost(5)).coerceAtMost(60_000L)) }
        }
    }
}
