package com.aeonos.portalha

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

class SensorBridge(
    private val context: android.content.Context,
    private val onPublish: (topic: String, payload: String, qos: Int) -> Unit,
    // When one of the app's windows was last touched, on SystemClock.elapsedRealtime (0 = never):
    // a knock next to a touch is the screen being tapped, not the frame being knocked.
    private val lastTouchAt: () -> Long = { 0L }
) : SensorEventListener {

    companion object {
        private const val TAG = "PortalHA"
        private const val RGB_TYPE = 65537
        private const val TAP_COOLDOWN_MS = 800L
        private const val TAP_RESET_MS = 1500L
        private const val GRAVITY_ALPHA = 0.85f

        // Publish deadbands (see Gate). A change must clear both the absolute and the relative band.
        private const val HEARTBEAT_MS = 60_000L
        private const val LIGHT_MIN_LX = 5f            // ambient light: >= max(5 lx, 10 %)
        private const val LIGHT_REL = 0.10f
        private const val LIGHT_MIN_INTERVAL_MS = 5_000L
        private const val RGB_MIN_ABS = 5f             // RGB channels (raw counts ~1e4): >= max(5, 10 %)
        private const val RGB_REL = 0.10f
        private const val RGB_MIN_INTERVAL_MS = 5_000L
        private const val ACCEL_MIN_DELTA = 0.5f       // m/s2 on any axis (gravity-filtered)
        private const val ACCEL_MIN_INTERVAL_MS = 5_000L
        private const val TEMP_MIN_DELTA = 0.3f        // degC, raw reading
        private const val TEMP_MIN_INTERVAL_MS = 30_000L
        private const val SETTLE_MS = 1_500L           // a change must still be there this much later
    }

    /**
     * Publish deadband for one high-rate sensor. HA records every MQTT state that differs from the
     * previous one, and these sensors were ~83% of all HA state changes (a Portal+'s ambient light
     * alone ~7k/h from +-3 lx flicker). A reading goes out only when [moved] says the latest reading
     * left the deadband around the value last published AND still does SETTLE_MS later (a
     * one-sample spike is dropped), at most once per [minIntervalMs]. The check re-runs on a timer,
     * so the end value of a step is published even when the sensor then goes quiet (on-change
     * sensors do). Without a real change the LAST PUBLISHED payload is re-sent every HEARTBEAT_MS:
     * HA is refreshed after an MQTT reconnect without recording a new state.
     * Sensor thread only (the handler below), so no locking.
     */
    private inner class Gate(
        private val minIntervalMs: Long,
        private val topic: (Prefs) -> String,
        private val moved: () -> Boolean,
        private val render: () -> String      // renders the latest reading AND records it as published
    ) {
        private var lastMs = 0L
        private var payload: String? = null
        private var pending = false
        private val recheck = Runnable { pending = false; offer(settled = true) }

        fun offer(settled: Boolean = false) {
            val p = prefs ?: return
            val now = SystemClock.elapsedRealtime()
            val last = payload
            if (last == null) { publishNow(p, now); return }
            if (moved()) {
                if (settled && now - lastMs >= minIntervalMs) { publishNow(p, now); return }
                if (!pending) {
                    pending = true
                    handler.postDelayed(recheck, maxOf(SETTLE_MS, lastMs + minIntervalMs - now))
                }
                return
            }
            if (now - lastMs >= HEARTBEAT_MS) { lastMs = now; onPublish(topic(p), last, 0) }
        }

        // Publishes the latest reading at once (first reading, or a settings change like the offset).
        fun publishNow(p: Prefs, now: Long = SystemClock.elapsedRealtime()) {
            lastMs = now
            val out = render()
            payload = out
            onPublish(topic(p), out, 0)
        }
    }

    // Which optional sensors this hardware actually has — drives whether the
    // matching HA entities are published. Portal has RGB (65537); Portal+ has
    // ambient temperature instead. Detected at start() from the sensor list.
    var hasRgb = false
        private set
    var hasTemperature = false
        private set

    private val sm = context.getSystemService(SensorManager::class.java)
    private val thread = HandlerThread("portal-ha-sensors").also { it.start() }
    private val handler = Handler(thread.looper)

    // The Portal+ 2nd gen ("cipher") has its accelerometer on the moving screen
    // arm, which heavily dampens body taps — measured de-gravitied force was only
    // ~0.4–1.5 (vs the still-floor <0.4). Scale the threshold down hard so firm
    // taps land just above that floor. This model only.
    private val isCipher = android.os.Build.DEVICE.equals("cipher", true)
    private val tapScale = if (isCipher) 0.25f else 1f

    @Volatile private var gravX = 0f
    @Volatile private var gravY = 0f
    @Volatile private var gravZ = 0f
    private var gravInit = false

    private var lastTapMs = 0L
    // Double knock -> the "Knock" event entity. Separate from the Tap sensor above (whose 800 ms
    // cooldown can't see a second knock anyway), which keeps behaving exactly as before.
    private val knocks = KnockDetector()
    // Latest readings, and what was last published (the deadband centre).
    private var lux = 0f
    private var pubLux = 0f
    private val rgb = FloatArray(3)
    private val pubRgb = FloatArray(3)
    private val pubAccel = FloatArray(3)
    private var lastTemp = Float.MIN_VALUE   // latest RAW temperature (no offset)
    private var pubTemp = 0f

    private fun outside(v: Float, centre: Float, minAbs: Float, rel: Float) =
        abs(v - centre) >= maxOf(minAbs, rel * abs(centre))

    private val lightGate = Gate(LIGHT_MIN_INTERVAL_MS, { HaDiscovery.lightStateTopic(it.deviceId) },
        { outside(lux, pubLux, LIGHT_MIN_LX, LIGHT_REL) },
        { pubLux = lux; "%.1f".format(lux) })

    private val rgbGate = Gate(RGB_MIN_INTERVAL_MS, { HaDiscovery.rgbStateTopic(it.deviceId) },
        { (0..2).any { outside(rgb[it], pubRgb[it], RGB_MIN_ABS, RGB_REL) } },
        {
            rgb.copyInto(pubRgb)
            """{"r":${"%.1f".format(rgb[0])},"g":${"%.1f".format(rgb[1])},"b":${"%.1f".format(rgb[2])}}"""
        })

    // Publishes the gravity (low-pass) vector: raw samples carry every tap/knock spike.
    private val accelGate = Gate(ACCEL_MIN_INTERVAL_MS, { HaDiscovery.accelStateTopic(it.deviceId) },
        {
            abs(gravX - pubAccel[0]) >= ACCEL_MIN_DELTA || abs(gravY - pubAccel[1]) >= ACCEL_MIN_DELTA ||
                abs(gravZ - pubAccel[2]) >= ACCEL_MIN_DELTA
        },
        {
            pubAccel[0] = gravX; pubAccel[1] = gravY; pubAccel[2] = gravZ
            """{"x":${"%.2f".format(gravX)},"y":${"%.2f".format(gravY)},"z":${"%.2f".format(gravZ)}}"""
        })

    // The deadband is on the raw reading; the offset is applied at publish time.
    private val tempGate = Gate(TEMP_MIN_INTERVAL_MS, { HaDiscovery.tempStateTopic(it.deviceId) },
        { abs(lastTemp - pubTemp) >= TEMP_MIN_DELTA },
        { pubTemp = lastTemp; "%.1f".format(lastTemp + (prefs?.tempOffset ?: 0f)) })

    @Volatile private var prefs: Prefs? = null

    fun start(prefs: Prefs) {
        this.prefs = prefs
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        sm.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == RGB_TYPE }?.let {
            hasRgb = true
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let {
            hasTemperature = true
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        Log.i(TAG, "sensors: rgb=$hasRgb temperature=$hasTemperature")
    }

    fun stop() {
        sm.unregisterListener(this)
        thread.quitSafely()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val p = prefs ?: return
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> handleLight(event)
            Sensor.TYPE_ACCELEROMETER -> handleAccel(event, p)
            Sensor.TYPE_AMBIENT_TEMPERATURE -> handleTemp(event)
            RGB_TYPE -> handleRgb(event)
        }
    }

    private fun handleTemp(event: SensorEvent) {
        lastTemp = event.values[0]   // raw reading; offset applied at publish time
        tempGate.offer()
    }

    // Last RAW temperature reading (no offset), or null before the first one.
    // Shown live in Sensor settings so the offset can be calibrated against a
    // real thermometer without guessing what the sensor currently reads.
    fun rawTemperature(): Float? = if (lastTemp == Float.MIN_VALUE) null else lastTemp

    // Re-emit the last temperature with the current offset — called when the
    // offset changes so HA updates immediately instead of waiting for a reading.
    fun republishTemperature() {
        val p = prefs ?: return
        if (lastTemp == Float.MIN_VALUE) return
        handler.post { tempGate.publishNow(p) }   // on the sensor thread, like every other gate call
    }

    private fun handleLight(event: SensorEvent) {
        lux = event.values[0]
        lightGate.offer()
    }

    private fun handleRgb(event: SensorEvent) {
        // The tcs34x0 driver can report NaN/Infinity in the dark; "%.1f" turns those into
        // NaN/Infinity, which isn't JSON (HA logged a template error per channel every ~30 s
        // all night). Skip such readings - the last published values stay in HA.
        for (i in 0..2) if (!event.values.getOrElse(i) { 0f }.isFinite()) return
        for (i in 0..2) rgb[i] = event.values.getOrElse(i) { 0f }
        rgbGate.offer()
    }

    private fun handleAccel(event: SensorEvent, p: Prefs) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val alpha = if (gravInit) GRAVITY_ALPHA else 0f
        gravX = alpha * gravX + (1 - alpha) * x
        gravY = alpha * gravY + (1 - alpha) * y
        gravZ = alpha * gravZ + (1 - alpha) * z
        gravInit = true

        val lx = x - gravX
        val ly = y - gravY
        val lz = z - gravZ
        val force = sqrt((lx * lx + ly * ly + lz * lz).toDouble()).toFloat()

        val now = System.currentTimeMillis()
        accelGate.offer()

        // Threshold is read live from prefs so HA slider and app slider take effect
        // immediately; tapScale lowers it on the less-sensitive Portal+ 2nd gen.
        val threshold = p.tapThreshold * tapScale
        if (force > threshold && now - lastTapMs > TAP_COOLDOWN_MS) {
            lastTapMs = now
            val dir = when {
                abs(lx) >= abs(ly) && abs(lx) >= abs(lz) -> if (lx > 0) "right" else "left"
                abs(ly) >= abs(lx) && abs(ly) >= abs(lz) -> if (ly > 0) "down" else "up"
                // On the cipher Portal+ the gesture reads as a screen tilt, so the
                // Z axis is more accurately up/down than front/back.
                else -> if (isCipher) { if (lz > 0) "up" else "down" } else { if (lz > 0) "front" else "back" }
            }
            Log.i(TAG, "tap: $dir  force=%.1f  threshold=%.1f (scale=%.2f)".format(force, threshold, tapScale))
            onPublish(HaDiscovery.tapStateTopic(p.deviceId), dir, 1)
            handler.removeCallbacksAndMessages("tap_reset")
            handler.postAtTime({
                onPublish(HaDiscovery.tapStateTopic(p.deviceId), "none", 0)
            }, "tap_reset", SystemClock.uptimeMillis() + TAP_RESET_MS)
        }

        // Same force, same (live) threshold as the tap above - the Tap Sensitivity number tunes
        // both. Timed on elapsedRealtime, the clock the touch timestamps use.
        knocks.onSample(SystemClock.elapsedRealtime(), force, threshold)?.let { c ->
            handler.postDelayed({ confirmKnock(c) }, knocks.touchGuardMs)
        }
    }

    // Runs touchGuardMs after the second knock, on the sensor thread like everything else here.
    private fun confirmKnock(c: KnockDetector.Candidate) {
        val p = prefs ?: return
        val touch = lastTouchAt()
        if (!knocks.confirm(c, touch)) {
            Log.i(TAG, "knock: double knock ignored - screen touched ${touch - c.firstMs}ms from its first knock")
            return
        }
        Log.i(TAG, "knock: DOUBLE (gap ${c.gapMs}ms)")
        onPublish(HaDiscovery.knockStateTopic(p.deviceId),
            """{"event_type":"double_knock","gap_ms":${c.gapMs}}""", 1)
    }
}
