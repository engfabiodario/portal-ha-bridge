package com.aeonos.portalha

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Watch together": N Portals play the same YouTube video in their YouTube screens (TvAppActivity),
 * started at the same instant and kept in step.
 *
 * Protocol - one fleet MQTT topic, [TOPIC] (never retained), JSON:
 *  from HA (script.portal_qa_watch):
 *    {"cmd":"load","session":"s..","portals":[slug..],"leader":slug,"video":id,"pos":sec,"ntp":"host"}
 *    {"cmd":"play"|"pause"|"toggle"|"stop"}  {"cmd":"seek","pos":sec} | {"cmd":"seek","delta":sec}
 *  from the session LEADER (one of the Portals; the only one that turns intents into times):
 *    {"cmd":"play_at","session",.."at":serverMs,"pos":sec}  {"cmd":"pause_at","session",.."at":serverMs,"pos":sec}
 *    {"cmd":"cue","session",.."pos":sec}
 * Times are in SyncClock time (every member syncs to the same LAN NTP server), never HA's or a
 * Portal's wall clock. Each member's page runs the control loop itself (TvAppActivity WATCH_JS): it
 * starts at `at`, then nudges playbackRate (+-3 %, +-6 % above 100 ms) toward
 * pos + (now - at) + its calibration, and seeks only past 0.5 s. A pad play/pause on any member is
 * sent as a "toggle" intent for the whole group. Each member publishes its own state (HA sensor
 * "Watch Together": idle / loading / profile / signin / ready / armed / playing / pausing / paused / ended
 * + error, clock). signin = this Portal is not signed in to YouTube (yt.be/activate) and the session did not allow a guest.
 */
class WatchTogether(private val host: Host) {

    interface Host {
        val slug: String
        val calibrationMs: Int
        fun publishFleet(json: String)
        fun publishState(state: String, attrs: JSONObject)
        fun openYouTube()
        fun closeYouTube(why: String)
    }

    companion object {
        private const val TAG = "PortalHA"
        const val TOPIC = "portal/watch/set"
        private const val START_LEAD_MS = 3_000L     // play_at this far ahead: every member gets the message and seeks
        private const val PAUSE_LEAD_MS = 600L

        /** Bridge device name -> HA slug ("Portal_Plus_Kitchen" -> "portal_plus_kitchen"). */
        fun slugOf(name: String): String =
            name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    @Volatile var session = ""; private set
    @Volatile private var portals: List<String> = emptyList()
    @Volatile private var leader = ""
    @Volatile private var video = ""
    // The session lets a signed-out Portal play as a guest (HA allow_guest; ads there break the sync).
    @Volatile private var guest = false
    @Volatile var state = "idle"; private set
    @Volatile private var lastReport = JSONObject()

    // Leader bookkeeping: the group's timeline.
    @Volatile private var gPos = 0.0          // seconds at gAt (or while paused)
    @Volatile private var gAt = 0L            // serverMs the timeline (re)started; 0 = paused / not started
    private fun groupPos(now: Long): Double = if (gAt == 0L) gPos else gPos + (now - gAt) / 1000.0

    val active: Boolean get() = session.isNotEmpty()
    val isLeader: Boolean get() = active && leader == host.slug

    fun status(): String =
        "watch: session=${session.ifEmpty { "-" }} state=$state leader=${leader.ifEmpty { "-" }}${if (isLeader) " (me)" else ""} " +
            "video=${video.ifEmpty { "-" }} portals=${portals.joinToString(",")} report=$lastReport ${SyncClock.status()}"

    /** A message on [TOPIC] (or adb DEBUG_CONFIG --es watch '<json>'). */
    fun handle(payload: String) {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: run { Log.w(TAG, "watch: not JSON: ${payload.take(80)}"); return }
        val cmd = o.optString("cmd")
        val sid = o.optString("session")
        if (cmd == "load") { load(o); return }
        // Everything else needs us in a session (and the same one when it names one).
        if (!active || (sid.isNotEmpty() && sid != session)) return
        when (cmd) {
            "play_at" -> { TvAppActivity.watchPlayAt(o.optLong("at"), o.optDouble("pos"), host.calibrationMs); setState("armed") }
            "pause_at" -> { TvAppActivity.watchPauseAt(o.optLong("at")); setState("pausing") }
            "cue" -> { TvAppActivity.watchCue(video, o.optDouble("pos"), guest); setState("loading") }
            "stop" -> end("stop", close = true)
            "play", "pause", "toggle", "seek" -> if (isLeader) lead(cmd, o)
        }
    }

    private fun load(o: JSONObject) {
        val list = o.optJSONArray("portals") ?: JSONArray()
        val members = (0 until list.length()).map { list.optString(it) }
        if (host.slug !in members) {
            if (active) end("another session started without this Portal", close = false)
            return
        }
        val v = o.optString("video").trim()
        if (!TvAppActivity.isVideoId(v)) { Log.w(TAG, "watch: load refused - bad video id '$v'"); return }
        session = o.optString("session").ifEmpty { "s${System.currentTimeMillis()}" }
        portals = members
        leader = o.optString("leader").ifEmpty { members.first() }
        video = v
        guest = o.optBoolean("guest", false)
        gPos = o.optDouble("pos", 0.0).coerceAtLeast(0.0); gAt = 0L
        val ntp = o.optString("ntp")
        if (ntp.isNotBlank()) SyncClock.start(ntp)
        Log.i(TAG, "watch: session $session video $video from ${gPos}s, ${members.size} Portals, leader $leader${if (isLeader) " (me)" else ""}, clock $ntp")
        host.openYouTube()
        TvAppActivity.watchCue(video, gPos, guest)
        setState("loading")
    }

    /** Leader only: an intent from HA or a member's pad becomes a timed command for everyone. */
    private fun lead(cmd: String, o: JSONObject) {
        val now = SyncClock.now() ?: run { Log.w(TAG, "watch: leader has no synced clock yet - '$cmd' dropped"); return }
        val playing = gAt != 0L
        val c = if (cmd == "toggle") (if (playing) "pause" else "play") else cmd
        when (c) {
            "play" -> if (!playing) {
                val at = now + START_LEAD_MS
                broadcast(JSONObject().put("cmd", "play_at").put("at", at).put("pos", gPos))
                gAt = at
            }
            "pause" -> if (playing) {
                val at = now + PAUSE_LEAD_MS
                gPos = groupPos(at); gAt = 0L
                broadcast(JSONObject().put("cmd", "pause_at").put("at", at).put("pos", gPos))
            }
            "seek" -> {
                val target = (if (o.has("pos")) o.optDouble("pos") else groupPos(now) + o.optDouble("delta", 0.0)).coerceAtLeast(0.0)
                if (playing) {
                    val at = now + START_LEAD_MS
                    gPos = target; gAt = at
                    broadcast(JSONObject().put("cmd", "play_at").put("at", at).put("pos", target))
                } else {
                    gPos = target
                    broadcast(JSONObject().put("cmd", "cue").put("pos", target))
                }
            }
        }
        Log.i(TAG, "watch: leader '$cmd' -> group at ${"%.1f".format(groupPos(now))}s ${if (gAt != 0L) "playing" else "paused"}")
    }

    /** To every member, this Portal included (MQTT echoes our own publish back to us). */
    private fun broadcast(o: JSONObject) {
        o.put("session", session)
        host.publishFleet(o.toString())
    }

    /** A member's own pad play/pause: ask the leader to do it for the group. */
    fun padToggle() {
        if (!active) return
        host.publishFleet(JSONObject().put("cmd", "toggle").put("session", session).toString())
    }

    /** The page's report (TvAppActivity PortalWatch.report). */
    fun onPageReport(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        lastReport = o
        val s = o.optString("state")
        if (s.isNotEmpty()) setState(s, force = s == "playing")
        if (s == "ended" && isLeader) { gAt = 0L; gPos = 0.0 }
    }

    /** The YouTube screen closed (alert, Close, idle...): this Portal leaves the session. */
    fun onScreenClosed() { if (active) end("YouTube screen closed", close = false) }

    private fun end(why: String, close: Boolean) {
        Log.i(TAG, "watch: leaving session $session ($why)")
        session = ""; portals = emptyList(); leader = ""; video = ""; gAt = 0L; gPos = 0.0
        TvAppActivity.watchStop()
        SyncClock.stop()
        setState("idle", force = true)
        if (close) host.closeYouTube("watch together stop")
    }

    private fun setState(s: String, force: Boolean = false) {
        if (s == state && !force) return
        state = s
        val a = JSONObject()
            .put("session", session).put("leader", leader).put("is_leader", isLeader).put("video", video)
            .put("portals", JSONArray(portals))
            .put("error_ms", lastReport.optInt("err", 0)).put("rate", lastReport.optDouble("rate", 1.0))
            .put("position", lastReport.optDouble("t", -1.0))
            .put("clock_rtt_ms", SyncClock.lastDelayMs).put("clock_server", SyncClock.server)
            .put("calibration_ms", host.calibrationMs)
        host.publishState(s, a)
    }

    /** Re-publish the attributes (error / rate) without a state change; throttled by the page (2 s). */
    fun republish() = setState(state, force = true)
}
