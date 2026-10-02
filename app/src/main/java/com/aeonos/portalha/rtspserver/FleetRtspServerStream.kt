package com.aeonos.portalha.rtspserver

import android.content.Context
import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.library.base.StreamBase
import com.pedro.library.util.sources.audio.AudioSource
import com.pedro.library.util.sources.video.VideoSource
import com.pedro.library.util.streamclient.StreamBaseClient
import java.nio.ByteBuffer

/**
 * RTSP-Server 1.3.0's RtspServerStream (pedroSG94, Apache-2.0) on the fleet server
 * ([FleetRtspServer]): headless source-based camera/mic -> encoder -> RTSP server.
 */
class FleetRtspServerStream(
    context: Context, port: Int, connectChecker: ConnectChecker,
    videoSource: VideoSource, audioSource: AudioSource,
) : StreamBase(context, videoSource, audioSource) {

    val rtspServer = FleetRtspServer(connectChecker, port)

    fun startStream() {
        super.startStream("")
        rtspServer.startServer()
    }

    override fun audioInfo(sampleRate: Int, isStereo: Boolean) = rtspServer.setAudioInfo(sampleRate, isStereo)
    override fun rtpStartStream(endPoint: String) {}
    override fun rtpStopStream() = rtspServer.stopServer()
    override fun getAacDataRtp(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) = rtspServer.sendAudio(aacBuffer, info)

    override fun onSpsPpsVpsRtp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        rtspServer.setVideoInfo(sps.duplicate(), pps?.duplicate(), vps?.duplicate())
    }

    override fun getH264DataRtp(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) = rtspServer.sendVideo(h264Buffer, info)
    override fun getStreamClient(): FleetRtspServerStreamClient = FleetRtspServerStreamClient(rtspServer)
    override fun setVideoCodecImp(codec: VideoCodec) = rtspServer.setVideoCodec(codec)
    override fun setAudioCodecImp(codec: AudioCodec) = rtspServer.setAudioCodec(codec)
}

class FleetRtspServerStreamClient(private val rtspServer: FleetRtspServer) : StreamBaseClient() {
    fun forceIpType(ipType: IpType) = rtspServer.forceIpType(ipType)
    fun getNumClients(): Int = rtspServer.getNumClients()
    fun getEndPointConnection(): String = "rtsp://${rtspServer.serverIp}:${rtspServer.port}/"
    override fun setAuthorization(user: String?, password: String?) = rtspServer.setAuth(user, password)
    override fun setBitrateExponentialFactor(factor: Float) {}
    override fun setReTries(reTries: Int) {}
    override fun reTry(delay: Long, reason: String, backupUrl: String?): Boolean = false
    override fun hasCongestion(percentUsed: Float): Boolean = rtspServer.hasCongestion(percentUsed)
    override fun setLogs(enabled: Boolean) {}          // the fleet server never logs per packet
    override fun setCheckServerAlive(enabled: Boolean) {}
    override fun resizeCache(newSize: Int) {}           // fixed, bounded per client
    override fun clearCache() {}
    override fun getBitrateExponentialFactor(): Float = 1f
    override fun getCacheSize(): Int = FleetServerClient.MAX_QUEUE_FRAMES
    override fun getItemsInCache(): Int = rtspServer.getItemsInCache()
    override fun getSentAudioFrames(): Long = rtspServer.sentAudioFrames
    override fun getSentVideoFrames(): Long = rtspServer.sentVideoFrames
    override fun getDroppedAudioFrames(): Long = rtspServer.droppedAudioFrames
    override fun getDroppedVideoFrames(): Long = rtspServer.droppedVideoFrames
    override fun resetSentAudioFrames() {}
    override fun resetSentVideoFrames() {}
    override fun resetDroppedAudioFrames() {}
    override fun resetDroppedVideoFrames() {}
    override fun setOnlyAudio(onlyAudio: Boolean) = rtspServer.setOnlyAudio(onlyAudio)
    override fun setOnlyVideo(onlyVideo: Boolean) = rtspServer.setOnlyVideo(onlyVideo)
}
