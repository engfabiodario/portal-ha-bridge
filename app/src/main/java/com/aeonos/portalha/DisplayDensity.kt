package com.aeonos.portalha

import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Display

/**
 * Remote display density (fleet 2026-10-22): the wall Portals have no USB, and `wm density` needs adb.
 * Downstairs Hallway (omni) ran at 136 dpi while the other Portal 10" walls run 160 (~15 % smaller UI).
 *
 * set(n): guard 100..320 (and never on SDK 28 = Portal+/TV unless forced); writes Settings.Secure
 * display_density_forced (WRITE_SECURE_SETTINGS, granted at provisioning) so it persists, then applies it live
 * through the hidden IWindowManager.setForcedDisplayDensityForUser(DEFAULT_DISPLAY, n, userId) (reachable with the
 * fleet's hidden_api_policy=1). reset: clearForcedDisplayDensityForUser + the setting cleared. A live apply that
 * fails = 'reboot needed' (the setting is read at boot). status: physical (initial) vs base (forced) vs what
 * this app's resources see now.
 *
 * Entry points: MQTT portal/<id>/config/display_density/set ('<dpi>', '<dpi>!' = allow on SDK 28, 'reset',
 * 'status'; the result line goes to portal/<id>/config/display_density/status, not retained) and adb
 * DEBUG_CONFIG --es displayDensity <n|n!|reset|status>. No HA entity (never voice-exposed).
 */
object DisplayDensity {
    private const val TAG = "PortalHA"
    private const val SETTING = "display_density_forced"
    const val MIN = 100
    const val MAX = 320

    private fun wms(): Any? = runCatching {
        Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
    }.getOrNull()

    private fun userId(): Int = runCatching {
        android.os.UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
    }.getOrDefault(Process.myUid() / 100000)

    private fun wmInt(name: String): Int? = runCatching {
        val w = wms() ?: return null
        Class.forName("android.view.IWindowManager").getMethod(name, Int::class.javaPrimitiveType)
            .invoke(w, Display.DEFAULT_DISPLAY) as Int
    }.getOrNull()

    fun status(ctx: Context): String {
        val physical = wmInt("getInitialDisplayDensity")
        val base = wmInt("getBaseDisplayDensity")
        val setting = runCatching { Settings.Secure.getString(ctx.contentResolver, SETTING) }.getOrNull()
        val app = ctx.applicationContext.resources.displayMetrics.densityDpi
        val cfg = ctx.applicationContext.resources.configuration.densityDpi
        return "density: physical=${physical ?: "?"} base=${base ?: "?"} override=${if (base != null && physical != null && base != physical) base else "none"} " +
            "setting='${setting ?: ""}' app=$app config=$cfg sdk=${Build.VERSION.SDK_INT}"
    }

    /** Returns the result line (also logged). */
    fun handle(ctx: Context, payload: String): String {
        val cmd = payload.trim().lowercase()
        val line = when {
            cmd.isEmpty() -> return ""            // purge of a retained command at connect
            cmd == "status" -> status(ctx)
            cmd == "reset" -> reset(ctx)
            else -> {
                val force = cmd.endsWith("!")
                val n = cmd.removeSuffix("!").trim().toIntOrNull()
                when {
                    n == null -> "density: bad payload '$payload' (a dpi $MIN..$MAX, '<dpi>!', 'reset' or 'status')"
                    n !in MIN..MAX -> "density: refused $n (allowed $MIN..$MAX)"
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !force ->
                        "density: refused $n on SDK ${Build.VERSION.SDK_INT} (Portal+/TV) - send '$n!' to force"
                    else -> set(ctx, n)
                }
            }
        }
        Log.i(TAG, line)
        return line
    }

    private fun set(ctx: Context, dpi: Int): String {
        val wrote = runCatching { Settings.Secure.putString(ctx.contentResolver, SETTING, dpi.toString()) }
            .onFailure { Log.w(TAG, "density: setting write failed: ${it.message}") }.getOrDefault(false)
        val live = runCatching {
            val w = wms() ?: error("no window manager service")
            Class.forName("android.view.IWindowManager").getMethod(
                "setForcedDisplayDensityForUser", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(w, Display.DEFAULT_DISPLAY, dpi, userId())
            true
        }.onFailure { Log.w(TAG, "density: live apply failed: ${(it.cause ?: it).javaClass.simpleName}: ${(it.cause ?: it).message}") }
            .getOrDefault(false)
        return "density: set $dpi - " + when {
            live -> "applied live"
            wrote -> "setting written, live apply failed: reboot needed"
            else -> "FAILED (no setting write, no live apply)"
        } + " | " + status(ctx)
    }

    private fun reset(ctx: Context): String {
        val live = runCatching {
            val w = wms() ?: error("no window manager service")
            Class.forName("android.view.IWindowManager").getMethod(
                "clearForcedDisplayDensityForUser", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(w, Display.DEFAULT_DISPLAY, userId())
            true
        }.onFailure { Log.w(TAG, "density: live reset failed: ${(it.cause ?: it).javaClass.simpleName}: ${(it.cause ?: it).message}") }
            .getOrDefault(false)
        val wrote = runCatching { Settings.Secure.putString(ctx.contentResolver, SETTING, "") }.getOrDefault(false)
        return "density: reset - " + when {
            live -> "cleared live"
            wrote -> "setting cleared, live reset failed: reboot needed"
            else -> "FAILED"
        } + " | " + status(ctx)
    }
}
