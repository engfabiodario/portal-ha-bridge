package com.aeonos.portalha

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fleet (2026-10-08): why did the Bridge's process end?
 *
 *  - Uncaught exceptions: a default handler (chained to the previous one, so Android still shows /
 *    kills as before) writes timestamp + thread + stack trace to
 *    <externalFilesDir>/crash/last-crash.txt, keeping the last 5 (last-crash.1.txt .. .4.txt).
 *  - Silent deaths (low-memory kill, native crash, adb force-stop, watchdog): Android 9/10 have no
 *    ApplicationExitInfo, so the service writes crash/heartbeat.txt every minute ("ts=... pid=...
 *    clean=false") and rewrites it with clean=true when it is destroyed normally. On the next start a
 *    heartbeat without clean=true = "previous run ended unexpectedly at <last heartbeat>" (logged +
 *    crash/previous-exit.txt; an app install/update after that heartbeat is named as the likely cause).
 *
 * fleet (2026-11-09, audit bridge-code.2): the heartbeat also carries elapsedRealtime (er=) and the kernel boot id
 * (boot=), so the verdict tells a process death inside the same boot ('same boot, process gone N s after its last
 * heartbeat') from a reboot / power loss - wall-clock stamps alone mislead (Kitchen 10-31: the clock was 16 days behind
 * after a boot without network). After an unexpected end the service saves what Android logged about the old pid:
 * crash/exit-MMdd-HHmmss.txt = the events / system / main / crash buffer lines naming the package or the old pid
 * (am_kill, am_proc_died, lowmemorykiller, ANR, WIN DEATH ...), capped at [EXIT_DUMP_LINES] lines, newest 7 kept.
 *
 * Path on the Portal: /sdcard/Android/data/com.aeonos.portalha/files/crash/ (Fleet Agent /fs/read).
 * Writes nothing outside that folder; never throws.
 */
object CrashRecorder {
    private const val TAG = "CrashRecorder"
    private const val KEEP = 5
    private const val HEARTBEAT_MS = 60_000L
    private const val EXIT_DUMP_LINES = 500
    private const val EXIT_KEEP = 7

    private fun bootId(): String =
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim().take(36) }.getOrDefault("?")

    @Volatile private var installed = false
    @Volatile private var dir: File? = null
    private var hbThread: HandlerThread? = null
    private var hbHandler: Handler? = null
    @Volatile private var hbRunning = false
    @Volatile private var lastPreviousExit = ""
    private val bootIdCached: String by lazy { bootId() }

    private fun crashDir(ctx: Context): File? = dir ?: runCatching {
        val base = ctx.applicationContext.getExternalFilesDir(null) ?: ctx.applicationContext.filesDir
        File(base, "crash").also { it.mkdirs(); dir = it }
    }.getOrNull()

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(ms))

    /** Install the uncaught-exception recorder (idempotent; chains to whatever handler was there). */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val d = crashDir(ctx)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching { writeCrash(d, thread, ex) }
            if (prev != null) prev.uncaughtException(thread, ex)
            else { Process.killProcess(Process.myPid()); System.exit(10) }
        }
        Log.i(TAG, "crash recorder on: ${d?.absolutePath ?: "no folder"}/last-crash.txt (last $KEEP kept)")
    }

    private fun writeCrash(d: File?, thread: Thread, ex: Throwable) {
        if (d == null) return
        for (i in KEEP - 1 downTo 1) {
            val from = if (i == 1) File(d, "last-crash.txt") else File(d, "last-crash.${i - 1}.txt")
            val to = File(d, "last-crash.$i.txt")
            if (from.exists()) { to.delete(); from.renameTo(to) }
        }
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        File(d, "last-crash.txt").writeText(
            "time=${stamp(System.currentTimeMillis())}\n" +
            "pid=${Process.myPid()} thread=${thread.name} (id ${thread.id})\n" +
            "version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\n" + sw.toString()
        )
        Log.e(TAG, "uncaught exception on ${thread.name} recorded in ${d.absolutePath}/last-crash.txt")
    }

    /**
     * Service start: judge the previous run from the heartbeat file, then keep the heartbeat going.
     * Returns the one-line verdict (also logged).
     */
    fun onServiceStart(ctx: Context): String {
        val d = crashDir(ctx) ?: return "crash folder unavailable"
        val hb = File(d, "heartbeat.txt")
        val verdict = runCatching {
            if (!hb.exists()) return@runCatching "no previous heartbeat (first run with the crash recorder)"
            val kv = hb.readText().trim().split(' ').mapNotNull { p ->
                val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i) to p.substring(i + 1)
            }.toMap()
            val ts = kv["ts"]?.toLongOrNull() ?: 0L
            val er = kv["er"]?.toLongOrNull()
            val sameBoot = kv["boot"] != null && kv["boot"] == bootId()
            val boot = when {
                kv["boot"] == null -> ""
                sameBoot && er != null -> "; same boot, ${(android.os.SystemClock.elapsedRealtime() - er) / 1000} s after its last heartbeat (uptime then ${er / 1000} s)"
                else -> "; the Portal REBOOTED since (or lost power)"
            }
            if (kv["clean"] == "true") return@runCatching "previous run ended cleanly at ${stamp(ts)}$boot"
            val updated = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            val cause = when {
                updated > ts -> " (app installed/updated at ${stamp(updated)} - likely the cause)"
                File(d, "last-crash.txt").lastModified() >= ts -> " (see last-crash.txt)"
                else -> " (no Java crash recorded: killed - low memory, force-stop, native crash or power loss)"
            }
            val line = "previous run ended unexpectedly at ${stamp(ts)} (last heartbeat, pid ${kv["pid"] ?: "?"})$cause$boot"
            runCatching {
                File(d, "previous-exit.txt").writeText("recorded=${stamp(System.currentTimeMillis())}\n$line\n")
            }
            // What Android logged about the old process, while its ring buffers still hold it (own thread: logcat -d is slow).
            if (sameBoot) kv["pid"]?.let { oldPid -> Thread({ saveExitDump(ctx, d, oldPid, line) }, "portal-ha-exitdump").also { it.isDaemon = true }.start() }
            line
        }.getOrElse { "heartbeat unreadable: ${it.message}" }
        lastPreviousExit = verdict
        Log.i(TAG, "$verdict - files in ${d.absolutePath}")
        startHeartbeat(d)
        return verdict
    }

    /** crash/exit-<stamp>.txt: every buffer line that names our package or the old pid (capped, newest [EXIT_KEEP] kept). */
    private fun saveExitDump(ctx: Context, d: File, oldPid: String, verdict: String) {
        runCatching {
            val pkg = ctx.packageName
            val keep = ArrayList<String>()
            for (buf in listOf("events", "system", "crash", "main")) {
                val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", buf, "-t", "20000").redirectErrorStream(true).start()
                val pidTok = Regex("(^|[^0-9])$oldPid([^0-9]|$)")
                p.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { l ->
                        if (l.contains(pkg) || l.contains("lowmemorykiller") || l.contains("lmkd") ||
                            (l.contains("am_") && pidTok.containsMatchIn(l)) || (l.contains("ActivityManager") && pidTok.containsMatchIn(l))) {
                            keep += "[$buf] $l"
                        }
                    }
                }
                runCatching { p.waitFor() }
            }
            val lines = if (keep.size > EXIT_DUMP_LINES) keep.takeLast(EXIT_DUMP_LINES) else keep
            val f = File(d, "exit-" + SimpleDateFormat("MMdd-HHmmss", Locale.US).format(Date()) + ".txt")
            f.writeText("recorded=${stamp(System.currentTimeMillis())} boot=${bootId()} er=${android.os.SystemClock.elapsedRealtime()}\n" +
                "$verdict\nold pid $oldPid; ${keep.size} matching line(s), last ${lines.size} kept\n\n" + lines.joinToString("\n") + "\n")
            d.listFiles { x -> x.name.startsWith("exit-") }?.sortedBy { it.name }?.dropLast(EXIT_KEEP)?.forEach { it.delete() }
            Log.i(TAG, "exit dump: ${f.name} (${lines.size} lines about pid $oldPid)")
        }.onFailure { Log.w(TAG, "exit dump failed: ${it.message}") }
    }

    /** Service destroyed normally: mark the run as ended cleanly. */
    fun onServiceStop() {
        hbRunning = false
        hbHandler?.removeCallbacksAndMessages(null)
        runCatching { hbThread?.quitSafely() }
        hbThread = null; hbHandler = null
        writeHeartbeat(clean = true)
    }

    fun status(): String = "crash: dir=${dir?.absolutePath ?: "none"} previous='$lastPreviousExit'"

    private fun startHeartbeat(d: File) {
        if (hbRunning) return
        hbRunning = true
        val t = HandlerThread("portal-ha-heartbeat").also { it.start() }
        val h = Handler(t.looper)
        hbThread = t; hbHandler = h
        val beat = object : Runnable {
            override fun run() {
                if (!hbRunning) return
                writeHeartbeat(clean = false)
                h.postDelayed(this, HEARTBEAT_MS)
            }
        }
        h.post(beat)
    }

    private fun writeHeartbeat(clean: Boolean) {
        val d = dir ?: return
        runCatching {
            val tmp = File(d, "heartbeat.tmp")
            tmp.writeText("ts=${System.currentTimeMillis()} pid=${Process.myPid()} clean=$clean er=${android.os.SystemClock.elapsedRealtime()} boot=$bootIdCached\n")
            if (!tmp.renameTo(File(d, "heartbeat.txt"))) {
                File(d, "heartbeat.txt").writeText(tmp.readText()); tmp.delete()
            }
        }
    }
}
