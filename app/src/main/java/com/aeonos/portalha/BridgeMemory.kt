package com.aeonos.portalha

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bridge memory sensor (fleet 2026-10-22): sensor.<slug>_bridge_memory (diagnostic, MB) so a leak can be tracked
 * against the Bridge's uptime. State = this app process's PSS (Debug.getMemoryInfo of our own process: no
 * permission, not rate-limited like ActivityManager.getProcessMemoryInfo); attributes = its summary split (Java /
 * native heap, graphics, code, ...), the system's free RAM (ActivityManager.MemoryInfo), the process uptime and the
 * WebView renderer's PSS when Android lets us see that process (it runs isolated - usually it doesn't: null).
 *
 * Sampled every [SAMPLE_MS] (5 min, first [FIRST_MS] after start) on its own thread; published when the PSS moved
 * >= 10 % since the last report, every [REFRESH_MS] (1 h), and with every MQTT state burst (force).
 */
class BridgeMemory(private val ctx: Context, private val sink: (state: String, attributesJson: String) -> Boolean) {

    private val thread = HandlerThread("portal-ha-memory").also { it.start() }
    private val handler = Handler(thread.looper)
    @Volatile private var started = false
    private var lastPss = 0.0
    private var lastPublishMs = 0L
    private var loggedRenderer = false

    private val tick = object : Runnable {
        override fun run() {
            sampleAndMaybePublish(force = false)
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        handler.postDelayed(tick, FIRST_MS)
    }

    fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    /** MQTT (re)connect / state burst: report now (off the caller's thread). */
    fun republish() { if (started) handler.post { sampleAndMaybePublish(force = true) } }

    private fun sampleAndMaybePublish(force: Boolean) {
        runCatching {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            val pss = mi.totalPss / 1024.0
            val now = SystemClock.elapsedRealtime()
            val moved = lastPss > 0 && kotlin.math.abs(pss - lastPss) >= lastPss * CHANGE_FRACTION
            if (!force && lastPublishMs != 0L && !moved && now - lastPublishMs < REFRESH_MS - 30_000L) return
            val stats = mi.memoryStats
            fun stat(k: String): String = stats[k]?.toLongOrNull()?.let { mb(it / 1024.0) } ?: "null"
            val am = ctx.getSystemService(ActivityManager::class.java)
            val sys = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val upH = (now - Process.getStartElapsedRealtime()) / 3_600_000.0
            val load = loadAvg()
            val attrs = "{\"pss_mb\":${mb(pss)},\"java_heap_mb\":${stat("summary.java-heap")}," +
                "\"native_heap_mb\":${stat("summary.native-heap")},\"graphics_mb\":${stat("summary.graphics")}," +
                "\"code_mb\":${stat("summary.code")},\"stack_mb\":${stat("summary.stack")}," +
                "\"private_other_mb\":${stat("summary.private-other")},\"system_mb\":${stat("summary.system")}," +
                "\"webview_renderer_pss_mb\":${rendererPssMb(am)}," +
                "\"system_free_mb\":${mb(sys.availMem / 1048576.0)},\"system_total_mb\":${mb(sys.totalMem / 1048576.0)}," +
                "\"system_low_memory\":${sys.lowMemory},\"low_memory_threshold_mb\":${mb(sys.threshold / 1048576.0)}," +
                "\"process_uptime_h\":${"%.2f".format(Locale.US, upH)},\"device_uptime_h\":${"%.2f".format(Locale.US, now / 3_600_000.0)}," +
                "\"load_1m\":${load[0]},\"load_5m\":${load[1]},\"load_15m\":${load[2]},\"swap_used_mb\":${swapUsedMb()}," +
                "\"sampled_at\":\"${isoNow()}\"}"
            if (sink(mb(pss), attrs)) {
                if (lastPublishMs == 0L || moved) Log.i(TAG, "memory: pss ${mb(pss)} MB, system free ${mb(sys.availMem / 1048576.0)} MB" +
                    (if (moved) " (moved >= 10 % from ${mb(lastPss)})" else ""))
                lastPss = pss; lastPublishMs = now
            }
        }.onFailure { Log.w(TAG, "memory: sample failed: ${it.message}") }
    }

    // The WebView renderer is an isolated process: getRunningAppProcesses normally hides it from us. If Android
    // does list it, its PSS; else null (no new permission is asked for).
    private fun rendererPssMb(am: ActivityManager?): String = runCatching {
        val procs = am?.runningAppProcesses ?: return "null"
        val r = procs.filter { it.pid != Process.myPid() && (it.processName.contains("sandboxed_process") || it.processName.contains("webview", true)) }
        if (!loggedRenderer) { loggedRenderer = true; Log.i(TAG, "memory: visible processes ${procs.joinToString { it.processName }}") }
        if (r.isEmpty()) return "null"
        val total = am.getProcessMemoryInfo(r.map { it.pid }.toIntArray()).sumOf { it.totalPss }
        if (total <= 0) "null" else mb(total / 1024.0)
    }.getOrDefault("null")

    // fleet (2026-10-23): system load + swap, to spot a Portal whose Meta services are stuck busy (Upstairs Hallway:
    // load 23-29 for hours vs 2-12 on the other Portal 10", 1.4 GB swapped vs ~0.75 GB, dashboard probes timing out).
    // /proc/loadavg when SELinux lets an app read it, else toybox `uptime` (sysinfo(), allowed for apps); null if neither.
    private fun loadAvg(): Array<String> = runCatching {
        val txt = runCatching { java.io.File("/proc/loadavg").readText() }.getOrNull()
            ?: run {
                val pr = ProcessBuilder("uptime").redirectErrorStream(true).start()
                val out = pr.inputStream.bufferedReader().use { it.readText() }
                pr.waitFor(); out.substringAfter("load average:", "")
            }
        val v = Regex("[0-9]+[.][0-9]+").findAll(txt).map { it.value }.take(3).toList()
        if (v.size == 3) v.toTypedArray() else arrayOf("null", "null", "null")
    }.getOrDefault(arrayOf("null", "null", "null"))

    private fun swapUsedMb(): String = runCatching {
        val m = java.io.File("/proc/meminfo").readLines().associate { l ->
            l.substringBefore(':') to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L)
        }
        val total = m["SwapTotal"] ?: return@runCatching "null"
        mb((total - (m["SwapFree"] ?: 0L)) / 1024.0)
    }.getOrDefault("null")

    private fun mb(v: Double) = "%.1f".format(Locale.US, v)

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        .let { it.substring(0, it.length - 2) + ":" + it.substring(it.length - 2) }

    companion object {
        private const val TAG = "PortalHA"
        private const val SAMPLE_MS = 5 * 60_000L
        private const val FIRST_MS = 3 * 60_000L
        private const val REFRESH_MS = 60 * 60_000L
        private const val CHANGE_FRACTION = 0.10
    }
}
