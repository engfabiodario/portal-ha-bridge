package com.aeonos.portalha.rtspserver

import android.media.MediaCodec
import android.os.SystemClock
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.onMainThreadHandler
import com.pedro.rtsp.utils.RtpConstants
import java.io.IOException
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Fleet RTSP server: RTSP-Server 1.3.0's RtspServer (pedroSG94, Apache-2.0) vendored and fixed so a
 * dead client can't starve the others or exhaust the heap (see [FleetServerClient] for the root
 * cause). Additions over the library:
 *  - a watchdog thread (1 s) evicts any client whose [FleetServerClient.stallReason] is set
 *    (write blocked / no write progress for 12 s, or idle 60 s before PLAY) - only that client;
 *  - at most [MAX_CLIENTS] clients: a new connection beyond that evicts the oldest LAGGING client,
 *    else the oldest one not playing yet, else the oldest one;
 *  - per-client bounded queues (no 10 MB queue per client);
 *  - the encoder threads never block on a client.
 * The SDP / command handling (ServerCommandManager) is the library's, unchanged.
 */
class FleetRtspServer(
    private val connectChecker: ConnectChecker,
    val port: Int,
) : FleetServerClient.Listener {

    companion object {
        private const val TAG = "PortalHA"
        const val MAX_CLIENTS = 6
        private const val VPN_INTERFACE = "tun"
        private const val DEFAULT_IP = "0.0.0.0"
    }

    private var server: ServerSocket? = null
    val serverIp: String get() = getIPAddress()
    private val clients = mutableListOf<FleetServerClient>()
    private var thread: Thread? = null
    private var watchdog: Thread? = null
    @Volatile private var running = false
    private val semaphore = Semaphore(0)
    private val serverCommandManager = ServerCommandManager()
    private var ipType = IpType.All
    private var nextId = 1
    @Volatile var evictions = 0L
        private set
    @Volatile var lastEviction = ""
        private set

    fun setAuth(user: String?, password: String?) = serverCommandManager.setAuth(user, password)

    fun startServer() {
        stopServer()
        running = true
        thread = Thread({ acceptLoop() }, "rtsp-accept").also { it.isDaemon = true; it.start() }
        watchdog = Thread({ watchdogLoop() }, "rtsp-watchdog").also { it.isDaemon = true; it.start() }
    }

    private fun acceptLoop() {
        try {
            if (!serverCommandManager.videoDisabled) {
                if (!serverCommandManager.videoInfoReady()) {
                    semaphore.drainPermits()
                    Log.i(TAG, "rtsp: waiting for video info")
                    semaphore.tryAcquire(5000, TimeUnit.MILLISECONDS)
                }
                if (!serverCommandManager.videoInfoReady()) {
                    onMainThreadHandler { connectChecker.onConnectionFailed("video info is null") }
                    return
                }
            }
            server = ServerSocket(port)
        } catch (e: InterruptedException) {
            return            // stopServer() during the wait (the library leaked this as an uncaught crash)
        } catch (e: IOException) {
            onMainThreadHandler { connectChecker.onConnectionFailed("Server creation failed") }
            Log.e(TAG, "rtsp: server creation failed", e)
            return
        }
        serverCommandManager.setServerInfo(serverIp, port)
        Log.i(TAG, "rtsp: server listening on $serverIp:$port (max $MAX_CLIENTS clients, stall eviction ${FleetServerClient.STALL_MS / 1000} s)")
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val socket = server?.accept() ?: break
                val addr = socket.inetAddress?.hostAddress
                if (addr == null) { runCatching { socket.close() }; continue }
                if (!running) { runCatching { socket.close() }; break }
                // As the library did per connection: the SDP / Content-Base carry the CURRENT address.
                val ip = serverIp
                synchronized(serverCommandManager) { serverCommandManager.setServerInfo(ip, port) }
                val client: FleetServerClient
                synchronized(clients) {
                    if (clients.size >= MAX_CLIENTS) evictForRoom()
                    client = FleetServerClient(socket, connectChecker, addr, serverCommandManager, this, nextId++)
                    clients.add(client)
                }
                client.start()
            } catch (e: SocketException) {
                break          // server.close() called
            } catch (e: IOException) {
                Log.w(TAG, "rtsp: accept error: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "rtsp: accept loop error: ${e.message}", e)
            }
        }
        Log.i(TAG, "rtsp: server finished")
    }

    /** Under the clients lock. */
    private fun evictForRoom() {
        val now = SystemClock.elapsedRealtime()
        val victim = clients.filter { !it.closed && it.laggingFor(now) > 2_000L }.minByOrNull { it.connectedAt }
            ?: clients.filter { !it.closed && !it.canSend }.minByOrNull { it.connectedAt }
            ?: clients.filter { !it.closed }.minByOrNull { it.connectedAt }
            ?: return
        evict(victim, "client limit $MAX_CLIENTS reached - a new client connected", now)
    }

    private fun evict(c: FleetServerClient, reason: String, now: Long) {
        Log.w(TAG, "rtsp: evicted client ${c.describe(now)} - $reason")
        evictions++
        lastEviction = "${c.clientAddress}: $reason"
        c.close("evicted: $reason")
    }

    private fun watchdogLoop() {
        while (running) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            }
            val now = SystemClock.elapsedRealtime()
            val snapshot = synchronized(clients) { clients.toList() }
            for (c in snapshot) {
                val why = c.stallReason(now) ?: continue
                evict(c, why, now)
            }
        }
    }

    override fun onClientClosed(client: FleetServerClient) {
        val removed = synchronized(clients) { clients.remove(client) }
        if (removed) {
            Log.i(TAG, "rtsp: client ${client.label} gone (${client.closeReason}) sent=${client.bytesSent / 1024}KB")
            onMainThreadHandler { connectChecker.onDisconnect() }
        }
    }

    fun getNumClients(): Int = synchronized(clients) { clients.size }

    fun stopServer() {
        running = false
        synchronized(clients) {
            clients.forEach { it.close("server stopped") }
            clients.clear()
        }
        runCatching { if (server?.isClosed == false) server?.close() }
        thread?.interrupt()
        watchdog?.interrupt()
        runCatching { thread?.join(100) }
        semaphore.release()
        thread = null
        watchdog = null
        server = null
    }

    fun isRunning(): Boolean = running

    // ── Status for the app (SelfHeal / adb rtspStatus) ─────────────────────────

    /** null = no client is PLAYing; true = at least one PLAYing client got data in [withinMs]; false = none did. */
    fun anyReceiving(withinMs: Long): Boolean? {
        val now = SystemClock.elapsedRealtime()
        val playing = synchronized(clients) { clients.filter { it.canSend && !it.closed && now - it.playingSince > withinMs } }
        if (playing.isEmpty()) return null
        return playing.any { it.receiving(now, withinMs) }
    }

    /** Evicts every PLAYing client that has received nothing for [olderThanMs]. Returns how many. */
    fun evictStalled(olderThanMs: Long, why: String): Int {
        val now = SystemClock.elapsedRealtime()
        val victims = synchronized(clients) {
            clients.filter { it.canSend && !it.closed && now - it.playingSince > olderThanMs && now - it.lastWriteAt > olderThanMs }
        }
        victims.forEach { evict(it, why, now) }
        return victims.size
    }

    fun status(): String {
        val now = SystemClock.elapsedRealtime()
        val list = synchronized(clients) { clients.map { it.describe(now) } }
        return "rtsp: status clients=${list.size}/$MAX_CLIENTS evictions=$evictions lastEviction='$lastEviction'" +
            (if (list.isEmpty()) "" else " | " + list.joinToString(" | "))
    }

    // ── Library API used by the stream / StreamBaseClient ──────────────────────

    fun setOnlyAudio(onlyAudio: Boolean) {
        if (onlyAudio) {
            RtpConstants.trackAudio = 0
            RtpConstants.trackVideo = 1
        } else {
            RtpConstants.trackVideo = 0
            RtpConstants.trackAudio = 1
        }
        serverCommandManager.audioDisabled = false
        serverCommandManager.videoDisabled = onlyAudio
    }

    fun setOnlyVideo(onlyVideo: Boolean) {
        RtpConstants.trackVideo = 0
        RtpConstants.trackAudio = 1
        serverCommandManager.videoDisabled = false
        serverCommandManager.audioDisabled = onlyVideo
    }

    fun sendVideo(h264Buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (serverCommandManager.videoDisabled) return
        synchronized(clients) {
            clients.forEach {
                if (it.canSend && !it.closed) runCatching { it.sendVideoFrame(h264Buffer.duplicate(), info) }
            }
        }
    }

    fun sendAudio(aacBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (serverCommandManager.audioDisabled) return
        synchronized(clients) {
            clients.forEach {
                if (it.canSend && !it.closed) runCatching { it.sendAudioFrame(aacBuffer.duplicate(), info) }
            }
        }
    }

    fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
        serverCommandManager.setVideoInfo(sps, pps, vps)
        semaphore.release()
    }

    fun setAudioInfo(sampleRate: Int, isStereo: Boolean) = serverCommandManager.setAudioInfo(sampleRate, isStereo)

    fun setVideoCodec(videoCodec: VideoCodec) {
        if (isRunning()) throw RuntimeException("Please set VideoCodec before startServer.")
        serverCommandManager.videoCodec = videoCodec
    }

    fun setAudioCodec(audioCodec: AudioCodec) {
        if (isRunning()) throw RuntimeException("Please set AudioCodec before startServer.")
        serverCommandManager.audioCodec = audioCodec
    }

    fun forceIpType(ipType: IpType) {
        if (isRunning()) throw RuntimeException("Please set IpType before startServer.")
        this.ipType = ipType
    }

    fun hasCongestion(percentUsed: Float): Boolean = synchronized(clients) {
        clients.any { it.queued() >= FleetServerClient.MAX_QUEUE_FRAMES * (percentUsed / 100f) }
    }

    fun getItemsInCache(): Int = synchronized(clients) { clients.sumOf { it.queued() } }
    val sentVideoFrames: Long get() = synchronized(clients) { clients.sumOf { it.sentVideo } }
    val sentAudioFrames: Long get() = synchronized(clients) { clients.sumOf { it.sentAudio } }
    val droppedVideoFrames: Long get() = synchronized(clients) { clients.sumOf { it.droppedVideo } }
    val droppedAudioFrames: Long get() = synchronized(clients) { clients.sumOf { it.droppedAudio } }

    private fun getIPAddress(): String {
        val interfaces: List<NetworkInterface> = NetworkInterface.getNetworkInterfaces().toList()
        val vpnInterfaces = interfaces.filter { it.displayName.contains(VPN_INTERFACE) }
        val address: String by lazy { interfaces.findAddress().firstOrNull() ?: DEFAULT_IP }
        return if (vpnInterfaces.isNotEmpty()) {
            vpnInterfaces.findAddress().firstOrNull() ?: address
        } else {
            address
        }
    }

    private fun List<NetworkInterface>.findAddress(): List<String?> = this.asSequence()
        .map { it.inetAddresses.asSequence() }
        .flatten()
        .filter { !it.isLoopbackAddress }
        .map { it.hostAddress }
        .filter { a ->
            a?.startsWith("fe80") != true && a?.startsWith("fc00") != true && a?.startsWith("fd00") != true
        }
        .filter { a ->
            when (ipType) {
                IpType.IPv4 -> a?.contains(":") == false
                IpType.IPv6 -> a?.contains(":") == true
                IpType.All -> true
            }
        }
        .toList()
}
