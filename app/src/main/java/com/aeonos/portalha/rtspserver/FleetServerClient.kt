package com.aeonos.portalha.rtspserver

import android.media.MediaCodec
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.onMainThreadHandler
import com.pedro.rtsp.rtcp.BaseSenderReport
import com.pedro.rtsp.rtp.packets.AacPacket
import com.pedro.rtsp.rtp.packets.Av1Packet
import com.pedro.rtsp.rtp.packets.BasePacket
import com.pedro.rtsp.rtp.packets.G711Packet
import com.pedro.rtsp.rtp.packets.H264Packet
import com.pedro.rtsp.rtp.packets.H265Packet
import com.pedro.rtsp.rtp.packets.OpusPacket
import com.pedro.rtsp.rtp.sockets.BaseRtpSocket
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtpFrame
import com.pedro.rtsp.rtsp.commands.Method
import com.pedro.rtsp.utils.RtpConstants
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One RTSP client of the fleet RTSP server (replaces RTSP-Server 1.3.0's ServerClient + RootEncoder
 * 2.4.6's RtspSender for it).
 *
 * WHY (2026-10-01): in the library every client's TCP sender wrote its RTP packets inside
 * `synchronized(RtpConstants.lock)` - ONE static lock for the whole process. A client whose peer
 * vanished without a FIN/RST (the laptop running Frigate rebooted) blocks in socket write() once its
 * kernel send buffer is full, HOLDING that lock, for the ~15 min the kernel needs to give up
 * (tcp_retries2). Every other client's sender then waits for the lock: new Frigate connections got
 * ESTAB but zero bytes (0 fps), and every client's 10 MB frame queue (10 MB / MTU = 6990 packets)
 * filled up - n clients x 10 MB + reconnect pile-up = OutOfMemoryError on the 256 MB heap.
 *
 * Here each client has:
 *  - its OWN write lock (RTP, RTCP and RTSP replies of that client only) - a stuck client can never
 *    hold up another one;
 *  - a dedicated sender thread and a BOUNDED queue ([MAX_QUEUE_FRAMES] packets, ~1.5 MB). On
 *    overflow the queue is emptied and video restarts at the next key frame (no corrupt GOP); the
 *    drop is for this client only;
 *  - progress stamps the server's watchdog reads ([stallReason]): a write blocked for [STALL_MS], or
 *    frames offered but nothing written for [STALL_MS] = the server closes THIS client's socket,
 *    which unblocks its write() and ends both of its threads;
 *  - SO_KEEPALIVE + TCP keepalive timers + TCP_USER_TIMEOUT so the kernel also drops a dead peer in
 *    ~20 s instead of ~15 min;
 *  - a byte-level request reader: interleaved '$' packets a client sends (RTCP receiver reports)
 *    are skipped instead of being parsed as garbage RTSP; EOF ends the client (the library looped).
 */
class FleetServerClient(
    private val socket: Socket,
    private val connectChecker: ConnectChecker,
    val clientAddress: String,
    private val scm: ServerCommandManager,
    private val listener: Listener,
    val id: Int,
) : Thread("rtsp-client-$id") {

    interface Listener {
        fun onClientClosed(client: FleetServerClient)
    }

    companion object {
        private const val TAG = "PortalHA"
        /** Packets queued per client (each <= MTU 1500 B): ~1.5 MB worst case, ~5 s of 2 Mbit/s. */
        const val MAX_QUEUE_FRAMES = 1000
        /** No write progress for this long = a stalled client (evicted by the server's watchdog). */
        const val STALL_MS = 12_000L
        /** A client that never PLAYs and sends nothing for this long is dropped. */
        const val PRE_PLAY_IDLE_MS = 60_000L
        private const val TCP_USER_TIMEOUT = 18        // linux/tcp.h
        private const val TCP_KEEPIDLE = 4
        private const val TCP_KEEPINTVL = 5
        private const val TCP_KEEPCNT = 6
        private const val SR_INTERVAL_MS = 3000L
        private const val SR_LENGTH = 28
        private const val MAX_REQUEST = 16 * 1024
    }

    val remotePort: Int = socket.port
    val connectedAt: Long = SystemClock.elapsedRealtime()
    val label: String get() = "#$id $clientAddress:$remotePort"

    private val input: InputStream = BufferedInputStream(socket.getInputStream(), 8 * 1024)
    private val out: OutputStream = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
    private val writeLock = Any()

    /** elapsedRealtime when the write in progress began; 0 = not writing. */
    @Volatile var writeStartedAt = 0L
        private set
    /** elapsedRealtime of the last completed write. */
    @Volatile var lastWriteAt = connectedAt
        private set
    /** elapsedRealtime of the last media packet handed to this client. */
    @Volatile var lastOfferAt = 0L
        private set
    @Volatile var lastRequestAt = connectedAt
        private set
    @Volatile var playingSince = 0L
        private set
    @Volatile var canSend = false
        private set
    @Volatile var closed = false
        private set
    @Volatile var closeReason = ""
        private set
    @Volatile var bytesSent = 0L
        private set
    @Volatile var droppedVideo = 0L
        private set
    @Volatile var droppedAudio = 0L
        private set
    @Volatile var overflows = 0L
        private set
    @Volatile var sentVideo = 0L
        private set
    @Volatile var sentAudio = 0L
        private set

    private val queue = ArrayBlockingQueue<RtpFrame>(MAX_QUEUE_FRAMES)
    // Touched only by the encoder threads (under the server's client-list lock).
    @Volatile private var waitKeyFrame = true
    private var lastOverflowLog = 0L

    private var protocol = Protocol.TCP
    private var myVideoPorts: IntArray? = null
    private var myAudioPorts: IntArray? = null
    private var videoPacket: BasePacket? = null
    private var audioPacket: BasePacket? = null
    private var udpRtp: BaseRtpSocket? = null
    private var udpReport: BaseSenderReport? = null
    private var sender: Thread? = null

    // TCP RTCP sender reports (same content as the library's BaseSenderReport).
    private val srVideo = ByteArray(SR_LENGTH)
    private val srAudio = ByteArray(SR_LENGTH)
    private var srVideoTime = 0L
    private var srAudioTime = 0L
    private var srVideoPackets = 0L
    private var srVideoOctets = 0L
    private var srAudioPackets = 0L
    private var srAudioOctets = 0L
    private val tcpHeader = ByteArray(4)

    init {
        isDaemon = true
        configureSocket()
    }

    private fun configureSocket() {
        runCatching { socket.keepAlive = true }
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.soTimeout = 30_000 }   // before PLAY only (cleared at PLAY)
        runCatching {
            // The socket's own FileDescriptor (libcore Socket.getFileDescriptor$, greylisted - fine
            // with the fleet's hidden_api_policy). NOT ParcelFileDescriptor.fromSocket: on Android 9
            // it wraps this very FileDescriptor object, and closing OR detaching that wrapper
            // invalidates the socket (tested: every client died with "Socket closed").
            val fd = Socket::class.java.getMethod("getFileDescriptor\$").invoke(socket) as java.io.FileDescriptor
            // Unacknowledged data older than 20 s = the kernel drops the connection.
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_USER_TIMEOUT, 20_000)
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 15)
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 5)
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 3)
        }.onFailure { Log.w(TAG, "rtsp: client $label socket options: ${it.message}") }
    }

    // ── Request side (this thread) ─────────────────────────────────────────────

    override fun run() {
        Log.i(TAG, "rtsp: client $label connected")
        try {
            while (!closed) {
                val text = try {
                    readRequest()
                } catch (e: SocketTimeoutException) {
                    if (canSend) continue
                    close("no request for 30 s before PLAY")
                    break
                }
                if (text == null) { close("client closed the connection"); break }
                if (text.isBlank()) continue          // an interleaved packet from the client, skipped
                lastRequestAt = SystemClock.elapsedRealtime()
                handle(text)
            }
        } catch (e: Exception) {
            close(if (closed) closeReason else "read: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            close(closeReason.ifEmpty { "ended" })
            listener.onClientClosed(this)
        }
    }

    /** One RTSP request's text, "" for a skipped interleaved packet, null at EOF. */
    private fun readRequest(): String? {
        var b = input.read()
        if (b == -1) return null
        if (b == '$'.code) {
            input.read()                                // channel
            val hi = input.read()
            val lo = input.read()
            if (hi < 0 || lo < 0) return null
            skipFully((hi shl 8) or lo)
            return ""
        }
        val sb = StringBuilder()
        while (true) {
            sb.append(b.toChar())
            val n = sb.length
            if (n >= 2 && sb[n - 1] == '\n' && (sb[n - 2] == '\n' || (n >= 4 && sb[n - 2] == '\r' && sb[n - 3] == '\n'))) {
                if (sb.isBlank()) { sb.setLength(0) } else break
            }
            if (n > MAX_REQUEST) throw IOException("request longer than $MAX_REQUEST bytes")
            b = input.read()
            if (b == -1) return null
        }
        val text = sb.toString()
        val cl = Regex("(?im)^content-length\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (cl in 1..65536) skipFully(cl)
        return text
    }

    private fun skipFully(n: Int) {
        var left = n
        while (left > 0) {
            val s = input.skip(left.toLong()).toInt()
            if (s > 0) { left -= s; continue }
            if (input.read() == -1) throw IOException("EOF inside a client packet")
            left--
        }
    }

    private fun handle(text: String) {
        val cmd = scm.getRequest(BufferedReader(StringReader(text)))
        if (cmd.cSeq == -1) {
            writeText(scm.createError(500, -1))
            return
        }
        val head = text.trimStart().uppercase()
        val response = if (cmd.method == Method.UNKNOWN &&
            (head.startsWith("GET_PARAMETER") || head.startsWith("SET_PARAMETER"))) {
            // Keep-alive some clients send; the library answered 400.
            "RTSP/1.0 200 OK\r\nServer: pedroSG94 Server\r\nCseq: ${cmd.cSeq}\r\nSession: 1185d20035702ca\r\nContent-Length: 0\r\n\r\n"
        } else synchronized(scm) {
            // scm is shared by all clients (SDP / ports / protocol): build the reply and take this
            // client's SETUP result in one step.
            val r = scm.createResponse(cmd.method, cmd.text, cmd.cSeq, clientAddress)
            if (cmd.method == Method.SETUP) {
                protocol = scm.protocol
                if (protocol == Protocol.UDP) {
                    myVideoPorts = scm.videoPorts.toIntArray().takeIf { it.size >= 2 }
                    myAudioPorts = scm.audioPorts.toIntArray().takeIf { it.size >= 2 }
                }
            }
            r
        }
        writeText(response)
        when (cmd.method) {
            Method.PLAY -> startPlaying()
            Method.TEARDOWN -> close("TEARDOWN")
            else -> Unit
        }
    }

    private fun writeText(s: String) {
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        synchronized(writeLock) {
            if (closed) throw IOException("closed")
            writeStartedAt = SystemClock.elapsedRealtime()
            try {
                out.write(bytes)
                out.flush()
            } finally {
                writeStartedAt = 0L
            }
            lastWriteAt = SystemClock.elapsedRealtime()
            bytesSent += bytes.size
        }
    }

    private fun startPlaying() {
        if (canSend || closed) return
        val vDis = scm.videoDisabled
        val aDis = scm.audioDisabled
        synchronized(scm) {
            if (!vDis) {
                val sps = scm.sps
                videoPacket = when (scm.videoCodec) {
                    VideoCodec.H264 -> H264Packet(sps ?: throw IOException("no sps"), scm.pps ?: throw IOException("no pps"))
                    VideoCodec.H265 -> H265Packet()
                    VideoCodec.AV1 -> Av1Packet()
                }
            }
            if (!aDis) {
                audioPacket = when (scm.audioCodec) {
                    AudioCodec.G711 -> G711Packet(scm.sampleRate)
                    AudioCodec.AAC -> AacPacket(scm.sampleRate)
                    AudioCodec.OPUS -> OpusPacket(scm.sampleRate)
                }
            }
        }
        val rnd = Random()
        val ssrcVideo = rnd.nextInt().toLong()
        val ssrcAudio = rnd.nextInt().toLong()
        videoPacket?.setSSRC(ssrcVideo)
        audioPacket?.setSSRC(ssrcAudio)
        if (protocol == Protocol.UDP) {
            val rtp = BaseRtpSocket.getInstance(Protocol.UDP, scm.videoServerPorts[0], scm.audioServerPorts[0])
            val rep = BaseSenderReport.getInstance(Protocol.UDP, scm.videoServerPorts[1], scm.audioServerPorts[1])
            rtp.setDataStream(socket.getOutputStream(), clientAddress)
            rep.setDataStream(socket.getOutputStream(), clientAddress)
            rep.setSSRC(ssrcVideo, ssrcAudio)
            myVideoPorts?.let { videoPacket?.setPorts(it[0], it[1]) }
            myAudioPorts?.let { audioPacket?.setPorts(it[0], it[1]) }
            udpRtp = rtp
            udpReport = rep
        } else {
            initSr(srVideo, ssrcVideo)
            initSr(srAudio, ssrcAudio)
        }
        runCatching { socket.soTimeout = 0 }
        val now = SystemClock.elapsedRealtime()
        playingSince = now
        lastWriteAt = now
        waitKeyFrame = true
        sender = Thread({ sendLoop() }, "rtsp-send-$id").also { it.isDaemon = true; it.start() }
        canSend = true
        Log.i(TAG, "rtsp: client $label PLAY (${protocol.name})")
        onMainThreadHandler { connectChecker.onConnectionSuccess() }
    }

    // ── Media side ─────────────────────────────────────────────────────────────

    /** Encoder thread. Never blocks: a full queue drops for this client only. */
    fun sendVideoFrame(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!canSend || closed) return
        val vp = videoPacket ?: return
        if (waitKeyFrame) {
            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) { droppedVideo++; return }
            waitKeyFrame = false
        }
        vp.createAndSendPacket(h264Buffer, info) { f ->
            if (waitKeyFrame) { droppedVideo++; return@createAndSendPacket }
            lastOfferAt = SystemClock.elapsedRealtime()
            if (!queue.offer(f)) overflow()
        }
    }

    fun sendAudioFrame(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!canSend || closed) return
        val ap = audioPacket ?: return
        ap.createAndSendPacket(aacBuffer, info) { f ->
            lastOfferAt = SystemClock.elapsedRealtime()
            if (!queue.offer(f)) droppedAudio++
        }
    }

    private fun overflow() {
        queue.clear()
        waitKeyFrame = true
        overflows++
        droppedVideo++
        val now = SystemClock.elapsedRealtime()
        if (now - lastOverflowLog > 10_000L) {
            lastOverflowLog = now
            Log.w(TAG, "rtsp: client $label can't keep up - its queue ($MAX_QUEUE_FRAMES packets) was full: dropped, video restarts at the next key frame (overflows=$overflows)")
        }
    }

    private fun sendLoop() {
        try {
            while (!closed) {
                val f = queue.poll(1, TimeUnit.SECONDS) ?: continue
                if (protocol == Protocol.TCP) {
                    writeInterleaved(2 * f.channelIdentifier, f.buffer, f.length)
                    if (f.isVideoFrame()) sentVideo++ else sentAudio++
                    maybeSenderReport(f)
                } else {
                    val rtp = udpRtp ?: continue
                    runBlocking { rtp.sendFrame(f, false) }
                    lastWriteAt = SystemClock.elapsedRealtime()
                    bytesSent += f.length
                    if (f.isVideoFrame()) sentVideo++ else sentAudio++
                    udpReport?.let { r -> runBlocking { r.update(f, false) } }
                }
            }
        } catch (e: InterruptedException) {
            // closing
        } catch (e: Exception) {
            close("send: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun writeInterleaved(channel: Int, buf: ByteArray, len: Int) {
        synchronized(writeLock) {
            if (closed) throw IOException("closed")
            tcpHeader[0] = '$'.code.toByte()
            tcpHeader[1] = channel.toByte()
            tcpHeader[2] = (len shr 8).toByte()
            tcpHeader[3] = (len and 0xFF).toByte()
            writeStartedAt = SystemClock.elapsedRealtime()
            try {
                out.write(tcpHeader)
                out.write(buf, 0, len)
                out.flush()
            } finally {
                writeStartedAt = 0L
            }
            lastWriteAt = SystemClock.elapsedRealtime()
            bytesSent += len + 4
        }
    }

    private fun initSr(b: ByteArray, ssrc: Long) {
        b.fill(0)
        b[0] = 0x80.toByte()
        b[1] = 200.toByte()
        setLong(b, SR_LENGTH / 4 - 1L, 2, 4)
        setLong(b, ssrc, 4, 8)
    }

    private fun maybeSenderReport(f: RtpFrame) {
        val now = System.currentTimeMillis()
        val video = f.channelIdentifier == RtpConstants.trackVideo
        val b: ByteArray
        if (video) {
            srVideoPackets++; srVideoOctets += f.length
            setLong(srVideo, srVideoPackets, 20, 24); setLong(srVideo, srVideoOctets, 24, 28)
            if (now - srVideoTime < SR_INTERVAL_MS) return
            srVideoTime = now; b = srVideo
        } else {
            srAudioPackets++; srAudioOctets += f.length
            setLong(srAudio, srAudioPackets, 20, 24); setLong(srAudio, srAudioOctets, 24, 28)
            if (now - srAudioTime < SR_INTERVAL_MS) return
            srAudioTime = now; b = srAudio
        }
        // Same timestamps as the library's BaseSenderReport (nanoTime as "NTP", the frame's RTP ts).
        val ntp = System.nanoTime()
        val hb = ntp / 1_000_000_000
        val lb = (ntp - hb * 1_000_000_000) * 4294967296L / 1_000_000_000
        setLong(b, hb, 8, 12)
        setLong(b, lb, 12, 16)
        setLong(b, f.timeStamp, 16, 20)
        writeInterleaved(2 * f.channelIdentifier + 1, b, SR_LENGTH)
    }

    private fun setLong(b: ByteArray, value: Long, begin: Int, end: Int) {
        var n = value
        for (i in end - 1 downTo begin) {
            b[i] = (n % 256).toByte()
            n = n shr 8
        }
    }

    // ── Watchdog / lifecycle ───────────────────────────────────────────────────

    /** Why this client should be evicted now, or null. Called by the server's watchdog. */
    fun stallReason(now: Long): String? {
        if (closed) return null
        val ws = writeStartedAt
        if (ws != 0L && now - ws > STALL_MS) return "write blocked for ${(now - ws) / 1000} s"
        if (canSend && now - playingSince > STALL_MS && now - lastWriteAt > STALL_MS &&
            lastOfferAt > lastWriteAt && now - lastOfferAt < 5_000L) {
            return "nothing written for ${(now - lastWriteAt) / 1000} s while frames were queued"
        }
        if (!canSend && now - lastRequestAt > PRE_PLAY_IDLE_MS) return "idle ${(now - lastRequestAt) / 1000} s without PLAY"
        return null
    }

    /** Receiving = PLAYing and wrote something in the last [withinMs]. */
    fun receiving(now: Long, withinMs: Long): Boolean = canSend && !closed && now - lastWriteAt <= withinMs

    /** Lagging: a write in progress for > [ms], or frames waiting with no write for > [ms]. */
    fun laggingFor(now: Long): Long {
        val ws = writeStartedAt
        if (ws != 0L) return now - ws
        if (canSend && lastOfferAt > lastWriteAt) return now - lastWriteAt
        return 0L
    }

    fun queued(): Int = queue.size

    /** Closes this client's socket (unblocks a stuck write/read); both threads end. Idempotent. */
    fun close(reason: String) {
        if (closed) return
        closed = true
        closeReason = reason
        canSend = false
        runCatching { socket.close() }
        queue.clear()
        sender?.interrupt()
        runCatching { udpRtp?.close() }
        runCatching { udpReport?.close() }
    }

    fun describe(now: Long): String =
        "$label ${if (canSend) "playing ${(now - playingSince) / 1000}s" else "setup"} " +
            "sent=${bytesSent / 1024}KB lastWrite=${(now - lastWriteAt) / 1000}s " +
            "writeBlocked=${if (writeStartedAt == 0L) 0 else (now - writeStartedAt) / 1000}s " +
            "queue=${queue.size} dropV=$droppedVideo dropA=$droppedAudio overflows=$overflows"
}
