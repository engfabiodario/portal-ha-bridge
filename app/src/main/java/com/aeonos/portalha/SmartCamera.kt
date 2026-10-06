package com.aeonos.portalha

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.RectF
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log

// Fleet: the Portal's "Smart Camera" (Meta's AI cameraman) framing.
//
// Camera 0 - the only camera an app can open - is a VIRTUAL camera: Meta's aiservice
// (com.facebook.portal.aiservice, "world model") reads the raw wide sensor and renders the
// AI director's crop (TrackAndHoldAiDirector: pans/zooms onto people) into our surface. So the
// stream is "zoomed in" and wanders. aiservice exposes the PUBLIC Portal SDK smart-camera
// control service (action com.facebook.portal.SMART_CAMERA_EXTERNAL_CONTROL_SERVICE, gated only
// by the NORMAL permission com.facebook.portal.permission.SMART_CAMERA_CONTROL - what 3rd-party
// call apps use). Its ModeSetting_Fixed (crop centre x/y + relative scale, 1.0 = the whole
// field at the output aspect) turns the director into a fixed full-field view = the widest
// picture the virtual camera can give.
//
// The setting belongs to our CONNECTION: aiservice drops it (back to its default auto
// framing) when we disconnect or die, and while a more-foreground client (a Portal call app)
// holds a connection it wins (lowest OOM score) and ours is ignored. So: connect at start,
// set the mode on connect, on every stream (re)start and every REASSERT_MS; reconnect after a
// binder death. Nothing here opens the camera or touches the stream.
//
// No root/system permission is involved; AIDL transactions are written by hand (codes from the
// Portal SDK's generated proxies: service getVersion=1 connect=2; connection close=1 setMode=5).
class SmartCamera(private val context: Context) {

    companion object {
        private const val TAG = "SmartCamera"
        private const val AISERVICE = "com.facebook.portal.aiservice"
        private const val BIND_ACTION = "com.facebook.portal.SMART_CAMERA_EXTERNAL_CONTROL_SERVICE"
        private const val SVC_DESC = "com.facebook.portal.smartcamera.external.control.ISmartCameraControlService"
        private const val CONN_DESC = "com.facebook.portal.smartcamera.external.control.ISmartCameraControlConnection"
        private const val TX_SVC_VERSION = 1
        private const val TX_SVC_CONNECT = 2
        private const val TX_CONN_CLOSE = 1
        private const val TX_CONN_SET_MODE = 5
        private const val REASSERT_MS = 5 * 60_000L
        private const val RETRY_MS = 30_000L
    }

    // "wide" = Fixed full field (default), "fixed" = Fixed with the x/y/scale below,
    // "auto" = leave Meta's own framing alone (no connection at all).
    @Volatile var mode = "wide"
    @Volatile var cropX = 0.5f
    @Volatile var cropY = 0.5f
    @Volatile var cropScale = 1.0f

    private val thread = HandlerThread("SmartCamera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val token: IBinder = Binder()
    @Volatile private var service: IBinder? = null
    @Volatile private var connection: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var version = -1
    @Volatile private var lastSetMs = 0L
    @Volatile private var lastResult = "never"
    @Volatile private var sets = 0
    @Volatile private var failures = 0

    private val death = IBinder.DeathRecipient {
        Log.w(TAG, "aiservice connection died - reconnecting in ${RETRY_MS / 1000} s")
        connection = null; service = null
        handler.post { unbind() }
        handler.postDelayed({ ensure("binder death") }, RETRY_MS)
    }

    private val sc = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            handler.post { onConnected(binder) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "aiservice control service disconnected")
            connection = null; service = null
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (mode != "auto") ensure("tick")
            handler.postDelayed(this, REASSERT_MS)
        }
    }

    fun available(): Boolean = runCatching { context.packageManager.getPackageInfo(AISERVICE, 0); true }.getOrDefault(false)

    fun start() {
        handler.removeCallbacks(tick)
        handler.post { ensure("start") }
        handler.postDelayed(tick, REASSERT_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        handler.post { closeConnection(); unbind() }
        thread.quitSafely()
    }

    /** Apply a new mode / crop now ("auto" releases our connection = Meta's own framing). */
    fun configure(newMode: String, x: Float = cropX, y: Float = cropY, scale: Float = cropScale) {
        mode = newMode.trim().lowercase().ifEmpty { "wide" }
        cropX = x.coerceIn(0f, 1f); cropY = y.coerceIn(0f, 1f); cropScale = scale.coerceIn(0.05f, 1f)
        handler.post {
            if (mode == "auto") { closeConnection(); unbind(); Log.i(TAG, "mode auto: released (Meta framing)") }
            else ensure("configure")
        }
    }

    /** Called by the service after the RTSP stream (re)starts: the virtual camera was re-attached. */
    fun reassert(why: String) { if (mode != "auto") handler.postDelayed({ ensure(why) }, 1500) }

    fun status(): String {
        val ago = if (lastSetMs == 0L) "never" else "${(SystemClock.elapsedRealtime() - lastSetMs) / 1000}s ago"
        return "smartcamera: status mode=$mode crop=($cropX,$cropY,$cropScale) bound=$bound " +
            "connected=${connection != null} version=$version sets=$sets failures=$failures last=$ago ($lastResult)"
    }

    private fun ensure(why: String) {
        if (mode == "auto") return
        val c = connection
        if (c != null && c.isBinderAlive) { setMode(c, why); return }
        if (!bound) {
            val i = Intent(BIND_ACTION).setPackage(AISERVICE)
            bound = runCatching { context.bindService(i, sc, Context.BIND_AUTO_CREATE) }
                .onFailure { Log.w(TAG, "bind failed: ${it.message}") }.getOrDefault(false)
            Log.i(TAG, "bind ($why): $bound")
            if (!bound) { failures++; lastResult = "bind refused"; handler.postDelayed({ ensure("retry") }, RETRY_MS) }
        } else {
            service?.let { onConnected(it) }   // bound but not connected yet (or connection lost)
        }
    }

    private fun onConnected(binder: IBinder) {
        service = binder
        try {
            version = transactInt(binder, SVC_DESC, TX_SVC_VERSION)
            val d = Parcel.obtain(); val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(SVC_DESC)
                d.writeStrongBinder(token)
                binder.transact(TX_SVC_CONNECT, d, r, 0)
                r.readException()
                val c = r.readStrongBinder() ?: throw IllegalStateException("null connection")
                runCatching { c.linkToDeath(death, 0) }
                connection = c
                Log.i(TAG, "connected to aiservice smart camera control (version $version)")
            } finally { d.recycle(); r.recycle() }
            connection?.let { setMode(it, "connect") }
        } catch (t: Throwable) {
            failures++; lastResult = "connect failed: ${t.message}"
            Log.w(TAG, "connect failed: $t")
            handler.postDelayed({ ensure("retry") }, RETRY_MS)
        }
    }

    private fun setMode(c: IBinder, why: String) {
        val data = Bundle()
        val x = if (mode == "fixed") cropX else 0.5f
        val y = if (mode == "fixed") cropY else 0.5f
        val s = if (mode == "fixed") cropScale else 1.0f
        // Same keys/types as the SDK's ModeSetting.Fixed.Builder (all five keys must be present).
        data.putFloat("camera.relative_crop_center_x", x)
        data.putFloat("camera.relative_crop_center_y", y)
        data.putFloat("camera.relative_crop_scale", s)
        data.putParcelable("camera.relative_exposure_region", null as RectF?)
        data.putParcelableArrayList("camera.relative_face_metering_regions", null)
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(CONN_DESC)
            d.writeInt(1)                       // non-null ModeSetting
            d.writeString("ModeSetting_Fixed")  // ModeSetting.writeToParcel: id, then the bundle
            d.writeBundle(data)
            c.transact(TX_CONN_SET_MODE, d, r, 0)
            r.readException()
            sets++; lastSetMs = SystemClock.elapsedRealtime(); lastResult = "fixed ($x,$y,$s) via $why"
            Log.i(TAG, "setMode Fixed crop=($x,$y,$s) ok ($why)")
        } catch (t: Throwable) {
            failures++; lastResult = "setMode failed: ${t.message}"
            Log.w(TAG, "setMode failed ($why): $t")
        } finally { d.recycle(); r.recycle() }
    }

    private fun transactInt(b: IBinder, desc: String, code: Int): Int {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(desc)
            b.transact(code, d, r, 0)
            r.readException()
            return r.readInt()
        } finally { d.recycle(); r.recycle() }
    }

    private fun closeConnection() {
        val c = connection ?: return
        connection = null
        runCatching { c.unlinkToDeath(death, 0) }
        val d = Parcel.obtain()
        try {
            d.writeInterfaceToken(CONN_DESC)
            c.transact(TX_CONN_CLOSE, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Throwable) {
        } finally { d.recycle() }
    }

    private fun unbind() {
        if (!bound) return
        bound = false; service = null
        runCatching { context.unbindService(sc) }
    }
}
