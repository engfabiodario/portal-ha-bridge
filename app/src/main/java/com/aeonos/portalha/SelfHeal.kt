package com.aeonos.portalha

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Soft self-heal: every [TICK_MS] the app checks its OWN components and restarts the ones that
 * stopped working, through the entry points the app already uses for them:
 *
 *  - mqtt      the broker client is disconnected on 2 ticks         -> the app's MQTT reconnect
 *  - stream    RTSP wanted but the encoder produced no frame for 2 ticks -> the streamer's own restart
 *  - webview   the dashboard doesn't answer a trivial evaluateJavascript in 10 s on 2 ticks
 *              -> reload the current start page
 *  - keepalive the Ava keep-alive is on but not parked             -> the keep-alive's own re-park
 *  - wifi      the Wi-Fi link is down for 2 ticks -> Android 9: WifiManager.reconnect() once per
 *              outage; Android 10: reported only
 *
 * It never reboots, never toggles the Wi-Fi radio, never changes a camera switch or preference,
 * never drives another app's UI. A tick is skipped while a call / ringing / alarm / cast / intercom
 * / wake hand-off / navigate page / one of our settings screens is up. Each action at most
 * [MAX_PER_HOUR] per rolling hour, with an exponential backoff between repeats of the same action
 * (reset when its check is ok again). One log line (tag SelfHeal) per check result change and per action.
 *
 * HA: switch.<slug>_self_heal (on/off, persisted) and sensor.<slug>_self_heal
 * (ok / fixing / failing, plus off while switched off; attributes last_check, last_action,
 * last_reason, actions_today). 'failing' = a check still broken on 3+ consecutive ticks.
 *
 * Test hooks (adb DEBUG_CONFIG): selfHealTest mqtt|stream|webview|none fakes that check failing
 * until its action has fired once; selfHealTick runs a check now; selfHealStatus logs a status line.
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
        /** null = no dashboard to probe (not in front, screen off); else true when it answered in [timeoutMs]. Blocking. */
        fun webviewProbe(timeoutMs: Long): Boolean?
        fun webviewReload(): String
        /** null = n/a (keep-alive off, paused, blocked, unsupported); true = parked; false = not parked. */
        fun keepAliveParked(): Boolean?
        fun keepAliveRepark(): String
        fun publish(state: String, attributesJson: String)
    }

    enum class Check(val id: String) { MQTT("mqtt"), STREAM("stream"), WEBVIEW("webview"), KEEPALIVE("keepalive"), WIFI("wifi") }

    private class CheckState {
        var last = "unknown"     // ok / bad: <why> / n/a: <why>
        var bad = 0              // consecutive bad ticks
        val actions = ArrayDeque<Long>()   // elapsedRealtime of actions in the last hour
        var streak = 0           // actions since the check was last ok (backoff exponent)
        var lastActionMs = 0L
        var wifiReconnected = false        // wifi: reconnect() already used for this outage
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
    private var actionsToday = 0
    private var actionsDay = -1

    private val tick = object : Runnable {
        override fun run() {
            runCheck("tick")
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start(on: Boolean) {
        enabled = on
        if (started) return
        started = true
        handler.postDelayed(tick, START_DELAY_MS)
        Log.i(TAG, "service up (enabled=$on, first check in ${START_DELAY_MS / 60_000} min, then every ${TICK_MS / 60_000} min)")
        handler.post { publish() }
    }

    fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    fun setEnabled(on: Boolean) {
        if (on == enabled) { handler.post { publish() }; return }
        enabled = on
        Log.i(TAG, "switch ${if (on) "ON" else "OFF"}")
        handler.post {
            if (!on) checks.values.forEach { it.bad = 0; it.streak = 0; it.last = "unknown" }
            publish()
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

    /** Re-send the HA state (MQTT just connected). */
    fun republish() { handler.post { publish() } }

    fun status(): String {
        val parts = checks.entries.joinToString(" ") { (c, s) -> "${c.id}=${s.last.substringBefore(':')}/${s.bad}" }
        return "status enabled=$enabled state=${stateNow()} ticks=$ticks $parts fake=${fake?.id ?: "none"} " +
            "actionsToday=$actionsToday lastAction='$lastAction' lastReason='$lastReason'"
    }

    private fun stateNow() = if (!enabled) "off" else state

    // ── The check ────────────────────────────────────────────────────────────────

    private fun runCheck(why: String) {
        if (!started) return
        if (!enabled) { publish(); return }
        val busy = runCatching { host.busyReason() }.getOrNull()
        if (busy != null) {
            if (busy != lastSkip) { lastSkip = busy; Log.i(TAG, "check skipped: $busy") }
            return
        }
        if (lastSkip.isNotEmpty()) { Log.i(TAG, "checks resumed (was: $lastSkip)"); lastSkip = "" }
        ticks++
        lastCheckWall = System.currentTimeMillis()

        val wifi = evalWifi()
        val wifiDown = wifi.startsWith("bad")
        judge(Check.WIFI, wifi)
        judge(Check.MQTT, evalMqtt(), skipActionWhy = if (wifiDown) "the Wi-Fi link is down" else null)
        judge(Check.STREAM, evalStream())
        judge(Check.WEBVIEW, evalWebview(), skipActionWhy = if (wifiDown) "the Wi-Fi link is down" else null)
        judge(Check.KEEPALIVE, evalKeepAlive())

        val worst = checks.values.maxOf { it.bad }
        val newState = when {
            worst >= FAILING_TICKS -> "failing"
            worst > 0 -> "fixing"
            else -> "ok"
        }
        if (newState != state) Log.i(TAG, "state $state -> $newState ($why)")
        state = newState
        publish()
    }

    private fun faked(c: Check) = fake == c

    private fun evalWifi(): String = runCatching {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return "n/a: no Wi-Fi"
        if (!wm.isWifiEnabled) return "n/a: Wi-Fi is off"
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val up = cm?.allNetworks?.any { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } == true
        if (up) "ok" else "bad: Wi-Fi link down"
    }.getOrElse { "n/a: ${it.message}" }

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
            s.bad = 0; s.streak = 0; s.wifiReconnected = false
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
            val wait = (TICK_MS shl (s.streak - 1)).coerceAtMost(HOUR_MS) - 5_000L
            if (now - s.lastActionMs < wait) {
                Log.i(TAG, "${c.id}: backing off (${(wait - (now - s.lastActionMs)) / 1000}s left)"); return
            }
        }
        val what: String = runCatching {
            when (c) {
                Check.MQTT -> host.mqttReconnect()
                Check.STREAM -> host.streamRestart()
                Check.WEBVIEW -> host.webviewReload()
                Check.KEEPALIVE -> host.keepAliveRepark()
                Check.WIFI -> wifiAction(s)
            }
        }.getOrElse { "failed: ${it.message}" }
        if (what.isEmpty()) return   // nothing done (wifi: report only / already used)
        s.actions.addLast(now); s.streak++; s.lastActionMs = now
        if (fake == c) { fake = null; Log.i(TAG, "test: '${c.id}' action fired - fake cleared") }
        countToday()
        lastAction = "${c.id}: $what"
        lastReason = reason
        Log.w(TAG, "action ${c.id}: $what (reason: $reason; ${s.actions.size} in the last hour, today $actionsToday)")
    }

    private fun wifiAction(s: CheckState): String {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P) {
            if (s.bad == BAD_TICKS_FOR_ACTION) Log.w(TAG, "wifi: link down for ${s.bad} ticks - report only on Android ${Build.VERSION.RELEASE}")
            return ""
        }
        if (s.wifiReconnected) return ""
        s.wifiReconnected = true
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return ""
        @Suppress("DEPRECATION")
        val ok = wm.reconnect()
        return "WifiManager.reconnect() = $ok"
    }

    private fun countToday() {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (day != actionsDay) { actionsDay = day; actionsToday = 0 }
        actionsToday++
    }

    // ── HA ──────────────────────────────────────────────────────────────────────

    private fun publish() {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (day != actionsDay) { actionsDay = day; actionsToday = 0 }
        val iso = if (lastCheckWall == 0L) "" else
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(lastCheckWall))
            .let { it.substring(0, it.length - 2) + ":" + it.substring(it.length - 2) }
        val checksJson = checks.entries.joinToString(",") { (c, s) -> "\"${c.id}\":\"${esc(s.last)}\"" }
        val attrs = "{\"last_check\":${if (iso.isEmpty()) "null" else "\"$iso\""}," +
            "\"last_action\":\"${esc(lastAction)}\",\"last_reason\":\"${esc(lastReason)}\"," +
            "\"actions_today\":$actionsToday,\"checks\":{$checksJson}}"
        runCatching { host.publish(stateNow(), attrs) }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "SelfHeal"
        const val TICK_MS = 5 * 60_000L
        const val START_DELAY_MS = 10 * 60_000L
        private const val WEBVIEW_TIMEOUT_MS = 10_000L
        // No encoded frame for longer than this = stale (a tick is 5 min; 2 such ticks = action).
        private const val STREAM_STALE_MS = 60_000L
        private const val BAD_TICKS_FOR_ACTION = 2
        private const val FAILING_TICKS = 3
        private const val MAX_PER_HOUR = 3
        private const val HOUR_MS = 60 * 60_000L
    }
}
