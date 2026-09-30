package zone.disinfo.wx.data

import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** One process-wide throttle shared by UI, workers and a foreground watch, including failures. */
internal class RainRequestCache(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private data class Entry(val request: Deferred<Result<Any>>, var completedAt: Long? = null)

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> get(key: String, request: suspend () -> T): T {
        val entry =
            synchronized(lock) {
                val now = elapsedRealtime()
                entries.entries.removeAll { (_, entry) ->
                    entry.completedAt?.let { now - it >= MIN_INTERVAL_MILLIS } == true
                }
                entries[key]
                    ?: run {
                        // Do not evict an unexpired key: doing so would allow another request
                        // inside two minutes.
                        if (entries.size >= MAX_KEYS)
                            throw IOException("Radar request budget is busy; retry shortly")
                        val deferred =
                            scope.async(start = CoroutineStart.LAZY) {
                                try {
                                    Result.success(request() as Any)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    Result.failure(error)
                                }
                            }
                        val created = Entry(deferred)
                        deferred.invokeOnCompletion {
                            synchronized(lock) { created.completedAt = elapsedRealtime() }
                        }
                        entries[key] = created
                        created
                    }
            }
        entry.request.start()
        // Cancelling one screen or worker only cancels its waiter, never another consumer's
        // request.
        return entry.request.await().getOrThrow() as T
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 120_000L
        const val MAX_KEYS = 128
        val shared = RainRequestCache()
    }
}
