package io.github.silascui.sloth.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.savedstate.SavedStateRegistryOwner
import androidx.viewpager2.widget.ViewPager2
import io.github.silascui.sloth.FeedPlaybackController
import io.github.silascui.sloth.VideoMedia
import io.github.silascui.sloth.VideoPlaybackRuntime

/**
 * Owns view listeners, host leases and lifecycle for a single feed. Use [bindVideoFeed] to create it.
 * Call [close] before replacing the adapter or rebinding the same view. All methods use the main thread.
 * For hidden tabs, call [setVisible] in addition to the owner's lifecycle.
 */
class VideoFeedBinding internal constructor(
    private val recycler: RecyclerView,
    private val owner: LifecycleOwner,
    media: List<VideoMedia>,
    private val pager: ViewPager2?,
    private val stateKey: String?,
    initiallyMuted: Boolean,
    loop: Boolean,
) : DefaultLifecycleObserver, AutoCloseable {
    val controller = FeedPlaybackController(initiallyMuted = initiallyMuted, loop = loop, warmupEnabled = pager != null)
    private val adapter = requireNotNull(recycler.adapter) { "Set the adapter before binding video playback" }
    private val registry = (owner as? SavedStateRegistryOwner)?.savedStateRegistry
    private var items = media.toList()
    private var started = false
    private var visible = true
    private var selectionEnabled = true
    private var controls: PlayerControlView? = null
    private var fullScreen: FullScreenCoordinator? = null
    private var pagerInputBeforeFullScreen: Boolean? = null
    private var selectionBeforeFullScreen: Boolean? = null
    private var playbackVisible = false
    private var closed = false
    private var pendingPagerSelection: Int? = null
    private data class Host(val position: Int, val item: VideoMedia)
    private val hosts = LinkedHashMap<VideoViewHolder, Host>()
    private val update = Runnable { refresh() }
    private val childListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) { scheduleRefresh() }
        override fun onChildViewDetachedFromWindow(view: View) {
            (recycler.getChildViewHolder(view) as? VideoViewHolder)?.let(::unregister)
            scheduleRefresh()
        }
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleRefresh() }
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (pager != null) return
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                refresh()
                controller.setScrolling(false)
            } else controller.setScrolling(true, settling = newState == RecyclerView.SCROLL_STATE_SETTLING)
        }
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_IDLE) scheduleRefresh()
        }
    }
    private val pageListener = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) { if (selectionEnabled) controller.select(position) }
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            controller.previewPageScroll(position, positionOffset)
        }
        override fun onPageScrollStateChanged(state: Int) {
            controller.setScrolling(state != ViewPager2.SCROLL_STATE_IDLE, state == ViewPager2.SCROLL_STATE_SETTLING)
            if (state == ViewPager2.SCROLL_STATE_IDLE) scheduleRefresh()
        }
    }
    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = invalidateHosts()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = invalidateHosts()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = invalidateHosts()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = invalidateHosts()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = invalidateHosts()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = invalidateHosts()
    }

    init {
        checkMainThread()
        check(VideoPlaybackRuntime.isInitialized) { "Initialize VideoPlaybackRuntime before binding video playback" }
        require(owner.lifecycle.currentState != Lifecycle.State.DESTROYED) { "Cannot bind to a destroyed lifecycle" }
        check(recycler.getTag(R.id.sloth_video_feed_binding) == null) { "Close the previous playback binding before rebinding" }
        require(adapter.itemCount == items.size) { "Media must match adapter positions and item count" }
        require(stateKey == null || stateKey.isNotBlank()) { "State key must not be blank" }
        require(stateKey == null || registry != null) { "State saving requires a SavedStateRegistryOwner" }
        controller.submitFeed(items)
        var restoredPlayback = false
        if (stateKey != null) {
            // Register first so duplicate keys fail without consuming another binding's state.
            registry!!.registerSavedStateProvider(stateKey) { saveState() }
            registry.consumeRestoredStateForKey(stateKey)?.let { state ->
                state.getBundle(PlaybackStateKeys.PLAYBACK)?.let {
                    controller.restoreState(it.readPlaybackState())
                    restoredPlayback = true
                }
                @Suppress("DEPRECATION")
                val scroll = state.getParcelable<android.os.Parcelable>(PlaybackStateKeys.SCROLL)
                if (pager == null) recycler.layoutManager?.onRestoreInstanceState(scroll)
            }
        }
        if (pager != null) {
            pendingPagerSelection = if (restoredPlayback) controller.selectedIndex else pager.currentItem
            restorePagerSelection()
        }
        recycler.setTag(R.id.sloth_video_feed_binding, this)
        recycler.addOnChildAttachStateChangeListener(childListener)
        recycler.addOnScrollListener(scrollListener)
        recycler.addOnLayoutChangeListener(layoutListener)
        adapter.registerAdapterDataObserver(dataObserver)
        pager?.registerOnPageChangeCallback(pageListener)
        owner.lifecycle.addObserver(this)
        scheduleRefresh()
    }

    /** Update the adapter first; for ListAdapter call this from submitList's commit callback. */
    fun submitFeed(media: List<VideoMedia>) {
        checkOpen()
        require(adapter.itemCount == media.size) { "Update adapter data before submitting the matching media" }
        invalidateHosts()
        items = media.toList()
        controller.submitFeed(items)
        restorePagerSelection()
        scheduleRefresh()
    }

    private fun restorePagerSelection() {
        val position = pendingPagerSelection ?: return
        if (items.isEmpty()) return
        pager?.setCurrentItem(position.coerceIn(items.indices), false)
        pendingPagerSelection = null
    }

    /** False pauses hidden tabs even when their lifecycle is still STARTED. */
    fun setVisible(value: Boolean) {
        checkOpen()
        visible = value
        if (!value) updateVisibility(false) else scheduleRefresh()
    }

    /** Bind reusable controls; provide an Activity to also manage full-screen entry and restoration. */
    fun bindControls(view: PlayerControlView, activity: Activity? = null) {
        checkOpen()
        check(view.getTag(R.id.sloth_control_binding).let { it == null || it === this }) { "Unbind controls before moving them to another feed" }
        clearControls()
        controls = view
        view.setTag(R.id.sloth_control_binding, this)
        view.bind(controller)
        if (activity != null) {
            val coordinator = FullScreenCoordinator(activity, owner)
            fullScreen = coordinator
            coordinator.onFullScreenChanged = { active ->
                view.isFullScreen = active
                if (active) {
                    selectionBeforeFullScreen = selectionEnabled
                    setSelectionEnabled(false)
                    pagerInputBeforeFullScreen = pager?.isUserInputEnabled
                    pager?.isUserInputEnabled = false
                } else {
                    selectionBeforeFullScreen?.let { setSelectionEnabled(it) }
                    selectionBeforeFullScreen = null
                    pagerInputBeforeFullScreen?.let { pager?.isUserInputEnabled = it }
                    pagerInputBeforeFullScreen = null
                }
            }
            view.onFullScreenClick = {
                if (coordinator.isFullScreen) coordinator.exit()
                else (recycler.findViewHolderForAdapterPosition(controller.selectedIndex) as? VideoViewHolder)?.let {
                    coordinator.enter(it.videoHost, controller)
                }
            }
        }
    }

    /** Detach a shared panel before binding it to another feed. Playback remains owned by this binding. */
    fun unbindControls() { checkOpen(); clearControls() }

    private fun clearControls() {
        fullScreen?.close()
        fullScreen = null
        controls?.apply {
            setTag(R.id.sloth_control_binding, null)
            onFullScreenClick = null
            isFullScreen = false
            bind(null)
        }
        controls = null
    }

    /** Temporarily freeze selection while the current host is displayed in a full-screen overlay. */
    fun setSelectionEnabled(value: Boolean) {
        checkOpen()
        selectionEnabled = value
        if (!value) recycler.stopScroll()
        if (value) scheduleRefresh()
    }

    private fun invalidateHosts() {
        updateVisibility(false)
        hosts.keys.toList().forEach(::unregister)
        scheduleRefresh()
    }

    private fun unregister(holder: VideoViewHolder) {
        hosts.remove(holder)
        controller.unregisterHost(holder)
    }

    private fun scheduleRefresh() {
        if (closed) return
        recycler.removeCallbacks(update)
        recycler.post(update)
    }

    private fun refresh() {
        if (closed) return
        check(recycler.adapter === adapter) { "Close the playback binding before replacing its adapter" }
        if (adapter.itemCount != items.size) { updateVisibility(false); return }
        val attached = (0 until recycler.childCount).mapNotNull {
            recycler.getChildViewHolder(recycler.getChildAt(it)) as? VideoViewHolder
        }.filter { it.absoluteAdapterPosition in items.indices }
        hosts.keys.filter { it !in attached }.toList().forEach(::unregister)
        for (holder in attached) {
            val position = holder.absoluteAdapterPosition
            val item = items[position]
            val old = hosts[holder]
            if (old?.position == position && old.item === item) continue
            unregister(holder)
            hosts[holder] = Host(position, item)
            controller.registerHost(holder, position, item, holder.videoHost)
        }
        if (!started || !visible) { updateVisibility(false); return }
        if (!selectionEnabled) return
        val selected = if (pager != null) pager.currentItem.takeIf { it in items.indices }
            else if (recycler.scrollState == RecyclerView.SCROLL_STATE_IDLE) mostVisiblePosition() else return
        if (selected == null) updateVisibility(false)
        else {
            if (controller.selectedIndex != selected) controller.select(selected)
            updateVisibility(true)
        }
    }

    private fun updateVisibility(value: Boolean) {
        if (playbackVisible == value) return
        playbackVisible = value
        controller.setVisible(value)
    }

    private fun mostVisiblePosition(): Int? {
        val viewport = Rect()
        if (!recycler.getGlobalVisibleRect(viewport)) return null
        val candidates = hosts.mapNotNull { (holder, host) ->
            if (!host.item.playable) return@mapNotNull null
            val rect = Rect()
            if (!holder.videoHost.getGlobalVisibleRect(rect) || !rect.intersect(viewport)) return@mapNotNull null
            VisibleVideo(host.position, rect.width().toLong() * rect.height())
        }
        return selectVisibleVideo(candidates, controller.selectedIndex)
    }

    override fun onStart(owner: LifecycleOwner) {
        started = true
        val state = pager?.scrollState ?: recycler.scrollState
        controller.setScrolling(state != RecyclerView.SCROLL_STATE_IDLE, state == RecyclerView.SCROLL_STATE_SETTLING)
        scheduleRefresh()
    }
    override fun onStop(owner: LifecycleOwner) {
        started = false
        recycler.removeCallbacks(update)
        updateVisibility(false)
    }
    override fun onDestroy(owner: LifecycleOwner) = close()

    private fun saveState() = Bundle().apply {
        putBundle(PlaybackStateKeys.PLAYBACK, controller.saveState().toBundle())
        if (pager == null) putParcelable(PlaybackStateKeys.SCROLL, recycler.layoutManager?.onSaveInstanceState())
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        clearControls()
        closed = true
        owner.lifecycle.removeObserver(this)
        recycler.removeCallbacks(update)
        recycler.removeOnChildAttachStateChangeListener(childListener)
        recycler.removeOnScrollListener(scrollListener)
        recycler.removeOnLayoutChangeListener(layoutListener)
        adapter.unregisterAdapterDataObserver(dataObserver)
        pager?.unregisterOnPageChangeCallback(pageListener)
        if (stateKey != null) registry?.unregisterSavedStateProvider(stateKey)
        controller.close()
        hosts.clear()
        recycler.setTag(R.id.sloth_video_feed_binding, null)
    }
    private fun checkOpen() { checkMainThread(); check(!closed) { "Playback binding is closed" } }
    private fun checkMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "Use playback bindings on the main thread" } }
}

/** Binds a card feed. Holders extend [VideoViewHolder]; playback follows the largest visible video at idle. */
fun RecyclerView.bindVideoFeed(
    owner: LifecycleOwner,
    media: List<VideoMedia>,
    stateKey: String? = null,
    initiallyMuted: Boolean = true,
    loop: Boolean = true,
): VideoFeedBinding = VideoFeedBinding(this, owner, media, null, stateKey, initiallyMuted, loop)

/** Binds a short-video pager, including adjacent warmup and scroll previews. */
fun ViewPager2.bindVideoFeed(
    owner: LifecycleOwner,
    media: List<VideoMedia>,
    stateKey: String? = null,
    initiallyMuted: Boolean = true,
    loop: Boolean = true,
): VideoFeedBinding = VideoFeedBinding(getChildAt(0) as RecyclerView, owner, media, this, stateKey, initiallyMuted, loop)
