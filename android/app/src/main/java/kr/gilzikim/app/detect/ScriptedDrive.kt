package kr.gilzikim.app.detect

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kr.gilzikim.app.trip.distanceM

/**
 * A scripted commute fed through the real detector. The emulator cannot drive a route or play
 * steering patterns over time, so this is how the pipeline is exercised in tests and demos.
 *
 * Events along the way (seconds from start) and what the detector should make of them:
 * -  40 s  swerve and return, no turn signal          -> SWERVE
 * -  70 s  lane change with the turn signal on        -> ignored
 * - 100 s  hard brake to 20 km/h, then re-accelerate  -> HARD_BRAKE
 * - 101 s  0.9 g jolt during that brake (a pothole)   -> IMPACT, merged into the same spot
 * - 125 s  low tire pressure flag comes on            -> pinned on the jolt: a damage claim
 * - 140 s  full stop at a light, then go              -> ignored
 * - 175 s  held 270 deg turn without a signal         -> ignored
 * - 200 s  ABS warning light while moving             -> ABS
 */
object ScriptedDrive {

    private val ROUTE = listOf(
        37.5240 to 127.0190,
        37.5205 to 127.0200,
        37.5165 to 127.0203,
        37.5130 to 127.0230,
        37.5098 to 127.0265,
        37.5075 to 127.0320,
        37.5050 to 127.0380,
    )

    private const val CRUISE_KMH = 45.0
    private const val STEP_MS = 100L

    fun commute(startT: Long): List<VehicleSample> {
        val path = densify(ROUTE)
        val total = path.last().third
        val out = mutableListOf<VehicleSample>()

        var t = startT
        var dist = 0.0
        var lastBrake = false
        var lastTurn = TurnState.OFF
        var lastAbs = false
        var jolted = false
        var lastLowTire = false
        var nextLocationT = startT
        var nextSteeringT = startT

        out += locationAt(path, 0.0, t)
        out += GearSample(t + 100, GearState.DRIVE)

        while (true) {
            val sec = (t - startT) / 1000.0
            val remaining = total - dist
            val v = speedAt(sec, remaining)
            if (remaining <= 0.5 && v <= 0.0) break

            val brake = brakeAt(sec) || remaining < 45.0
            if (brake != lastBrake) { out += BrakeSample(t, brake); lastBrake = brake }

            val turn = if (sec in 68.5..72.0) TurnState.LEFT else TurnState.OFF
            if (turn != lastTurn) { out += TurnSignalSample(t, turn); lastTurn = turn }

            val abs = sec in 200.0..201.5
            if (abs != lastAbs) { out += WarningLightSample(t, WarningLight.ABS, abs); lastAbs = abs }

            if (!jolted && sec >= 101.0) { out += ImpactSample(t, 0.9); jolted = true }

            val lowTire = sec >= 125.0
            if (lowTire != lastLowTire) { out += LowTireSample(t, lowTire); lastLowTire = lowTire }

            if (t >= nextSteeringT) {
                out += SteeringSample(t, steeringAt(sec))
                nextSteeringT = t + if (nearManeuver(sec)) STEP_MS else 1_000L
            }
            if (t >= nextLocationT) {
                out += locationAt(path, dist, t)
                nextLocationT = t + 1_000L
            }

            dist = min(total, dist + v / 3.6 * STEP_MS / 1000.0)
            t += STEP_MS
        }
        out += locationAt(path, total, t)
        out += BrakeSample(t, true)
        out += GearSample(t + 500, GearState.PARK)
        return out
    }

    private fun speedAt(sec: Double, remainingM: Double): Double {
        val base = when {
            sec < 5 -> CRUISE_KMH * sec / 5
            sec in 100.0..102.0 -> lerp(45.0, 20.0, (sec - 100) / 2)
            sec in 102.0..106.0 -> lerp(20.0, 42.0, (sec - 102) / 4)
            sec in 106.0..108.0 -> lerp(42.0, 45.0, (sec - 106) / 2)
            sec in 140.0..144.0 -> lerp(45.0, 0.0, (sec - 140) / 4)
            sec in 144.0..149.0 -> 0.0
            sec in 149.0..155.0 -> lerp(0.0, 45.0, (sec - 149) / 6)
            else -> CRUISE_KMH
        }
        if (remainingM <= 0.5) return 0.0
        return if (remainingM < 45.0) min(base, max(6.0, CRUISE_KMH * remainingM / 45.0)) else base
    }

    private fun brakeAt(sec: Double) = sec in 99.8..102.0 || sec in 139.8..149.0

    private fun steeringAt(sec: Double): Double = when {
        sec in 40.0..40.4 -> lerp(0.0, -38.0, (sec - 40.0) / 0.4)
        sec in 40.4..40.8 -> lerp(-38.0, 12.0, (sec - 40.4) / 0.4)
        sec in 40.8..41.2 -> lerp(12.0, 0.0, (sec - 40.8) / 0.4)
        sec in 70.0..70.5 -> lerp(0.0, 30.0, (sec - 70.0) / 0.5)
        sec in 70.5..71.0 -> lerp(30.0, -20.0, (sec - 70.5) / 0.5)
        sec in 71.0..71.5 -> lerp(-20.0, 0.0, (sec - 71.0) / 0.5)
        sec in 175.0..176.0 -> lerp(0.0, 270.0, sec - 175.0)
        sec in 176.0..179.0 -> 270.0
        sec in 179.0..180.0 -> lerp(270.0, 0.0, sec - 179.0)
        else -> sin(sec * 1.7)
    }

    private fun nearManeuver(sec: Double) =
        sec in 39.0..42.0 || sec in 69.0..72.5 || sec in 174.0..184.0

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f.coerceIn(0.0, 1.0)

    /** Route as (lat, lon, distanceFromStart) every ~2 m. */
    private fun densify(waypoints: List<Pair<Double, Double>>): List<Triple<Double, Double, Double>> {
        val out = mutableListOf(Triple(waypoints[0].first, waypoints[0].second, 0.0))
        var acc = 0.0
        for ((a, b) in waypoints.zipWithNext()) {
            val seg = distanceM(a.first, a.second, b.first, b.second)
            val steps = (seg / 2.0).toInt().coerceAtLeast(1)
            for (s in 1..steps) {
                val f = s.toDouble() / steps
                out += Triple(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, acc + seg * f)
            }
            acc += seg
        }
        return out
    }

    private fun locationAt(path: List<Triple<Double, Double, Double>>, dist: Double, t: Long): LocationSample {
        val i = path.indexOfFirst { it.third >= dist }.let { if (it < 0) path.lastIndex else it }
        return LocationSample(t, path[i].first, path[i].second)
    }
}
