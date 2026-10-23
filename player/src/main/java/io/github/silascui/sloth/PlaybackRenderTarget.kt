package io.github.silascui.sloth

import androidx.media3.common.Player

/** 仅渲染层接触 Player，业务页面通过 PlaybackHandle 发命令。 */
interface PlaybackRenderTarget {
    fun bindPlayback(handle: PlaybackHandle, player: Player?)
    fun unbindPlayback(handle: PlaybackHandle)
    fun showPlaybackBlocked(blocked: Boolean)
    fun onPlaybackPreparing() {}
    fun onPlaybackFirstFrame()
}
