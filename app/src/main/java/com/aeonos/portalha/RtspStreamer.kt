package com.aeonos.portalha

import android.content.Context
import android.media.MediaCodecInfo
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.library.util.sources.audio.NoAudioSource
import com.aeonos.portalha.rtspserver.FleetRtspServerStream
import com.aeonos.portalha.rtspserver.IpType

// Headless camera -> H.264 (+ optional AAC) -> RTSP server. RtspServerStream is
// the source-based (no preview view) variant, so it runs in our background
// service. It owns Camera 0 (and the mic when audio is on) while streaming, so
// it replaces the MJPEG path; motion detection can't run at the same time.
class RtspStreamer(private val context: Context, private val port: Int = 8554) : ConnectChecker {

    companion object { private const val TAG = "PortalHA" }

    private var stream: FleetRtspServerStream? = null
    @Volatile var isStreaming = false
        private set
    // Fired when the stream is fatally dead while isStreaming is still true —
    // the server socket failed to bind (EADDRINUSE after a restart) or a client
    // asked for video the encoder never produced (camera refused/never opened).
    // BridgeService uses it to flag recovery; without it these states were
    // invisible and the stream stayed dead until an app restart.
    @Volatile var onStreamDead: ((String) -> Unit)? = null
    // Live mic tap when the stream carries audio (see MicTapSource); BridgeService
    // resolves this through the streamer on every SoundMonitor chunk, so stream
    // restarts re-tap automatically without rewiring.
    @Volatile var micTap: MicTapSource? = null
        private set
    // Manual base offset (deg, 0/90/180/270) from the ROTATE button. 0 = camera
    // native landscape. Corrects the base on top of the accelerometer auto value.
    @Volatile var rotationOffset = 0
    // Auto component (deg) from the accelerometer / physical orientation, set by
    // BridgeService's OrientationEventListener — keeps the stream upright as the
    // Portal is physically turned (the OS display rotation is locked).
    @Volatile var autoRotation = 0
    // elapsedRealtime of the last ENCODED video frame (RootEncoder's FpsListener fires about once a
    // second while the encoder produces frames, clients or not); 0 = none since the last start().
    // SelfHeal reads it: a wanted stream whose encoder went quiet is restarted.
    @Volatile var lastFrameElapsed = 0L
        private set
    @Volatile var startedElapsed = 0L
        private set

    // Both Portal+ models ("aloha" 1st-gen, "cipher" 2nd-gen) have a front camera
    // whose usable cam (Camera 0) reports only 1280x720 + 4:3 sizes but whose true
    // FOV is ~SQUARE — so any 16:9 request comes out stretched. Encoding a square
    // surface makes the stream natively display 1:1 in every player with no aspect
    // override (verified on aloha via raw frames; cipher shares the exact camera
    // architecture). aloha encodes 720x720: the full 720 vertical lines of the
    // 1280x720 capture, same framing as the old 480x480, 2.25x the pixels.
    private val squashedFrontCam = android.os.Build.DEVICE.lowercase() in setOf("aloha", "cipher")

    // Fleet: camera to stream from. "" / "0" = Camera 0 (the processed 1280x720 feed,
    // default). Any other id (Portals: "1" = the raw 12-13 MP sensor, wider 4:3 view)
    // is EXPERIMENTAL: Meta's aiservice normally holds it for auto-framing/presence.
    // The stream is prepared at 1440x1080 and switched to that camera once running;
    // if the camera can't be opened the stream keeps running on Camera 0.
    // TESTED 2026-09-27 on aloha: camera 1 is hidden from apps without the privileged
    // android.permission.CAMERA_PRIV (only Meta's system apps have it), so openCameraId("1")
    // never reaches CameraService. Kept as an inert option (default off) for other hardware.
    @Volatile var cameraId: String = ""

    // Fleet: encoder size override (landscape WxH, before rotation), null = model default.
    // Camera 0 is Meta's virtual camera: aiservice renders its (fixed, see SmartCamera) view
    // into whatever surface size we ask for, so this only sets the output size/aspect.
    @Volatile var sizeOverride: Pair<Int, Int>? = null

    // Capture params from the last start(), reused by restart() on rotation change.
    private var baseWidth = 1280
    private var baseHeight = 720
    private var baseFps = 15
    private var baseBitrate = 2_000_000
    private var baseAudio = true

    /** One line for adb rtspStatus / logs: the server's clients, their progress and evictions. */
    fun rtspStatus(): String = stream?.rtspServer?.status() ?: "rtsp: status server not running"

    /** null = no PLAYing client; true = one got data within [withinMs]; false = none did (SelfHeal). */
    fun clientsReceiving(withinMs: Long): Boolean? = stream?.rtspServer?.anyReceiving(withinMs)

    /** Evicts PLAYing clients that received nothing for [olderThanMs]; returns how many. */
    fun evictStalledClients(olderThanMs: Long, why: String): Int = stream?.rtspServer?.evictStalled(olderThanMs, why) ?: 0

    fun url() = "rtsp://${BridgeService.localIp() ?: "0.0.0.0"}:$port/"

    private fun currentRotation(): Int = (((rotationOffset + autoRotation) % 360) + 360) % 360

    fun start(width: Int, height: Int, fps: Int, bitrate: Int, withAudio: Boolean): Boolean {
        if (isStreaming) return true
        baseWidth = width; baseHeight = height; baseFps = fps; baseBitrate = bitrate; baseAudio = withAudio
        return runCatching {
            // Portal cameras are all front-facing; RootEncoder defaults to BACK
            // (empty here), so build the source explicitly on FRONT.
            // FleetCameraSource opens FRONT at exactly the encoder size (Camera2Source would
            // swap e.g. 960x720 for a listed 640x480 and upscale it).
            val video = FleetCameraSource(context)
            // Neither source opens the mic — critical so the RTSP stream doesn't
            // hold the capture slot and starve/garble Portal calls or fight the
            // SoundMonitor. withAudio uses MicTapSource: a copy of SoundMonitor's
            // capture (16 kHz mono, matching prepareAudio below), silence-filled
            // while the mic is yielded. Without audio, prepareAudio() is still
            // called to satisfy startStream() — an empty AAC track in the SDP.
            val audio = if (withAudio) MicTapSource().also { micTap = it }
                        else NoAudioSource()
            // Fleet RTSP server (rtspserver/): RTSP-Server 1.3.0 vendored with per-client write locks,
            // bounded per-client queues, a 12 s stalled-client watchdog and a 6-client cap. The
            // library's global send lock let ONE dead client (peer gone without FIN) freeze every
            // client for ~15 min and its 10 MB-per-client queues ended in OutOfMemoryError.
            val s = FleetRtspServerStream(context, port, this, video, audio)
            // Kill the library's per-packet logging ("BaseRtpSocket: wrote packet…",
            // ~150 lines/s with a UDP client like go2rtc attached) — it floods the
            // device log so hard that chatty prunes OUR diagnostics away.
            s.getStreamClient().setLogs(false)
            // The server puts ITS OWN address into the SDP (o=/c= and Content-Base), and clients send
            // SETUP/PLAY to that Content-Base. Left on All it picked the Portal's IPv6 ULA
            // (fd4f:...), so every client that can't route IPv6 (Frigate's laptop after it moved to
            // Ethernet, 2026-10-01) hung at SETUP: all Portal cameras at 0 fps. IPv4 only.
            s.getStreamClient().forceIpType(IpType.IPv4)
            stream = s
            lastFrameElapsed = 0L
            startedElapsed = android.os.SystemClock.elapsedRealtime()
            s.setFpsListener { lastFrameElapsed = android.os.SystemClock.elapsedRealtime() }
            // Pass the LANDSCAPE capture dims + rotation; prepareVideo swaps the
            // ENCODER size itself for 90/270 (don't pre-swap — that double-swaps).
            // NOTE: portrait still letterboxes because the Portal front cam can only
            // capture landscape; the camera-fill is a library limitation. Fine for
            // a landscape-mounted Portal (auto-rotate keeps it landscape = no bars).
            val rot = currentRotation()
            // The squashing front cam scales its true FOV into whatever surface we ask
            // for. The two Portal+ models differ:
            //   aloha  - ~SQUARE FOV: encode 480x480 -> displays 1:1 natively, correct
            //            in any player with no override.
            //   cipher - 4:3 FOV but the cam is portrait-mounted, so making it upright
            //            (rot=90) forces the content into a 480x640 portrait buffer
            //            (the 4:3 scene squashed into 3:4). The stream is correct only
            //            when displayed at 4:3. RootEncoder can't bake that (no SAR
            //            setter; its scale modes only letterbox) so the 4:3 is applied
            //            viewer-side: HA WebRTC card adds a SAR via go2rtc's ffmpeg
            //            (#raw=-bsf:v h264_metadata=sample_aspect_ratio=16/9); VLC uses
            //            its 4:3 setting. TODO: a DIY encoder pipeline could stamp SAR.
            val corrected = squashedFrontCam && width * 9 == height * 16
            val isCipher = android.os.Build.DEVICE.equals("cipher", true)
            var fullSensor = cameraId.isNotBlank() && cameraId != "0"
            val override = sizeOverride
            fun encSize(full: Boolean): Pair<Int, Int> = when {
                full -> 1440 to 1080
                override != null -> override
                corrected -> if (isCipher) 640 to 480 else 720 to 720
                else -> width to height
            }
            var (encW, encH) = encSize(fullSensor)
            // Force H.264 Constrained Baseline — WebRTC browser decoders (and most
            // RTSP camera clients) need it. RootEncoder's default is HIGH profile,
            // which WebRTC rejects → "one keyframe then freeze". Fall back to the
            // encoder default if this device can't do Constrained Baseline.
            val profile = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
            fun prepare(w: Int, h: Int): Boolean {
                // Level 3.1 tops out at 1280x720; bigger frames need 4.0.
                val level = if (w * h > 921_600) MediaCodecInfo.CodecProfileLevel.AVCLevel4
                            else MediaCodecInfo.CodecProfileLevel.AVCLevel31
                val br = if (w * h > 921_600) maxOf(bitrate, 3_000_000) else bitrate
                if (s.prepareVideo(w, h, br, fps, 2, rot, profile, level)) return true
                Log.w(TAG, "Constrained-Baseline prepare failed at ${w}x$h; using encoder default profile")
                return s.prepareVideo(w, h, br, fps, 2, rot)
            }
            var videoOk = prepare(encW, encH)
            if (!videoOk && fullSensor) {
                Log.w(TAG, "camera $cameraId: ${encW}x$encH prepare failed; back to Camera 0")
                fullSensor = false
                encSize(false).let { encW = it.first; encH = it.second }
                videoOk = prepare(encW, encH)
            }
            // Always prepare the audio encoder — startStream() requires it even with
            // NoAudioSource (NoAudioSource just means no mic is opened, no data fed).
            val audioOk = s.prepareAudio(16000, false, 64_000)
            if (videoOk && audioOk) {
                s.startStream()
                isStreaming = true
                if (fullSensor) {
                    // start() opened Camera 0 by facing; hop to the requested camera now
                    // that the capture surface exists. A refusal leaves Camera 0 running.
                    runCatching { video.openCameraId(cameraId) }
                        .onSuccess { Log.i(TAG, "camera $cameraId: opened for the stream") }
                        .onFailure { Log.w(TAG, "camera $cameraId: open failed (${it.message}); staying on Camera 0") }
                }
                Log.i(TAG, "RTSP streaming on ${url()} ${encW}x${encH} rot=$rot squash=$squashedFrontCam camera=${if (fullSensor) cameraId else "0"} (audio=$withAudio)")
                true
            } else {
                Log.w(TAG, "RTSP prepare failed (video=$videoOk audio=$audioOk)")
                runCatching { s.stopStream() }
                stream = null
                false
            }
        }.getOrElse { Log.w(TAG, "RTSP start error: ${it.message}", it); stream = null; false }
    }

    // Apply a new rotation by tearing the stream down and starting fresh with the
    // current rotation in prepareVideo. stopStream() interrupts RtspServer's accept
    // thread (the library leaks an uncaught InterruptedException — BridgeService's
    // crash guard swallows it); the settle delay lets that thread die and port 8554
    // release before the new server binds. Clients reconnect (resolution changes),
    // so only call on an actual orientation change, not continuously.
    fun restart(): Boolean {
        stop()
        // 350ms sometimes lost the race — the old accept thread hadn't released
        // port 8554 yet and the new bind died with EADDRINUSE (which also leaks
        // the port in-process). 700ms + the BridgeService dead-stream backoff
        // retries cover the tail.
        runCatching { Thread.sleep(700) }   // let the accept thread die + port release
        return start(baseWidth, baseHeight, baseFps, baseBitrate, baseAudio)
    }

    fun stop() {
        isStreaming = false
        micTap = null
        runCatching { stream?.stopStream() }
        stream = null
        Log.i(TAG, "RTSP streaming stopped")
    }

    override fun onConnectionStarted(url: String) { Log.i(TAG, "rtsp client connecting: $url") }
    override fun onConnectionSuccess() { Log.i(TAG, "rtsp client connected") }
    override fun onConnectionFailed(reason: String) {
        Log.w(TAG, "rtsp failed: $reason")
        // Only the two known-fatal signatures kill the stream; per-client
        // handshake noise must not trigger restarts of a healthy stream.
        if (isStreaming &&
            (reason.contains("Server creation failed") || reason.contains("video info is null"))) {
            onStreamDead?.invoke(reason)
        }
    }
    override fun onNewBitrate(bitrate: Long) { }
    override fun onDisconnect() { Log.i(TAG, "rtsp client disconnected") }
    override fun onAuthError() { Log.w(TAG, "rtsp auth error") }
    override fun onAuthSuccess() { Log.i(TAG, "rtsp auth success") }
}
