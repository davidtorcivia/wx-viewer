package zone.disinfo.wx.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class WeatherHttpResponse(val body: String, val cacheStatus: String? = null)

/** Shared, bounded HTTPS transport; cancellation disconnects the active socket. */
class WeatherHttpClient {
    suspend fun get(
        url: String,
        readTimeoutMs: Int = 25_000,
        totalTimeoutMs: Long = 30_000,
    ): WeatherHttpResponse {
        require(readTimeoutMs in 1_000..180_000 && totalTimeoutMs in 1_000..180_000) {
            "Invalid request timeout"
        }
        return withTimeoutOrNull(totalTimeoutMs) { getCancellable(url, readTimeoutMs) }
            ?: throw IOException("The weather server took too long to respond")
    }

    private suspend fun getCancellable(url: String, readTimeoutMs: Int): WeatherHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val parsed = URL(url)
            require(parsed.protocol == "https" && parsed.userInfo == null && parsed.ref == null) {
                "HTTPS required"
            }
            val active = AtomicReference<HttpURLConnection?>(null)
            val future = executor.submit {
                var connection: HttpURLConnection? = null
                try {
                    if (!continuation.isActive) return@submit
                    connection = parsed.openConnection() as HttpURLConnection
                    active.set(connection)
                    if (!continuation.isActive) return@submit
                    connection.apply {
                        requestMethod = "GET"
                        connectTimeout = 12_000
                        readTimeout = readTimeoutMs
                        instanceFollowRedirects = false
                        useCaches = false
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("User-Agent", "WX-Viewer-Android/1.0")
                    }
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val message =
                            when (code) {
                                404 -> "Weather data is unavailable for this location"
                                429 -> "The weather server is busy. Try again shortly"
                                in 300..399 ->
                                    "The server redirected this request. Check its HTTPS address"
                                else -> "Weather server returned HTTP $code"
                            }
                        throw WeatherHttpException(code, message)
                    }
                    val bytes = ByteArrayOutputStream()
                    connection.inputStream.use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (!continuation.isActive) return@submit
                            val count = stream.read(buffer)
                            if (count == -1) break
                            if (bytes.size() + count > MAX_RESPONSE_BYTES)
                                throw IOException("Weather response is too large")
                            bytes.write(buffer, 0, count)
                        }
                    }
                    val response =
                        WeatherHttpResponse(
                            bytes.toString(Charsets.UTF_8.name()),
                            connection.getHeaderField("X-Cache"),
                        )
                    if (continuation.isActive) continuation.resume(response)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    active.set(null)
                    connection?.disconnect()
                }
            }
            continuation.invokeOnCancellation {
                active.getAndSet(null)?.disconnect()
                future.cancel(true)
            }
        }

    companion object {
        private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
        private val executor =
            Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "wx-http").apply { isDaemon = true }
            }
    }
}

class WeatherHttpException(val statusCode: Int, message: String) : IOException(message)
