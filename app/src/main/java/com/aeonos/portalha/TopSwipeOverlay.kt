package com.aeonos.portalha

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Fleet 2026-11-16 (Fabio: "swipe down and get to settings or the desktop on every Portal"): a thin, invisible strip
 * along the middle of the TOP edge. A downward swipe that starts on it calls [onPull] (= desktop mode: the launcher with
 * Settings + the floating "Back | Dashboard" pill). A plain tap on it does nothing. The walls have no Android status bar to
 * pull (it is overscanned away because the parked Ava sliver would force it on screen), so this replaces that gesture.
 *
 * Off by default: Bridge toggle `top_swipe` (MQTT portal/<id>/control/set toggle:top_swipe:on|off). Hidden while desktop
 * mode is on (the pill is the way back then). Only the middle [WIDTH_FRACTION] of the top edge, [HEIGHT_DP] tall, so
 * the dashboard keeps every other touch.
 */
class TopSwipeOverlay(private val context: Context) {
    companion object {
        private const val TAG = "PortalHA"
        private const val HEIGHT_DP = 28f
        private const val WIDTH_FRACTION = 0.4f
        private const val PULL_DP = 56f
    }

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    @Volatile var onPull: (() -> Unit)? = null
    @Volatile private var pulls = 0

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    val isShowing: Boolean get() = root != null

    fun show() = main.post { showNow() }
    fun hide() = main.post { hideNow() }
    fun status(): String = "topswipe=${if (root != null) "shown" else "hidden"} pulls=$pulls"

    private fun showNow() {
        if (root != null) return
        if (!Settings.canDrawOverlays(context)) { Log.w(TAG, "topswipe: not shown - no overlay permission"); return }
        runCatching {
            val width = (context.resources.displayMetrics.widthPixels * WIDTH_FRACTION).toInt().coerceAtLeast(dp(120f))
            val v = object : View(context) {
                private var downY = 0f
                private var fired = false
                override fun onTouchEvent(e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downY = e.rawY; fired = false }
                        MotionEvent.ACTION_MOVE -> if (!fired && e.rawY - downY >= dp(PULL_DP)) {
                            fired = true; pulls++
                            Log.i(TAG, "topswipe: pull down -> desktop")
                            runCatching { onPull?.invoke() }.onFailure { Log.w(TAG, "topswipe: ${it.message}") }
                        }
                    }
                    return true
                }
            }.apply { setBackgroundColor(Color.TRANSPARENT) }
            val p = WindowManager.LayoutParams(
                width, dp(HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0; y = 0
                title = "PortalHA:TopSwipe"
            }
            root = v
            wm.addView(v, p)
            Log.i(TAG, "topswipe: shown (${width}x${dp(HEIGHT_DP)} at the top centre)")
            // Under `wm overscan` (the walls push the status bar 52 px off-screen) a window asked for at y=0 lands ABOVE
            // the visible screen. Measure where it really landed (as AvaKeepAlive does) and move it down by the shift.
            main.postDelayed({ fixOverscan(v, p) }, 300L)
        }.onFailure { Log.w(TAG, "topswipe: show failed: ${it.message}"); root = null }
    }

    private fun fixOverscan(v: View, p: WindowManager.LayoutParams) {
        if (root !== v || v.height <= 0) return
        val loc = IntArray(2)
        runCatching { v.getLocationOnScreen(loc) }.onFailure { return }
        val dy = loc[1] - p.y
        if (dy >= 0) return
        p.y = -dy
        runCatching { wm.updateViewLayout(v, p) }
        Log.i(TAG, "topswipe: landed $dy px above the screen (overscan) - moved down to y=${p.y}")
    }

    private fun hideNow() {
        val v = root ?: return
        root = null
        runCatching { wm.removeViewImmediate(v) }
        Log.i(TAG, "topswipe: hidden")
    }
}
