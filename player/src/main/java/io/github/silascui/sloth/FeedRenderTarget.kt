package io.github.silascui.sloth

import android.content.Context
import android.graphics.SurfaceTexture
import androidx.media3.common.Player

/** View-independent host contract. A feed host can render at most one current or warm lease. */
interface FeedRenderTarget : PlaybackRenderTarget {
    fun renderingContext(): Context
    fun isHostAttached(): Boolean
    fun outputReady(): Boolean
    fun outputTexture(): SurfaceTexture?
    var onPlaybackToggle: (() -> Unit)?
    var onPlaybackSeek: ((Long) -> Unit)?
    var onPlaybackHostAttached: (() -> Unit)?
    fun setFeedScrolling(scrolling: Boolean)
    fun setShowPlayerButton(show: Boolean)
    fun bindWarmup(token: Any, player: Player)
    fun unbindWarmup(token: Any)
    fun promoteWarmup(token: Any)
}
