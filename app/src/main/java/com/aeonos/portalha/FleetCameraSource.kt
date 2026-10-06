package com.aeonos.portalha

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import com.pedro.encoder.input.video.Camera2ApiManager
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

    private val camera = Camera2ApiManager(context)
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

    /** Experimental camera hop (see RtspStreamer.cameraId). */
    fun openCameraId(id: String) {
        if (isRunning()) stop()
        camera.openCameraId(id)
    }
}
