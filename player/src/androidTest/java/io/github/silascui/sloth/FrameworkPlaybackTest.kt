package io.github.silascui.sloth

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@android.annotation.SuppressLint("UnsafeOptInUsageError")
@RunWith(AndroidJUnit4::class)
class FrameworkPlaybackTest {
    private lateinit var scenario: ActivityScenario<FrameworkTestActivity>
    private lateinit var activity: FrameworkTestActivity
    private lateinit var fixture: File
    private val controllers = mutableListOf<FeedPlaybackController>()
    private val details = mutableListOf<SingleVideoController>()
    private val metrics = mutableListOf<PlaybackMetrics>()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var result = false; main { result = condition() }; if (result) return
            Thread.sleep(10L)
        }
        fail(message)
    }
    private fun group(): VideoPreloadCoordinator? = VideoPlaybackRuntime.javaClass.getDeclaredField("resources").run {
        isAccessible = true; get(VideoPlaybackRuntime) as? VideoPreloadCoordinator
    }
    @Before fun start() {
        scenario = ActivityScenario.launch(FrameworkTestActivity::class.java)
        scenario.onActivity { activity = it }
        fixture = File(activity.cacheDir, "framework-smoke.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("smoke.mp4").use { input -> fixture.outputStream().use(input::copyTo) }
        main {
            if (VideoPlaybackRuntime.isInitialized) VideoPlaybackRuntime.shutdown()
            VideoPlaybackRuntime.initialize(activity, PlayerConfiguration(events = object : PlaybackEvents {
                override fun onFirstFrame(metrics: PlaybackMetrics) { this@FrameworkPlaybackTest.metrics += metrics }
            }))
        }
    }
    @After fun finish() {
        main { controllers.forEach { it.close() }; details.forEach { it.close() }; VideoPlaybackRuntime.shutdown() }
        fixture.delete(); scenario.close()
    }
    @Test fun feedPromotesWarmEngineAndOldLeaseCannotControlIt() {
        lateinit var controller: FeedPlaybackController
        lateinit var views: List<VideoHostView>
        lateinit var previous: PlaybackHandle
        lateinit var nextPhysical: Player
        val items = listOf(VideoMedia("first", fixture.toURI().toString()), VideoMedia("second", fixture.toURI().toString()))
        main {
            controller = FeedPlaybackController().also(controllers::add); controller.submitFeed(items)
            views = items.mapIndexed { index, item -> VideoHostView(activity).also {
                activity.root.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)); controller.registerHost(it, index, item, it)
            } }
            controller.setVisible(true)
        }
        await("first frame and adjacent warm frame") { metrics.isNotEmpty() && group()?.engines?.hasWarmFrame == true }
        main {
            previous = controller.currentHandle!!
            nextPhysical = VideoRenderBinding.engine(views[1].player)!!
            assertEquals(2, group()!!.engines.engineCount)
            controller.setScrolling(true)
            assertTrue("drag does not pause the current item", previous.isPlaying)
            controller.setScrolling(true, settling = true); controller.select(1)
            assertSame(nextPhysical, VideoRenderBinding.engine(views[1].player))
            assertTrue(VideoPlaybackRuntime.lastStartupSample!!.warmFrameReused)
            previous.pause(); previous.seekTo(99_000L); previous.close()
            assertTrue(controller.currentHandle!!.isActive)
            controller.setScrolling(false)
        }
        await("promoted item plays") { controller.currentHandle?.isPlaying == true }
        main {
            assertEquals(2, group()!!.engines.engineCount)
            controller.setVisible(false); assertNull(group())
        }
    }
    @Test fun detailRevokesFeedWithoutCreatingAnotherPlayerGroup() {
        lateinit var feed: FeedPlaybackController
        lateinit var detail: SingleVideoController
        lateinit var old: PlaybackHandle
        lateinit var originalGroup: VideoPreloadCoordinator
        main {
            val item = VideoMedia("feed", fixture.toURI().toString())
            val host = VideoHostView(activity).also(activity.root::addView)
            feed = FeedPlaybackController().also(controllers::add); feed.submitFeed(listOf(item)); feed.registerHost(host, 0, item, host); feed.setVisible(true)
        }
        await("feed first frame") { metrics.isNotEmpty() }
        main {
            old = feed.currentHandle!!; originalGroup = group()!!
            detail = SingleVideoController(initiallyMuted = true).also(details::add)
            detail.load(VideoMedia("detail", fixture.toURI().toString()), VideoHostView(activity).also(activity.root::addView))
            detail.setVisible(true)
            assertFalse(old.isActive); assertSame(originalGroup, group()); assertTrue(group()!!.engines.engineCount <= 2)
            old.pause(); old.close(); assertTrue(detail.currentHandle!!.isActive)
        }
        await("detail frame") { metrics.size >= 2 && detail.currentHandle?.isPlaying == true }
    }
    @Test fun asyncManualConsentSurvivesMeteredGateButCannotAuthorizeANewerSelection() {
        lateinit var controller: FeedPlaybackController
        var granted = false
        var retry: (() -> Unit)? = null
        val items = listOf(VideoMedia("consent-a", fixture.toURI().toString()), VideoMedia("consent-b", fixture.toURI().toString()))
        main {
            VideoPlaybackRuntime.shutdown()
            VideoPlaybackRuntime.initialize(activity, PlayerConfiguration(autoplayAllowed = { false },
                playbackPolicy = PlaybackPolicy { _, _, manual, _, onAllowed ->
                    assertTrue("metered autoplay cannot reach the consent prompt", manual)
                    if (granted) true else { retry = onAllowed; false }
                }, events = object : PlaybackEvents {
                    override fun onFirstFrame(metrics: PlaybackMetrics) { this@FrameworkPlaybackTest.metrics += metrics }
                }))
            controller = FeedPlaybackController().also(controllers::add); controller.submitFeed(items)
            items.forEachIndexed { index, item ->
                val host = VideoHostView(activity).also(activity.root::addView)
                controller.registerHost(host, index, item, host)
            }
            controller.setVisible(true); assertNull(group()); controller.toggle(0)
            assertNotNull(retry); granted = true; retry!!.invoke()
        }
        await("consent must resume the original manual request despite autoplay being disabled") {
            metrics.size == 1 && controller.currentHandle?.isPlaying == true
        }
        main { controller.select(1); retry!!.invoke() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        main {
            assertEquals("old consent cannot start a newer selection", "consent-b", controller.currentHandle!!.session.mediaId)
            assertFalse(VideoPlaybackRuntime.wantsToPlay(controller.currentHandle!!))
            controller.toggle(1)
        }
        await("new item needs its own explicit request") { metrics.size == 2 && controller.currentHandle?.isPlaying == true }
    }

    @Test fun pausedWarmupHonorsExactRestoreAndReleasesStandby() {
        lateinit var pool: VideoEnginePool
        lateinit var host: VideoHostView
        val pts = AtomicReference<Long?>(null)
        val item = VideoMedia("restore", fixture.toURI().toString())
        main {
            host = VideoHostView(activity).also(activity.root::addView)
            pool = VideoEnginePool(activity, {}) { name -> ExoPlayer.Builder(activity).setName(name).build().also {
                it.setVideoFrameMetadataListener { positionUs, _, _, _ -> pts.compareAndSet(null, positionUs) }
            } }
        }
        try {
            await("output surface") { host.outputReady() }
            main { pool.warm(VideoEnginePool.Request(item, host, 2_000L)) {
                DefaultMediaSourceFactory(activity).createMediaSource(androidx.media3.common.MediaItem.Builder().setUri(item.url).setMediaId(item.sourceKey).build())
            } }
            await("precise paused frame") { pool.hasWarmFrame }
            main {
                assertTrue(pts.get()!! in 2_000_000L..2_041_667L)
                val player = VideoRenderBinding.engine(host.player) as ExoPlayer
                assertEquals(2_000L, player.currentPosition); assertFalse(player.playWhenReady); assertEquals(0F, player.volume)
                pool.cancelWarmup(host, releaseEngine = true)
                assertTrue(player.isReleased); assertEquals(1, pool.engineCount)
            }
        } finally { main { pool.release() } }
    }
    @SdkSuppress(minSdkVersion = 31)
    @Test fun stagedBudgetDropsCodecRateThenEndsAndCannotExpireReplacement() {
        lateinit var boost: WarmupSeekBoost
        lateinit var first: ExoPlayer
        lateinit var second: ExoPlayer
        main { boost = WarmupSeekBoost(); first = ExoPlayer.Builder(activity).build(); second = ExoPlayer.Builder(activity).build(); assertTrue(boost.begin(first, 2_000L)) }
        try {
            await("codec rate soft deadline") { !first.scrubbingModeParameters.shouldIncreaseCodecOperatingRate }
            main { assertTrue(first.isScrubbingModeEnabled); boost.begin(second, 2_000L); assertFalse(first.isScrubbingModeEnabled); boost.finish(first) }
            Thread.sleep(1_100L)
            main { assertTrue(second.isScrubbingModeEnabled); assertFalse(second.scrubbingModeParameters.shouldIncreaseCodecOperatingRate) }
            await("scheduling hard deadline") { !second.isScrubbingModeEnabled }
        } finally { main { boost.finish(first); boost.finish(second); first.release(); second.release() } }
    }
}
