package zone.disinfo.wx.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.Place

@RunWith(AndroidJUnit4::class)
class RadarIntentOwnershipTest {
    private val origin = 1_700_000_000L
    private fun session(source: String = "gfs"): RadarSession {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DisplayCache.initialize(context)
        return RadarSession(context, "intent-${System.nanoTime()}", Place("intent", "Intent", 40.7, -74.0)).apply {
            overlay = "radar"
            range = "hourly"
            speed = 0
            frames = RadarFrames((0..36).map { RadarFrame(origin + it * 60L, source) })
            time = (origin + 23 * 60L).toDouble()
            playing = true
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aSamePositionSeekAndReplayRetiresThePreviousTimer() = runTest {
        for (source in listOf("gfs", "mrms")) {
            val session = session(source)
            val selected = session.time
            val deadline = if (source == "mrms") 16L else 500L
            val old = launch { runRadarPlayback(session) { false } }
            try {
                testScheduler.runCurrent()
                testScheduler.advanceTimeBy(deadline - 1)
                testScheduler.runCurrent()
                val revision = session.commandRevision
                session.seekTo(selected)
                session.setPlayingIntent(true)
                assertEquals(revision + 2, session.commandRevision)
                testScheduler.advanceTimeBy(1)
                testScheduler.runCurrent()
                assertTrue("The prior command's timer must retire even when time is unchanged", old.isCompleted)
                assertEquals(selected, session.time, 0.0)
                val current = launch { runRadarPlayback(session) { false } }
                try {
                    testScheduler.runCurrent()
                    testScheduler.advanceTimeBy(deadline - 1)
                    testScheduler.runCurrent()
                    assertEquals("The new Play command owns a fresh interval", selected, session.time, 0.0)
                    testScheduler.advanceTimeBy(1)
                    testScheduler.runCurrent()
                    assertTrue(current.isActive)
                    if (source == "gfs") assertEquals((origin + 24 * 60L).toDouble(), session.time, 0.0)
                } finally { current.cancelAndJoin() }
            } finally { old.cancelAndJoin() }
        }
    }

    @Test fun metadataUsesTheLatestSeekInsteadOfAnEarlierDeepLink() {
        val session = session()
        val request = session.beginMetadataRequest()
        val selected = origin + 36 * 60 * .301234
        session.seekTo(selected)
        session.setPlayingIntent(true)
        val revision = session.commandRevision
        val response = RadarFrames(session.frames.frames.map { it.copy(revision = 2) })
        assertTrue(session.applyMetadata(request, response, origin + 5 * 60, origin + 10))
        assertSame(response, session.frames)
        assertEquals(selected, session.time, 0.0)
        assertEquals(revision, session.commandRevision)
        assertTrue(session.playing)
    }

    @Test fun obsoleteRefreshAndRapidLayerReturnCannotCommitMetadataOrSavedViews() {
        val session = session()
        val response = RadarFrames(session.frames.frames.map { it.copy(revision = 2) })
        val oldRequest = session.beginMetadataRequest()
        val oldTimeline = session.timelineIdentity()
        val frames = session.frames
        val selected = session.time
        session.invalidateMetadata()
        val latestRequest = session.beginMetadataRequest()
        assertFalse(session.applyMetadata(oldRequest, response, null, origin))
        assertSame(frames, session.frames)
        assertEquals(selected, session.time, 0.0)
        assertTrue(session.owns(latestRequest))

        // Returning to the same values before recomposition must still invalidate old work.
        session.noteTimelineCommand()
        session.overlay = "wind"
        session.range = "extended"
        session.noteTimelineCommand()
        session.overlay = "radar"
        session.range = "hourly"
        assertFalse("An old saved-view completion no longer owns the timeline", session.owns(oldTimeline))
        assertFalse(session.owns(latestRequest))
        assertFalse(session.applyMetadata(latestRequest, response, null, origin))
        assertEquals(selected, session.time, 0.0)
        val current = session.beginMetadataRequest()
        assertTrue(session.applyMetadata(current, response, null, origin))
        assertSame(response, session.frames)
        assertEquals(selected, session.time, 0.0)
        assertFalse(session.owns(current.timeline.copy(cacheGeneration = current.timeline.cacheGeneration - 1)))
    }
}
