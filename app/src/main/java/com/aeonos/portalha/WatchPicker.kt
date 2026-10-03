package com.aeonos.portalha

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Watch together" picker over the YouTube screen (long-press a video tile, or the pad's Together button):
 * Portal chips (every Bridge that announced itself on portal/watch/members/<slug>) + an All chip to their
 * right, Start / Cancel. Same chip rules as the TVs card: All selects every Portal; deselecting one clears
 * All; selecting each one by hand lights All. This Portal is picked by default. Start hands the video and the
 * picked slugs to [onStart] (BridgeService publishes the 'start' request HA turns into script.portal_qa_watch).
 */
class WatchPicker(
    private val ctx: Context,
    private val root: FrameLayout,
    private val video: String,
    private val title: String,
    private val members: List<Pair<String, String>>,   // (slug, display name)
    self: String,
    private val onStart: (List<String>) -> Unit,
    private val onClose: () -> Unit,
) {
    private val picked = LinkedHashSet<String>().apply { if (members.any { it.first == self }) add(self) }
    private lateinit var overlay: FrameLayout
    private val chips = ArrayList<Pair<String, TextView>>()   // slug ("*" = All) -> chip

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private fun chipBg(on: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(22).toFloat()
        setColor(if (on) Color.argb(220, 220, 38, 38) else Color.argb(60, 255, 255, 255))
        setStroke(dp(1), Color.argb(if (on) 0 else 90, 255, 255, 255))
    }

    private fun button(text: String, primary: Boolean, click: () -> Unit) = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
        setPadding(dp(26), dp(12), dp(26), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (primary) Color.argb(235, 220, 38, 38) else Color.argb(70, 255, 255, 255))
        }
        setOnClickListener { click() }
    }

    fun show() {
        overlay = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            isClickable = true                       // swallow taps: nothing reaches the TV page underneath
            setOnClickListener { close() }
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.argb(245, 24, 24, 27)) }
            isClickable = true                       // a tap inside the panel doesn't close it
        }
        panel.addView(TextView(ctx).apply {
            text = "Watch together"; setTextColor(Color.WHITE); textSize = 22f
        })
        panel.addView(TextView(ctx).apply {
            text = title.ifBlank { "youtu.be/$video" }; setTextColor(Color.argb(170, 255, 255, 255)); textSize = 14f
            maxLines = 2; setPadding(0, dp(4), 0, dp(14))
        })
        val cols = if (ctx.resources.displayMetrics.widthPixels > ctx.resources.displayMetrics.heightPixels) 4 else 3
        val grid = GridLayout(ctx).apply { columnCount = cols }
        (members.map { it } + Pair("*", "All")).forEach { (slug, name) ->
            val c = TextView(ctx).apply {
                text = name; setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER
                minWidth = dp(150); setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { toggle(slug) }
            }
            val lp = GridLayout.LayoutParams().apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            grid.addView(c, lp)
            chips.add(Pair(slug, c))
        }
        panel.addView(grid)
        if (members.isEmpty()) panel.addView(TextView(ctx).apply {
            text = "No Portal has announced itself yet (wait a moment after the Bridge starts)."
            setTextColor(Color.argb(170, 255, 255, 255)); textSize = 14f
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(18), 0, 0) }
        row.addView(button("Cancel", false) { close() })
        row.addView(View(ctx), LinearLayout.LayoutParams(dp(12), 1))
        row.addView(button("Start together", true) {
            if (picked.isEmpty()) return@button
            val order = members.map { it.first }.filter { it in picked }
            close(); onStart(order)
        })
        panel.addView(row)
        overlay.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        paint()
    }

    private fun toggle(slug: String) {
        if (slug == "*") {
            if (picked.size == members.size) picked.clear() else members.forEach { picked.add(it.first) }
        } else if (!picked.remove(slug)) picked.add(slug)
        paint()
    }

    private fun paint() {
        val all = members.isNotEmpty() && picked.size == members.size
        chips.forEach { (slug, c) -> c.background = chipBg(if (slug == "*") all else slug in picked) }
    }

    val isShowing: Boolean get() = ::overlay.isInitialized && overlay.parent != null

    fun close() {
        if (::overlay.isInitialized) root.removeView(overlay)
        onClose()
    }
}
