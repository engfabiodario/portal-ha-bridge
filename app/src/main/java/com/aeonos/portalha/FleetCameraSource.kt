package com.aeonos.portalha

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.util.sources.video.VideoSource

// Fleet: front-camera source that captures at EXACTLY the encoder size.
//
// RootEncoder's Camera2Source swaps a requested size for a "supported" one of the same aspect
// (Camera2ResolutionCalculator), e.g. 960x720 -> 640x480 (Portal camera 0 lists only 1280x720,
// 640x480, 320x240), then upscales it. Camera 0 on a Portal is Meta's virtual camera (aiservice
// renders into any surface size; 720x720 already proved that), so we just size the surface to
// what we encode. Same Camera2ApiManager underneath (same session/request/fps handling).
class FleetCameraSource(context: Context) : VideoSource() {

    private val camera = Camera2ApiManager(context)
    private var surface: Surface? = null

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean {
        require(width % 2 == 0 && height % 2 == 0) { "width and height values must be divisible by 2" }
        return true
    }

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (!isRunning()) {
            surfaceTexture.setDefaultBufferSize(width, height)
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
