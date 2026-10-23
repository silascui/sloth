package io.github.silascui.sloth.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.AdapterView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.silascui.sloth.PlaybackController

/**
 * Moves a view to an immersive dialog and returns it on back, exit, stop or destruction.
 * Orientation follows the host Activity's policy. No new player or playback lease is created.
 */
class FullScreenCoordinator(
    private val activity: Activity,
    private val owner: LifecycleOwner = requireNotNull(activity as? LifecycleOwner) { "Provide a LifecycleOwner" },
) : DefaultLifecycleObserver, AutoCloseable {
    private data class Entry(val target: View, val parent: ViewGroup, val index: Int,
        val params: ViewGroup.LayoutParams, val placeholder: View, val dialog: Dialog, val controls: PlayerControlView?)
    private var entry: Entry? = null
    private var closed = false
    val isFullScreen: Boolean get() = entry != null
    var onFullScreenChanged: ((Boolean) -> Unit)? = null

    init {
        checkMainThread()
        require(owner.lifecycle.currentState != Lifecycle.State.DESTROYED) { "Cannot use a destroyed lifecycle" }
        owner.lifecycle.addObserver(this)
    }

    fun enter(target: View, controller: PlaybackController? = null) {
        checkMainThread()
        check(!closed) { "Full-screen coordinator is closed" }
        check(!activity.isFinishing && !activity.isDestroyed) { "Activity is not available" }
        if (entry?.target === target) return
        exit()
        val parent = requireNotNull(target.parent as? ViewGroup) { "Full-screen target must have a parent" }
        require(parent !is RecyclerView && parent !is AdapterView<*>) {
            "Full-screen targets must be inside a stable item container, not an adapter-owned root"
        }
        check(target.getTag(R.id.sloth_fullscreen_owner) == null) { "View already belongs to a full-screen coordinator" }
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val controls = controller?.let { PlayerControlView(activity).apply { bind(it) } }
        val value = Entry(target, parent, parent.indexOfChild(target), target.layoutParams,
            View(activity).apply { layoutParams = target.layoutParams }, dialog, controls)
        entry = value
        target.setTag(R.id.sloth_fullscreen_owner, this)
        try {
            onFullScreenChanged?.invoke(true)
            parent.removeView(target)
            parent.addView(value.placeholder, value.index)
            val root = FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
                ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                    val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                    insets
                }
                addView(target, FrameLayout.LayoutParams(-1, -1))
                controls?.let { addView(it, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
            }
            controls?.apply { isFullScreen = true; onFullScreenClick = { exit() } }
            dialog.setContentView(root)
            dialog.setOnDismissListener { restore(value) }
            dialog.show()
            ViewCompat.requestApplyInsets(root)
            dialog.window?.let { window ->
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        } catch (failure: RuntimeException) {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            restore(value)
            throw failure
        }
    }

    fun toggle(target: View, controller: PlaybackController? = null) {
        if (isFullScreen) exit() else enter(target, controller)
    }

    fun exit() {
        checkMainThread()
        val current = entry ?: return
        current.dialog.setOnDismissListener(null)
        current.dialog.dismiss()
        restore(current)
    }

    private fun restore(value: Entry) {
        if (entry !== value) return
        entry = null
        value.controls?.bind(null)
        if (value.target.parent !== value.parent) {
            (value.target.parent as? ViewGroup)?.removeView(value.target)
            value.parent.removeView(value.placeholder)
            value.parent.addView(value.target, value.index.coerceIn(0, value.parent.childCount), value.params)
        } else value.parent.removeView(value.placeholder)
        value.target.setTag(R.id.sloth_fullscreen_owner, null)
        onFullScreenChanged?.invoke(false)
    }

    override fun onStop(owner: LifecycleOwner) = exit()
    override fun onDestroy(owner: LifecycleOwner) = close()
    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        exit()
        owner.lifecycle.removeObserver(this)
        onFullScreenChanged = null
    }
    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "Use full-screen controls on the main thread" } }
}
