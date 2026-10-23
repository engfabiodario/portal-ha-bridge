package com.aeonos.portalha

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter

/**
 * Fleet (2026-10-23): a small persistent trace of the Wi-Fi stack's own log lines, so a Wi-Fi outage can be
 * explained the next morning. Why: on the Portal 10" logcat holds only ~45 s (bt_stack VERBOSE floods it at
 * 200-270 lines/s), so the 30-min rejoins after a Deco reboot (Upstairs Hallway / Guest 00:30, Downstairs Hallway
 * 03:00 on 10-23) left no evidence at all.
 *
 * Tails `logcat -s` for the Wi-Fi tags only (filtered inside logd: a few lines a minute while connected) into
 * <externalFilesDir>/wifi/wifi-trace.txt, rotated at [MAX_BYTES] to wifi-trace.1.txt (so <= ~2 MB in all).
 * Readable with the Fleet Agent: /fs/read?path=/sdcard/Android/data/com.aeonos.portalha/files/wifi/wifi-trace.txt
 * Needs READ_LOGS (already granted for the presence monitor); without it, it logs once and does nothing.
 * Never throws; restarts the reader with a 60 s pause if logcat ends.
 */
object WifiTrace {
    private const val TAG = "PortalHA"
    private const val MAX_BYTES = 1_000_000L
    private val FILTER = listOf(
        "wpa_supplicant:I", "WifiClientModeImpl:D", "WifiStateMachine:D", "WifiNetworkSelector:D",
        "WifiConnectivityManager:D", "WifiConfigManager:D", "WificondScannerImpl:D", "WificondControl:D",
        "WifiScanningService:D", "WifiDiagnosticStateMachine:D", "FbaWifiEvent:D", "IpClient.wlan0:D",
        "SupplicantStaIfaceHal:D", "WifiService:I", "SelfHeal:I"
    )

    @Volatile private var running = false
    @Volatile private var process: Process? = null
    private var thread: Thread? = null

    fun start(ctx: Context) {
        if (running) return
        val base = ctx.getExternalFilesDir(null) ?: return
        val dir = File(base, "wifi").apply { mkdirs() }
        running = true
        thread = Thread({ loop(dir) }, "portal-ha-wifitrace").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        runCatching { process?.destroy() }
        process = null
    }

    private fun loop(dir: File) {
        var first = true
        while (running) {
            try {
                // -T 1: start at the newest line (no backlog dump into the file on every restart).
                val cmd = listOf("logcat", "-v", "threadtime", "-T", "1", "-s") + FILTER
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                process = p
                if (first) { Log.i(TAG, "wifi trace: recording to ${File(dir, "wifi-trace.txt")}"); first = false }
                var out = FileWriter(File(dir, "wifi-trace.txt"), true)
                var size = File(dir, "wifi-trace.txt").length()
                out.write("---- wifi trace started ${java.util.Date()}\n"); out.flush()
                p.inputStream.bufferedReader().use { r ->
                    while (running) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("--------- beginning of")) continue
                        out.write(line); out.write("\n"); out.flush()
                        size += line.length + 1
                        if (size > MAX_BYTES) {
                            runCatching { out.close() }
                            val cur = File(dir, "wifi-trace.txt"); val old = File(dir, "wifi-trace.1.txt")
                            runCatching { old.delete(); cur.renameTo(old) }
                            out = FileWriter(File(dir, "wifi-trace.txt"), true); size = 0L
                        }
                    }
                }
                runCatching { out.close() }
            } catch (e: Exception) {
                Log.w(TAG, "wifi trace: reader stopped: ${e.message} (READ_LOGS granted?)")
            }
            if (running) runCatching { Thread.sleep(60_000L) }
        }
    }
}
