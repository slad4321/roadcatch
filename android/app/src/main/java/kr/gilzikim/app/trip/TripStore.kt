package kr.gilzikim.app.trip

import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kr.gilzikim.app.detect.ScriptedDrive
import kr.gilzikim.app.detect.TripRecorder
import kr.gilzikim.app.detect.VehicleSample

/** Process-wide home of the live recorder and the latest finished trip (in memory for now). */
object TripStore {

    private val _latest = MutableStateFlow<Trip?>(null)
    val latest: StateFlow<Trip?> = _latest

    private val live = TripRecorder()

    /** Called from SDK callback threads. */
    @Synchronized
    fun onLiveSample(sample: VehicleSample) {
        live.onSample(sample)?.let { _latest.value = it }
    }

    /** Runs the scripted commute through a separate recorder; the emulator cannot drive a route. */
    fun replayScriptedDrive() {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 40); set(Calendar.SECOND, 0)
        }.timeInMillis
        val recorder = TripRecorder(titlePrefix = "모의 주행 · ")
        var trip: Trip? = null
        ScriptedDrive.commute(start).forEach { s -> recorder.onSample(s)?.let { trip = it } }
        // Each replay is a fresh review: a new id resets the answers kept per trip on screen.
        _latest.value = trip?.copy(id = "${trip!!.id}-replay-${System.currentTimeMillis()}")
    }
}
