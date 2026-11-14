package kr.gilzikim.app.detect

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kr.gilzikim.app.trip.Candidate
import kr.gilzikim.app.trip.TrackPoint
import kr.gilzikim.app.trip.Trip
import kr.gilzikim.app.trip.distanceM

/**
 * Cuts the sample stream into trips: gear D starts one, gear P ends it. Everything in between goes
 * through a fresh [HazardDetector], and the finished [Trip] is what the post-drive screen shows.
 */
class TripRecorder(
    private val config: DetectorConfig = DetectorConfig(),
    private val titlePrefix: String = "",
) {
    private var detector = HazardDetector(config)
    private val points = mutableListOf<TrackPoint>()
    private var lastLocation: LocationSample? = null
    private var startT = 0L

    var isRecording = false
        private set

    /** Feeds one sample; returns the finished trip when this sample ends one. */
    fun onSample(sample: VehicleSample): Trip? {
        if (sample is LocationSample) lastLocation = sample
        if (sample is GearSample) {
            if (!isRecording && sample.gear == GearState.DRIVE) start(sample.t)
            if (isRecording && sample.gear == GearState.PARK) {
                detector.onSample(sample)
                return finish(sample.t)
            }
        }
        if (!isRecording) return null
        detector.onSample(sample)
        if (sample is LocationSample) addPoint(sample)
        return null
    }

    fun reportVoice(t: Long) {
        if (isRecording) detector.reportVoice(t)
    }

    private fun start(t: Long) {
        isRecording = true
        startT = t
        points.clear()
        detector = HazardDetector(config)
        lastLocation?.let {
            val anchored = it.copy(t = t)
            detector.onSample(anchored)
            addPoint(anchored)
        }
    }

    private fun addPoint(s: LocationSample) {
        val last = points.lastOrNull()
        if (last == null || distanceM(last.lat, last.lon, s.lat, s.lon) >= MIN_POINT_SPACING_M) {
            points += TrackPoint(s.lat, s.lon, s.t)
        }
    }

    private fun finish(t: Long): Trip? {
        isRecording = false
        if (points.isEmpty()) lastLocation?.let { points += TrackPoint(it.lat, it.lon, startT) }
        if (points.isEmpty()) return null
        if (points.last().timeMillis < t) points += points.last().copy(timeMillis = t)

        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] +
                distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        val candidates = detector.results.mapIndexed { i, d ->
            val nearest = points.indices.minBy { distanceM(points[it].lat, points[it].lon, d.lat, d.lon) }
            Candidate("c${i + 1}", d.lat, d.lon, d.t, d.reasons, d.evidence, cumulative[nearest])
        }
        val clock = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(startT))
        return Trip(id = "trip-$startT", title = "$titlePrefix$clock 출발", points = points.toList(), candidates = candidates)
    }

    private companion object {
        const val MIN_POINT_SPACING_M = 3.0
    }
}
