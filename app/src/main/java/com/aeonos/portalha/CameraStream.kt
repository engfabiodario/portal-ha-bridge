package com.aeonos.portalha

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraAccessException
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

class CameraStream(private val context: Context) {

    companion object {
        private const val TAG = "PortalHA"
        private const val TARGET_FPS = 15
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS
        private const val JPEG_QUALITY = 70
    }

    private val thread = HandlerThread("portal-ha-camera").also { it.start() }
    private val handler = Handler(thread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var captureRequest: CaptureRequest? = null

    @Volatile var isActive = false
        private set
    // Manual stream rotation in degrees (0/90/180/270), set from prefs/UI.
    @Volatile var rotation = 0
    @Volatile private var cameraWanted = false
    @Volatile private var selectedCameraId: String? = null
    @Volatile private var lastFrameMs = 0L
    @Volatile private var openPending = false

    // Frame buffers reused across frames (camera handler thread only)
    private var nv21Buf: ByteArray? = null
    private var rotBuf: ByteArray? = null

    var onFrame: ((ByteArray) -> Unit)? = null
    var onStateChange: ((active: Boolean) -> Unit)? = null

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (cameraId == selectedCameraId && cameraWanted && !isActive && !openPending) {
                openPending = true
                // Delay before retrying — Portal's system camera does rapid release/acquire
                // cycles; jumping in immediately causes an on/off flicker loop.
                handler.postDelayed({
                    openPending = false
                    if (cameraWanted && !isActive) openCamera()
                }, 3_000L)
                Log.i(TAG, "Camera $cameraId available — retrying in 3s")
            }
        }
        override fun onCameraUnavailable(cameraId: String) {
            // Don't proactively close — onDisconnected fires if the system
            // actually evicts us. Portal's camera service often peeks and
            // releases without disconnecting our session.
            if (cameraId == selectedCameraId) {
                Log.i(TAG, "Camera $cameraId reported unavailable (another app opened it)")
            }
        }
    }

    // Retry after a failed open — covers the camera still being held by another
    // app (e.g. the Portal launcher after returning to us) where no availability
    // transition will ever fire for us.
    private fun scheduleRetry(reason: String) {
        if (!cameraWanted || openPending) return
        openPending = true
        Log.i(TAG, "camera open failed ($reason) — retrying in 5s")
        handler.postDelayed({
            openPending = false
            if (cameraWanted && !isActive) openCamera()
        }, 5_000L)
    }

    fun start() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "CAMERA not granted — run: adb shell pm grant ${context.packageName} android.permission.CAMERA")
            return
        }
        Log.i(TAG, "CameraStream.start() cameraWanted=$cameraWanted isActive=$isActive openPending=$openPending")
        cameraWanted = true
        val cm = context.getSystemService(CameraManager::class.java)
        selectedCameraId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: cm.cameraIdList.firstOrNull()
        if (selectedCameraId == null) { Log.w(TAG, "No camera found"); return }
        cm.registerAvailabilityCallback(availabilityCallback, handler)
        handler.post { openCamera() }
    }

    private fun openCamera() {
        val cameraId = selectedCameraId ?: return
        if (!cameraWanted) return
        Log.i(TAG, "openCamera() cameraId=$cameraId cameraWanted=$cameraWanted isActive=$isActive")
        val cm = context.getSystemService(CameraManager::class.java)
        val map = cm.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)
        val size = sizes.filter { it.width <= 640 }.maxByOrNull { it.width }
            ?: sizes.minByOrNull { it.width }!!

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            if (!cameraWanted) return@setOnImageAvailableListener
            try {
                r.acquireLatestImage()?.use { img ->
                    val now = System.currentTimeMillis()
                    if (now - lastFrameMs >= FRAME_INTERVAL_MS) {
                        lastFrameMs = now
                        onFrame?.invoke(yuvToJpeg(img))
                    }
                }
            } catch (_: Exception) { }
        }, handler)

        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!cameraWanted) { camera.close(); return }
                    cameraDevice = camera
                    try {
                        camera.createCaptureSession(listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (!cameraWanted) { session.close(); return }
                                    captureSession = session
                                    try {
                                        val req = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_PREVIEW
                                        ).apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.CONTROL_AE_MODE,
                                                CaptureRequest.CONTROL_AE_MODE_ON)
                                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                                        }.build()
                                        captureRequest = req
                                        session.setRepeatingRequest(req, null, handler)
                                        isActive = true
                                        Log.i(TAG, "Camera $cameraId streaming — publishing ON")
                                        onStateChange?.invoke(true)
                                    } catch (e: CameraAccessException) {
                                        Log.w(TAG, "setRepeatingRequest failed (camera taken): ${e.message}")
                                        session.close(); captureSession = null
                                        scheduleRetry("setRepeatingRequest")
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.w(TAG, "Camera session configure failed")
                                    scheduleRetry("configure failed")
                                }
                            }, handler)
                    } catch (e: CameraAccessException) {
                        Log.w(TAG, "createCaptureSession failed (camera taken): ${e.message}")
                        // onDisconnected will fire next and clean up cameraDevice
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.i(TAG, "Camera disconnected (taken by another app)")
                    isActive = false; camera.close(); cameraDevice = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera error $error"); isActive = false; onStateChange?.invoke(false)
                    camera.close(); cameraDevice = null
                    scheduleRetry("error $error")
                }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "openCamera failed: ${e.message}")
            scheduleRetry("open exception")
        }
    }

    private fun yuvToJpeg(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val size = w * h * 3 / 2
        val nv21 = nv21Buf?.takeIf { it.size == size } ?: ByteArray(size).also { nv21Buf = it }

        // Copy Y rows (handling row stride padding)
        var pos = 0
        val yBuf = yPlane.buffer
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, pos, w)
            pos += w
        }

        // Interleave V then U for NV21
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val idx = row * uvRowStride + col * uvPixelStride
                vBuf.position(idx); nv21[pos++] = vBuf.get()
                uBuf.position(idx); nv21[pos++] = uBuf.get()
            }
        }

        // Apply the user-selected stream rotation
        val (data, outW, outH) = rotateNV21(nv21, w, h, rotation)

        val yuvImage = YuvImage(data, ImageFormat.NV21, outW, outH, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, outW, outH), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    // Rotates an NV21 frame clockwise by 90/180/270. Returns the (possibly
    // swapped) output dimensions; 0 returns the input untouched.
    private fun rotateNV21(src: ByteArray, w: Int, h: Int, rot: Int): Triple<ByteArray, Int, Int> {
        if (rot == 0) return Triple(src, w, h)
        val size = w * h * 3 / 2
        val out = rotBuf?.takeIf { it.size == size } ?: ByteArray(size).also { rotBuf = it }
        val ySize = w * h
        when (rot) {
            90 -> {
                for (y in 0 until h) for (x in 0 until w)
                    out[x * h + (h - 1 - y)] = src[y * w + x]
                for (py in 0 until h / 2) for (px in 0 until w / 2) {
                    val si = ySize + py * w + px * 2
                    val di = ySize + px * h + (h / 2 - 1 - py) * 2
                    out[di] = src[si]; out[di + 1] = src[si + 1]
                }
                return Triple(out, h, w)
            }
            270 -> {
                for (y in 0 until h) for (x in 0 until w)
                    out[(w - 1 - x) * h + y] = src[y * w + x]
                for (py in 0 until h / 2) for (px in 0 until w / 2) {
                    val si = ySize + py * w + px * 2
                    val di = ySize + (w / 2 - 1 - px) * h + py * 2
                    out[di] = src[si]; out[di + 1] = src[si + 1]
                }
                return Triple(out, h, w)
            }
            180 -> {
                for (i in 0 until ySize) out[ySize - 1 - i] = src[i]
                var si = ySize
                var di = size - 2
                while (si < size) {
                    out[di] = src[si]; out[di + 1] = src[si + 1]
                    si += 2; di -= 2
                }
                return Triple(out, w, h)
            }
        }
        return Triple(src, w, h)
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
        captureRequest = null
    }

    fun stop() {
        Log.i(TAG, "CameraStream.stop() cameraWanted=$cameraWanted isActive=$isActive")
        cameraWanted = false
        isActive = false
        openPending = false
        runCatching {
            context.getSystemService(CameraManager::class.java)
                .unregisterAvailabilityCallback(availabilityCallback)
        }
        closeCamera()
        selectedCameraId = null
        // Post a final correction at the end of the handler queue.
        // If onConfigured raced with stop() and set isActive=true / published "ON",
        // this message runs after it and forces the correct final state.
        handler.post {
            if (!cameraWanted) {
                isActive = false
                onStateChange?.invoke(false)
            }
        }
    }

    fun release() {
        stop()
        // fleet (2026-11-16, review): quitting at once lost an in-flight openCamera's onOpened (posted to a dead handler)
        // = camera 0 never closed. Give a pending open 3 s to land (onOpened closes a camera nobody wants), then quit.
        handler.postDelayed({ thread.quitSafely() }, 3_000L)
    }
}
