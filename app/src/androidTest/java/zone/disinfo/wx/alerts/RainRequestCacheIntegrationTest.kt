package zone.disinfo.wx.alerts

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.WxViewModel
import zone.disinfo.wx.data.AppSettings
import zone.disinfo.wx.data.Place
import zone.disinfo.wx.data.RainNowcastParser
import zone.disinfo.wx.data.RainRequestCache
import zone.disinfo.wx.data.SettingsStore

/** Device integration for shared request coalescing without making test network calls. */
@RunWith(AndroidJUnit4::class)
class RainRequestCacheIntegrationTest {
    @Test
    fun concurrentConsumersShareOneRequestAndPreserveItsOriginalResult() = runBlocking {
        val clock = AtomicLong(1_000)
        val calls = AtomicInteger()
        val cache = RainRequestCache(clock::get, this)
        val result = Any()
        val all =
            (1..12)
                .map {
                    async {
                        cache.get("server|notify|same-point") {
                            calls.incrementAndGet()
                            result
                        }
                    }
                }
                .awaitAll()
        assertEquals(1, calls.get())
        all.forEach { assertSame(result, it) }
        clock.addAndGet(119_999)
        assertSame(
            result,
            cache.get("server|notify|same-point") {
                calls.incrementAndGet()
                Any()
            },
        )
        assertEquals(1, calls.get())
        clock.incrementAndGet()
        cache.get("server|notify|same-point") {
            calls.incrementAndGet()
            Any()
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun errorsAreAlsoThrottledAndNeverBecomeSuccessfulDryData() = runBlocking {
        val clock = AtomicLong(1_000)
        val calls = AtomicInteger()
        val cache = RainRequestCache(clock::get, this)
        repeat(3) {
            val attempt = runCatching {
                cache.get<String>("server|notify|point") {
                    calls.incrementAndGet()
                    throw IOException("503 radar stale")
                }
            }
            assertTrue(attempt.isFailure)
            assertEquals("503 radar stale", attempt.exceptionOrNull()?.message)
        }
        assertEquals(1, calls.get())
        clock.addAndGet(120_000)
        assertEquals(
            "fresh",
            cache.get("server|notify|point") {
                calls.incrementAndGet()
                "fresh"
            },
        )
        assertEquals(2, calls.get())
    }

    @Test
    fun visibleRefreshWaitsForResponseBeforeStartingItsNextCooldown() = runBlocking {
        val application =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val settingsStore = SettingsStore(application)
        val original = settingsStore.load()
        val fixture =
            AppSettings(
                serverUrl = "https://wx-refresh-${UUID.randomUUID()}.invalid",
                places = emptyList(),
            )
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val viewModels = ViewModelStore()
        settingsStore.save(fixture)
        val producer =
            async(Dispatchers.IO) {
                RainRequestCache.shared.get(
                    fixture.serverUrl + "|nowcast|lat=40.7128&lon=-74.0060"
                ) {
                    started.complete(Unit)
                    finish.await()
                    val now = System.currentTimeMillis()
                    RainNowcastParser.parse(
                        JSONObject()
                            .put("time", now / 1_000)
                            .put("step", 60)
                            .put("dbz", JSONArray(List(61) { 0 }))
                            .put("rain", JSONObject.NULL)
                            .toString(),
                        now,
                    )
                }
            }
        try {
            started.await()
            val model =
                withContext(Dispatchers.Main) {
                    ViewModelProvider(
                        viewModels,
                        ViewModelProvider.AndroidViewModelFactory(application),
                    )[WxViewModel::class.java]
                }
            val generation =
                withContext(Dispatchers.Main) {
                    model.updateSettings(
                        fixture.copy(places = listOf(Place("fixture", "Fixture", 40.7128, -74.006)))
                    )
                    model.refreshRain()
                }
            val waiting = CompletableDeferred<Unit>()
            val completion =
                async(Dispatchers.Main) {
                    waiting.complete(Unit)
                    model.awaitRainRefresh(generation)
                }
            waiting.await()
            withContext(Dispatchers.Main) {
                /* Let the already-dispatched waiter reach suspension. */
            }
            assertFalse(
                "The visible timer must not advance while its shared response is pending",
                completion.isCompleted,
            )
            finish.complete(Unit)
            producer.await()
            withTimeout(10_000) { completion.await() }
            withContext(Dispatchers.Main) { assertNotNull(model.state.rainNowcast) }
        } finally {
            finish.complete(Unit)
            producer.cancelAndJoin()
            withContext(Dispatchers.Main) { viewModels.clear() }
            settingsStore.save(original)
        }
    }

    @Test
    fun cancellingOneWaiterDoesNotCancelTheOtherConsumer() = runBlocking {
        val clock = AtomicLong(1_000)
        val cache = RainRequestCache(clock::get, this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = async {
            cache.get("server|nowcast|point") {
                calls.incrementAndGet()
                started.complete(Unit)
                finish.await()
                "shared"
            }
        }
        started.await()
        val second = async {
            cache.get("server|nowcast|point") {
                calls.incrementAndGet()
                "unexpected"
            }
        }
        first.cancelAndJoin()
        finish.complete(Unit)
        assertEquals("shared", second.await())
        assertEquals(1, calls.get())
    }
}
