package kr.gilzikim.app.ui.postdrive

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Short place name ("신사동 도산대로") for a candidate, from the platform geocoder.
 * Returns null when the vehicle has no geocoder or it does not answer; the caller then falls back
 * to the distance along the trip. A third-party reverse-geocoding API would send coordinates off
 * the car, which needs its own consent, so it is not used here.
 */
suspend fun resolvePlaceName(context: Context, lat: Double, lon: Double): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context, Locale.KOREA)
    val address = withTimeoutOrNull(4_000) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine<Address?> { cont ->
                geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.firstOrNull())
                    override fun onError(errorMessage: String?) = cont.resume(null)
                })
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            }
        }
    } ?: return null
    val area = address.subLocality ?: address.locality
    val road = address.thoroughfare
    return listOfNotNull(area, road).distinct().joinToString(" ").ifBlank { address.getAddressLine(0) }
}
