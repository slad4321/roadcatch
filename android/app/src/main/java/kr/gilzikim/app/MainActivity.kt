package kr.gilzikim.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kr.gilzikim.app.signal.SignalHub
import kr.gilzikim.app.trip.TripStore
import kr.gilzikim.app.ui.postdrive.PostDriveScreen
import kr.gilzikim.app.ui.theme.GilzikimTheme
import kr.gilzikim.app.ui.theme.GzColors

class MainActivity : ComponentActivity() {

    private lateinit var hub: SignalHub

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermissions()) hub.startLocation()
        }

    private fun hasLocationPermissions() = LOCATION_PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hub = SignalHub.get(applicationContext)
        setContent {
            GilzikimTheme {
                var showLogger by remember { mutableStateOf(false) }
                val latest by TripStore.latest.collectAsState()
                Box(modifier = Modifier.fillMaxSize().background(GzColors.Background)) {
                    val trip = latest
                    if (showLogger || trip == null) {
                        Surface(modifier = Modifier.fillMaxSize()) { SignalScreen(hub) }
                    } else {
                        PostDriveScreen(trip)
                    }
                    // Developer-only controls; not part of the product screen.
                    Row(modifier = Modifier.align(Alignment.BottomStart)) {
                        DevButton(if (showLogger) "주행 후 화면" else "개발자 로그") { showLogger = !showLogger }
                        DevButton("모의 주행") {
                            TripStore.replayScriptedDrive()
                            showLogger = false
                        }
                    }
                }
            }
        }
        // Until a real trip is recorded, show the scripted commute run through the real detector.
        if (TripStore.latest.value == null) TripStore.replayScriptedDrive()
        hub.start()
        val missing = LOCATION_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) hub.startLocation() else permissionLauncher.launch(missing.toTypedArray())
    }

    // The hub is process-wide and deliberately never stopped here: M3 has to show whether signals
    // keep arriving while the app is in the background.

    private companion object {
        // pleos.car.permission.FUSED_LOCATION is a runtime permission on the Pleos emulator
        // (it is not granted at install time), so it has to be requested like the Android ones.
        val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            "pleos.car.permission.FUSED_LOCATION",
        )
    }
}

@Composable
private fun SignalScreen(hub: SignalHub) {
    val values by hub.values.collectAsState()
    val events by hub.events.collectAsState()
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)

    Row(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("로드캐치 · 신호 로거", style = MaterialTheme.typography.titleLarge)
            SignalHub.DISPLAY_ORDER.forEach { key ->
                values[key]?.let { Text("$key: $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Column(modifier = Modifier.weight(1.3f)) {
            Text("이벤트 (${events.size})", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(events) { e ->
                    Text(
                        "${timeFormat.format(Date(e.timeMillis))} [${e.source}] ${e.text}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DevButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = GzColors.TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(12.dp),
    )
}
