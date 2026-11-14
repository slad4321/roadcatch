package kr.gilzikim.app.net

import android.content.Context
import kotlin.math.abs
import kotlin.math.cos
import org.json.JSONObject

/** One road from the bundled network: [cls] 0 = major, 1 = middle, 2 = minor; lat/lon pairs. */
class Road(val cls: Int, val latLon: DoubleArray)

/**
 * Data shipped inside the APK so the demo works with no server and no network (the Pleos emulator
 * has none): the road network around the scripted commute (OpenStreetMap, ODbL, attributed on the
 * map) and the place names of its spots, as gilzikim-server resolved them.
 */
object DemoAssets {

    @Volatile private var roads: List<Road>? = null
    @Volatile private var places: List<Pair<DoubleArray, Place>>? = null

    fun roads(context: Context): List<Road> = roads ?: synchronized(this) {
        roads ?: load(context, "demo_roads.json") { json ->
            val arr = json.getJSONArray("roads")
            List(arr.length()) { i ->
                val r = arr.getJSONArray(i)
                Road(r.getInt(0), DoubleArray(r.length() - 1) { r.getDouble(it + 1) })
            }
        }.also { roads = it }
    }

    /** A bundled place within ~40 m of the point, if any. */
    fun place(context: Context, lat: Double, lon: Double): Place? {
        val all = places ?: synchronized(this) {
            places ?: load(context, "demo_places.json") { json ->
                val arr = json.getJSONArray("places")
                List(arr.length()) { i ->
                    val p = arr.getJSONObject(i)
                    doubleArrayOf(p.getDouble("lat"), p.getDouble("lon")) to Place(
                        name = p.getString("name"),
                        jurisdiction = p.optString("jurisdiction").ifEmpty { null },
                        agency = p.optString("agency").ifEmpty { null },
                    )
                }
            }.also { places = it }
        }
        val k = cos(Math.toRadians(lat))
        return all.minByOrNull { (ll, _) -> abs(ll[0] - lat) + abs(ll[1] - lon) * k }
            ?.takeIf { (ll, _) -> abs(ll[0] - lat) < 0.0004 && abs(ll[1] - lon) * k < 0.0004 }
            ?.second
    }

    private fun <T> load(context: Context, name: String, parse: (JSONObject) -> List<T>): List<T> =
        runCatching {
            context.assets.open(name).bufferedReader().use { parse(JSONObject(it.readText())) }
        }.getOrDefault(emptyList())
}
