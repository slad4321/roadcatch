package kr.gilzikim.app.net

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.gilzikim.app.BuildConfig
import kr.gilzikim.app.trip.Candidate
import org.json.JSONArray
import org.json.JSONObject

/** What the server knows about a candidate's location. */
data class Place(
    val name: String,
    /** "expressway" or "local": a hint for which reporting channel applies. */
    val jurisdiction: String? = null,
    /** Who to file a damage claim with, e.g. "강남구청" or "한국도로공사". A hint, not a ruling. */
    val agency: String? = null,
)

/**
 * Asks gilzikim-server for place names of candidates. Returns an empty map when no server is
 * configured or it cannot be reached; callers fall back to the distance along the trip.
 */
object PlaceClient {

    private const val TAG = "Gilzikim"

    suspend fun fetch(context: Context, candidates: List<Candidate>): Map<String, Place> {
        if (candidates.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            val fromServer = if (BuildConfig.SERVER_URL.isEmpty()) emptyMap() else runCatching { post(candidates) }
                .onFailure { Log.w(TAG, "[서버] 주소 조회 실패: $it") }
                .getOrDefault(emptyMap())
            // No server (release build) or no answer: the names bundled for the demo trip.
            fromServer + candidates.filter { it.id !in fromServer }
                .mapNotNull { c -> DemoAssets.place(context, c.lat, c.lon)?.let { c.id to it } }
        }
    }

    private fun post(candidates: List<Candidate>): Map<String, Place> {
        val body = JSONObject().put(
            "points",
            JSONArray(candidates.map { JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon) }),
        )
        val connection = URL("${BuildConfig.SERVER_URL}/v1/places").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 3_000
            // The development server rate-limits its geocoder to one point per second.
            connection.readTimeout = 5_000 + 1_500 * candidates.size
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val places = JSONObject(connection.inputStream.bufferedReader().readText()).getJSONObject("places")
            return places.keys().asSequence().associateWith { id ->
                val p = places.getJSONObject(id)
                Place(
                    name = p.getString("name"),
                    jurisdiction = p.optString("jurisdiction").ifEmpty { null },
                    agency = p.optString("agency").takeUnless { it.isEmpty() || it == "null" },
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
