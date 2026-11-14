package kr.gilzikim.app.net

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.gilzikim.app.BuildConfig

/**
 * Base map tiles (256 px, Web Mercator) from gilzikim-server, which caches OpenStreetMap tiles.
 * Without a configured server the map shows the route on a plain background.
 */
object TileClient {

    private val memory = LruCache<String, ImageBitmap>(96)

    fun cached(z: Int, x: Int, y: Int): ImageBitmap? = memory.get("$z/$x/$y")

    suspend fun load(z: Int, x: Int, y: Int): ImageBitmap? {
        if (BuildConfig.SERVER_URL.isEmpty()) return null
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL("${BuildConfig.SERVER_URL}/v1/tiles/$key.png").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 3_000
                    connection.readTimeout = 10_000
                    check(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { it.readBytes() }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()?.also { memory.put(key, it) }
        }
    }
}
