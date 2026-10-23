package io.github.silascui.sloth

import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackControllerTest {
    private lateinit var scenario: ActivityScenario<FrameworkTestActivity>
    private lateinit var activity: FrameworkTestActivity
    private lateinit var controller: FeedPlaybackController
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail(message)
    }
    @Before fun start() {
        scenario = ActivityScenario.launch(FrameworkTestActivity::class.java)
        scenario.onActivity { activity = it }
        main {
            VideoPlaybackRuntime.initialize(activity, PlayerConfiguration(autoplayAllowed = { false }))
            controller = FeedPlaybackController()
        }
    }
    @After fun finish() {
        main { controller.close(); VideoPlaybackRuntime.shutdown() }
        scenario.close()
    }
    private fun bind(url: String = "asset:///missing-controller-fixture.mp4") {
        val media = VideoMedia("controls", url)
        val host = VideoHostView(activity)
        activity.root.addView(host, FrameLayout.LayoutParams(-1, -1))
        controller.submitFeed(listOf(media))
        controller.registerHost(host, 0, media, host)
        controller.setVisible(true)
    }
    @Test fun manualPauseCancelsAnOutstandingConsentRequest() {
        var allow: (() -> Unit)? = null
        var isCurrent: (() -> Boolean)? = null
        main {
            controller.close()
            VideoPlaybackRuntime.shutdown()
            VideoPlaybackRuntime.initialize(activity, PlayerConfiguration(autoplayAllowed = { false },
                playbackPolicy = PlaybackPolicy { _, _, _, current, onAllowed ->
                    isCurrent = current
                    allow = onAllowed
                    false
                }))
            controller = FeedPlaybackController()
            bind()
            controller.play()
            assertNotNull(allow)
            assertTrue(controller.state.playWhenReady)
            controller.pause()
            assertFalse(isCurrent!!.invoke())
            allow!!.invoke()
        }
        instrumentation.waitForIdleSync()
        main {
            assertTrue(controller.state.userPaused)
            assertFalse(controller.state.playWhenReady)
            assertEquals(PlaybackStatus.PAUSED, controller.state.status)
        }
    }
    @Test fun anAuthorizedManualPlaySurvivesAHostReadyNotification() {
        main {
            controller.close()
            VideoPlaybackRuntime.shutdown()
            VideoPlaybackRuntime.initialize(activity, PlayerConfiguration(autoplayAllowed = { false }))
            controller = FeedPlaybackController()
            bind()
            controller.play()
            val target = controller.currentHandle!!.target as FeedRenderTarget
            target.onPlaybackHostAttached?.invoke()
            assertNotEquals(PlaybackBlockReason.POLICY, controller.state.blockReason)
            assertTrue(controller.state.playWhenReady)
            controller.pause()
            target.onPlaybackHostAttached?.invoke()
            assertTrue(controller.state.userPaused)
            assertFalse(controller.state.playWhenReady)
        }
    }
    @Test fun retryPreparesTheSameFailedMediaAgain() {
        var failures = 0
        main {
            controller.close()
            VideoPlaybackRuntime.shutdown()
            VideoPlaybackRuntime.initialize(activity, PlayerConfiguration(autoplayAllowed = { false }, events = object : PlaybackEvents {
                override fun onError(mediaId: String, errorCode: Int) { failures++ }
            }))
            controller = FeedPlaybackController()
            bind()
            controller.play()
        }
        await("initial failure should be observable") { failures == 1 && controller.state.status == PlaybackStatus.ERROR }
        main { assertTrue(controller.state.canRetry); controller.retry() }
        await("retry must trigger a second load, not reuse the failed state") { failures == 2 && controller.state.status == PlaybackStatus.ERROR }
    }
    @Test fun closingDeliversTerminalStateAndMakesCommandsHarmless() {
        val observed = mutableListOf<PlaybackStatus>()
        main {
            bind()
            controller.observeState { observed += it.status }
            controller.close()
            val terminal = controller.state
            controller.setSpeed(2f)
            controller.setMuted(false)
            controller.seekTo(1_000L)
            controller.replay()
            controller.play()
            controller.pause()
            controller.retry()
            controller.close()
            assertEquals(terminal, controller.state)
            assertEquals(PlaybackStatus.CLOSED, observed.last())
            assertEquals(PlaybackStatus.CLOSED, controller.state.status)
            assertFalse(controller.state.canPlay)
            assertNull(controller.currentHandle)
        }
    }
    @Test fun singleVideoUsesTheSameCommandAndStateContract() {
        main {
            val detail: PlaybackController = SingleVideoController()
            detail.setMuted(true)
            detail.setSpeed(1.5f)
            assertTrue(detail.state.muted)
            assertEquals(1.5f, detail.state.speed, 0f)
            detail.close()
            assertEquals(PlaybackStatus.CLOSED, detail.state.status)
        }
    }
    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
