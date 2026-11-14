package kr.gilzikim.app.detect

import kr.gilzikim.app.trip.CandidateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardDetectorTest {

    private val lat = 37.5165
    private val lon = 127.0203

    /** Builds a sample stream with a fixed location and an explicit speed every 100 ms. */
    private class Script(private val lat: Double, private val lon: Double) {
        val samples = mutableListOf<VehicleSample>()

        fun cruise(fromMs: Long, toMs: Long, kmh: Double, steering: Double = 0.0) {
            var t = fromMs
            while (t < toMs) {
                samples += LocationSample(t, lat, lon)
                samples += SpeedSample(t, kmh)
                samples += SteeringSample(t, steering)
                t += 100
            }
        }

        fun steer(points: List<Pair<Long, Double>>, kmh: Double) {
            points.forEach { (t, angle) ->
                samples += SpeedSample(t, kmh)
                samples += SteeringSample(t, angle)
            }
        }
    }

    private fun run(samples: List<VehicleSample>): List<CandidateReason> {
        val detector = HazardDetector()
        samples.sortedBy { it.t }.forEach(detector::onSample)
        return detector.results.map { it.reason }
    }

    private val flick = listOf(10_000L to -10.0, 10_200L to -38.0, 10_600L to 12.0, 11_000L to 2.0)

    @Test
    fun swerveAndReturnIsDetected() {
        val s = Script(lat, lon).apply {
            cruise(0, 10_000, 45.0)
            steer(flick, 45.0)
            cruise(11_100, 14_000, 45.0)
        }
        assertEquals(listOf(CandidateReason.SWERVE), run(s.samples))
    }

    @Test
    fun swerveWithTurnSignalIsIgnored() {
        val s = Script(lat, lon).apply {
            cruise(0, 10_000, 45.0)
            samples += TurnSignalSample(9_000, TurnState.LEFT)
            steer(flick, 45.0)
            samples += TurnSignalSample(11_500, TurnState.OFF)
            cruise(11_100, 14_000, 45.0)
        }
        assertEquals(emptyList<CandidateReason>(), run(s.samples))
    }

    @Test
    fun swerveAtLowSpeedIsIgnored() {
        val s = Script(lat, lon).apply {
            cruise(0, 10_000, 10.0)
            steer(flick, 10.0)
            cruise(11_100, 14_000, 10.0)
        }
        assertEquals(emptyList<CandidateReason>(), run(s.samples))
    }

    @Test
    fun heldTurnIsNotASwerve() {
        val s = Script(lat, lon).apply {
            cruise(0, 10_000, 25.0)
            steer(listOf(10_000L to 90.0, 10_500L to 270.0), 25.0)
            cruise(10_600, 14_000, 25.0, steering = 270.0)
            steer(listOf(14_000L to 120.0, 14_500L to 0.0), 25.0)
            cruise(14_600, 20_000, 25.0)
        }
        assertEquals(emptyList<CandidateReason>(), run(s.samples))
    }

    @Test
    fun hardBrakeThenReaccelerationIsDetected() {
        val samples = mutableListOf<VehicleSample>()
        val profile = listOf(0L to 45.0, 1_000L to 45.0, 2_000L to 38.0, 3_000L to 22.0, 4_000L to 24.0, 5_000L to 33.0, 6_000L to 40.0)
        samples += BrakeSample(1_200, true)
        samples += BrakeSample(3_100, false)
        profile.forEach { (t, v) -> samples += LocationSample(t, lat, lon); samples += SpeedSample(t, v) }
        assertEquals(listOf(CandidateReason.HARD_BRAKE), run(samples))
    }

    @Test
    fun stoppingAtALightIsNotHardBrake() {
        val samples = mutableListOf<VehicleSample>()
        val profile = listOf(0L to 45.0, 1_000L to 45.0, 2_000L to 30.0, 3_000L to 12.0, 4_000L to 0.0, 8_000L to 0.0, 9_000L to 15.0, 10_000L to 30.0)
        samples += BrakeSample(1_200, true)
        samples += BrakeSample(8_000, false)
        profile.forEach { (t, v) -> samples += LocationSample(t, lat, lon); samples += SpeedSample(t, v) }
        assertEquals(emptyList<CandidateReason>(), run(samples))
    }

    @Test
    fun warningLightCountsOnlyWhileMoving() {
        val parked = listOf(
            LocationSample(0, lat, lon), SpeedSample(0, 0.0), GearSample(0, GearState.PARK),
            WarningLightSample(500, WarningLight.ABS, true),
        )
        assertEquals(emptyList<CandidateReason>(), run(parked))

        val moving = listOf(
            LocationSample(0, lat, lon), SpeedSample(0, 40.0),
            WarningLightSample(500, WarningLight.ICY_ROAD, true),
        )
        assertEquals(listOf(CandidateReason.ICY_ROAD), run(moving))
    }

    @Test
    fun sameSpotWithinWindowIsReportedOnce() {
        val samples = listOf(
            LocationSample(0, lat, lon), SpeedSample(0, 40.0),
            WarningLightSample(500, WarningLight.ABS, true),
            WarningLightSample(1_500, WarningLight.ABS, false),
            SpeedSample(4_000, 40.0),
            WarningLightSample(5_000, WarningLight.ABS, true),
        )
        assertEquals(listOf(CandidateReason.ABS), run(samples))
    }

    @Test
    fun withoutAnySpeedSourceGearDriveCountsAsMoving() {
        // The emulator has no speed signal and a fixed location.
        val samples = mutableListOf<VehicleSample>(LocationSample(0, lat, lon), GearSample(0, GearState.DRIVE))
        var t = 0L
        while (t < 10_000) { samples += SteeringSample(t, 0.0); t += 500 }
        flick.forEach { (ft, a) -> samples += SteeringSample(ft, a) }
        assertEquals(listOf(CandidateReason.SWERVE), run(samples))
    }

    @Test
    fun joltWhileMovingIsADamageCandidate() {
        val s = Script(lat, lon).apply {
            cruise(0, 10_000, 40.0)
            samples += ImpactSample(5_000, 0.8)
        }
        val detector = HazardDetector()
        s.samples.sortedBy { it.t }.forEach(detector::onSample)
        val d = detector.results.single()
        assertEquals(CandidateReason.IMPACT, d.reason)
        assertTrue(d.evidence.damage)
        assertEquals(40.0, d.evidence.speedKmh!!, 0.1)
    }

    @Test
    fun smallJoltOrJoltWhileParkedIsIgnored() {
        val s = Script(lat, lon).apply {
            cruise(0, 5_000, 40.0)
            samples += ImpactSample(2_000, 0.3)
            cruise(5_000, 10_000, 0.0)
            samples += ImpactSample(8_000, 1.2)
        }
        assertEquals(emptyList<CandidateReason>(), run(s.samples))
    }

    @Test
    fun lowTireLaterIsPinnedOnTheEarlierJolt() {
        val s = Script(lat, lon).apply {
            cruise(0, 120_000, 40.0)
            samples += ImpactSample(10_000, 0.9)
            samples += LowTireSample(70_000, true)
        }
        val detector = HazardDetector()
        s.samples.sortedBy { it.t }.forEach(detector::onSample)
        val d = detector.results.single()
        assertEquals(10_000L, d.t)
        assertTrue(d.evidence.lowTire)
    }

    @Test
    fun lowTireWithNothingBeforeIsItsOwnCandidate() {
        val s = Script(lat, lon).apply {
            cruise(0, 20_000, 40.0)
            samples += LowTireSample(10_000, true)
        }
        assertEquals(listOf(CandidateReason.TPMS), run(s.samples))
    }

    @Test
    fun scriptedCommuteYieldsExactlyTheThreeIntendedCandidates() {
        val recorder = TripRecorder()
        var trip: kr.gilzikim.app.trip.Trip? = null
        ScriptedDrive.commute(startT = 0L).forEach { s -> recorder.onSample(s)?.let { trip = it } }
        val finished = trip ?: error("trip did not finish")

        assertEquals(
            listOf(CandidateReason.SWERVE, CandidateReason.HARD_BRAKE, CandidateReason.ABS),
            finished.candidates.map { it.reason },
        )
        // The pothole: hard brake and jolt at one spot, and the low tire flag 24 s later pinned on it.
        val pothole = finished.candidates[1]
        assertEquals(listOf(CandidateReason.HARD_BRAKE, CandidateReason.IMPACT), pothole.reasons)
        assertTrue(pothole.evidence.damage && pothole.evidence.lowTire)
        // Speed comes from 1 s location fixes here, so allow a few km/h.
        assertEquals(45.0, pothole.evidence.speedKmh!!, 3.0)
        assertEquals(20.0, pothole.evidence.lowestKmh!!, 3.0)
        assertTrue(finished.candidates.filter { it !== pothole }.none { it.evidence.damage })
        assertTrue("length ${finished.lengthM}", finished.lengthM in 2_500.0..3_200.0)
        val distances = finished.candidates.map { it.distanceFromStartM }
        assertEquals(distances.sorted(), distances)
    }
}
