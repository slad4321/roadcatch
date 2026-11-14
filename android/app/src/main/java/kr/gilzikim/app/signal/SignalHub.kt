package kr.gilzikim.app.signal

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs
import kr.gilzikim.app.detect.BrakeSample
import kr.gilzikim.app.detect.GearSample
import kr.gilzikim.app.detect.GearState
import kr.gilzikim.app.detect.LocationSample
import kr.gilzikim.app.detect.SteeringSample
import kr.gilzikim.app.detect.TurnSignalSample
import kr.gilzikim.app.detect.TurnState
import kr.gilzikim.app.detect.WarningLight
import kr.gilzikim.app.detect.WarningLightSample
import kr.gilzikim.app.trip.TripStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * M3 signal logger: subscribes to every vehicle signal the hazard detector will need and records
 * what actually arrives (values, delivery interval, errors) so the assumptions about the Pleos
 * emulator can be checked before the detector is built on top of them.
 */
class SignalHub(private val context: Context) {

    data class Event(val timeMillis: Long, val source: String, val text: String)

    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events

    private var vehicle: Vehicle? = null
    private var fusedLocation: FusedLocation? = null
    private var started = false
    private var locationStarted = false

    private val steerMeter = Meter()
    private val locationMeter = Meter()
    private var lastLoggedSteer: Float? = null
    private var lastTelltales: Map<TelltaleType, TelltaleState> = emptyMap()

    fun start() {
        if (started) return
        started = true
        val v = Vehicle(context)
        vehicle = v
        runCatching { v.initialize() }
            .onSuccess { setValue(KEY_VEHICLE, "initialize() 호출됨") }
            .onFailure { fail("Vehicle.initialize", it) }
        // The SDK binds to a vehicle-side service; give the binding a moment before subscribing.
        Handler(Looper.getMainLooper()).postDelayed({ registerVehicleSignals() }, VEHICLE_BIND_DELAY_MS)
    }

    fun stop() {
        started = false
        locationStarted = false
        runCatching { vehicle?.release() }
        runCatching { fusedLocation?.release() }
        vehicle = null
        fusedLocation = null
    }

    // ---------------------------------------------------------------- vehicle signals

    private fun registerVehicleSignals() {
        val v = vehicle ?: return
        log("시스템", "Vehicle 신호 구독 시작")

        guard("SteeringWheel") {

            v.getSteeringWheel().checkSteeringWheelAngleCapabilities(
                { cfg, bounds -> log("capability", "핸들각 $cfg $bounds") },
                { e -> fail("capability 핸들각", e) },
            )
            v.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> onSteering(a) }, { e -> fail("get 핸들각", e) })
            v.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(angle: Float?) = onSteering(angle)
                override fun onFailed(e: Exception) = fail("핸들각 구독", e)
            })
        }

        guard("Brake") {
            v.getBrake().checkBrakePedalStatusCapabilities(
                { cfg -> log("capability", "브레이크 $cfg") },
                { e -> fail("capability 브레이크", e) },
            )
            v.getBrake().getCurrentBrakePedalStatus({ p -> onBrake(p) }, { e -> fail("get 브레이크", e) })
            v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(pressed: Boolean?) = onBrake(pressed)
                override fun onFailed(e: Exception) = fail("브레이크 구독", e)
            })
        }

        guard("Gear") {
            v.getGear().getCurrentGearSelectionState({ g -> onGear(g) }, { e -> fail("get 기어", e) })
            v.getGear().registerGearSelection(object : GearSelectionListener {
                override fun onSelected(gear: VehicleGear?) = onGear(gear)
                override fun onFailed(e: Exception) = fail("기어 구독", e)
            })
        }

        guard("TurnSignal") {
            v.getTurnSignal().checkTurnSignalStateCapabilities(
                { cfg -> log("capability", "방향지시등 $cfg") },
                { e -> fail("capability 방향지시등", e) },
            )
            v.getTurnSignal().getCurrentTurnSignalState({ s -> onTurnSignal(s) }, { e -> fail("get 방향지시등", e) })
            v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = onTurnSignal(state)
                override fun onFailed(e: Exception) = fail("방향지시등 구독", e)
            })
        }

        guard("Safety") {
            v.getSafety().checkTelltalesCapabilities(
                { cfg -> log("capability", "경고등 $cfg") },
                { e -> fail("capability 경고등", e) },
            )
            v.getSafety().getTelltalesInfo({ t -> onTelltales(t) }, { e -> fail("get 경고등", e) })
            v.getSafety().registerTelltales(object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(telltales: Telltales?) = onTelltales(telltales)
                override fun onFailed(e: Exception) = fail("경고등 구독", e)
            })
        }

        guard("Tire") {
            v.getTire().checkTirePressureCapabilities(
                { cfg, areas -> log("capability", "타이어 압력 $cfg ${areas.size}개 휠") },
                { e -> fail("capability 타이어", e) },
            )
            for ((label, wheel) in WHEELS) {
                val area = WheelArea(wheel)
                v.getTire().getTirePressure(area, { p -> onTire(label, p) }, { e -> fail("get 타이어 $label", e) })
                v.getTire().registerTirePressure(area, object : TirePressureListener {
                    override fun onTirePressureUpdated(pressure: Float?) = onTire(label, pressure)
                    override fun onFailed(e: Exception) = fail("타이어 $label 구독", e)
                })
            }
        }
    }

    /** The SDK throws SecurityException directly (not through onFailed) when a permission is missing. */
    private inline fun guard(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail("$name 구독", t)
        }
    }

    private fun onSteering(angle: Float?) {
        if (angle == null) return
        TripStore.onLiveSample(SteeringSample(now(), angle.toDouble()))
        val stats = steerMeter.tick(System.currentTimeMillis())
        setValue(KEY_STEERING, "%.1f° (%s)".format(angle, stats))
        val last = lastLoggedSteer
        if (last == null || abs(angle - last) >= STEER_LOG_STEP_DEG) {
            lastLoggedSteer = angle
            log("핸들", "%.1f°".format(angle))
        }
    }

    private fun onBrake(pressed: Boolean?) {
        if (pressed == null) return
        TripStore.onLiveSample(BrakeSample(now(), pressed))
        val text = if (pressed) "밟힘" else "해제"
        if (_values.value[KEY_BRAKE] != text) log("브레이크", text)
        setValue(KEY_BRAKE, text)
    }

    private fun onGear(gear: VehicleGear?) {
        if (gear == null) return
        TripStore.onLiveSample(GearSample(now(), gear.toGearState()))
        if (_values.value[KEY_GEAR] != gear.name) log("기어", gear.name)
        setValue(KEY_GEAR, gear.name)
    }

    private fun onTurnSignal(state: TurnSignalValue?) {
        if (state == null) return
        TripStore.onLiveSample(TurnSignalSample(now(), state.toTurnState()))
        if (_values.value[KEY_TURN] != state.name) log("방향지시등", state.name)
        setValue(KEY_TURN, state.name)
    }

    private fun onTelltales(telltales: Telltales?) {
        val list = telltales?.telltales ?: return
        val current = list.associate { it.type to it.state }
        val previous = lastTelltales
        if (previous.isEmpty()) {
            val on = current.filterValues { it != TelltaleState.OFF }
            log("경고등", "최초 수신 ${current.size}종, 켜짐 ${on.size}종: ${on.entries.joinToString { "${it.key}=${it.value}" }}")
        } else {
            for ((type, state) in current) {
                if (previous[type] != state) log("경고등", "$type ${previous[type]} → $state")
            }
        }
        lastTelltales = current
        for ((type, light) in WATCHED_LIGHTS) {
            val on = (current[type] ?: TelltaleState.OFF) != TelltaleState.OFF
            val wasOn = (previous[type] ?: TelltaleState.OFF) != TelltaleState.OFF
            if (on != wasOn || previous.isEmpty()) TripStore.onLiveSample(WarningLightSample(now(), light, on))
        }
        val active = current.filterValues { it != TelltaleState.OFF }.keys
        setValue(KEY_TELLTALES, active.joinToString().ifEmpty { "없음" } + " (총 ${current.size}종)")
    }

    private fun onTire(label: String, pressure: Float?) {
        if (pressure == null) return
        val key = "타이어 $label"
        val text = "%.2f".format(pressure)
        if (_values.value[key] != text) log("타이어", "$label $text")
        setValue(key, text)
    }

    // ---------------------------------------------------------------- location

    /** Call once the location permission is granted; vehicle signals do not depend on it. */
    fun startLocation() {
        if (locationStarted) return
        locationStarted = true
        runCatching {
            val fl = FusedLocation(context)
            fusedLocation = fl
            val supported = fl.isSupported()
            setValue(KEY_LOCATION_SDK, "지원 여부: $supported")
            if (!supported) return
            fl.initialize()
            fl.registerFusedLocationCallback(object : FusedLocationListener {
                override fun onChanged(data: LocationData?) = onLocation(data)
                override fun onError(exception: Exception) = fail("위치 구독", exception)
            })
        }.onFailure {
            locationStarted = false
            setValue(KEY_LOCATION_SDK, "시작 실패: ${it.message}")
            fail("위치 시작", it)
        }
    }

    private fun onLocation(d: LocationData?) {
        if (d == null) return
        TripStore.onLiveSample(LocationSample(now(), d.latitude, d.longitude))
        val stats = locationMeter.tick(System.currentTimeMillis())
        setValue(
            KEY_LOCATION,
            "%.5f, %.5f  방향 %.0f°  신뢰 %.2f (%s)".format(d.latitude, d.longitude, d.heading, d.confidence, stats),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun setValue(key: String, value: String) {
        _values.update { it + (key to value) }
    }

    private fun log(source: String, text: String) {
        Log.i(TAG, "[$source] $text")
        _events.update { (listOf(Event(System.currentTimeMillis(), source, text)) + it).take(MAX_EVENTS) }
    }

    private fun fail(source: String, error: Throwable?) {
        Log.w(TAG, "[오류] $source: $error")
        _events.update {
            (listOf(Event(System.currentTimeMillis(), "오류", "$source: $error")) + it).take(MAX_EVENTS)
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun VehicleGear.toGearState(): GearState = when (this) {
        VehicleGear.GEAR_PARK -> GearState.PARK
        VehicleGear.GEAR_REVERSE -> GearState.REVERSE
        VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> GearState.OTHER
        else -> GearState.DRIVE
    }

    private fun TurnSignalValue.toTurnState(): TurnState = when (this) {
        TurnSignalValue.LEFT -> TurnState.LEFT
        TurnSignalValue.RIGHT -> TurnState.RIGHT
        else -> TurnState.OFF
    }

    private class Meter {
        private var count = 0
        private var first = 0L

        @Synchronized
        fun tick(now: Long): String {
            if (count == 0) first = now
            count++
            val avg = if (count > 1) (now - first) / (count - 1) else 0L
            return "n=$count, 평균 ${avg}ms"
        }
    }

    companion object {
        const val TAG = "Gilzikim"

        @Volatile
        private var instance: SignalHub? = null

        /**
         * One hub per process. The activity is recreated on display/size changes, and a second hub
         * would subscribe to every signal again and double all counts.
         */
        fun get(context: Context): SignalHub =
            instance ?: synchronized(this) {
                instance ?: SignalHub(context.applicationContext).also { instance = it }
            }

        const val KEY_VEHICLE = "Vehicle SDK"
        const val KEY_LOCATION_SDK = "위치 SDK"
        const val KEY_LOCATION = "위치"
        const val KEY_STEERING = "핸들각"
        const val KEY_BRAKE = "브레이크 페달"
        const val KEY_GEAR = "기어"
        const val KEY_TURN = "방향지시등"
        const val KEY_TELLTALES = "켜진 경고등"

        val DISPLAY_ORDER = listOf(
            KEY_VEHICLE, KEY_LOCATION_SDK, KEY_LOCATION, KEY_GEAR, KEY_STEERING, KEY_BRAKE, KEY_TURN,
            KEY_TELLTALES, "타이어 LF", "타이어 RF", "타이어 LR", "타이어 RR",
        )

        private const val VEHICLE_BIND_DELAY_MS = 1500L
        private const val STEER_LOG_STEP_DEG = 45f
        private const val MAX_EVENTS = 300

        /** Telltales the detector reacts to. All need PRIVATE_CAR_SAFETY. */
        private val WATCHED_LIGHTS = listOf(
            TelltaleType.ABS to WarningLight.ABS,
            TelltaleType.ESC to WarningLight.ESC,
            TelltaleType.ICY_ROAD to WarningLight.ICY_ROAD,
            TelltaleType.TPMS to WarningLight.TPMS,
        )

        private val WHEELS = listOf(
            "LF" to WheelValue.LEFT_FRONT,
            "RF" to WheelValue.RIGHT_FRONT,
            "LR" to WheelValue.LEFT_REAR,
            "RR" to WheelValue.RIGHT_REAR,
        )
    }
}
