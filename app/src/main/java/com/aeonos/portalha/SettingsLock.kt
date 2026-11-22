package com.aeonos.portalha

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * fleet (2026-11-22, review x10-02): the Bridge's own settings screens are a few taps from every wall (drawer > Settings).
 * One tap there could stop the camera feed (camera service / streaming / camera power off, motion on), take the stream
 * audio away (go2rtc stalls), or give the mic back to the Bridge's own wake word (Ava deaf). Home Assistant, MQTT and the
 * Setup scripts own all of these, so on the wall they are LOCKED IN THEIR FLEET STATE:
 *  - a switch may still be moved TOWARD the fleet state (camera service / streaming / stream audio / camera ON, motion /
 *    Bridge wake word / Alexa OFF, Coexist ON) - that is what Setup-Portal / Setup-Ava tap (uiautomator) on a fresh or
 *    broken Portal, so those runs keep working unchanged;
 *  - a switch already in the fleet state is greyed (no tap moves it away), Rotate and the Sleep / Wake tests are greyed.
 * Unlock (2 min): hold the screen title 5 s; or adb DEBUG_CONFIG --ei settingsUnlockMinutes N / MQTT control/set
 * settings_unlock:<minutes> (max 30; 0 = lock again). adb DEBUG_CONFIG and the MQTT / HA switches are not affected.
 * The connection screen (MainActivity: broker / HA URL / Save) is NOT locked: Setup-Portal types there on a rename or
 * -Reconfigure of a connected Bridge.
 */
object SettingsLock {
    private const val KEY = "settings_unlock_until"
    private const val HOLD_MS = 5_000L
    const val UNLOCK_MIN_DEFAULT = 2
    const val UNLOCK_MIN_MAX = 30

    private fun sp(ctx: Context) = ctx.getSharedPreferences("portal_ha", Context.MODE_PRIVATE)

    /** True while the wall controls are locked (the default). */
    fun locked(ctx: Context): Boolean = System.currentTimeMillis() >= sp(ctx).getLong(KEY, 0L)

    fun unlockedForSec(ctx: Context): Long = ((sp(ctx).getLong(KEY, 0L) - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)

    /** minutes > 0 = unlocked for that long (max [UNLOCK_MIN_MAX]); 0 = locked now. Returns a status line. */
    fun unlock(ctx: Context, minutes: Int): String {
        val m = minutes.coerceIn(0, UNLOCK_MIN_MAX)
        sp(ctx).edit().putLong(KEY, if (m == 0) 0L else System.currentTimeMillis() + m * 60_000L).apply()
        val line = if (m == 0) "settings: wall lock ON" else "settings: wall lock off for $m min"
        android.util.Log.i("PortalHA", line)
        return line
    }

    fun status(ctx: Context): String = if (locked(ctx)) "settings_lock=on" else "settings_lock=off(${unlockedForSec(ctx)}s)"

    /**
     * Adds the 'managed by Home Assistant' line under the screen's header row (the row holding btn_back) and the 5 s
     * hold-to-unlock on the title. [refresh] re-applies the activity's own enabled states after a lock change.
     */
    fun install(activity: Activity, refresh: () -> Unit): TextView? = runCatching {
        val back = activity.findViewById<Button>(R.id.btn_back) ?: return null
        val header = back.parent as? ViewGroup ?: return null
        val column = header.parent as? LinearLayout ?: return null
        val title = (0 until header.childCount).map { header.getChildAt(it) }.firstOrNull { it is TextView && it !is Button }
        val note = TextView(activity).apply {
            textSize = 13f
            alpha = 0.75f
            setTextColor(0xFFCFD8DC.toInt())
            setPadding(0, 0, 0, (12 * activity.resources.displayMetrics.density).toInt())
        }
        column.addView(note, column.indexOfChild(header) + 1,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val h = Handler(Looper.getMainLooper())
        fun paint() {
            note.text = if (locked(activity))
                "Camera, stream and microphone controls are managed by Home Assistant / Setup - locked on this screen " +
                    "(they can only be put back to the fleet setting). Hold the title 5 s to unlock for $UNLOCK_MIN_DEFAULT min."
            else "Unlocked for ${unlockedForSec(activity)} s - every control works. Hold the title 5 s to lock again."
        }
        paint()
        var unlockAt: Runnable? = null
        title?.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val r = Runnable {
                        val msg = if (locked(activity)) unlock(activity, UNLOCK_MIN_DEFAULT) else unlock(activity, 0)
                        Toast.makeText(activity, msg.removePrefix("settings: "), Toast.LENGTH_SHORT).show()
                        paint(); refresh()
                    }
                    unlockAt = r
                    h.postDelayed(r, HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { unlockAt?.let { h.removeCallbacks(it) }; unlockAt = null }
            }
            true
        }
        // The unlock runs out while the screen is open: lock the controls again by themselves.
        val tick = object : Runnable {
            var wasLocked = locked(activity)
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) return
                val l = locked(activity)
                paint()
                if (l != wasLocked) { wasLocked = l; refresh() }
                h.postDelayed(this, 1_000L)
            }
        }
        h.postDelayed(tick, 1_000L)
        note
    }.getOrNull()

    /** The control may move only toward [fleetState]: enabled while it is NOT there (or unlocked), greyed once it is. */
    fun guard(ctx: Context, v: View, current: Boolean, fleetState: Boolean, otherwiseEnabled: Boolean = true) {
        val allowed = otherwiseEnabled && (!locked(ctx) || current != fleetState)
        v.isEnabled = allowed
        if (!allowed && otherwiseEnabled) v.alpha = 0.45f else if (otherwiseEnabled) v.alpha = 1f
    }

    /** A change AWAY from [fleetState] while locked (a tap the greying did not stop): refused, the caller snaps back. */
    fun refuses(ctx: Context, newValue: Boolean, fleetState: Boolean, what: String): Boolean {
        if (!locked(ctx) || newValue == fleetState) return false
        android.util.Log.w("PortalHA", "settings: '$what' change refused - wall lock (managed by Home Assistant / Setup)")
        Toast.makeText(ctx, "$what is managed by Home Assistant - locked here", Toast.LENGTH_SHORT).show()
        return true
    }

    /** Buttons with no fleet direction (Rotate, Sleep / Wake tests): greyed while locked. */
    fun lockButton(ctx: Context, v: View) {
        val l = locked(ctx)
        v.isEnabled = !l
        v.alpha = if (l) 0.45f else 1f
    }
}
