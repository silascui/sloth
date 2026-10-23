package io.github.silascui.sloth.ui

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.silascui.sloth.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackControlsTest {
    private var scenario: ActivityScenario<BindingTestActivity>? = null
    private fun launch() = ActivityScenario.launch(BindingTestActivity::class.java).also { scenario = it }
    @After fun finish() {
        scenario?.close()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (VideoPlaybackRuntime.isInitialized) VideoPlaybackRuntime.shutdown()
        }
    }
    @Test fun panelRoutesCommandsAndReleasesObserversWhenDetached() {
        launch().onActivity { activity ->
            val fake = FakeController()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            val panel = PlayerControlView(activity).apply { bind(fake) }
            root.addView(panel)
            assertEquals(1, fake.observers.size)
            panel.findViewById<Button>(R.id.sloth_control_play).performClick()
            assertEquals("play", fake.command)
            fake.emit(fake.state.copy(status = PlaybackStatus.PLAYING, playWhenReady = true))
            panel.findViewById<Button>(R.id.sloth_control_play).performClick()
            assertEquals("pause", fake.command)
            panel.findViewById<Button>(R.id.sloth_control_mute).performClick()
            assertEquals("mute:false", fake.command)
            panel.findViewById<Button>(R.id.sloth_control_speed).performClick()
            assertEquals("speed:1.5", fake.command)
            fake.emit(fake.state.copy(canSeek = true, durationMs = 10_000L))
            panel.findViewById<SeekBar>(R.id.sloth_control_seek).performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 500f) })
            assertEquals("seek:5000", fake.command)
            fake.emit(fake.state.copy(status = PlaybackStatus.ERROR, playWhenReady = false, error = PlaybackFailure(1)))
            val retry = panel.findViewById<Button>(R.id.sloth_control_retry)
            assertEquals(View.VISIBLE, retry.visibility)
            retry.performClick()
            assertEquals("retry", fake.command)
            root.removeView(panel)
            assertTrue(fake.observers.isEmpty())
            assertEquals(0, fake.closeCalls)
        }
    }
    @Test fun fullscreenExitRestoresViewPositionAndLayoutWithoutClosingPlayback() {
        launch().onActivity { activity ->
            val root = FrameLayout(activity)
            val target = View(activity)
            root.addView(target, FrameLayout.LayoutParams(123, 234))
            root.addView(View(activity))
            activity.setContentView(root)
            val fake = FakeController()
            val coordinator = FullScreenCoordinator(activity)
            val modes = mutableListOf<Boolean>()
            coordinator.onFullScreenChanged = { modes += it }
            coordinator.enter(target, fake)
            assertTrue(coordinator.isFullScreen)
            assertNotSame(root, target.parent)
            coordinator.exit()
            coordinator.exit()
            assertSame(root, target.parent)
            assertEquals(0, root.indexOfChild(target))
            assertEquals(123, target.layoutParams.width)
            assertEquals(234, target.layoutParams.height)
            assertEquals(listOf(true, false), modes)
            assertTrue(fake.observers.isEmpty())
            assertEquals(0, fake.closeCalls)
            coordinator.close()
            assertThrows(IllegalStateException::class.java) { coordinator.enter(target) }
        }
    }
    @Test fun bindingControlsManageFullscreenAndRestorePagerInputOnStop() {
        val activity = launch()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        lateinit var panel: PlayerControlView
        lateinit var host: View
        lateinit var originalParent: Any
        activity.onActivity {
            panel = PlayerControlView(it)
            (it.findViewById<View>(android.R.id.content) as android.view.ViewGroup).addView(panel)
            it.binding.bindControls(panel, it)
            host = (it.recycler.findViewHolderForAdapterPosition(0) as VideoViewHolder).videoHost
            originalParent = host.parent
            panel.findViewById<Button>(R.id.sloth_control_fullscreen).performClick()
            assertTrue(panel.isFullScreen)
            assertFalse(it.pager.isUserInputEnabled)
            assertNotSame(originalParent, host.parent)
        }
        activity.moveToState(Lifecycle.State.CREATED)
        activity.onActivity {
            assertFalse(panel.isFullScreen)
            assertTrue(it.pager.isUserInputEnabled)
            assertSame(originalParent, host.parent)
            it.binding.unbindControls()
            assertEquals(View.GONE, panel.findViewById<Button>(R.id.sloth_control_fullscreen).visibility)
            assertFalse(panel.findViewById<Button>(R.id.sloth_control_play).isEnabled)
        }
    }
    @Test fun stoppingActivityRestoresFullscreenTarget() {
        val activity = launch()
        lateinit var target: View
        lateinit var parent: FrameLayout
        lateinit var coordinator: FullScreenCoordinator
        activity.onActivity {
            parent = FrameLayout(it)
            target = View(it)
            parent.addView(target)
            it.setContentView(parent)
            coordinator = FullScreenCoordinator(it)
            coordinator.enter(target)
        }
        activity.moveToState(Lifecycle.State.CREATED)
        activity.onActivity {
            assertFalse(coordinator.isFullScreen)
            assertSame(parent, target.parent)
        }
    }
    private class FakeController : PlaybackController {
        override var state = PlaybackState(mediaId = "fake", visible = true, canPlay = true)
        val observers = mutableSetOf<(PlaybackState) -> Unit>()
        var command: String? = null
        var closeCalls = 0
        override fun observeState(observer: (PlaybackState) -> Unit): AutoCloseable {
            observers += observer
            observer(state)
            return AutoCloseable { observers -= observer }
        }
        fun emit(value: PlaybackState) { state = value; observers.toList().forEach { it(value) } }
        override fun play() { command = "play" }
        override fun pause() { command = "pause" }
        override fun retry() { command = "retry" }
        override fun replay() { command = "replay" }
        override fun seekTo(positionMs: Long) { command = "seek:$positionMs" }
        override fun setSpeed(value: Float) { command = "speed:$value" }
        override fun setMuted(value: Boolean) { command = "mute:$value" }
        override fun close() { closeCalls++ }
    }
}
