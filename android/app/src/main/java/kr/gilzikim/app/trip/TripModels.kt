package kr.gilzikim.app.trip

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class TrackPoint(val lat: Double, val lon: Double, val timeMillis: Long)

/** Why the car recorded a hazard candidate. The driver labels it after parking. */
enum class CandidateReason(val label: String, val short: String) {
    // Declaration order is display order when one spot has several reasons.
    // Formal noun forms (on-screen copy rule): [label] in the detail panel, [short] on map chips.
    SWERVE("급조향", "급조향"),
    HARD_BRAKE("급제동 후 재가속", "급제동"),
    IMPACT("노면 충격", "노면 충격"),
    ABS("ABS 작동", "ABS 작동"),
    ESC("차체 자세 제어(ESC) 작동", "ESC 작동"),
    ICY_ROAD("노면 결빙 경고", "결빙 경고"),
    TPMS("타이어 공기압 저하", "공기압 저하"),
    VOICE("음성 신고", "음성 신고"),
}

/**
 * What the driver says actually happened. One tap: the three hazard types, OTHER (opens voice
 * input), or NONE. A spot left unanswered is "could not tell" and is left to cross-checking.
 */
enum class HazardLabel(val label: String) {
    POTHOLE("도로 파임"),
    DEBRIS("낙하물"),
    SLIPPERY("노면 미끄러움"),
    OTHER("기타"),
    NONE("이상 없음"),
}

/**
 * What the car measured at a candidate: the post-drive screen shows these as "what was detected",
 * and they are the evidence for a damage claim.
 */
data class Evidence(
    /** Speed when it started. */
    val speedKmh: Double? = null,
    /** Hard brake: the speed it dropped to before speeding up again. */
    val lowestKmh: Double? = null,
    /** Swerve: how far the wheel turned away from straight. */
    val steeringDeg: Double? = null,
    val brake: Boolean = false,
    val turnSignal: Boolean = false,
    /** Peak jolt from the head unit's accelerometer, in g. */
    val impactG: Double? = null,
    /** Tire pressure went low shortly after (CAR_SAFETY flag or TPMS light). */
    val lowTire: Boolean = false,
) {
    /** The car itself may be damaged, so this spot is also a claim. */
    val damage: Boolean get() = impactG != null || lowTire

    fun merge(other: Evidence) = Evidence(
        speedKmh = speedKmh ?: other.speedKmh,
        lowestKmh = lowestKmh ?: other.lowestKmh,
        steeringDeg = steeringDeg ?: other.steeringDeg,
        brake = brake || other.brake,
        turnSignal = turnSignal || other.turnSignal,
        impactG = listOfNotNull(impactG, other.impactG).maxOrNull(),
        lowTire = lowTire || other.lowTire,
    )
}

data class Candidate(
    val id: String,
    val lat: Double,
    val lon: Double,
    val timeMillis: Long,
    /** Everything that fired at this spot, in [CandidateReason] order. */
    val reasons: List<CandidateReason>,
    val evidence: Evidence,
    /** Distance along the trip from its start, in meters. */
    val distanceFromStartM: Double,
) {
    val reason: CandidateReason get() = reasons.first()
}

data class Trip(
    val id: String,
    val title: String,
    val points: List<TrackPoint>,
    val candidates: List<Candidate>,
) {
    val startedAt: Long get() = points.first().timeMillis
    val endedAt: Long get() = points.last().timeMillis
    val durationMinutes: Long get() = (endedAt - startedAt) / 60_000
    val lengthM: Double by lazy { pathLength(points) }
}

fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

fun pathLength(points: List<TrackPoint>): Double =
    points.zipWithNext().sumOf { (a, b) -> distanceM(a.lat, a.lon, b.lat, b.lon) }

fun formatDistance(m: Double): String =
    if (m < 1000) "${m.toInt()}m" else "%.1fkm".format(m / 1000)
