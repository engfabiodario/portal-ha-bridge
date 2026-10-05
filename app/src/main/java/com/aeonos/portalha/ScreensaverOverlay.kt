package com.aeonos.portalha

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Photo-frame screensaver: an ImmichFrame / Immich Kiosk page rendered full-screen over the
 * dashboard.
 *
 * ★It is an OVERLAY, never an Activity or a DreamService. Backgrounding DashboardActivity is
 * what evicts Camera 0 on Android 10, which kills the RTSP stream (see the stream-wedge notes) —
 * so the photos are drawn in a TYPE_APPLICATION_OVERLAY window and the dashboard stays the
 * foreground activity underneath, still streaming. This is the same trick the wake cover, the
 * talk buttons and the announce orb already use.
 *
 * The page is a JavaScript app, so we render it rather than scraping it — there is no image in
 * its HTML to scrape. The WebView is built on [show] and destroyed on [hide] rather than kept
 * around: a Portal has ~2.8 GB of RAM and the dashboard's own renderer is already the largest
 * process on the device.
 *
 * ## Touch
 * A transparent catcher sits above the WebView and takes every touch, so the page never sees
 * one. That is deliberate: it lets us keep the frame's own left/right navigation *and* still
 * have a way out, which the page alone could not give us.
 *
 *  - left third   → previous photo
 *  - right third  → next photo
 *  - centre third → exit the screensaver (full height)
 *
 * Navigation is delivered as a synthetic `keydown` on `window`, because both frames listen for
 * one and only read `event.key`:
 *   - ImmichFrame: `{ArrowRight: next, ArrowLeft: back, " ": pause, i: showInfo}`
 *   - Immich Kiosk: the same arrows, plus space for play/pause
 * Injecting keys is steadier than synthesising clicks on their invisible edge buttons, which
 * would break the moment either project moves its markup around.
 */
class ScreensaverOverlay(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)

    @Volatile private var root: FrameLayout? = null
    private var web: WebView? = null
    private var onExit: (() -> Unit)? = null
    @Volatile private var visible = false
    // The window's params, kept so a concealed (prestaged) frame can be made untouchable.
    private var winLp: WindowManager.LayoutParams? = null

    /** Fleet: brightness % while the photos are on screen (0 = no override). Any thread. */
    @Volatile var brightnessPct: Int = 0
        set(v) { field = v.coerceIn(0, 100); main.post { applyBrightness() } }

    /** Fleet: called (main thread) whenever the photos appear on / leave the screen. */
    var onShowingChanged: ((Boolean) -> Unit)? = null
    private var lastReported: Boolean? = null

    /**
     * The photo window's own screenBrightness override: [brightnessPct] while visible, none while
     * concealed (removing the window ends it by itself). Never at or above the system brightness.
     * Main thread.
     */
    private fun applyBrightness() {
        val v = root
        val lp = winLp
        val showing = v != null && lp != null && visible
        if (v != null && lp != null) {
            val sys = runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255) / 255f
            }.getOrDefault(1f)
            val want = if (showing && brightnessPct in 1..100 && brightnessPct / 100f < sys) brightnessPct / 100f
                else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            if (lp.screenBrightness != want) {
                lp.screenBrightness = want
                runCatching { wm.updateViewLayout(v, lp) }
                    .onFailure { android.util.Log.w(TAG, "screensaver: brightness failed: ${it.message}") }
                android.util.Log.i(TAG, "screensaver: brightness override " +
                    (if (want < 0) "none" else "${(want * 100).toInt()}%"))
            }
        }
        if (lastReported != showing) { lastReported = showing; onShowingChanged?.invoke(showing) }
    }

    /** Photos are on screen right now. */
    val isShowing: Boolean get() = root != null && visible

    /** Loaded and running, but not on screen — see [prestage]. */
    val isStaged: Boolean get() = root != null

    /**
     * Put [url] on screen. [exit] is called on a centre tap — the caller owns what "exit" means
     * (it also has to restart its idle timer, so we don't self-hide here).
     *
     * If the page was already [prestage]d this only reveals it, which is the whole point: the
     * frame appears at once instead of showing the bare white SvelteKit shell for a second while
     * it boots.
     */
    fun show(url: String, exit: () -> Unit) {
        if (isStaged) { setVisible(true); return }
        create(url, exit, showNow = true)
    }

    /**
     * Load the page but keep it off screen, ready to appear instantly.
     *
     * Used while the panel sleeps: ImmichFrame is a JavaScript app and takes a moment to boot,
     * during which it paints white — unacceptable as the first thing you see when you walk up to
     * a Portal. Staging it behind a dark screen moves that cost somewhere nobody is looking.
     *
     * Costs a live WebView for as long as it is staged, which is why it is opt-in: these panels
     * have ~2.8 GB of RAM and the dashboard's own renderer is already the biggest process on the
     * device.
     */
    fun prestage(url: String, exit: () -> Unit) {
        if (isStaged) { setVisible(false); return }   // already loaded — just conceal it
        create(url, exit, showNow = false)
    }

    private fun setVisible(want: Boolean) {
        main.post {
            val v = root ?: return@post
            if (visible == want) return@post
            visible = want
            setTouchable(v, want)
            // No fade when concealing: that only happens behind a dark screen, where an
            // animation would just be work nobody can see.
            if (want) v.animate().alpha(1f).setDuration(FADE_MS).start() else v.alpha = 0f
            android.util.Log.i(TAG, "screensaver: ${if (want) "revealed (prestaged)" else "concealed"}")
            applyBrightness()
        }
    }

    /**
     * A concealed frame is alpha 0, but a window takes touches whatever its content's alpha - so a
     * prestaged frame still on screen after a wake (wake-to-photos off, or a dismiss hold running)
     * silently swallowed every tap meant for the dashboard underneath, left/right ones paging the
     * invisible photos. Untouchable while concealed; touchable again when revealed.
     */
    private fun setTouchable(v: View, touchable: Boolean) {
        val lp = winLp ?: return
        val flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags == lp.flags) return
        lp.flags = flags
        runCatching { wm.updateViewLayout(v, lp) }
            .onFailure { android.util.Log.w(TAG, "screensaver: touchable=$touchable failed: ${it.message}") }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun create(url: String, exit: () -> Unit, showNow: Boolean) {
        if (url.isBlank()) return
        if (!Settings.canDrawOverlays(context)) {
            android.util.Log.w(TAG, "screensaver: no overlay permission — not showing")
            return
        }
        main.post {
            if (root != null) return@post
            runCatching {
                onExit = exit
                val container = FrameLayout(context)
                container.setBackgroundColor(Color.BLACK)

                val wv = WebView(context)
                wv.setBackgroundColor(Color.BLACK)
                wv.isVerticalScrollBarEnabled = false
                wv.isHorizontalScrollBarEnabled = false
                wv.overScrollMode = View.OVER_SCROLL_NEVER
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                // Keep any navigation the page does inside this WebView.
                // fleet: the overlay (prestaged) lives for the whole process, and every WebView of an
                // app shares ONE renderer. When that renderer dies (OOM / LMK - 2026-10-05: Dining Room
                // + Upstairs Hallway while HA hung), a WebViewClient that does not handle
                // onRenderProcessGone makes Android KILL THE WHOLE APP: camera, MQTT and the dashboard
                // were gone and only the launcher was left. Drop the overlay instead; it is rebuilt on
                // the next show.
                wv.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        android.util.Log.w(TAG, "screensaver: WebView renderer gone (crash=${detail.didCrash()}) - overlay dropped, app kept alive")
                        main.post { dropAfterRendererGone(view) }
                        return true
                    }
                }
                container.addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))

                // Transparent catcher ABOVE the page: added last, so it wins every touch.
                val catcher = View(context)
                catcher.setOnTouchListener { v, e ->
                    BridgeService.noteTouch()   // flicking through photos is using the screen
                    if (e.actionMasked == MotionEvent.ACTION_UP) onTap(e.x, v.width)
                    true
                }
                container.addView(catcher, FrameLayout.LayoutParams(MATCH, MATCH))

                val lp = WindowManager.LayoutParams(
                    MATCH, MATCH,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // Touchable (no FLAG_NOT_TOUCHABLE) but not focusable: we want taps without
                    // taking key focus off the dashboard. KEEP_SCREEN_ON so the panel doesn't
                    // blank mid-slideshow — the caller decides when photos end.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE
                )
                lp.screenOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                // Staged out of sight: not touchable either (see setTouchable).
                if (!showNow) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

                root = container
                winLp = lp
                web = wv
                visible = showNow
                container.alpha = 0f
                wm.addView(container, lp)
                if (showNow) container.animate().alpha(1f).setDuration(FADE_MS).start()
                wv.loadUrl(url)
                android.util.Log.i(TAG,
                    if (showNow) "screensaver: shown ($url)" else "screensaver: prestaged ($url)")
                applyBrightness()
            }.onFailure {
                android.util.Log.w(TAG, "screensaver: show failed: ${it.message}")
                root = null
                web = null
                winLp = null
                visible = false
            }
        }
    }

    /** The renderer died under this overlay: remove it at once (no fade - the page is gone). */
    private fun dropAfterRendererGone(wv: WebView) {
        val v = root
        if (v != null && web === wv) {
            root = null
            onExit = null
            visible = false
            winLp = null
            web = null
            runCatching { wm.removeView(v) }
            if (lastReported != false) { lastReported = false; onShowingChanged?.invoke(false) }
        }
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv); wv.destroy() }
    }

    fun hide() {
        main.post {
            val v = root ?: return@post
            root = null
            onExit = null
            visible = false
            winLp = null
            val wv = web
            web = null
            v.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                runCatching { wm.removeView(v) }
                // Tear the page down properly or it keeps running (and holding memory) invisibly.
                runCatching {
                    wv?.stopLoading()
                    wv?.loadUrl("about:blank")
                    (wv?.parent as? ViewGroup)?.removeView(wv)
                    wv?.destroy()
                }
            }.start()
            android.util.Log.i(TAG, "screensaver: hidden")
            if (lastReported != false) { lastReported = false; onShowingChanged?.invoke(false) }
        }
    }

    /**
     * Portal TV (no touchscreen, a D-pad remote): the overlay is not focusable, so remote keys land on the
     * dashboard under the photos - DashboardActivity hands them here first. While the photos are on screen:
     * left / right = previous / next photo (like the side thirds), any other navigation key (OK, up, down,
     * Back) = exit (like the centre tap). Returns true when the key was used for the photos.
     */
    fun onRemoteKey(keyCode: Int): Boolean {
        if (!isShowing) return false
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> sendKey("ArrowLeft", "previous (remote)")
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> sendKey("ArrowRight", "next (remote)")
            else -> {
                android.util.Log.i(TAG, "screensaver: remote key $keyCode -> exit")
                onExit?.invoke()
            }
        }
        return true
    }

    private fun onTap(x: Float, width: Int) {
        if (width <= 0) return
        when {
            x < width / 3f -> sendKey("ArrowLeft", "previous")
            x > width * 2f / 3f -> sendKey("ArrowRight", "next")
            else -> {
                android.util.Log.i(TAG, "screensaver: centre tap -> exit")
                onExit?.invoke()
            }
        }
    }

    /**
     * Drive the page's own navigation with a synthetic key event.
     *
     * The two frames bind this quite differently, and the dispatch has to satisfy both:
     *  - ImmichFrame: `window.addEventListener("keydown", …)`, switching on `event.key`.
     *  - Immich Kiosk: htmx, declared in the HTML rather than its JS bundle —
     *    `hx-trigger="… keyup[key=='ArrowLeft'] from:body …"`. So it wants **keyup**, and
     *    its listener sits on **body**.
     *
     * Hence: dispatch on `document.body` with `bubbles:true`, and send both keydown and keyup.
     * Bubbling carries it body → document → window, so listeners at any of those three levels
     * fire, and `event.target` is `document.body` — which Kiosk's own JS handler also requires
     * (`if (e.target === document.body)`). Dispatching on `window`, as this used to, reaches
     * window listeners ONLY: events do not propagate downward, which is exactly why Kiosk
     * navigation did nothing while the centre-tap exit still worked.
     *
     * `code` is set alongside `key` because Kiosk's JS switch reads `event.code`; for the arrows
     * the two strings are identical, so one value serves both.
     */
    private fun sendKey(key: String, what: String) {
        val wv = web ?: return
        android.util.Log.i(TAG, "screensaver: $what")
        runCatching {
            wv.evaluateJavascript(
                "(function(){var t=document.body||document.documentElement;" +
                    "['keydown','keyup'].forEach(function(ty){" +
                    "t.dispatchEvent(new KeyboardEvent(ty," +
                    "{key:'$key',code:'$key',bubbles:true,cancelable:true}));});})();",
                null
            )
        }
    }

    private companion object {
        const val TAG = "PortalHA"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val FADE_MS = 400L
    }
}
