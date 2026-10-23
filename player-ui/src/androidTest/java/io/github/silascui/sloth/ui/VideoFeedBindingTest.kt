package io.github.silascui.sloth.ui

import android.content.Intent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.silascui.sloth.VideoPlaybackRuntime
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoFeedBindingTest {
    private var scenario: ActivityScenario<BindingTestActivity>? = null
    private fun launch() = ActivityScenario.launch(BindingTestActivity::class.java).also { scenario = it }
    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    @After fun finish() {
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (VideoPlaybackRuntime.isInitialized) VideoPlaybackRuntime.shutdown()
        }
    }
    @Test fun pagerAndUserPlaybackStateSurviveRecreation() {
        val activity = launch()
        activity.onActivity {
            it.pager.setCurrentItem(2, false)
            it.binding.controller.setSpeed(1.5f)
            it.binding.controller.setMuted(false)
        }
        idle()
        activity.recreate()
        idle()
        activity.onActivity {
            assertEquals(2, it.pager.currentItem)
            val state = it.binding.controller.saveState()
            assertEquals(2, state.selectedIndex)
            assertEquals(1.5f, state.speed, 0f)
            assertFalse(state.muted)
        }
    }
    @Test fun duplicateBindingIsRejectedAndCloseAllowsRebinding() {
        launch().onActivity {
            assertThrows(IllegalStateException::class.java) { it.pager.bindVideoFeed(it, it.media) }
            it.binding.close()
            it.binding.close()
            it.binding = it.pager.bindVideoFeed(it, it.media, stateKey = "fixture")
        }
    }
    @Test fun stoppingHidesPlaybackAndDestroyClosesBinding() {
        val activity = launch()
        lateinit var binding: VideoFeedBinding
        activity.onActivity { binding = it.binding }
        activity.moveToState(Lifecycle.State.CREATED)
        activity.onActivity { assertNull(binding.controller.currentHandle) }
        activity.moveToState(Lifecycle.State.DESTROYED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertThrows(IllegalStateException::class.java) { binding.setVisible(true) }
        }
    }
    @Test fun hiddenPageReleasesItsPlaybackHandle() {
        launch().onActivity {
            it.binding.setVisible(false)
            assertNull(it.binding.controller.currentHandle)
        }
        idle()
    }
    @Test fun listAutomaticallySelectsANewVisibleHolderAfterProgrammaticScroll() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = ActivityScenario.launch<BindingTestActivity>(Intent(context, BindingTestActivity::class.java).putExtra("list", true))
            .also { scenario = it }
        idle()
        activity.onActivity {
            (it.recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(4, 0)
        }
        idle()
        activity.onActivity { assertEquals(4, it.binding.controller.selectedIndex) }
    }
    @Test fun clearingAndRefillingAdapterDoesNotRetainOldHosts() {
        val activity = launch()
        activity.onActivity {
            it.media = emptyList()
            it.pager.adapter!!.notifyDataSetChanged()
            it.binding.submitFeed(it.media)
        }
        idle()
        activity.onActivity { assertNull(it.binding.controller.currentHandle) }
        activity.onActivity {
            it.media = listOf(io.github.silascui.sloth.VideoMedia("replacement", "asset:///unused.mp4"))
            it.pager.adapter!!.notifyDataSetChanged()
            it.binding.submitFeed(it.media)
        }
        idle()
        activity.onActivity {
            assertEquals(0, it.binding.controller.selectedIndex)
            assertFalse(it.binding.controller.saveState().sessions.any { snapshot -> snapshot.mediaId.startsWith("test-") })
        }
    }

    @Test fun bindingPreservesTheHostsInitialPagerSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = ActivityScenario.launch<BindingTestActivity>(Intent(context, BindingTestActivity::class.java).putExtra("initial-page", 1))
            .also { scenario = it }
        idle()
        activity.onActivity {
            assertEquals(1, it.pager.currentItem)
            assertEquals(1, it.binding.controller.selectedIndex)
        }
    }

}
