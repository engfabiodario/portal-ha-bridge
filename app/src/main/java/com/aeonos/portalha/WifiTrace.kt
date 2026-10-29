package com.aeonos.portalha

import android.content.Context
import android.net.ConnectivityManager
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
        "HalDevMgr:I", "ClientModeManager:V", "ActiveModeWarden:V", "WifiHAL:I", "PortalWifiHelper:V",
        // fleet (2026-10-29): WHO disables auto-join. Upstairs Hallway Guest sat 3 days off Wi-Fi with
        // "Dario-WiFi-IoT" NETWORK_SELECTION_DISABLED_NO_INTERNET_PERMANENT (persisted across reboots); that is set
        // only via ConnectivityService (no-internet / avoid-bad-Wi-Fi decision -> preventAutomaticReconnect ->
        // ClientModeImpl 'NETWORK_STATUS_UNWANTED_DISABLE_AUTOJOIN'). Validation itself is NetworkMonitor/<netId>
        // (per-network tag: see [nmTags]); the no-internet notification is NetworkNotificationManager.
        "ConnectivityService:D", "NetworkNotificationManager:D", "NetworkMonitor:D", "WifiNetworkFactory:D",
        "MultinetworkPolicyTracker:D", "WifiNoInternetDialog:D"
    )
    /** NetworkMonitor logs as 'NetworkMonitor/<netId>' (Android 10 NetworkStack): logcat -s can't wildcard, so the
     *  reader filters a window of ids from the newest network on (ids only grow, one per (re)connect) and is
     *  restarted with a new window when the Wi-Fi network id gets near its end. */
    private const val NM_WINDOW = 40
    @Volatile private var nmStart = -1
    private var appCtx: Context? = null
    private fun newestNetId(): Int = runCatching {
        val cm = appCtx?.getSystemService(ConnectivityManager::class.java)
        cm?.allNetworks?.mapNotNull { it.toString().toIntOrNull() }?.maxOrNull()
    }.getOrNull() ?: -1
    private fun nmTags(): List<String> {
        val id = newestNetId()
        nmStart = if (id > 0) (id - 2).coerceAtLeast(100) else 100
        return (nmStart until nmStart + NM_WINDOW).map { "NetworkMonitor/$it:D" }
    }
    /** Framework network id of the last connect attempt ('CMD_START_CONNECT ... nid=N' / 'Evaluator selects "x":N').
     *  Apps without location get networkId -1 from getConnectionInfo(), so SelfHeal takes it from here for
     *  enableNetwork(nid, true). -1 = not seen yet. */
    @Volatile var lastNetId = -1
        private set
    // Also the selector's 'Disabled configured networks:' entry ('WifiNetworkSelector:   "Dario-WiFi-IoT":0 reason=...'):
    // a disabled network never reaches CMD_START_CONNECT, and that is exactly when SelfHeal needs its id.
    private val NID = Regex("""CMD_START_CONNECT .* nid=(\d+)|Evaluator selects ".*":(\d+)|WifiNetworkSelector: {2,}"[^"]*":(\d+)""")
    private fun nidOf(line: String): Int? = NID.find(line)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
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
        appCtx = ctx.applicationContext
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
        // The id from earlier traces (a Portal connected since boot logs no connect line until it drops).
        if (lastNetId < 0) runCatching {
            for (f in listOf(File(dir, "wifi-trace.1.txt"), File(dir, "wifi-trace.txt"))) {
                if (f.exists()) f.forEachLine { l -> nidOf(l)?.let { lastNetId = it } }
            }
            Log.i(TAG, "wifi trace: network id from earlier traces: $lastNetId")
        }
        while (running) {
            try {
                // -T 1: start at the newest line (no backlog dump into the file on every restart).
                val cmd = listOf("logcat", "-v", "threadtime", "-T", "1", "-s") + FILTER + nmTags()
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                process = p
                if (first) { Log.i(TAG, "wifi trace: recording to ${File(dir, "wifi-trace.txt")}"); first = false }
                var out = FileWriter(File(dir, "wifi-trace.txt"), true)
                var size = File(dir, "wifi-trace.txt").length()
                out.write("---- wifi trace started ${java.util.Date()} (NetworkMonitor/$nmStart..${nmStart + NM_WINDOW - 1})\n"); out.flush()
                var nextWindowCheck = System.currentTimeMillis() + 60_000L
                var rewindow = false
                p.inputStream.bufferedReader().use { r ->
                    while (running) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("--------- beginning of")) continue
                        out.write(line); out.write("\n"); out.flush()
                        // fleet (2026-10-29): this per-line hook was MISSING in 73dcfb3/87006cc2 (lastNetId stayed -1,
                        // so SelfHeal's enableNetwork step never ran, and no outage-*.txt was ever written).
                        nidOf(line)?.let { lastNetId = it }
                        snapshot(dir, line)
                        val nowMs = System.currentTimeMillis()
                        if (nowMs > nextWindowCheck) {          // ~once a minute: the newest network still in the window?
                            nextWindowCheck = nowMs + 60_000L
                            if (newestNetId() > nmStart + NM_WINDOW - 6) { rewindow = true; break }
                        }
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
                if (rewindow) { runCatching { p.destroy() }; continue }   // new NetworkMonitor window, no 60 s pause
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
