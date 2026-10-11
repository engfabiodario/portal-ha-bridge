package com.aeonos.portalha

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioChunk
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.StreamFormat
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Sendspin audio sink for the Portal - CLOSED-LOOP synchronised playback.
 *
 * Every chunk carries the server-clock time at which its first sample must reach the speaker.
 * The old sink only waited for that time and then wrote the chunk, which left three errors
 * uncorrected (multi-room rooms audibly apart, 2026-10-10):
 *  - the OUTPUT LATENCY (AudioTrack buffer + Meta's audio HAL/DSP) was never subtracted, and it
 *    differs per model (Portal+ Android 9 vs Portal 10" Android 10);
 *  - a chunk that was late (thread woke late, GC, Wi-Fi burst, a join while the AudioTrack was
 *    still being built - AudioBuffer accepts up to 1 s late) was written anyway and every later
 *    chunk queued behind it: the lag only ever grew and stayed until the next stream/start;
 *  - DAC clock vs server clock drift was never corrected.
 *
 * Now the feeder knows, for every chunk, WHEN the next frame it writes will be heard:
 *   from AudioTrack.getTimestamp (frame P heard at T):  nextOut = T + (framesWritten - P) / rate
 *   when the track is drained:  nextOut = now + hwLatency + (framesWritten - head) / rate
 * (hwLatency learned from the same timestamps, seeded from the hidden AudioTrack.getLatency).
 * It compares that with the chunk's scheduled time S:  error E = nextOut - S  (> 0 = late).
 *  - |E| > 25 ms : hard re-align - trim the late part of the chunk (or drop it), or write
 *                  silence first when early. Happens at start, on a join, after a stall.
 *  - 1.5..25 ms  : gentle catch-up - the chunk is resampled by 1-3 frames (<= ~0.3 %, inaudible),
 *                  the classic drift correction.
 * Chunks are otherwise written back to back (the blocking AudioTrack write paces us), so in
 * steady state the pipeline stays full and contiguous. Every player in a group does the same
 * against the same server clock, so a Portal that joins mid-song lands on the others within a
 * few ms. A residual per-model offset the HAL does not report (speaker DSP) = Music Assistant's
 * per-player "Static playback delay", which this client now accepts (set_static_delay).
 */
class SendspinAudioPlayer(
    private val buffer: AudioBuffer,
    private val clock: ClockSync,
) : AudioPlayer {

    private companion object {
        const val TAG = "PortalHA"
        const val HARD_US = 25_000L            // beyond this: trim / pad at once
        const val SOFT_US = 1_500L             // dead band of the gentle correction
        const val LOOKAHEAD_US = 10_000_000L   // we take chunks long before they are due
        const val MAX_PAD_US = 400_000L        // pad at most this much silence per pass ...
        const val SILENCE_PIECE_FRAMES = 1024  // ... in small blocking writes (stop stays responsive)
        const val HW_ALPHA = 0.05              // EMA weight of a new hardware-latency sample
        const val ERR_ALPHA = 0.2              // EMA weight of a new sync-error sample
    }

    private var track: AudioTrack? = null
    private var format: StreamFormat? = null
    private var feeder: Thread? = null

    @Volatile private var running = false
    @Volatile private var dropped = 0L
    @Volatile private var gain = 1f
    @Volatile private var systemMuted = false
    @Volatile private var written = 0L
    @Volatile private var flushRequested = false

    // Feeder-thread state.
    private var rate = 44100
    private var frameBytes = 4
    private var channels = 2
    private var pcm16 = true
    private var framesWritten = 0L            // frames handed to the track since its last flush
    private var hwLatencyUs = -1L             // HAL/DSP latency beyond the track's own buffer
    private var pending: AudioChunk? = null
    private var aligned = false
    private var errSmoothUs = 0.0
    private var hardStreak = 0
    private var hardMinUs = 0L
    private var lastUnderrunCount = 0
    private val ts = AudioTimestamp()
    private var silence = ByteArray(0)

    // Stats for the 10 s log line.
    private var trims = 0; private var pads = 0; private var soft = 0; private var underruns = 0
    private var trimmedUs = 0L; private var paddedUs = 0L

    override val isPlaying: Boolean get() = running
    override val droppedDecodeFrames: Long get() = dropped

    override fun configure(format: StreamFormat) {
        // A track change / seek re-issues stream/start with the SAME format: keep the AudioTrack,
        // drop what is queued (it belongs to the old timeline) and re-align on the next chunk.
        if (format == this.format && track != null) {
            flush()
            Log.i(TAG, "sendspin: stream restarted, same format - buffer flushed, re-aligning")
            return
        }
        val wasRunning = running
        val hadTrack = track != null
        stopFeeder()
        releaseTrack()
        // A fresh start keeps what already arrived for the new stream (stop() flushed the old
        // one); a mid-session format change drops the old-format leftovers.
        if (hadTrack) buffer.flush()
        this.format = format
        if (!format.codec.equals("pcm", ignoreCase = true)) {
            Log.w(TAG, "sendspin: server chose codec '${format.codec}' but only PCM is supported")
            return
        }
        val channelMask = if (format.channels >= 2)
            AndroidAudioFormat.CHANNEL_OUT_STEREO else AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = when (format.bitDepth) {
            16 -> AndroidAudioFormat.ENCODING_PCM_16BIT
            8 -> AndroidAudioFormat.ENCODING_PCM_8BIT
            32 -> AndroidAudioFormat.ENCODING_PCM_FLOAT
            else -> {
                Log.w(TAG, "sendspin: unsupported bit depth ${format.bitDepth}, assuming 16")
                AndroidAudioFormat.ENCODING_PCM_16BIT
            }
        }
        rate = format.sampleRate
        channels = if (format.channels >= 2) 2 else 1
        val bytesPerSample = when (encoding) {
            AndroidAudioFormat.ENCODING_PCM_8BIT -> 1
            AndroidAudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        pcm16 = encoding == AndroidAudioFormat.ENCODING_PCM_16BIT
        frameBytes = channels * bytesPerSample
        silence = ByteArray(SILENCE_PIECE_FRAMES * frameBytes)
        val minBuf = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
            .coerceAtLeast(8192)
        val t = AudioTrack.Builder()
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
            // A couple of buffers' headroom against scheduling jitter. Its length no longer
            // matters for sync: the feeder measures the whole output latency.
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(if (systemMuted) 0f else gain) }   // a rebuild must not un-mute us
        track = t
        resetAlignment()
        hwLatencyUs = initialHwLatencyUs(t)
        Log.i(TAG, "sendspin: audio configured ${format.sampleRate}Hz " +
            "${format.channels}ch ${format.bitDepth}bit buf=${minBuf * 2}B " +
            "hwLatency~${hwLatencyUs / 1000}ms resume=$wasRunning")
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

    /** Drop everything queued (stream/clear, seek, track restart). The feeder re-aligns. */
    override fun flush() {
        buffer.flush()
        if (running) flushRequested = true else {
            runCatching { track?.pause(); track?.flush() }
            resetAlignment()
        }
    }

    override fun stop() {
        stopFeeder()
        releaseTrack()
        buffer.flush()            // leftovers of this stream must not leak into the next one
        Log.i(TAG, "sendspin: audio stopped (wrote ${written}B)")
    }

    override fun transition(format: StreamFormat) = configure(format)

    /** Stops the feeder thread and waits for it, so it can't outlive the track it writes to. */
    private fun stopFeeder() {
        running = false
        feeder?.let { runCatching { it.join(800) } }
        feeder = null
    }

    override fun setVolume(gain: Float) {
        this.gain = gain.coerceIn(0f, 1f)
        applyGain()
    }

    /**
     * Silence for a call / Alexa turn / the intercom - but keep consuming the stream.
     *
     * Deliberately NOT a pause: this is a synchronised group stream, so stopping would put us
     * out of step with the other rooms. Muting keeps our place in the timeline.
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

    private fun resetAlignment() {
        framesWritten = 0
        pending = null
        aligned = false
        errSmoothUs = 0.0
        hardStreak = 0
        hardMinUs = 0L
    }

    // ── timing ────────────────────────────────────────────────────────────────

    private fun usToFrames(us: Long): Long = us * rate / 1_000_000L
    private fun framesToUs(frames: Long): Long = frames * 1_000_000L / rate

    /** Seed: hidden AudioTrack.getLatency() (ms, includes the track buffer) minus the buffer. */
    private fun initialHwLatencyUs(t: AudioTrack): Long {
        val totalMs = runCatching {
            AudioTrack::class.java.getMethod("getLatency").invoke(t) as Int
        }.getOrNull()
        val bufUs = runCatching { framesToUs(t.bufferSizeInFrames.toLong()) }.getOrDefault(0L)
        return if (totalMs != null && totalMs > 0) (totalMs * 1000L - bufUs).coerceAtLeast(0L) else 0L
    }

    /** Frames handed to the track but not yet pulled by the mixer. */
    private fun bufferedFrames(t: AudioTrack): Long {
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val diff = (framesWritten - head) and 0xFFFFFFFFL
        return if (diff > 0x7FFFFFFFL) 0L else diff
    }

    /**
     * Local time (µs) at which the next frame we write will be heard. Uses the track's
     * presentation timestamp while it plays (and learns the HAL latency from it); when the
     * track is drained (start, stall) the timestamp is stale, so the learned latency is used.
     */
    private fun nextOutUs(t: AudioTrack, nowUs: Long): Long {
        val buffered = bufferedFrames(t)
        val fallback = nowUs + hwLatencyUs.coerceAtLeast(0) + framesToUs(buffered)
        if (framesWritten == 0L) return fallback
        if (!runCatching { t.getTimestamp(ts) }.getOrDefault(false)) return fallback
        val tsUs = ts.nanoTime / 1000L
        if (ts.framePosition <= 0 || nowUs - tsUs > 500_000L || tsUs - nowUs > 200_000L) return fallback
        val ahead = framesWritten - ts.framePosition
        if (ahead < 0 || ahead > usToFrames(5_000_000)) return fallback
        val out = tsUs + framesToUs(ahead)
        val sample = out - nowUs - framesToUs(buffered)
        if (sample < 0 || sample > 2_000_000L) return fallback
        hwLatencyUs = if (hwLatencyUs <= 0) sample
            else (hwLatencyUs * (1 - HW_ALPHA) + sample * HW_ALPHA).roundToLong()
        return out
    }

    /** Local time (µs) the chunk's first sample must be heard. */
    private fun scheduledUs(c: AudioChunk, nowUs: Long): Long =
        clock.toLocalMicros(c.serverTimestampMicros, nowUs) - buffer.staticDelayMicros

    // ── feeder ────────────────────────────────────────────────────────────────

    private fun feedLoop() {
        var lastReport = System.currentTimeMillis()
        var idleLogged = false
        while (running) {
            val t = track ?: break
            if (flushRequested) {
                flushRequested = false
                runCatching { t.pause(); t.flush(); t.play() }
                resetAlignment()
            }
            val chunk = pending ?: buffer.poll(ClockSync.localMicros() + LOOKAHEAD_US)
            if (chunk == null) {                       // nothing queued yet / stream stalled
                if (!idleLogged && written == 0L &&
                    System.currentTimeMillis() - lastReport > 5_000) {
                    Log.i(TAG, "sendspin: waiting for audio chunks (buffer empty)")
                    idleLogged = true
                }
                Thread.sleep(5)
                continue
            }
            pending = chunk
            val now = ClockSync.localMicros()
            // The track ran dry (stream stall, Wi-Fi gap): whatever comes next is re-aligned at once.
            val urc = runCatching { t.underrunCount }.getOrDefault(0)
            if (urc != lastUnderrunCount) {
                if (aligned && urc > lastUnderrunCount) { aligned = false; underruns++ }
                lastUnderrunCount = urc
            }
            val err = nextOutUs(t, now) - scheduledUs(chunk, now)   // > 0: late, < 0: early
            errSmoothUs = if (aligned) errSmoothUs * (1 - ERR_ALPHA) + err * ERR_ALPHA else err.toDouble()
            // Hard moves on the raw error before the first write; afterwards only after 3 chunks
            // in a row agree, by the smallest of them (a single noisy timestamp must not cut the
            // music).
            val hardErr = if (!aligned) err else {
                if (abs(err) > HARD_US && (hardStreak == 0 || (err > 0) == (hardMinUs > 0))) {
                    hardMinUs = if (hardStreak == 0 || abs(err) < abs(hardMinUs)) err else hardMinUs
                    hardStreak++
                } else { hardStreak = 0; hardMinUs = 0 }
                if (hardStreak >= 3) hardMinUs.also { hardStreak = 0; hardMinUs = 0 } else 0L
            }
            val chunkFrames = (chunk.data.size / frameBytes).toLong()

            if (hardErr < -HARD_US) {
                // Early: fill the gap with silence, at most MAX_PAD_US per pass (a chunk that is
                // seconds ahead is simply waited for while the silence plays out).
                val padUs = (-hardErr).coerceAtMost(MAX_PAD_US)
                if (writeSilence(t, usToFrames(padUs))) { pads++; paddedUs += padUs }
                errSmoothUs = 0.0
                aligned = true
                continue
            }
            var data = chunk.data
            if (hardErr > HARD_US) {
                val dropFrames = usToFrames(hardErr)
                trims++; trimmedUs += hardErr
                errSmoothUs = 0.0
                if (dropFrames >= chunkFrames) {        // whole chunk is already past
                    pending = null
                    aligned = true
                    continue
                }
                data = data.copyOfRange((dropFrames * frameBytes).toInt(), data.size)
            } else if (aligned && pcm16 && abs(errSmoothUs) > SOFT_US && chunkFrames > 64) {
                // Gentle catch-up: 1 frame per 25 ms chunk ~ 0.09 %, up to 3 frames when further off.
                val k = ((abs(errSmoothUs) / 5_000.0).toInt() + 1).coerceAtMost(3)
                data = resample16(data, if (errSmoothUs > 0) -k else k)
                soft++
            }
            aligned = true
            pending = null
            val n = runCatching { t.write(data, 0, data.size) }.getOrDefault(0)
            if (n < 0) {
                Log.w(TAG, "sendspin: AudioTrack.write error $n")
                dropped++
            } else {
                written += n
                framesWritten += n / frameBytes
                idleLogged = false
            }
            val ms = System.currentTimeMillis()
            if (ms - lastReport > 10_000) {
                Log.i(TAG, "sendspin: audio ${written / 1024}KB written, buffered=${buffer.size} " +
                    "dropped=$dropped sync err=${"%.1f".format(err / 1000.0)}ms " +
                    "avg=${"%.1f".format(errSmoothUs / 1000.0)}ms hw=${hwLatencyUs / 1000}ms " +
                    "out=${framesToUs(bufferedFrames(t)) / 1000}ms static=${buffer.staticDelayMicros / 1000}ms " +
                    "trims=$trims(${trimmedUs / 1000}ms) pads=$pads(${paddedUs / 1000}ms) soft=$soft underruns=$underruns")
                lastReport = ms
                trims = 0; pads = 0; soft = 0; underruns = 0; trimmedUs = 0; paddedUs = 0
            }
        }
    }

    /** Blocking write of [frames] of silence in small pieces. false if interrupted. */
    private fun writeSilence(t: AudioTrack, frames: Long): Boolean {
        var left = frames
        while (left > 0 && running && !flushRequested) {
            val f = left.coerceAtMost(SILENCE_PIECE_FRAMES.toLong()).toInt()
            val n = runCatching { t.write(silence, 0, f * frameBytes) }.getOrDefault(-1)
            if (n <= 0) return false
            framesWritten += n / frameBytes
            left -= n / frameBytes
        }
        return left <= 0
    }

    /**
     * Linear-interpolation resample of interleaved 16-bit PCM by [delta] frames
     * (negative = fewer frames = catch up, positive = more frames = wait). First and last
     * frames are kept, so chunk boundaries stay continuous.
     */
    private fun resample16(src: ByteArray, delta: Int): ByteArray {
        val inFrames = src.size / frameBytes
        val outFrames = inFrames + delta
        if (outFrames < 2 || inFrames < 2) return src
        val out = ByteArray(outFrames * frameBytes)
        val step = (inFrames - 1).toDouble() / (outFrames - 1).toDouble()
        for (i in 0 until outFrames) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = pos - i0
            for (ch in 0 until channels) {
                val a = sample16(src, i0 * channels + ch)
                val b = sample16(src, i1 * channels + ch)
                val v = (a + (b - a) * frac).roundToInt().coerceIn(-32768, 32767)
                val o = (i * channels + ch) * 2
                out[o] = (v and 0xFF).toByte()
                out[o + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun sample16(b: ByteArray, idx: Int): Int {
        val o = idx * 2
        return (b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)
    }

    private fun releaseTrack() {
        runCatching { track?.pause(); track?.flush(); track?.stop() }
        runCatching { track?.release() }
        track = null
        resetAlignment()
    }
}
