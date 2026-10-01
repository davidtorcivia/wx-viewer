package zone.disinfo.wx.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.WeatherHttpException

/** The real radar metadata retry policy, with deterministic transport outcomes. */
@RunWith(AndroidJUnit4::class)
class RadarMetadataRetryTest {
    @Test fun transientGatewaysRecoverWithoutSkippingTheRequestedMetadata() = runBlocking {
        for (status in listOf(502, 503, 504)) {
            var calls = 0
            val result = withTimeout(3_000) {
                radarMetadataWithRetry {
                    if (++calls == 1) throw WeatherHttpException(status, "Synthetic transient gateway")
                    JSONObject().put("field", "gust").put("complete", true)
                }
            }
            assertEquals(2, calls)
            assertEquals("gust", result.getString("field"))
            assertTrue(result.getBoolean("complete"))
        }
    }

    @Test fun repeatedGatewayErrorsRemainBoundedAndSurfaceTheFailure() = runBlocking {
        var calls = 0
        try {
            withTimeout(6_000) {
                radarMetadataWithRetry {
                    calls++
                    throw WeatherHttpException(502, "Synthetic persistent gateway")
                }
            }
            fail("Persistent unavailability must remain an error")
        } catch (failure: WeatherHttpException) {
            assertEquals(502, failure.statusCode)
        }
        assertEquals("At most two retries", 3, calls)
    }

    @Test fun cancellationAndNonTransientErrorsNeverKeepRetrying() = runBlocking {
        for (status in listOf(401, 403, 404, 429, 500, 501)) {
            var calls = 0
            try {
                radarMetadataWithRetry {
                    calls++
                    throw WeatherHttpException(status, "Synthetic permanent or rate-limited response")
                }
                fail("HTTP $status must surface without retry")
            } catch (failure: WeatherHttpException) { assertEquals(status, failure.statusCode) }
            assertEquals(1, calls)
        }
        var calls = 0
        try {
            withTimeout(50) {
                radarMetadataWithRetry {
                    calls++
                    throw WeatherHttpException(503, "Synthetic temporary response")
                }
            }
            fail("Caller cancellation must interrupt backoff")
        } catch (_: TimeoutCancellationException) { }
        assertEquals("A cancelled layer must not launch another request", 1, calls)
    }
}
