package kr.gilzikim.app.detect

import kotlin.math.abs
import kotlin.math.max
import kr.gilzikim.app.trip.CandidateReason
import kr.gilzikim.app.trip.Evidence
import kr.gilzikim.app.trip.distanceM

/**
 * Thresholds for the hazard rules. These are first guesses, not validated values: they have to be
 * tuned with real drives, which is why they live in one place.
 */
data class DetectorConfig(
    // Swerve: a quick steering flick away from the current heading and back, with no turn signal.
    val swerveMinSpeedKmh: Double = 20.0,
    val swervePeakDeg: Double = 25.0,
    val swerveReturnDeg: Double = 8.0,
    val swerveMaxDurationMs: Long = 2_500,
    val turnSignalQuietMs: Long = 3_000,
    // Hard brake then re-acceleration without stopping: braking for something on the road.
    val brakeMinSpeedKmh: Double = 30.0,
    val brakeDropKmh: Double = 15.0,
    val brakeDropWindowMs: Long = 3_000,
    val reaccelGainKmh: Double = 10.0,
    val reaccelWindowMs: Long = 5_000,
    val reaccelMinSpeedKmh: Double = 5.0,
    // Damage: a jolt while moving, and low tire pressure that shows up a little later.
    val impactMinG: Double = 0.5,
    val lowTireLookbackMs: Long = 180_000,
    // General.
    val drivingMinSpeedKmh: Double = 5.0,
    val speedStaleMs: Long = 3_000,
    val dedupDistanceM: Double = 50.0,
    val dedupWindowMs: Long = 30_000,
)

/**
 * Turns a stream of [VehicleSample]s from one trip into hazard candidates.
 *
 * Known limits, by design of the first version:
 * - A lane change without a turn signal looks like a swerve. Single-vehicle detections are only
 *   candidates; the server must see several vehicles at the same spot before calling it a hazard.
 * - Without any speed source (the emulator has none), gear D is treated as "moving" for the swerve
 *   rule and the brake rule is off.
 */
class HazardDetector(private val config: DetectorConfig = DetectorConfig()) {

    data class Detection(
        val t: Long,
        val lat: Double,
        val lon: Double,
        val reasons: List<CandidateReason>,
        val evidence: Evidence,
    ) {
        val reason: CandidateReason get() = reasons.first()
    }

    private val detections = mutableListOf<Detection>()
    val results: List<Detection> get() = detections

    private val locations = ArrayDeque<LocationSample>()
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private var hasExplicitSpeed = false
    private var gear = GearState.PARK

    private var turnState = TurnState.OFF
    private var lastTurnSignalT = Long.MIN_VALUE / 2

    private var brakePressed = false
    private var lastBrakePressT = Long.MIN_VALUE / 2

    private var steeringBaseline: Double? = null
    private var excursionStartT: Long? = null
    private var excursionPeakDeg = 0.0
    private var inExcursion = false

    private var dropStartT: Long? = null
    private var dropPeakKmh = 0.0
    private var dropMinKmh = 0.0
    private var dropMinT = 0L

    private val lights = mutableMapOf<WarningLight, Boolean>()
    private var lowTire = false

    fun onSample(sample: VehicleSample) {
        when (sample) {
            is LocationSample -> onLocation(sample)
            is SpeedSample -> {
                hasExplicitSpeed = true
                onSpeed(sample.t, sample.kmh)
            }
            is SteeringSample -> onSteering(sample.t, sample.angleDeg)
            is BrakeSample -> {
                if (sample.pressed && !brakePressed) lastBrakePressT = sample.t
                brakePressed = sample.pressed
            }
            is TurnSignalSample -> {
                if (sample.state != TurnState.OFF || turnState != TurnState.OFF) lastTurnSignalT = sample.t
                turnState = sample.state
            }
            is GearSample -> gear = sample.gear
            is WarningLightSample -> onWarningLight(sample)
            is ImpactSample -> onImpact(sample)
            is LowTireSample -> onLowTire(sample.t, sample.low)
        }
    }

    // ------------------------------------------------------------ speed and location

    private fun onLocation(s: LocationSample) {
        val previous = locations.lastOrNull()
        locations.addLast(s)
        while (locations.size > 2 && s.t - locations.first().t > LOCATION_HISTORY_MS) locations.removeFirst()
        if (!hasExplicitSpeed && previous != null) {
            val dt = s.t - previous.t
            if (dt in 1..5_000) {
                val kmh = distanceM(previous.lat, previous.lon, s.lat, s.lon) / dt * 3_600.0
                onSpeed(s.t, kmh)
            }
        }
    }

    private fun currentSpeedKmh(t: Long): Double? {
        val last = speeds.lastOrNull() ?: return null
        return if (t - last.first <= config.speedStaleMs) last.second else null
    }

    private fun isMoving(t: Long, minKmh: Double): Boolean {
        val v = currentSpeedKmh(t)
        return if (v != null) v >= minKmh else speeds.isEmpty() && gear == GearState.DRIVE
    }

    // ------------------------------------------------------------ rule: hard brake, then re-accelerate

    private fun onSpeed(t: Long, kmh: Double) {
        speeds.addLast(t to kmh)
        while (speeds.size > 1 && t - speeds.first().first > SPEED_HISTORY_MS) speeds.removeFirst()

        val start = dropStartT
        if (start == null) {
            val (peakT, peakKmh) = speeds.filter { it.first >= t - config.brakeDropWindowMs }.maxBy { it.second }
            val braked = brakePressed || lastBrakePressT >= peakT - 1_000
            if (peakKmh >= config.brakeMinSpeedKmh && peakKmh - kmh >= config.brakeDropKmh && braked) {
                dropStartT = peakT
                dropPeakKmh = peakKmh
                dropMinKmh = kmh
                dropMinT = t
            }
            return
        }
        if (kmh < dropMinKmh) {
            dropMinKmh = kmh
            dropMinT = t
        }
        when {
            // Came (almost) to a stop: traffic or a light, not avoiding something.
            dropMinKmh < config.reaccelMinSpeedKmh -> dropStartT = null
            kmh - dropMinKmh >= config.reaccelGainKmh -> {
                emit(dropMinT, CandidateReason.HARD_BRAKE, Evidence(speedKmh = dropPeakKmh, lowestKmh = dropMinKmh, brake = true))
                dropStartT = null
            }
            t - start > config.brakeDropWindowMs + config.reaccelWindowMs -> dropStartT = null
        }
    }

    // ------------------------------------------------------------ rule: swerve and return

    private fun onSteering(t: Long, angle: Double) {
        val baseline = steeringBaseline ?: run {
            steeringBaseline = angle
            return
        }
        val deviation = abs(angle - baseline)
        val start = excursionStartT

        if (!inExcursion) {
            if (deviation <= config.swerveReturnDeg) {
                excursionStartT = null
                steeringBaseline = baseline * 0.8 + angle * 0.2
                return
            }
            if (start == null) {
                excursionStartT = t
                excursionPeakDeg = 0.0
            }
            excursionPeakDeg = max(excursionPeakDeg, deviation)
            if (deviation >= config.swervePeakDeg) {
                inExcursion = true
            } else if (start != null && t - start > config.swerveMaxDurationMs) {
                // Slow drift (a long curve): follow it instead of treating it as a flick.
                steeringBaseline = angle
                excursionStartT = null
            }
            return
        }

        val from = start ?: t
        excursionPeakDeg = max(excursionPeakDeg, deviation)
        if (deviation <= config.swerveReturnDeg) {
            val quick = t - from <= config.swerveMaxDurationMs
            val noSignal = turnState == TurnState.OFF && lastTurnSignalT < from - config.turnSignalQuietMs
            if (quick && noSignal && isMoving(from, config.swerveMinSpeedKmh)) {
                val evidence = Evidence(
                    speedKmh = speedNear(from),
                    steeringDeg = excursionPeakDeg,
                    brake = brakePressed || lastBrakePressT >= from - 1_000,
                )
                emit(from, CandidateReason.SWERVE, evidence)
            }
            inExcursion = false
            excursionStartT = null
        } else if (t - from > config.swerveMaxDurationMs) {
            // Held too long: a turn at an intersection, not an evasive flick.
            inExcursion = false
            excursionStartT = null
            steeringBaseline = angle
        }
    }

    // ------------------------------------------------------------ rule: warning lights

    private fun onWarningLight(s: WarningLightSample) {
        if (s.light == WarningLight.TPMS) {
            onLowTire(s.t, s.on)
            return
        }
        val wasOn = lights[s.light] ?: false
        lights[s.light] = s.on
        if (s.on && !wasOn && isMoving(s.t, config.drivingMinSpeedKmh)) {
            val reason = when (s.light) {
                WarningLight.ABS -> CandidateReason.ABS
                WarningLight.ESC -> CandidateReason.ESC
                WarningLight.ICY_ROAD -> CandidateReason.ICY_ROAD
                WarningLight.TPMS -> CandidateReason.TPMS
            }
            emit(s.t, reason, Evidence(speedKmh = speedNear(s.t), brake = brakePressed))
        }
    }

    // ------------------------------------------------------------ rule: damage

    private fun onImpact(s: ImpactSample) {
        if (s.g < config.impactMinG || !isMoving(s.t, config.drivingMinSpeedKmh)) return
        emit(s.t, CandidateReason.IMPACT, Evidence(speedKmh = speedNear(s.t), brake = brakePressed, impactG = s.g))
    }

    /**
     * Pressure usually drops a while after the hit, so a new low-tire flag is pinned on the spot
     * that most likely caused it: a recent jolt first, otherwise the latest candidate. Only with
     * nothing recent does it become a candidate of its own, where the flag came on.
     */
    private fun onLowTire(t: Long, low: Boolean) {
        val wasLow = lowTire
        lowTire = low
        if (!low || wasLow) return
        val recent = detections.indices.filter { t - detections[it].t in 0..config.lowTireLookbackMs }
        val cause = recent.lastOrNull { detections[it].evidence.impactG != null } ?: recent.lastOrNull()
        if (cause != null) {
            val d = detections[cause]
            detections[cause] = d.copy(evidence = d.evidence.copy(lowTire = true))
        } else if (isMoving(t, config.drivingMinSpeedKmh)) {
            emit(t, CandidateReason.TPMS, Evidence(speedKmh = speedNear(t), lowTire = true))
        }
    }

    /** Voice reports bypass the rules: the driver said there is something on the road. */
    fun reportVoice(t: Long) = emit(t, CandidateReason.VOICE, Evidence(speedKmh = speedNear(t)))

    // ------------------------------------------------------------ output

    private fun speedNear(t: Long): Double? =
        speeds.minByOrNull { abs(it.first - t) }?.takeIf { abs(it.first - t) <= config.speedStaleMs }?.second

    /** Several rules firing at one spot become one candidate with all their reasons and evidence. */
    private fun emit(t: Long, reason: CandidateReason, evidence: Evidence) {
        val location = locations.minByOrNull { abs(it.t - t) } ?: return
        val same = detections.indexOfFirst {
            abs(it.t - t) < config.dedupWindowMs &&
                distanceM(it.lat, it.lon, location.lat, location.lon) < config.dedupDistanceM
        }
        if (same < 0) {
            detections += Detection(t, location.lat, location.lon, listOf(reason), evidence)
            return
        }
        val d = detections[same]
        detections[same] = d.copy(
            t = minOf(d.t, t),
            reasons = (d.reasons + reason).distinct().sortedBy { it.ordinal },
            evidence = d.evidence.merge(evidence),
        )
    }

    private companion object {
        const val LOCATION_HISTORY_MS = 120_000L
        const val SPEED_HISTORY_MS = 15_000L
    }
}
