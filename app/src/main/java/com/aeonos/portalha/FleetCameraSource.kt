package com.aeonos.portalha

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.os.SystemClock
import android.util.Log
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.util.sources.video.VideoSource

// Fleet: front-camera source that always captures at the camera's LARGEST listed size.
//
// RootEncoder's Camera2Source sizes the capture from the ENCODER size: a size of the same aspect
// from the camera's list (Camera2ResolutionCalculator: 960x720 -> 640x480), else the size itself,
// which the framework then rounds to the closest listed one - the Portal+ 720x720 stream was a
// 640x480 capture (the virtual camera's configureStreams says so), upscaled. Camera 0 on a Portal
// is Meta's virtual camera: aiservice renders its view stretched to fill whatever buffer it gets,
// and RootEncoder's GL stage stretches the buffer to the encoder size, so the ENCODER aspect alone
// decides the picture's aspect and a bigger buffer only adds detail.
// Same Camera2ApiManager underneath (same session/request/fps handling).
class FleetCameraSource(context: Context) : VideoSource() {

    private val camera = Camera2ApiManager(context).also { it.setCameraCallbacks(openLog) }
    private var surface: Surface? = null
    @Volatile var bufferSize = "?"
        private set

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        require(width % 2 == 0 && height % 2 == 0) { "width and height values must be divisible by 2" }
        return true
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (!isRunning()) {
            val big = runCatching { camera.getCameraResolutions(CameraHelper.Facing.FRONT) }.getOrNull()
                ?.maxByOrNull { it.width.toLong() * it.height }
            val bw = big?.width ?: width
            val bh = big?.height ?: height
            bufferSize = "${bw}x$bh"
            surfaceTexture.setDefaultBufferSize(bw, bh)
            surface = Surface(surfaceTexture)
            camera.prepareCamera(surface, fps)
            camera.openCameraFacing(CameraHelper.Facing.FRONT)
        }
    }

    override fun stop() {
        if (isRunning()) camera.closeCamera()
    }

    override fun release() {
        runCatching { surface?.release() }
        surface = null
    }

    override fun isRunning(): Boolean = camera.isRunning

    companion object {
        /**
         * fleet (2026-11-09, audit bridge-code.m0): ONE log line per camera open result (tag PortalHA, 'camera: ...').
         * RootEncoder's Camera2ApiManager swallows an open refusal (onError 3 = CAMERA_DISABLED: Android refused
         * the camera to a process without a visible activity) and the stream still reports 'streaming', so the
         * result was never recorded. [last] / [lastElapsed] feed self-heal's stream check and the camera revive.
         */
        @Volatile var last = "unknown"
            private set
        @Volatile var lastElapsed = 0L
            private set
        @Volatile var opened = false
            private set
        @Volatile var openCount = 0
            private set
        @Volatile var refusedCount = 0
            private set
        fun status(): String = "camera: last='$last' ${if (lastElapsed == 0L) "never" else "${(SystemClock.elapsedRealtime() - lastElapsed) / 1000}s ago"} opened=$openCount refused=$refusedCount"
        private val openLog = object : CameraCallbacks {
            override fun onCameraChanged(facing: CameraHelper.Facing) = Unit
            override fun onCameraOpened() {
                opened = true; openCount++; last = "opened"; lastElapsed = SystemClock.elapsedRealtime()
                Log.i("PortalHA", "camera: opened (front, open #$openCount)")
            }
            override fun onCameraDisconnected() {
                opened = false; last = "disconnected"; lastElapsed = SystemClock.elapsedRealtime()
                Log.i("PortalHA", "camera: disconnected")
            }
            override fun onCameraError(error: String) {
                opened = false; refusedCount++; last = "refused: $error"; lastElapsed = SystemClock.elapsedRealtime()
                Log.w("PortalHA", "camera: open refused / error: $error (refusal #$refusedCount)")
            }
        }
    }

    /** Experimental camera hop (see RtspStreamer.cameraId). */
    fun openCameraId(id: String) {
        if (isRunning()) stop()
        camera.openCameraId(id)
    }
}
