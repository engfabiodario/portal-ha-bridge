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
        "SupplicantStaIfaceHal:D", "WifiService:I", "SelfHeal:I",
        // fleet (wifi recovery 2026-10-29): the framework's own Wi-Fi shutdown/restart paths (AOSP 10 SelfRecovery
        // 'STA interface down, disable wifi' / 'Already restarted wifi (2) times ... Disabling wifi', WifiController
        // 'Recovery has been throttled, disable wifi', last-resort watchdog, HAL/driver), and the helper's lines.
        "SelfRecovery:V", "WifiController:V", "WifiLastResortWatchdog:V", "WifiNative:I", "WifiVendorHal:I",
        "HalDevMgr:I", "ClientModeManager:V", "ActiveModeWarden:V", "WifiHAL:I", "PortalWifiHelper:V"
    )
    /** Framework network id of the last connect attempt ('CMD_START_CONNECT ... nid=N' / 'Evaluator selects "x":N').
     *  Apps without location get networkId -1 from getConnectionInfo(), so SelfHeal takes it from here for
     *  enableNetwork(nid, true). -1 = not seen yet. */
    @Volatile var lastNetId = -1
        private set
    private val NID = Regex("""CMD_START_CONNECT .* nid=(\d+)|Evaluator selects ".*":(\d+)""")
    /** Outage snapshots (wifi/outage-<start>.txt, newest 7 kept, never rotated): every line from the first
     *  'Entering DisconnectedState' for 30 min, so a multi-day outage keeps its start
     *  even after wifi-trace.txt + .1 (2 MB) rotated past it. */
    private const val SNAP_MS = 30 * 60_000L
    private const val SNAP_KEEP = 7

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
                runCatching { snapOut?.close() }; snapOut = null
            } catch (e: Exception) {
                Log.w(TAG, "wifi trace: reader stopped: ${e.message} (READ_LOGS granted?)")
            }
            if (running) runCatching { Thread.sleep(60_000L) }
        }
    }

    private var snapOut: FileWriter? = null
    private var snapUntil = 0L
    private fun snapshot(dir: File, line: String) {
        val now = System.currentTimeMillis()
        if (snapOut == null && line.contains("WifiDiagnosticStateMachine: Entering DisconnectedState")) {
            runCatching {
                val f = File(dir, "outage-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date(now)) + ".txt")
                snapOut = FileWriter(f, true); snapUntil = now + SNAP_MS
                dir.listFiles { x -> x.name.startsWith("outage-") }?.sortedBy { it.name }?.dropLast(SNAP_KEEP)?.forEach { it.delete() }
            }
        }
        val o = snapOut ?: return
        runCatching { o.write(line); o.write("\n"); o.flush() }
        if (now > snapUntil) {   // a fixed 30-min window: the nightly flaps (5-8 drops) stay in ONE file
            runCatching { o.close() }; snapOut = null
        }
    }
}
