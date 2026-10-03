package com.aeonos.portalha

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The YouTube screen: the TV web client (youtube.com/tv) in a WebView. Two ways in:
 *
 *  - CAST (upstream): a phone casts over DIAL; all control happens on the phone.
 *  - STANDALONE (fleet): HA opens it (button "YouTube", navigate "youtube", adb DEBUG_CONFIG
 *    --ez youtube true). youtube.com/tv is remote-driven, so a translucent touch pad (arrows, OK,
 *    back, play/pause, close) sends key events into the WebView. Sign-in is the TV one: the page
 *    shows a code, enter it at youtube.com/activate on a phone (once per Portal); the session lives
 *    in this app's WebView profile (cookies + localStorage on disk, flushed on every pause/exit),
 *    so it survives Bridge restarts and updates (not a reinstall/clear data).
 *
 * The Leanback client gates on user agent, so we present a Tizen smart-TV UA
 * (verified fine on the Portal's Chromium-131 WebView: 1080p MSE, smooth).
 *
 * Camera-safe: this is one of OUR activities (same uid as the camera owner), in the dashboard's own
 * task, so the process stays foreground and Camera 0 keeps streaming under it. Ava keep-alive: the
 * parked sliver is allowed next to this screen (AvaKeepAlive parks over it, the corner cover copies
 * THIS window), so Ava keeps hearing while YouTube shows.
 *
 * Exit paths back to the HA dashboard:
 *  - Close on the pad, HA "YouTube Close", DEBUG_CONFIG --ez youtube false, Show Dashboard,
 *  - an ALERT navigate (doorbell / intruder: a navigate with seconds) - BridgeService closes us first,
 *  - standalone: no touch AND nothing playing for [STANDALONE_IDLE_EXIT_MS]; the screen going off,
 *  - cast: the phone disconnects (loungeStatus remote count 0 with nothing playing), nothing played
 *    for [CAST_IDLE_EXIT_MS], a long-press outside the pad, an explicit DIAL DELETE /run.
 */
class TvAppActivity : Activity() {

    companion object {
        private const val TAG = "PortalHA"
        private const val EXTRA_QUERY = "launch_query"
        private const val EXTRA_STANDALONE = "standalone"
        private const val EXTRA_VIDEO = "video"
        private const val TV_URL = "https://www.youtube.com/tv"
        // A UA youtube.com/tv accepts; without it the page bounces to youtube.com.
        private const val TV_UA =
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36"

        private const val POLL_MS = 5_000L          // playback/exit re-check cadence
        private const val DISCONNECT_GRACE_MS = 10_000L  // remotes==0 + not playing this long → exit
        private const val CAST_IDLE_EXIT_MS = 5 * 60_000L         // cast: nothing played this long → exit
        private const val STANDALONE_IDLE_EXIT_MS = 20 * 60_000L  // standalone: no touch AND nothing played
        // "Playing" = a video was seen playing within this long (three polls, so one slow or
        // missed evaluateJavascript round doesn't flip it).
        private const val PLAYING_FRESH_MS = 3 * POLL_MS
        private const val STATUS_LOG_MS = 60_000L
        private const val COOKIE_FLUSH_MS = 60_000L
        private const val PAD_HIDE_MS = 10_000L
        private const val KEY_REPEAT_DELAY_MS = 400L
        private const val KEY_REPEAT_MS = 140L

        // A YouTube video id (what "youtube:<id>" / --es youtubeVideo may carry).
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{6,20}$")

        // Live instance so BridgeService can close us (same pattern as BridgeService.instance).
        @Volatile private var instance: TvAppActivity? = null
        // Between onStart and onStop: the screen is up on the display (Android 9: also while a
        // keep-alive park merely pauses it).
        @Volatile private var visibleNow = false

        /** True while the YouTube screen exists (DIAL app state for the phone, HA binary sensor). */
        fun isShowing(): Boolean = instance != null

        /** True while the YouTube screen is visible on the display (keep-alive parks next to it). */
        fun isVisible(): Boolean = instance != null && visibleNow

        /** "standalone" / "cast" / "" (not showing). */
        fun mode(): String = instance?.let { if (it.standalone) "standalone" else "cast" } ?: ""

        /** The video id the page is on ("" = browsing / unknown), from the last poll. */
        fun videoId(): String = instance?.lastVideoId ?: ""

        /**
         * True while the YouTube screen is up and a video is actually playing (or just about to:
         * a fresh launch counts from its launch). The on-device screen-off timer holds off for
         * this - FLAG_KEEP_SCREEN_ON only stops the OS timeout, not ours. A video left paused
         * lets the countdown run again from the last moment it played.
         */
        fun isPlayingVideo(): Boolean {
            val a = instance ?: return false
            return System.currentTimeMillis() - a.lastPlayingMs < PLAYING_FRESH_MS
        }

        /** Bring up the YouTube screen with the DIAL launch params (pairingCode=…). */
        fun launch(ctx: Context, query: String) {
            ctx.startActivity(Intent(ctx, TvAppActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_QUERY, query))
        }

        /** Open the YouTube screen from HA (no phone): the touch pad drives it. [video] = optional id. */
        fun launchStandalone(ctx: Context, video: String? = null) {
            val v = video?.trim()?.takeIf { VIDEO_ID.matches(it) }
            ctx.startActivity(Intent(ctx, TvAppActivity::class.java)
                // NO_USER_ACTION: the dashboard must not read this start as "the user left for
                // another app" (that would switch the dashboard's auto-return and the keep-alive off).
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_STANDALONE, true)
                .apply { if (v != null) putExtra(EXTRA_VIDEO, v) })
        }

        fun isVideoId(s: String): Boolean = VIDEO_ID.matches(s.trim())

        /** Close the YouTube screen (HA close, DIAL stop, an alert). Safe from any thread. */
        fun close(reason: String = "dial stop") {
            instance?.let { a -> a.runOnUiThread { a.exitToDashboard(reason) } }
        }

        /** adb / HA test hook: press a pad key ("up", "down", "left", "right", "ok", "back", "playpause"). */
        fun pressKey(name: String): Boolean {
            val a = instance ?: return false
            a.runOnUiThread { a.padKey(name.trim().lowercase()) }
            return true
        }

        /** Touch mode changed (DEBUG_CONFIG youtubeTouch): applies to the showing screen at once. */
        fun setTouchMode(mode: String) {
            instance?.let { a -> a.runOnUiThread { a.touchMode = mode; a.applyTouchMode() } }
        }

        /** One status line (DEBUG_CONFIG --ez youtubeStatus true, and once a minute while showing). */
        fun status(): String = instance?.statusLine() ?: "youtube: status not showing"

        /**
         * The keep-alive's corner cover while this screen is in front: the pixels of OUR window
         * behind the parked sliver (same contract as DashboardActivity.copyRegion).
         */
        fun copyRegion(src: Rect, cb: (android.graphics.Bitmap?) -> Unit) {
            val act = instance
            if (act == null || !visibleNow || src.width() <= 0 || src.height() <= 0) { cb(null); return }
            runCatching {
                val v = act.window.peekDecorView()
                if (v == null || !v.isAttachedToWindow || v.width < src.right || v.height < src.bottom) { cb(null); return }
                val bmp = android.graphics.Bitmap.createBitmap(src.width(), src.height(), android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(act.window, src, bmp, { result ->
                    cb(if (result == android.view.PixelCopy.SUCCESS) bmp else null)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { cb(null) }
        }
    }

    private lateinit var webView: WebView
    private lateinit var gestures: GestureDetector
    private val handler = Handler(Looper.getMainLooper())

    // Connected-remote tracking, fed by the lounge bind channel via CastBridge.
    // Starts at 1: in cast mode we exist because a phone just cast to us.
    @Volatile private var remotes = 1
    @Volatile private var lastRemoteSeenMs = 0L   // last time remotes was > 0
    @Volatile private var lastPlayingMs = 0L      // last time a video was actually playing
    @Volatile private var lastTouchMs = 0L        // last touch on this screen (wall clock)
    // Sticky: once HA opened us, a phone that casts and leaves doesn't close the screen.
    @Volatile private var standalone = false
    private var exiting = false

    // Last poll (for the status line, HA attributes and the frame-drop check).
    @Volatile private var lastVideoId = ""
    @Volatile private var lastPoll = ""
    @Volatile private var playingNow = false
    private var lastStatusLogMs = 0L
    private var lastCookieFlushMs = 0L

    // Touch pad.
    private lateinit var pad: LinearLayout
    private lateinit var padHandle: TextView
    private var padShown = true
    private var gestureOnPad = false
    // How touches on the page drive it (Prefs.youtubeTouch): "mouse" / "native" / "pad".
    private var touchMode = "mouse"
    // mouse mode: the gesture in progress (window coordinates = WebView coordinates, it fills the window).
    private var mDownX = 0f
    private var mDownY = 0f
    private var mLastX = 0f
    private var mLastY = 0f
    private var mDownTime = 0L
    private var mDragging = false
    private var mScrollAccX = 0f
    private var mScrollAccY = 0f

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableImmersive()

        webView = WebView(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildPad(root)
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = TV_UA
        }
        // The TV sign-in (youtube.com/activate code) is kept in this profile's cookies and
        // localStorage, which WebView keeps on disk; accept them (third-party too: the sign-in
        // hops through accounts.google.com) and flush on every pause and exit.
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(CastBridge(), "PortalCast")
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = false   // keep everything in the TV client

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(loungeShimJs(), null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(loungeShimJs(), null)
                view.requestFocus()
            }
        }

        // Escape hatch (cast mode, upstream): a long-press outside the pad exits to the dashboard.
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) { if (!standalone && !gestureOnPad) exitToDashboard("long-press") }
        })

        touchMode = runCatching { Prefs(this).youtubeTouch }.getOrDefault("mouse")
        applyTouchMode()
        loadFromIntent(intent, fresh = true)
        handler.postDelayed(pollRunnable, POLL_MS)
        BridgeService.youtubeStateChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        loadFromIntent(intent, fresh = false)   // a new cast / open while already showing
    }

    private fun loadFromIntent(intent: Intent?, fresh: Boolean) {
        val now = System.currentTimeMillis()
        val wantsStandalone = intent?.getBooleanExtra(EXTRA_STANDALONE, false) == true
        val video = intent?.getStringExtra(EXTRA_VIDEO).orEmpty()
        lastTouchMs = now; lastPlayingMs = now                     // fresh grace period
        if (wantsStandalone) {
            standalone = true
            applyTouchMode()
            when {
                video.isNotEmpty() -> load("$TV_URL#/watch?v=$video")
                fresh -> load(TV_URL)
                else -> Log.i(TAG, "youtube: open requested - already showing, page kept")
            }
            BridgeService.youtubeStateChanged()
            return
        }
        val query = intent?.getStringExtra(EXTRA_QUERY).orEmpty()
        remotes = 1; lastRemoteSeenMs = now
        load(if (query.isBlank()) TV_URL else "$TV_URL?$query")
        BridgeService.youtubeStateChanged()
    }

    private fun load(url: String) {
        Log.i(TAG, "${if (standalone) "youtube" else "cast"}: loading ${url.substringBefore('?')}${if (url.contains('?')) "?..." else ""}")
        webView.loadUrl(url)
        webView.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        visibleNow = true
    }

    override fun onResume() {
        super.onResume()
        enableImmersive()
        BridgeService.youtubeInFront()     // the keep-alive re-parks Ava next to this screen
    }

    override fun onPause() {
        super.onPause()
        flushCookies()
    }

    override fun onStop() {
        super.onStop()
        visibleNow = false
        if (exiting || isChangingConfigurations) return
        if (!standalone) return
        // Fully hidden (a call, the screen off): YouTube is a foreground-only thing on a wall.
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (!interactive) {
            exitToDashboard("screen off")
        } else {
            webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v&&!v.paused)v.pause();})()", null)
            Log.i(TAG, "youtube: hidden by something else - video paused")
        }
    }

    // ── Exit policy + status ──────────────────────────────────────────────────

    private val pollJs =
        "(function(){var v=document.querySelector('video');var m=(location.hash||'').match(/[?&]v=([A-Za-z0-9_-]+)/);" +
            "var q=v&&v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():null;" +
            "return JSON.stringify({p:(v&&!v.paused&&!v.ended&&v.currentTime>0)?1:0,t:v?Math.round(v.currentTime*10)/10:-1," +
            "d:v&&isFinite(v.duration)?Math.round(v.duration):-1,w:v?v.videoWidth:0,h:v?v.videoHeight:0," +
            "dr:q?q.droppedVideoFrames:-1,tf:q?q.totalVideoFrames:-1,mu:v?(v.muted?1:0):-1,vol:v?Math.round(v.volume*100):-1," +
            "id:m?m[1]:''});})()"

    private val pollRunnable = object : Runnable {
        override fun run() {
            webView.evaluateJavascript(pollJs) { result ->
                val now = System.currentTimeMillis()
                val o = runCatching {
                    val s = org.json.JSONTokener(result ?: "").nextValue() as? String ?: return@runCatching null
                    org.json.JSONObject(s)
                }.getOrNull()
                val playing = o?.optInt("p", 0) == 1
                if (playing) lastPlayingMs = now
                if (o != null) {
                    lastPoll = "t=${o.optDouble("t")}/${o.optInt("d")}s res=${o.optInt("w")}x${o.optInt("h")} " +
                        "dropped=${o.optInt("dr")}/${o.optInt("tf")} muted=${o.optInt("mu")} vol=${o.optInt("vol")}"
                    val id = o.optString("id", "")
                    if (id != lastVideoId || playing != playingNow) {
                        lastVideoId = id; playingNow = playing
                        BridgeService.youtubeStateChanged()
                    }
                }
                if (now - lastStatusLogMs >= STATUS_LOG_MS) { lastStatusLogMs = now; Log.i(TAG, statusLine()) }
                if (now - lastCookieFlushMs >= COOKIE_FLUSH_MS) flushCookies()
                val remoteGone = !standalone && remotes == 0 &&
                    now - lastRemoteSeenMs > DISCONNECT_GRACE_MS &&
                    now - lastPlayingMs > DISCONNECT_GRACE_MS
                val idle = if (standalone)
                    now - lastPlayingMs > STANDALONE_IDLE_EXIT_MS && now - lastTouchMs > STANDALONE_IDLE_EXIT_MS
                else now - lastPlayingMs > CAST_IDLE_EXIT_MS
                when {
                    remoteGone -> exitToDashboard("phone disconnected")
                    idle -> exitToDashboard("idle")
                    else -> handler.postDelayed(this, POLL_MS)
                }
            }
        }
    }

    private fun statusLine(): String {
        val now = System.currentTimeMillis()
        return "youtube: status mode=${if (standalone) "standalone" else "cast"} visible=$visibleNow " +
            "playing=${isPlayingVideo()} video=${lastVideoId.ifEmpty { "-" }} $lastPoll " +
            "touch=${(now - lastTouchMs) / 1000}s ago played=${(now - lastPlayingMs) / 1000}s ago pad=${if (padShown) "shown" else "hidden"} touchMode=$touchMode"
    }

    private fun flushCookies() {
        lastCookieFlushMs = System.currentTimeMillis()
        runCatching { CookieManager.getInstance().flush() }
    }

    /** All exits land here: bring the HA dashboard back, then finish. */
    private fun exitToDashboard(reason: String) {
        if (exiting) return
        exiting = true
        Log.i(TAG, "${if (standalone) "youtube" else "cast"}: exit ($reason) → dashboard")
        flushCookies()
        runCatching { webView.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.pause();})()", null) }
        runCatching {
            startActivity(Intent(this, DashboardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
        finish()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        BridgeService.noteTouch()   // activity for the screen-off timer
        lastTouchMs = System.currentTimeMillis()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureOnPad = onPad(ev)
            if (padShown) schedulePadHide()
        }
        gestures.onTouchEvent(ev)
        if (gestureOnPad) return super.dispatchTouchEvent(ev)
        if (touchMode == "native") return super.dispatchTouchEvent(ev)
        if (touchMode == "mouse") { mouseFromTouch(ev); return true }
        // youtube.com/tv is remote-driven: a touch on the page itself only brings the pad up
        // (a click would switch the Leanback client into its pointer mode).
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !padShown) showPad()
        return true
    }

    // ── Touch as a pointer remote ("mouse" mode) ─────────────────────────────
    // youtube.com/tv supports a pointer remote (a mouse): hover highlights, click selects, the wheel
    // scrolls. A finger becomes that pointer: a tap = move + click right where it landed, a drag =
    // wheel notches along the drag (rows left/right, the page up/down), the finger never "clicks" after a drag.

    private fun mouseEvent(action: Int, x: Float, y: Float, buttons: Int, downTime: Long): MotionEvent {
        val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val pc = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(pp), arrayOf(pc),
            0, buttons, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
    }

    private fun hover(x: Float, y: Float) {
        val e = mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, SystemClock.uptimeMillis())
        webView.dispatchGenericMotionEvent(e); e.recycle()
    }

    private fun click(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val primary = MotionEvent.BUTTON_PRIMARY
        listOf(
            mouseEvent(MotionEvent.ACTION_DOWN, x, y, primary, t),
            mouseEvent(MotionEvent.ACTION_UP, x, y, 0, t)
        ).forEach { e -> webView.dispatchTouchEvent(e); e.recycle() }
    }

    private fun wheel(x: Float, y: Float, h: Float, v: Float) {
        val pp = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val pc = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, h); setAxisValue(MotionEvent.AXIS_VSCROLL, v)
        }
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, arrayOf(pp), arrayOf(pc), 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_MOUSE, 0)
        webView.dispatchGenericMotionEvent(e); e.recycle()
    }

    private fun mouseFromTouch(ev: MotionEvent) {
        val x = ev.x; val y = ev.y
        val step = dp(48).toFloat()      // finger travel per wheel notch
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mDownX = x; mDownY = y; mLastX = x; mLastY = y; mDownTime = SystemClock.uptimeMillis()
                mDragging = false; mScrollAccX = 0f; mScrollAccY = 0f
                hover(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!mDragging && Math.hypot((x - mDownX).toDouble(), (y - mDownY).toDouble()) > dp(14)) mDragging = true
                if (mDragging) {
                    // Finger left = see more to the right (HSCROLL +); finger up = see further down (VSCROLL -).
                    mScrollAccX += mLastX - x; mScrollAccY += mLastY - y
                    val nx = (mScrollAccX / step).toInt(); val ny = (mScrollAccY / step).toInt()
                    if (nx != 0 || ny != 0) {
                        wheel(mDownX, mDownY, nx.toFloat(), -ny.toFloat())
                        mScrollAccX -= nx * step; mScrollAccY -= ny * step
                        BridgeService.youtubeFrontChanged()
                    }
                }
                mLastX = x; mLastY = y
            }
            MotionEvent.ACTION_UP -> {
                if (!mDragging && SystemClock.uptimeMillis() - mDownTime < 600L) {
                    click(x, y)
                    BridgeService.youtubeFrontChanged()
                }
                mDragging = false
            }
            MotionEvent.ACTION_CANCEL -> mDragging = false
        }
    }

    /** Pad up front only in "pad" mode; the other modes keep it one tap away (the Remote handle). */
    fun applyTouchMode() {
        if (touchMode == "pad") showPad() else hidePadNow()
    }

    private fun hidePadNow() {
        handler.removeCallbacks(padHide)
        padShown = false
        pad.visibility = View.GONE
        padHandle.visibility = View.VISIBLE
    }

    private fun onPad(ev: MotionEvent): Boolean {
        val r = Rect()
        val v: View = if (padShown) pad else padHandle
        if (v.visibility != View.VISIBLE || !v.getGlobalVisibleRect(r)) return false
        return r.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    // ── Touch pad ─────────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun padButton(label: String, key: String, sizeDp: Int = 64, repeat: Boolean = false): TextView {
        val b = TextView(this)
        b.text = label
        b.setTextColor(Color.WHITE)
        b.textSize = if (label.length > 2) 15f else 24f
        b.gravity = Gravity.CENTER
        b.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(150, 20, 20, 20))
            setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }
        b.contentDescription = key
        val lp = LinearLayout.LayoutParams(dp(sizeDp), dp(56))
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        b.layoutParams = lp
        var repeater: Runnable? = null
        b.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.6f
                    padKey(key)
                    if (repeat) {
                        val r = object : Runnable {
                            override fun run() { padKey(key); handler.postDelayed(this, KEY_REPEAT_MS) }
                        }
                        repeater = r
                        handler.postDelayed(r, KEY_REPEAT_DELAY_MS)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    repeater?.let { handler.removeCallbacks(it) }; repeater = null
                }
            }
            true
        }
        return b
    }

    private fun spacer(): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(64), dp(56)).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) } }

    private fun row(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        v.forEach { addView(it) }
    }

    /**
     * Bottom-LEFT, on purpose: the bottom-right corner belongs to the Ava keep-alive's parked
     * sliver and its tap shield (never put a touch target near it).
     */
    private fun buildPad(root: FrameLayout) {
        pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(70, 0, 0, 0))
            }
        }
        pad.addView(row(spacer(), padButton("▲", "up", repeat = true), spacer()))
        pad.addView(row(padButton("◀", "left", repeat = true), padButton("OK", "ok"), padButton("▶", "right", repeat = true)))
        pad.addView(row(spacer(), padButton("▼", "down", repeat = true), spacer()))
        pad.addView(row(padButton("Back", "back"), padButton("⏯", "playpause"), padButton("Close", "close")))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START)
        lp.setMargins(dp(20), dp(20), dp(20), dp(20))
        root.addView(pad, lp)

        padHandle = TextView(this).apply {
            text = "Remote"
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(90, 0, 0, 0))
            }
            visibility = View.GONE
            setOnClickListener { showPad() }
        }
        val hlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40), Gravity.BOTTOM or Gravity.START)
        hlp.setMargins(dp(20), dp(20), dp(20), dp(20))
        root.addView(padHandle, hlp)
    }

    private val padHide = Runnable {
        // Keep it up while nothing plays (browsing / the sign-in code screen): it's the only way around.
        // (playingNow = a video really seen playing - not the launch grace isPlayingVideo() counts.)
        if (!playingNow && touchMode == "pad") { schedulePadHide(); return@Runnable }
        padShown = false
        pad.visibility = View.GONE
        padHandle.visibility = View.VISIBLE
        BridgeService.youtubeFrontChanged()
    }

    private fun schedulePadHide() {
        handler.removeCallbacks(padHide)
        handler.postDelayed(padHide, PAD_HIDE_MS)
    }

    private fun showPad() {
        padShown = true
        pad.visibility = View.VISIBLE
        padHandle.visibility = View.GONE
        schedulePadHide()
        BridgeService.youtubeFrontChanged()
    }

    /** A pad key: a key event straight into the WebView (no window focus needed), or close. */
    fun padKey(key: String) {
        lastTouchMs = System.currentTimeMillis()
        val code = when (key) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok", "enter", "select" -> KeyEvent.KEYCODE_ENTER
            // Leanback reads Escape (27) as Back; KEYCODE_BACK would be eaten by the WebView.
            "back", "escape" -> KeyEvent.KEYCODE_ESCAPE
            "playpause", "play", "pause" -> -1
            "close" -> { exitToDashboard("closed on the pad"); return }
            else -> { Log.w(TAG, "youtube: unknown pad key '$key'"); return }
        }
        if (code == -1) { playPause(); return }
        sendKey(code)
        BridgeService.youtubeFrontChanged()
    }

    private fun sendKey(code: Int) {
        if (!webView.hasFocus()) webView.requestFocus()
        val t = SystemClock.uptimeMillis()
        webView.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        webView.dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
    }

    /** Media play/pause key first (the client's own handling, its UI follows); the video element itself if that didn't take. */
    private fun playPause() {
        val read = "(function(){var v=document.querySelector('video');return v?(v.paused?'paused':'playing'):'none';})()"
        webView.evaluateJavascript(read) { before ->
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            handler.postDelayed({
                webView.evaluateJavascript(read) { after ->
                    if (before != null && before == after && !before.contains("none")) {
                        webView.evaluateJavascript(
                            "(function(){var v=document.querySelector('video');if(!v)return;if(v.paused)v.play();else v.pause();})()", null)
                        Log.i(TAG, "youtube: play/pause by the video element (the media key didn't take)")
                    }
                }
            }, 700L)
        }
    }

    // Fed by the JS shim below with the number of connected remotes each time
    // the lounge bind channel reports a loungeStatus.
    inner class CastBridge {
        @JavascriptInterface
        fun remotes(n: Int) {
            remotes = n
            if (n > 0) lastRemoteSeenMs = System.currentTimeMillis()
        }
    }

    /**
     * Two jobs, injected before the client boots:
     *
     * 1. NAME: the lounge screen name is what the phone's cast menu shows, but
     *    the web client hardcodes "YouTube on TV" on its bind request (it
     *    ignores the name stored in localStorage — verified on-device).
     *    Rewrite the bind URL's name param to this Portal's device name.
     *
     * 2. DISCONNECT: the bind long-poll's streamed response carries lounge
     *    events; each loungeStatus lists the connected devices. Count the
     *    REMOTE_CONTROL entries in every new chunk and report to PortalCast —
     *    zero remotes = the phone disconnected (there is no DIAL DELETE for it).
     */
    private fun loungeShimJs(): String {
        val name = Prefs(this).deviceName
            .replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function () {
              if (window.__phaShim) return; window.__phaShim = true;
              function fix(u) {
                try {
                  if (typeof u === 'string' && u.indexOf('/api/lounge/') > -1 && /[?&]name=/.test(u)) {
                    return u.replace(/([?&]name=)[^&]*/, '$1' + encodeURIComponent('$name'));
                  }
                } catch (e) {}
                return u;
              }
              function scan(xhr) {
                try {
                  var t = xhr.responseText || '';
                  var chunk = t.substring(xhr.__phaSeen || 0);
                  xhr.__phaSeen = t.length;
                  if (chunk.indexOf('loungeStatus') > -1 && window.PortalCast) {
                    var n = (chunk.match(/REMOTE_CONTROL/g) || []).length;
                    PortalCast.remotes(n);
                  }
                } catch (e) {}
              }
              var xo = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function (m, u) {
                arguments[1] = fix(u);
                if (typeof arguments[1] === 'string' &&
                    arguments[1].indexOf('/api/lounge/bc/bind') > -1) {
                  var xhr = this;
                  xhr.addEventListener('progress', function () { scan(xhr); });
                  xhr.addEventListener('load', function () { scan(xhr); });
                }
                return xo.apply(this, arguments);
              };
              if (window.fetch) {
                var of = window.fetch;
                window.fetch = function (u, o) {
                  if (typeof u === 'string') u = fix(u); return of.call(this, u, o);
                };
              }
            })();
        """.trimIndent()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersive() {
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onDestroy() {
        if (instance === this) { instance = null; visibleNow = false }
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(padHide)
        flushCookies()
        BridgeService.castScreenClosed()
        BridgeService.youtubeStateChanged()
        webView.destroy()
        super.onDestroy()
    }
}
