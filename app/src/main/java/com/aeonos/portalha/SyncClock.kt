package com.aeonos.portalha

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * A shared clock for "Watch together": every Portal of a session asks the SAME LAN NTP server
 * (fleet: the homelab laptop's Windows Time service, 192.168.68.99, given in the session command)
 * and keeps its own offset. The system clock is never touched (no root) and is useless for this:
 * measured 2026-10-02, the Portals' wall clocks were -316 / +145 / -206 ms off the laptop.
 *
 * SNTP (RFC 4330) bursts of [BURST] requests; the sample with the lowest round trip wins (its error is
 * at most half that round trip, ~2-5 ms on the Decos). The result is anchored to elapsedRealtime, so
 * a system-clock step in between can't move it. [now] = server time in ms (epoch), or null before the
 * first good burst.
 */
object SyncClock {
    private const val TAG = "PortalHA"
    private const val BURST = 8
    private const val GAP_MS = 120L
    private const val TIMEOUT_MS = 800
    private const val NTP_EPOCH_OFFSET = 2_208_988_800L   // 1900 -> 1970, seconds

    // serverMs = elapsedRealtime + base
    @Volatile private var base: Long? = null
    @Volatile var lastDelayMs: Long = -1; private set
    @Volatile var lastSyncElapsed: Long = 0; private set
    @Volatile var server: String = ""; private set
    @Volatile private var running = false

    fun now(): Long? = base?.let { SystemClock.elapsedRealtime() + it }

    /** Server time minus this Portal's wall clock (what the page adds to Date.now()), or null. */
    fun wallOffsetMs(): Long? = now()?.let { it - System.currentTimeMillis() }

    fun status(): String {
        val age = if (lastSyncElapsed == 0L) -1 else (SystemClock.elapsedRealtime() - lastSyncElapsed) / 1000
        return "clock: server=${server.ifEmpty { "-" }} synced=${base != null} rtt=${lastDelayMs}ms age=${age}s wallOffset=${wallOffsetMs() ?: "-"}ms"
    }

    /** One burst now (blocking, call off the main thread). Keeps the previous result when this one is worse. */
    @Synchronized
    fun syncBlocking(host: String): Boolean {
        if (host.isBlank()) return false
        if (host != server) { server = host; base = null; lastDelayMs = -1 }
        var best: Pair<Long, Long>? = null   // (delay, base)
        runCatching {
            val addr = InetAddress.getByName(host)
            DatagramSocket().use { sock ->
                sock.soTimeout = TIMEOUT_MS
                repeat(BURST) {
                    runCatching { sample(sock, addr) }.getOrNull()?.let { s -> if (best == null || s.first < best!!.first) best = s }
                    Thread.sleep(GAP_MS)
                }
            }
        }.onFailure { Log.w(TAG, "clock: sync with $host failed: ${it.message}") }
        val b = best ?: return base != null
        val prevOk = base != null && SystemClock.elapsedRealtime() - lastSyncElapsed < 180_000L
        // A congested burst (Wi-Fi spike) must not replace a good recent one.
        if (prevOk && lastDelayMs in 0..30 && b.first > maxOf(30L, lastDelayMs * 3)) {
            Log.i(TAG, "clock: burst rtt ${b.first}ms - kept the previous offset (rtt ${lastDelayMs}ms)")
            return true
        }
        val old = base
        base = b.second; lastDelayMs = b.first; lastSyncElapsed = SystemClock.elapsedRealtime()
        Log.i(TAG, "clock: synced to $host rtt=${b.first}ms step=${if (old == null) "first" else "${b.second - old}ms"} wallOffset=${wallOffsetMs()}ms")
        return true
    }

    /** Keeps re-syncing every [periodMs] on a thread of its own until [stop]. */
    fun start(host: String, periodMs: Long = 60_000L) {
        server = host
        if (running) return
        running = true
        Thread({
            while (running) {
                syncBlocking(server)
                var waited = 0L
                while (running && waited < periodMs) { Thread.sleep(500); waited += 500 }
            }
        }, "portal-ha-syncclock").apply { isDaemon = true }.start()
    }

    fun stop() { running = false }

    /** One request: (round trip ms, base ms) - base so that server = elapsedRealtime + base. */
    private fun sample(sock: DatagramSocket, addr: InetAddress): Pair<Long, Long> {
        val buf = ByteArray(48)
        buf[0] = 0x1B   // LI 0, VN 3, mode 3 (client)
        val t0 = SystemClock.elapsedRealtimeNanos()
        sock.send(DatagramPacket(buf, buf.size, addr, 123))
        val resp = DatagramPacket(ByteArray(48), 48)
        sock.receive(resp)
        val t3 = SystemClock.elapsedRealtimeNanos()
        val d = resp.data
        val t1 = ntpMs(d, 32)   // server receive
        val t2 = ntpMs(d, 40)   // server transmit
        val rtt = ((t3 - t0) / 1_000_000L) - (t2 - t1)
        // server time at our t3 = t2 + rtt/2  ->  base = that - elapsedRealtime(t3)
        val b = t2 + rtt / 2 - t3 / 1_000_000L
        return Pair(rtt.coerceAtLeast(0L), b)
    }

    private fun ntpMs(d: ByteArray, off: Int): Long {
        var sec = 0L; var frac = 0L
        for (i in 0..3) sec = (sec shl 8) or (d[off + i].toLong() and 0xFF)
        for (i in 4..7) frac = (frac shl 8) or (d[off + i].toLong() and 0xFF)
        return (sec - NTP_EPOCH_OFFSET) * 1000L + (frac * 1000L ushr 32)
    }
}
