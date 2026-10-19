package com.aeonos.portalha

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service used for two things:
 *  - performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) to blank the screen without device admin;
 *  - reporting which app is in the foreground (window-state changes carry the package name),
 *    which is how BridgeService knows we are sitting in the Meta Calls flow. It is the only
 *    permission-free way to see the foreground package on this build (UsageStats isn't granted).
 */
class ScreenAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: ScreenAccessibility? = null
        private const val TAG = "PortalHA"

        /** The Portal TV remote's navigation keys (D-pad, OK, Back) - the ones the photo screensaver uses. */
        fun isRemoteNavKey(code: Int): Boolean = when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> true
            else -> false
        }
    }

    // Fleet (Portal TV "ripley", 2026-10-17): after a (re)park the Ava keep-alive window holds the input focus,
    // so the remote's keys went to Ava and never reached DashboardActivity.dispatchKeyEvent - the photo
    // screensaver could not be closed (logcat: KEYCODE_BACK with AvaKeepAliveActivity on top, no 'screensaver:
    // remote key'). Key filtering sees every key first, whatever window has the focus: while the photos show,
    // the whole press (down, repeats, up) goes to them; any other key is left alone. Touch Portals: never.
    private val noTouchscreen by lazy { !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
    private var photoKey = -1

    // Fleet 2026-10-19: the same focus problem on the YouTube screen (TvAppActivity, watch together): while
    // it is up, the remote's D-pad / OK / Back / play-pause go to it (TvAppActivity.remoteKey) - photos first.
    private var youtubeKey = -1

    private fun isYouTubeKey(code: Int): Boolean = isRemoteNavKey(code) || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!noTouchscreen) return false
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (photoKey == code) return true
            if (youtubeKey == code) { TvAppActivity.remoteKey(code, event.repeatCount); return true }
            if (event.repeatCount == 0 && isRemoteNavKey(code) && BridgeService.screensaverRemoteKey(code)) {
                photoKey = code
                return true
            }
            if (event.repeatCount == 0 && isYouTubeKey(code) && TvAppActivity.remoteKey(code, 0)) {
                youtubeKey = code
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (photoKey == code) { photoKey = -1; return true }
            if (youtubeKey == code) { youtubeKey = -1; return true }
        }
        return false
    }

    override fun onServiceConnected() {
        instance = this
        // fleet: Android rebinds an enabled accessibility service as soon as our process is
        // re-created (e.g. after a crash) - the most reliable hook to bring the Bridge back.
        BridgeService.revive(this, "accessibility service connected")
        // Event types come from the XML config (typeWindowStateChanged). Do NOT override
        // serviceInfo here — a runtime setServiceInfo after a package update could land before
        // the system finishes wiring event delivery and leave us bound but deaf to events
        // (observed: gestures still dispatched, but no window events arrived).
        Log.i(TAG, "ScreenAccessibility connected (eventTypes=${serviceInfo?.eventTypes}, caps=${serviceInfo?.capabilities})")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "ScreenAccessibility unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // IMEs, toasts and pop-ups also fire this, but reporting their package is harmless —
        // BridgeService only acts on a known short list of calling packages, ignoring the rest.
        val pkg = event.packageName?.toString() ?: return
        BridgeService.noteForegroundPackage(pkg)
    }

    override fun onInterrupt() = Unit

    fun sleepNow() {
        Log.i(TAG, "sleepNow: GLOBAL_ACTION_LOCK_SCREEN")
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    /**
     * Inject a single tap at a fraction of the screen (0..1 in each axis), used to dismiss
     * Meta's launcher photo-home when we open it for a call — one tap anywhere clears it.
     * Fractions rather than pixels so it is correct whatever the panel size/rotation.
     */
    fun tapFraction(fx: Float, fy: Float) {
        val dm = resources.displayMetrics
        val x = (dm.widthPixels * fx).coerceIn(1f, dm.widthPixels - 1f)
        val y = (dm.heightPixels * fy).coerceIn(1f, dm.heightPixels - 1f)
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        Log.i(TAG, "tap: injected at ($x,$y) dispatched=$ok")
    }
}
