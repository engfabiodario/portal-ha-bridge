package com.aeonos.portalha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Fleet 2026-10-23 (desktop mode way out): a small floating pill shown ONLY while desktop mode is on.
 * The walls have no on-screen Back/Home (status/nav bar pushed off-screen by `wm overscan`, Immortal quick
 * buttons off), so an app that covers the whole screen (Meta "Portal settings", Alexa setup...) left no way
 * back but HA's "Back to Dashboard" (Kitchen, Fabio 10-23).
 *
 *  - "Dashboard" (house icon) = [onDashboard] = exactly HA's Back to Dashboard (ends desktop mode).
 *  - "Back" (chevron) = [onBack] = GLOBAL_ACTION_BACK through ScreenAccessibility (hidden when it isn't bound).
 *
 * TYPE_APPLICATION_OVERLAY, so it stays above every app activity; FLAG_NOT_FOCUSABLE, so the app under it keeps
 * the input focus (Back goes to it). Placed top-right, below the overscanned bar area; draggable (a move past the
 * touch slop drags instead of tapping), clamped clear of the bottom-right keep-alive corner + tap shield.
 * Position kept for the process lifetime. All window work on the main thread.
 */
class DesktopButtonOverlay(private val context: Context) {

    companion object {
        private const val TAG = "PortalHA"
        // Last dragged position (gravity TOP|END offsets, px); -1 = default.
        @Volatile private var savedX = -1
        @Volatile private var savedY = -1
    }

    var onDashboard: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    /** False = no Back half (the accessibility service isn't bound). Read at each show(). */
    var backAvailable: () -> Boolean = { true }

    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var backView: View? = null
    private var dashView: View? = null
    @Volatile var shownAtMs = 0L; private set
    @Volatile var taps = 0; private set

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    val isShowing: Boolean get() = root != null

    fun show() = main.post { showNow() }

    fun hide() = main.post { hideNow() }

    private fun showNow() {
        if (root != null) return
        if (!Settings.canDrawOverlays(context)) { Log.w(TAG, "desktop: button not shown - no overlay permission"); return }
        runCatching {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(28f).toFloat()
                    setColor(Color.parseColor("#E6202124"))
                    setStroke(dp(1.5f), Color.parseColor("#99FFFFFF"))
                }
                setPadding(dp(6f), 0, dp(6f), 0)
                elevation = dp(6f).toFloat()
            }
            val withBack = backAvailable()
            if (withBack) {
                val b = item(Glyph.BACK, "Back") { onBack?.invoke() }
                bar.addView(b); backView = b
                bar.addView(View(context).apply { setBackgroundColor(Color.parseColor("#55FFFFFF")) },
                    LinearLayout.LayoutParams(dp(1f), dp(32f)))
            }
            val d = item(Glyph.HOME, "Dashboard") { onDashboard?.invoke() }
            bar.addView(d); dashView = d
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(56f),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = if (savedX >= 0) savedX else dp(24f)
                y = if (savedY >= 0) savedY else dp(84f)
                title = "PortalHA:DesktopButton"
            }
            clamp(p, null)
            root = bar; lp = p
            wm.addView(bar, p)
            shownAtMs = System.currentTimeMillis()
            Log.i(TAG, "desktop: button shown (back=$withBack, x=${p.x} y=${p.y} from top-right)")
        }.onFailure { Log.w(TAG, "desktop: button show failed: ${it.message}"); root = null; lp = null }
    }

    private fun hideNow() {
        val v = root ?: return
        root = null; lp = null; backView = null; dashView = null; shownAtMs = 0L
        runCatching { wm.removeViewImmediate(v) }.onFailure { runCatching { wm.removeView(v) } }
        Log.i(TAG, "desktop: button removed")
    }

    /** Screen-space centre of the Dashboard (or Back) item, for the injected-tap test; null = not showing. */
    fun centerOnScreen(back: Boolean): IntArray? {
        val v = (if (back) backView else dashView) ?: return null
        if (!v.isAttachedToWindow) return null
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return intArrayOf(loc[0] + v.width / 2, loc[1] + v.height / 2)
    }

    fun status(): String {
        val v = root ?: return "button=none"
        val loc = IntArray(2); runCatching { v.getLocationOnScreen(loc) }
        return "button=shown attached=${v.isAttachedToWindow} visible=${v.windowVisibility == View.VISIBLE} " +
            "rect=${loc[0]},${loc[1]} ${v.width}x${v.height} back=${backView != null} taps=$taps"
    }

    // Keep the pill on screen and out of the bottom-right corner (keep-alive sliver + tap shield, ~150 dp).
    private fun clamp(p: WindowManager.LayoutParams, v: View?) {
        val dm = context.resources.displayMetrics
        val w = v?.width?.takeIf { it > 0 } ?: dp(300f)
        val h = dp(56f)
        p.x = p.x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
        val corner = dp(150f)
        if (p.x < corner && p.y + h > dm.heightPixels - corner) p.y = (dm.heightPixels - corner - h).coerceAtLeast(0)
    }

    private enum class Glyph { BACK, HOME }

    private fun item(glyph: Glyph, label: String, onTap: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(16f), 0)
            minimumHeight = dp(56f)
            contentDescription = label
        }
        row.addView(GlyphView(context, glyph), LinearLayout.LayoutParams(dp(26f), dp(26f)))
        row.addView(TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(8f), 0, 0, 0)
        })
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56f))
        row.setOnClickListener { taps++; Log.i(TAG, "desktop: button '$label' tapped"); onTap() }
        row.setOnTouchListener(DragOrTap(row))
        return row
    }

    // A press that moves past the touch slop drags the whole pill; anything else is the item's click.
    private inner class DragOrTap(private val item: View) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = lp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                    item.alpha = 0.6f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) { dragging = true; item.alpha = 1f }
                    if (dragging) {
                        p.x = startX - dx.toInt()          // gravity END: x grows to the left
                        p.y = startY + dy.toInt()
                        clamp(p, root)
                        root?.let { r -> runCatching { wm.updateViewLayout(r, p) } }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    item.alpha = 1f
                    if (dragging) { savedX = p.x; savedY = p.y } else item.performClick()
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> { item.alpha = 1f; dragging = false }
            }
            return true
        }
    }

    private class GlyphView(context: Context, private val glyph: Glyph) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paint.strokeWidth = w * 0.11f
            path.reset()
            if (glyph == Glyph.BACK) {
                path.moveTo(w * 0.62f, h * 0.18f); path.lineTo(w * 0.30f, h * 0.50f); path.lineTo(w * 0.62f, h * 0.82f)
            } else {
                // roof + walls + door: a plain house
                path.moveTo(w * 0.10f, h * 0.48f); path.lineTo(w * 0.50f, h * 0.12f); path.lineTo(w * 0.90f, h * 0.48f)
                path.moveTo(w * 0.22f, h * 0.40f); path.lineTo(w * 0.22f, h * 0.88f); path.lineTo(w * 0.78f, h * 0.88f)
                path.lineTo(w * 0.78f, h * 0.40f)
                path.moveTo(w * 0.42f, h * 0.88f); path.lineTo(w * 0.42f, h * 0.62f); path.lineTo(w * 0.58f, h * 0.62f)
                path.lineTo(w * 0.58f, h * 0.88f)
            }
            c.drawPath(path, paint)
        }
    }
}
