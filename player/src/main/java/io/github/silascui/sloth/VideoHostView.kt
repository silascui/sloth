package io.github.silascui.sloth

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/** Optional stock UI. Business overlays/cover loading remain owned by the host application. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
class VideoHostView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs), FeedRenderTarget {
    private val renderView: PlayerView
    val coverView: ImageView
    private val buffering: ProgressBar
    private val playButton: ImageButton
    val player: Player? get() = renderView.player
    private var handle: PlaybackHandle? = null
    private var warmToken: Any? = null
    private var scrolling = false
    private var blocked = false
    private var bufferingPending = false
    private var bufferingShown = false
    override var onPlaybackToggle: (() -> Unit)? = null
    override var onPlaybackSeek: ((Long) -> Unit)? = null
    override var onPlaybackHostAttached: (() -> Unit)? = null
    private val showBuffering = Runnable {
        bufferingPending = false
        if (shouldShowBuffering()) { bufferingShown = true; buffering.visibility = VISIBLE }
    }
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = updateBuffering()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateBuffering()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            clearBuffering(); blocked = true; updateButton()
        }
    }
    init {
        inflate(context, R.layout.avp_video_host, this)
        renderView = findViewById(R.id.avp_player)
        coverView = findViewById(R.id.avp_cover)
        buffering = findViewById(R.id.avp_buffering)
        playButton = findViewById(R.id.avp_play)
        renderView.setOnClickListener { onPlaybackToggle?.invoke() }
        playButton.setOnClickListener { onPlaybackToggle?.invoke() }
    }
    override fun renderingContext(): Context = context
    override fun isHostAttached(): Boolean = isAttachedToWindow
    override fun outputTexture(): SurfaceTexture? = surface()?.surfaceTexture
    override fun outputReady(): Boolean = surface()?.isAvailable == true
    private fun surface(): TextureView? = VideoRenderBinding.texture(player) ?: renderView.videoSurfaceView as? TextureView
    override fun setFeedScrolling(scrolling: Boolean) { this.scrolling = scrolling; updateButton() }
    override fun setShowPlayerButton(show: Boolean) { blocked = show; updateButton() }
    private fun updateButton() { playButton.visibility = if (blocked && !scrolling && handle != null) VISIBLE else GONE }
    override fun bindWarmup(token: Any, player: Player) {
        check(handle == null)
        warmToken = token; VideoRenderBinding.bind(renderView, player)
        clearBuffering(); coverView.visibility = VISIBLE; setShowPlayerButton(false)
    }
    override fun unbindWarmup(token: Any) {
        if (warmToken !== token) return
        warmToken = null; VideoRenderBinding.bind(renderView, null)
        clearBuffering(); coverView.visibility = VISIBLE
    }
    override fun promoteWarmup(token: Any) { if (warmToken === token) warmToken = null }
    override fun bindPlayback(handle: PlaybackHandle, player: Player?) {
        val changed = this.handle !== handle || this.player !== player
        this.handle = handle; warmToken = null
        if (changed) {
            this.player?.removeListener(listener); clearBuffering()
            VideoRenderBinding.bind(renderView, player); player?.addListener(listener)
            coverView.visibility = VISIBLE
        }
        updateBuffering(); updateButton()
    }
    override fun unbindPlayback(handle: PlaybackHandle) {
        if (this.handle !== handle) return
        player?.removeListener(listener); clearBuffering(); this.handle = null
        VideoRenderBinding.bind(renderView, null); coverView.visibility = VISIBLE; setShowPlayerButton(false)
    }
    override fun showPlaybackBlocked(blocked: Boolean) = setShowPlayerButton(blocked)
    override fun onPlaybackPreparing() { coverView.visibility = VISIBLE }
    override fun onPlaybackFirstFrame() { coverView.visibility = GONE }
    private fun shouldShowBuffering() = isAttachedToWindow && handle != null &&
        player?.let { it.playWhenReady && it.playbackState == Player.STATE_BUFFERING && it.playerError == null } == true
    private fun clearBuffering() {
        removeCallbacks(showBuffering); bufferingPending = false; bufferingShown = false; buffering.visibility = GONE
    }
    private fun updateBuffering() {
        if (!shouldShowBuffering()) { clearBuffering(); return }
        buffering.visibility = if (bufferingShown) VISIBLE else GONE
        if (!bufferingPending && !bufferingShown) { bufferingPending = true; postDelayed(showBuffering, BUFFERING_INDICATOR_DELAY_MS) }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); updateBuffering()
        post { if (isAttachedToWindow) onPlaybackHostAttached?.invoke() }
    }
    override fun onDetachedFromWindow() {
        VideoPlaybackRuntime.cancelWarmup(this); clearBuffering(); super.onDetachedFromWindow()
    }
    private companion object {
        private const val BUFFERING_INDICATOR_DELAY_MS = 300L
    }

}

/** Use when implementing a custom FeedRenderTarget or floating host around Media3 PlayerView. */
object PlayerRendering {
    fun bind(view: PlayerView, player: Player?) = VideoRenderBinding.bind(view, player)
    fun texture(player: Player?): TextureView? = VideoRenderBinding.texture(player)
}
