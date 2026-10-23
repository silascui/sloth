package io.github.silascui.sloth.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import io.github.silascui.sloth.VideoHostView

/**
 * A holder supplies a render host; the binding owns registration and playback.
 * The default container keeps the adapter-owned item in place during full-screen transfers.
 * Custom item views should likewise contain [videoHost] rather than use it as their root.
 */
open class VideoViewHolder(
    val videoHost: VideoHostView,
    itemView: View = containerFor(videoHost),
) : RecyclerView.ViewHolder(itemView)

private fun containerFor(host: VideoHostView): View = FrameLayout(host.context).apply {
    layoutParams = host.layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}
