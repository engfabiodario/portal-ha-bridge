package com.aeonos.portalha

import android.os.Handler
import android.os.HandlerThread
import android.util.Log

// Reads Meta's own face-presence detection by tailing logcat. Portal's
// PresenceManager logs a heartbeat (~every 30s) ONLY while a person is present;
// it goes silent when the room is empty (verified on-device 2026-06-12). The
// boolean is the heartbeat's *liveness*, not its text.
//
// Requires READ_LOGS, grantable via:
//   adb shell pm grant com.aeonos.portalha android.permission.READ_LOGS
//
// This rides Portal's Camera 1 (Aloha), independent of our Camera 0, so it works
// with our camera feature off.
class PresenceMonitor(private val onPresenceChange: (present: Boolean) -> Unit) {

    companion object {
        private const val TAG = "PortalHA"
        // A beat newer than this (by its own log timestamp) counts as live —
        // filters out the stale backlog logcat dumps at startup.
        private const val FRESH_MS = 45_000L
        // Declare "absent" once the newest beat is older than this. Portal beats
        // every ~30s, so this allows one miss plus margin.
        private const val ABSENT_MS = 50_000L
        private const val CHECK_INTERVAL_MS = 10_000L
    }

    @Volatile private var running = false
    @Volatile private var lastBeatMs = 0L
    @Volatile private var present = false
    val isPresent: Boolean get() = present
    private var process: Process? = null
    private var readerThread: Thread? = null
    private val checkThread = HandlerThread("portal-ha-presence").also { it.start() }
    private val checkHandler = Handler(checkThread.looper)

    fun start() {
        if (running) return
        running = true
        lastBeatMs = 0L
        present = false
        val g = ++gen
        readerThread = Thread({ readLoop(g) }, "portal-ha-presence-log").also { it.isDaemon = true; it.start() }
        checkHandler.post(checkRunnable)
        Log.i(TAG, "PresenceMonitor started")
    }

    fun stop() {
        running = false
        checkHandler.removeCallbacks(checkRunnable)
        runCatching { process?.destroy() }
        process = null
        if (present) { present = false; onPresenceChange(false) }
        Log.i(TAG, "PresenceMonitor stopped")
    }

    fun release() {
        stop()
        checkThread.quitSafely()
    }

    // fleet (2026-11-09, audit bridge-code.9): the reader ran logcat ONCE - if logcat ended (EOF / exception) the thread
    // was gone while 'running' stayed true, so presence went silently dead until the next Bridge restart. Now it starts
    // logcat again with a backoff (2 s doubling to 60 s, reset after a reader that lived 5 min); a replayed backlog line
    // is still ignored by its own epoch stamp (FRESH_MS). Liveness for the self-heal status line: [status].
    @Volatile private var readerStarts = 0
    @Volatile private var readerAlive = false
    @Volatile private var lastReaderEnd = ""
    fun status(): String = "presence: reader=${if (!running) "stopped" else if (readerAlive) "alive" else "restarting"} starts=$readerStarts lastEnd='$lastReaderEnd'"

    @Volatile private var gen = 0
    private fun readLoop(myGen: Int) {
        var backoff = 2_000L
        while (running && myGen == gen) {
            val began = android.os.SystemClock.elapsedRealtime()
            readerStarts++
            readerAlive = true
            val why = readOnce()
            readerAlive = false
            if (!running || myGen != gen) break
            lastReaderEnd = why
            if (android.os.SystemClock.elapsedRealtime() - began > 5 * 60_000L) backoff = 2_000L
            Log.w(TAG, "PresenceMonitor reader ended ($why) - restarting in ${backoff / 1000} s (start #${readerStarts + 1})")
            runCatching { Thread.sleep(backoff) }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    /** One logcat session; returns why it ended. */
    private fun readOnce(): String {
        try {
            // -v epoch → each line is prefixed with a Unix timestamp we can age-check.
            val p = ProcessBuilder(
                "logcat", "-v", "epoch",
                "-s", "PresenceManager:I", "aloha.CameraServiceController:I"
            ).redirectErrorStream(true).start()
            process = p
            p.inputStream.bufferedReader().use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    if (!line.contains("presence", ignoreCase = true)) continue
                    // fleet (2026-10-23): judge the MESSAGE only. The whole line holds the tag, and 'PresenceManager'
                    // itself contains the word, so EVERY PresenceManager line counted as a face beat - and Meta logs a
                    // burst of them on every screen-off ("Screen Off current state", "transitionToState [STANDBY]",
                    // "... by [com.facebook.alohaservices.presence]") and every wake: 'presence ON' for 50 s right after
                    // each screen timeout with nobody there (Coffee Area 01:47:08 -> 01:47:16, Bar 03:56:36 -> 03:56:44,
                    // 04:54:07 -> 04:54:16) - the wall wake loop's 'meta_presence'. Real detections still count:
                    // "Notify people presence", "onNotifyPresence presence updated [...]", "onNotifyPresence, awake".
                    val msg = line.substringAfter(": ", "").replace("alohaservices.presence", "", ignoreCase = true)
                    if (!msg.contains("presence", ignoreCase = true)) continue
                    val epoch = line.trimStart().substringBefore(' ').toDoubleOrNull() ?: continue
                    val beatMs = (epoch * 1000).toLong()
                    // Only count beats that are genuinely recent (ignore backlog).
                    if (System.currentTimeMillis() - beatMs < FRESH_MS) {
                        lastBeatMs = System.currentTimeMillis()
                    }
                }
            }
            runCatching { process?.destroy() }
            return "logcat ended"
        } catch (e: Exception) {
            Log.w(TAG, "PresenceMonitor reader stopped: ${e.message} (READ_LOGS granted?)")
            runCatching { process?.destroy() }
            return "error: ${e.message}"
        }
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val live = lastBeatMs != 0L && System.currentTimeMillis() - lastBeatMs < ABSENT_MS
            if (live != present) {
                present = live
                Log.i(TAG, "presence -> ${if (live) "DETECTED" else "CLEAR"}")
                onPresenceChange(live)
            }
            checkHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }
}
