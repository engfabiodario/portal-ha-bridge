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
 *
 * fleet (2026-11-09, audit bridge-code.6/.11):
 *  - NetworkMonitor's HEALTHY re-validation cycle (Forcing reevaluation / GK enabled / Multiple Parallel Probing /
 *    PROBE_DNS ... OK / PROBE_HTTP(S) ... ret=204 / isCaptivePortal isSuccessful()=true) was 71-76 % of the trace bytes
 *    on the Portal 10" (1 MB = only ~5 h): those lines are counted, not written - one summary line every 10 min and
 *    before any other NetworkMonitor line. Every failing / unusual NetworkMonitor line is still written in full.
 *  - The first 30 min after each Bridge process start also go to wifi/start-MMdd-HHmmss.txt (newest 7 kept), like the
 *    outage-*.txt snapshots: a process restart in the night keeps its context after the trace rotated past it.
 *  - Meta's own gateway checker (tags DGW, CollectPingData: 'ConnectivityManager ping failure', gateway ping results)
 *    is recorded; [lastApSuffix] = the last 3 octets of the AP the supplicant associated with (for SelfHeal's
 *    'lan: ok -> bad' link line; apps without location get a masked BSSID from WifiInfo).
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
        "MultinetworkPolicyTracker:D", "WifiNoInternetDialog:D",
        // fleet (2026-11-09, audit bridge-code.11): Meta's gateway ping checker (seen on the Portal+ in the Kitchen 10-31 logcat).
        "DGW:V", "CollectPingData:V"
    )
    /** NetworkMonitor lines of a healthy re-validation (counted, not written). */
    private val NM_HEALTHY = Regex(
        "NetworkMonitor/\\d+: (Forcing reevaluation for UID|GK enabled:|Multiple Parallel Probing:|PROBE_DNS \\S+ \\d+ms OK|" +
        "PROBE_HTTPS? \\S+ time=\\d+ms ret=204 |isCaptivePortal: isSuccessful\\(\\)=true isPortal\\(\\)=false)")
    private const val NM_SUMMARY_MS = 10 * 60_000L
    private var nmSuppressed = 0
    private var nmSuppressedSinceMs = 0L
    /** Last 3 octets of the AP the supplicant last associated with ('' = not seen yet). */
    @Volatile var lastApSuffix = ""
        private set
    private val AP = Regex("(?:Associated with|CTRL-EVENT-CONNECTED - Connection to) ([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})")
    private const val START_SNAP_MS = 30 * 60_000L
    private var startOut: FileWriter? = null
    private var startUntil = 0L
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
    // fleet (2026-11-21, review b4-12): reader generation. restart_service = stop + start in the same process: the old
    // reader woke from its 60 s sleep with running=true again and a SECOND reader wrote / rotated the same file.
    @Volatile private var gen = 0

    // fleet (2026-11-21, review b4-04): Meta's routine chatter (87 % of a 1 MB trace = 4 h on 11-19) is counted, not
    // written: the 5 healthy DGW stream-group shapes, every CollectPingData line of a ping with 0 % loss, and repeated
    // NUD_FAILED lines per neighbour. A ping with loss, any other DGW line and the first NUD_FAILED per neighbour per
    // 10 min are written in full; a summary line every 10 min.
    private val DGW_ROUTINE = Regex("DGW\\s*: .*(DGW is now connected|DGW for serviceId=\\S+ is now connected|processing request with connection state|" +
        "Request is not queued for serviceId|notify StreamGroup error: TransientError|notify sgt \\(\\S+\\) error TransientError)")
    private val PING_LOSS = Regex("CollectPingData: \\d+ packets transmitted, \\d+ received, (\\d+)% packet loss")
    private val PING_RTT = Regex("CollectPingData: rtt min/avg/max/mdev = [0-9.]+/([0-9.]+)/")
    private val NUD = Regex("NUD_FAILED.*?((?:\\d{1,3}\\.){3}\\d{1,3}|[0-9a-fA-F:]{6,})")
    private const val ROUTINE_SUMMARY_MS = 10 * 60_000L
    private var rtDgw = 0
    private var rtPing = 0
    private var rtPingRuns = 0
    private var rtRttMax = 0.0
    private var rtNud = 0
    private var rtSinceMs = 0L
    private val nudSeen = HashMap<String, Long>()

    /** True = a routine line, counted instead of written. */
    private fun routine(line: String, now: Long): Boolean {
        if (rtSinceMs == 0L) rtSinceMs = now
        if (line.contains("DGW")) {
            if (DGW_ROUTINE.containsMatchIn(line)) { rtDgw++; return true }
            return false
        }
        if (line.contains("CollectPingData")) {
            PING_LOSS.find(line)?.let { m -> return if ((m.groupValues[1].toIntOrNull() ?: 0) == 0) { rtPing++; rtPingRuns++; true } else false }
            PING_RTT.find(line)?.let { m -> m.groupValues[1].toDoubleOrNull()?.let { if (it > rtRttMax) rtRttMax = it } }
            rtPing++
            return true
        }
        if (line.contains("NUD_FAILED")) {
            val key = NUD.find(line)?.groupValues?.get(1) ?: "?"
            val last = nudSeen[key]
            if (last != null && now - last < ROUTINE_SUMMARY_MS) { rtNud++; return true }
            nudSeen[key] = now
            return false
        }
        return false
    }

    /** The 10-minute summary line of the routine lines not written, or null. */
    private fun routineSummary(now: Long): String? {
        if (rtSinceMs == 0L || now - rtSinceMs < ROUTINE_SUMMARY_MS) return null
        val s = if (rtDgw + rtPing + rtNud == 0) null
            else "---- routine since ${java.util.Date(rtSinceMs)}: DGW $rtDgw line(s), CollectPingData $rtPing line(s) " +
                "($rtPingRuns ping(s) at 0 % loss, worst avg rtt ${"%.1f".format(java.util.Locale.US, rtRttMax)} ms), NUD_FAILED repeats $rtNud - not written\n"
        rtDgw = 0; rtPing = 0; rtPingRuns = 0; rtRttMax = 0.0; rtNud = 0; rtSinceMs = now
        if (nudSeen.size > 64) nudSeen.clear()
        return s
    }

    fun start(ctx: Context) {
        if (running && thread?.isAlive == true) return
        appCtx = ctx.applicationContext
        val base = ctx.getExternalFilesDir(null) ?: return
        val dir = File(base, "wifi").apply { mkdirs() }
        val myGen = ++gen
        running = true
        // The first 30 min of this process (audit bridge-code.6).
        runCatching {
            val now = System.currentTimeMillis()
            val f = File(dir, "start-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date(now)) + ".txt")
            startOut = FileWriter(f, true).also { it.write("---- Bridge process start ${java.util.Date(now)} pid=${android.os.Process.myPid()}\n"); it.flush() }
            startUntil = now + START_SNAP_MS
            dir.listFiles { x -> x.name.startsWith("start-") }?.sortedBy { it.name }?.dropLast(SNAP_KEEP)?.forEach { it.delete() }
        }
        thread = Thread({ loop(dir, myGen) }, "portal-ha-wifitrace").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        gen++
        runCatching { process?.destroy() }
        process = null
    }

    private fun loop(dir: File, myGen: Int) {
        var first = true
        // The id from earlier traces (a Portal connected since boot logs no connect line until it drops).
        if (lastNetId < 0) runCatching {
            for (f in listOf(File(dir, "wifi-trace.1.txt"), File(dir, "wifi-trace.txt"))) {
                if (f.exists()) f.forEachLine { l -> nidOf(l)?.let { lastNetId = it } }
            }
            Log.i(TAG, "wifi trace: network id from earlier traces: $lastNetId")
        }
        while (running && myGen == gen) {
            // fleet (2026-11-15): the process + file are closed in finally - an exception (write error, storage gone)
            // left the logcat child running (blocked on a full pipe) and the FileWriter open, one more of each per minute.
            var pRef: Process? = null
            var outRef: FileWriter? = null
            try {
                // -T 1: start at the newest line (no backlog dump into the file on every restart).
                val cmd = listOf("logcat", "-v", "threadtime", "-T", "1", "-s") + FILTER + nmTags()
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                pRef = p
                process = p
                if (first) { Log.i(TAG, "wifi trace: recording to ${File(dir, "wifi-trace.txt")}"); first = false }
                var out = FileWriter(File(dir, "wifi-trace.txt"), true)
                outRef = out
                var size = File(dir, "wifi-trace.txt").length()
                out.write("---- wifi trace started ${java.util.Date()} (NetworkMonitor/$nmStart..${nmStart + NM_WINDOW - 1})\n"); out.flush()
                var nextWindowCheck = System.currentTimeMillis() + 60_000L
                var rewindow = false
                p.inputStream.bufferedReader().use { r ->
                    while (running && myGen == gen) {
                        val line = r.readLine() ?: break
                        if (line.startsWith("--------- beginning of")) continue
                        AP.find(line)?.let { m -> lastApSuffix = m.groupValues[1].lowercase().takeLast(8) }
                        startSnapshot(line)
                        val tNow = System.currentTimeMillis()
                        routineSummary(tNow)?.let { sum -> out.write(sum); size += sum.length }
                        if (routine(line, tNow)) continue
                        if (line.contains("NetworkMonitor/")) {
                            if (NM_HEALTHY.containsMatchIn(line)) {
                                if (nmSuppressed == 0) nmSuppressedSinceMs = tNow
                                nmSuppressed++
                                if (tNow - nmSuppressedSinceMs < NM_SUMMARY_MS) continue
                            }
                            if (nmSuppressed > 0) {
                                val sum = "---- NetworkMonitor: $nmSuppressed healthy validation line(s) not written since ${java.util.Date(nmSuppressedSinceMs)}\n"
                                out.write(sum); size += sum.length
                                nmSuppressed = 0
                                if (NM_HEALTHY.containsMatchIn(line)) { out.flush(); continue }
                            }
                        }
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
                            outRef = out
                        }
                    }
                }
                runCatching { out.close() }
                runCatching { snapOut?.close() }; snapOut = null
                if (rewindow) { runCatching { p.destroy() }; continue }   // new NetworkMonitor window, no 60 s pause
            } catch (e: Exception) {
                Log.w(TAG, "wifi trace: reader stopped: ${e.message} (READ_LOGS granted?)")
                runCatching { outRef?.close() }
                runCatching { pRef?.destroy() }
            }
            if (running && myGen == gen) runCatching { Thread.sleep(60_000L) }
        }
        if (myGen != gen) Log.i(TAG, "wifi trace: old reader (generation $myGen) ended")
    }

    private fun startSnapshot(line: String) {
        val o = startOut ?: return
        runCatching { o.write(line); o.write("\n"); o.flush() }
        if (System.currentTimeMillis() > startUntil) { runCatching { o.close() }; startOut = null }
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
