package com.aeonos.portalha

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.StreamFormat
import kotlin.concurrent.thread

/**
 * Sendspin audio sink for the Portal.
 *
 * The protocol library does the hard parts — Kalman clock sync and a timestamp-ordered buffer —
 * so playback is just: ask the buffer when the next chunk is due, sleep until then, and write it
 * to an [AudioTrack]. PCM chunks are raw frames; Opus chunks (one raw Opus packet each, ~20 ms) are
 * decoded with the platform MediaCodec right before they are written, so the timing stays the
 * buffer's. Anything else is logged loudly rather than played as noise.
 */
class SendspinAudioPlayer(
    private val buffer: AudioBuffer,
    private val clock: ClockSync,
) : AudioPlayer {

    companion object {
        private const val TAG = "PortalHA"
        private const val OPUS_MIME = "audio/opus"

        /** True when this Portal has an Opus decoder (Android 5+ ships the software one). */
        fun opusDecoderAvailable(): Boolean = runCatching {
            val f = MediaFormat.createAudioFormat(OPUS_MIME, 48000, 2)
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null
        }.getOrDefault(false)

        /** OpusHead (RFC 7845) for raw packets: pre-skip 0 - the server already shifted its timestamps by it. */
        private fun opusHead(channels: Int, rate: Int): ByteBuffer =
            ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("OpusHead".toByteArray(Charsets.US_ASCII)); put(1); put(channels.toByte())
                putShort(0); putInt(rate); putShort(0); put(0); flip()
            }

        private fun nativeLong(v: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(v); flip() }
    }

    private var decoder: MediaCodec? = null
    private var decInfo = MediaCodec.BufferInfo()
    private var decPts = 0L
    private var pcmScratch = ByteArray(0)

    private var track: AudioTrack? = null
    private var format: StreamFormat? = null
    private var feeder: Thread? = null

    @Volatile private var running = false
    @Volatile private var dropped = 0L
    @Volatile private var gain = 1f
    @Volatile private var systemMuted = false
    @Volatile private var written = 0L

    override val isPlaying: Boolean get() = running
    override val droppedDecodeFrames: Long get() = dropped

    override fun configure(format: StreamFormat) {
        // A track change re-issues stream/start with the SAME format. Rebuilding the AudioTrack
        // for that is both wasteful and audible, so keep it and just drop whatever is still
        // queued for the previous track — those chunks are already overdue against the new
        // timeline and would otherwise be dumped out in a burst (garbled audio on track change).
        if (format == this.format && track != null) {
            buffer.flush()
            runCatching { decoder?.flush() }
            runCatching { track?.pause(); track?.flush(); if (running) track?.play() }
            Log.i(TAG, "sendspin: stream restarted, same format — buffer flushed")
            return
        }
        // The server re-issues stream/start (and so configure) mid-session. Tear the old track
        // down properly and resume playing if we were already running — otherwise the feeder
        // would go on writing into a released track and the new one would never be started.
        val wasRunning = running
        stopFeeder()
        releaseTrack()
        buffer.flush()
        this.format = format
        val opus = format.codec.equals("opus", ignoreCase = true)
        if (!opus && !format.codec.equals("pcm", ignoreCase = true)) {
            Log.w(TAG, "sendspin: server chose codec '${format.codec}' - only PCM and Opus are supported")
            return
        }
        if (opus && !openOpus(format)) return
        val channelMask = if (format.channels >= 2)
            AndroidAudioFormat.CHANNEL_OUT_STEREO else AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = if (opus) AndroidAudioFormat.ENCODING_PCM_16BIT else when (format.bitDepth) {
            16 -> AndroidAudioFormat.ENCODING_PCM_16BIT
            8 -> AndroidAudioFormat.ENCODING_PCM_8BIT
            32 -> AndroidAudioFormat.ENCODING_PCM_FLOAT
            else -> {
                Log.w(TAG, "sendspin: unsupported bit depth ${format.bitDepth}, assuming 16")
                AndroidAudioFormat.ENCODING_PCM_16BIT
            }
        }
        val minBuf = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
            .coerceAtLeast(8192)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(format.sampleRate)
                    .setChannelMask(channelMask)
                    .build())
            // A couple of buffers' headroom: enough to ride out scheduling jitter without
            // adding latency the group sync would have to compensate for.
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(if (systemMuted) 0f else gain) }   // a rebuild must not un-mute us
        Log.i(TAG, "sendspin: audio configured ${format.codec} ${format.sampleRate}Hz " +
            "${format.channels}ch ${format.bitDepth}bit buf=${minBuf * 2}B resume=$wasRunning")
        if (wasRunning) start()
    }

    override fun start() {
        val t = track ?: run { Log.w(TAG, "sendspin: start with no track"); return }
        if (running) return
        running = true
        written = 0
        runCatching { t.play() }.onFailure { Log.w(TAG, "sendspin: play failed: ${it.message}") }
        feeder = thread(isDaemon = true, name = "sendspin-audio") { feedLoop() }
        Log.i(TAG, "sendspin: audio started")
    }

    override fun flush() {
        buffer.flush()
        runCatching { decoder?.flush() }
        runCatching { track?.pause(); track?.flush(); if (running) track?.play() }
    }

    override fun stop() {
        stopFeeder()
        releaseTrack()
        Log.i(TAG, "sendspin: audio stopped (wrote ${written}B)")
    }

    override fun transition(format: StreamFormat) = configure(format)

    /** Stops the feeder thread and waits for it, so it can't outlive the track it writes to. */
    private fun stopFeeder() {
        running = false
        feeder?.let { runCatching { it.join(500) } }
        feeder = null
    }

    override fun setVolume(gain: Float) {
        this.gain = gain.coerceIn(0f, 1f)
        applyGain()
    }

    /**
     * Silence for a call / Alexa turn / the intercom — but keep consuming the stream.
     *
     * Deliberately NOT a pause: this is a synchronised group stream, so stopping would put us
     * out of step with the other rooms and we'd have to catch up afterwards. Muting keeps our
     * place in the timeline, so when the turn ends we're exactly where everyone else is.
     */
    fun muteForSystem(muted: Boolean) {
        if (systemMuted == muted) return
        systemMuted = muted
        Log.i(TAG, "sendspin: ${if (muted) "muted" else "unmuted"} for system audio")
        applyGain()
    }

    private fun applyGain() {
        runCatching { track?.setVolume(if (systemMuted) 0f else gain) }
    }

    private fun feedLoop() {
        var lastReport = System.currentTimeMillis()
        var idleLogged = false
        while (running) {
            val t = track ?: break
            val waitMicros = buffer.nextChunkDelayMicros()
            if (waitMicros == null) {                 // nothing queued yet
                if (!idleLogged && written == 0L &&
                    System.currentTimeMillis() - lastReport > 5_000) {
                    Log.i(TAG, "sendspin: waiting for audio chunks (buffer empty)")
                    idleLogged = true
                }
                Thread.sleep(5)
                continue
            }
            if (waitMicros > 1_000) {                 // not due yet — nap, but stay responsive
                Thread.sleep((waitMicros / 1000).coerceAtMost(20L))
                continue
            }
            val chunk = buffer.poll() ?: continue
            val dec = decoder
            if (dec != null) {
                // Opus: decode this packet and write whatever PCM the codec hands back.
                decodeOpus(dec, chunk.data, t)
            } else {
                writePcm(t, chunk.data, chunk.data.size)
            }
            if (written > 0) idleLogged = false
            val now = System.currentTimeMillis()
            if (now - lastReport > 10_000) {
                Log.i(TAG, "sendspin: audio ${written / 1024}KB written, " +
                    "buffered=${buffer.size} dropped=$dropped")
                lastReport = now
            }
        }
    }

    /** Blocking write: AudioTrack paces us to real time from here on. */
    private fun writePcm(t: AudioTrack, data: ByteArray, len: Int) {
        val n = runCatching { t.write(data, 0, len) }.getOrDefault(0)
        if (n < 0) {
            Log.w(TAG, "sendspin: AudioTrack.write error $n")
            dropped++
        } else {
            written += n
        }
    }

    private fun openOpus(format: StreamFormat): Boolean {
        releaseDecoder()
        return runCatching {
            val f = MediaFormat.createAudioFormat(OPUS_MIME, format.sampleRate, format.channels)
            f.setByteBuffer("csd-0", opusHead(format.channels, format.sampleRate))
            f.setByteBuffer("csd-1", nativeLong(0L))              // codec delay (ns): none, see opusHead
            f.setByteBuffer("csd-2", nativeLong(80_000_000L))     // seek pre-roll 80 ms (RFC 7845)
            val c = MediaCodec.createDecoderByType(OPUS_MIME)
            c.configure(f, null, null, 0)
            c.start()
            decoder = c; decPts = 0L; decInfo = MediaCodec.BufferInfo()
            Log.i(TAG, "sendspin: opus decoder ${c.name} started")
            true
        }.getOrElse {
            Log.w(TAG, "sendspin: opus decoder failed: ${it.message}")
            releaseDecoder()
            false
        }
    }

    /** One raw Opus packet in, its PCM written to the track (the software decoder answers at once). */
    private fun decodeOpus(c: MediaCodec, packet: ByteArray, t: AudioTrack) {
        try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val ib = c.getInputBuffer(inIdx)!!
                ib.clear(); ib.put(packet)
                c.queueInputBuffer(inIdx, 0, packet.size, decPts, 0)
                decPts += 20_000
            } else {
                dropped++
            }
            var timeout = 5_000L
            while (true) {
                val outIdx = c.dequeueOutputBuffer(decInfo, timeout)
                timeout = 0L
                if (outIdx >= 0) {
                    val size = decInfo.size
                    if (size > 0) {
                        val ob = c.getOutputBuffer(outIdx)!!
                        if (pcmScratch.size < size) pcmScratch = ByteArray(size)
                        ob.position(decInfo.offset); ob.get(pcmScratch, 0, size)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (size > 0) writePcm(t, pcmScratch, size)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "sendspin: opus output ${c.outputFormat}")
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            dropped++
            Log.w(TAG, "sendspin: opus decode error ${e.message}")
            runCatching { c.flush() }
        }
    }

    private fun releaseDecoder() {
        val c = decoder ?: return
        decoder = null
        runCatching { c.stop() }
        runCatching { c.release() }
    }

    private fun releaseTrack() {
        releaseDecoder()
        runCatching { track?.pause(); track?.flush(); track?.stop() }
        runCatching { track?.release() }
        track = null
    }
}
