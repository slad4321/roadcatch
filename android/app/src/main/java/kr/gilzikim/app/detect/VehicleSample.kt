package kr.gilzikim.app.detect

/**
 * Plain, Android-free view of the vehicle signals the detector consumes, so the rules can be
 * unit tested on the JVM and replayed from scripts. [t] is milliseconds on any monotonic clock.
 */
sealed interface VehicleSample {
    val t: Long
}

enum class GearState { PARK, DRIVE, REVERSE, NEUTRAL, OTHER }

enum class TurnState { OFF, LEFT, RIGHT }

enum class WarningLight { ABS, ESC, ICY_ROAD, TPMS }

data class SteeringSample(override val t: Long, val angleDeg: Double) : VehicleSample

data class BrakeSample(override val t: Long, val pressed: Boolean) : VehicleSample

data class TurnSignalSample(override val t: Long, val state: TurnState) : VehicleSample

data class GearSample(override val t: Long, val gear: GearState) : VehicleSample

data class WarningLightSample(override val t: Long, val light: WarningLight, val on: Boolean) : VehicleSample

data class LocationSample(override val t: Long, val lat: Double, val lon: Double) : VehicleSample

/** Vehicle speed from the AAOS Car API. Without it, speed is derived from consecutive locations. */
data class SpeedSample(override val t: Long, val kmh: Double) : VehicleSample

/** A jolt from the head unit's accelerometer: how far the total acceleration left 1 g, in g. */
data class ImpactSample(override val t: Long, val g: Double) : VehicleSample

/** The low tire pressure flag (`getLowTirePressure`, CAR_SAFETY) for any wheel. */
data class LowTireSample(override val t: Long, val low: Boolean) : VehicleSample
