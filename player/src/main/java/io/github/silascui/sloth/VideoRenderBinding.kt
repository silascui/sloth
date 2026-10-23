package io.github.silascui.sloth

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** 引擎拥有输出 Surface；宿主只借用 TextureView，解绑 UI 不同步清除解码输出。 */
@SuppressLint("UnsafeOptInUsageError")
internal class VideoRenderBinding(context: Context, val engine: ExoPlayer) {
    private data class Host(val view: View, val container: ViewGroup, val placeholder: View, val index: Int)
    private var host: Host? = null
    private var texture: SurfaceTexture? = null
    private var output: Surface? = null
    private var released = false
    private val playbackHandler = Handler(engine.playbackLooper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasFrame = false
    private val render = TextureView(context.applicationContext)
    val player: Player = PresentationPlayer(engine, this)
    private val events = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { hasFrame = false }
        override fun onRenderedFirstFrame() { hasFrame = true; revealShutter() }
        override fun onVideoSizeChanged(videoSize: VideoSize) { updateAspectRatio(videoSize) }
    }

    init {
        engine.addListener(events)
        render.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                val retained = texture
                if (retained != null && retained !== value) {
                    // 跨窗口重新挂载也使用原 consumer，不交接或提前释放 renderer 正在使用的 Surface。
                    render.setSurfaceTexture(retained)
                    return
                }
                texture = value
                if (output == null) {
                    output = Surface(value).also(engine::setVideoSurface)
                }
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean = false
        }
    }

    fun attach(view: PlayerView) {
        if (host?.view === view) return
        val placeholder = requireNotNull(view.videoSurfaceView)
        val parent = placeholder.parent as ViewGroup
        attach(Host(view, parent, placeholder, parent.indexOfChild(placeholder)))
    }

    private fun attach(value: Host) {
        check(!released)
        detach()
        host = value
        // 首次绑定借用宿主已经就绪的 texture，避免新建 TextureView 等下一帧才能预热。
        // 之后该 consumer 只归引擎所有，宿主原 View 不再持有它。
        val original = value.placeholder as? TextureView
        val adopted = if (texture == null && original?.isAvailable == true) original.surfaceTexture else null
        if (adopted != null) original?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = false
        }
        value.container.removeView(value.placeholder)
        if (adopted != null) {
            original?.surfaceTextureListener = null
            texture = adopted
            if (output == null) output = Surface(adopted).also(engine::setVideoSurface)
        }
        val retained = texture
        if (!render.isAvailable && retained != null && render.surfaceTexture !== retained) render.setSurfaceTexture(retained)
        value.container.addView(render, value.index.coerceAtLeast(0), FrameLayout.LayoutParams(-1, -1))
        updateAspectRatio(engine.videoSize)
    }

    fun detach(view: View? = null) {
        val previous = host ?: return
        if (view != null && previous.view !== view) return
        // 先撤销租约，旧宿主迟到的解绑不能影响新的宿主。
        host = null
        previous.container.removeView(render)
        previous.container.addView(previous.placeholder, previous.index.coerceIn(0, previous.container.childCount))
    }

    private fun updateAspectRatio(size: VideoSize) {
        val current = host ?: return
        var ratio = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0F
        val rotation = size.unappliedRotationDegrees
        if (rotation == QUARTER_TURN_DEGREES || rotation == THREE_QUARTER_TURN_DEGREES) ratio = if (ratio == 0F) 0F else 1F / ratio
        when (val container = current.container) {
            is androidx.media3.ui.AspectRatioFrameLayout -> container.setAspectRatio(ratio)
        }
        val matrix = Matrix()
        if (rotation != 0 && render.width > 0 && render.height > 0) {
            val cx = render.width / 2F
            val cy = render.height / 2F
            matrix.postRotate(rotation.toFloat(), cx, cy)
            val rect = android.graphics.RectF(0F, 0F, render.width.toFloat(), render.height.toFloat())
            matrix.mapRect(rect)
            matrix.postScale(render.width / rect.width(), render.height / rect.height(), cx, cy)
        }
        render.setTransform(matrix)
    }

    private fun revealShutter() {
        if (!hasFrame) return
        when (val view = host?.view) {
            is PlayerView -> view.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)?.visibility = View.INVISIBLE
        }
    }

    /** 在 release 消息之后确认播放队列已处理，不能仅依据 isReleased 推断超时释放已完成。 */
    fun releaseAfterEngine() {
        check(engine.isReleased)
        if (released) return
        detach()
        engine.removeListener(events)
        released = true
        val dispose = Runnable {
            render.surfaceTextureListener = null
            output?.release()
            output = null
            texture?.release()
            texture = null
        }
        // 共享 playback Looper 仍存活时，排在 renderer 的 release 之后再归还输出。
        // 独占 Looper 已退出队列时，release 已关闭该队列，不再有 renderer 使用输出。
        if (!playbackHandler.post { mainHandler.post(dispose) }) dispose.run()
    }

    private class PresentationPlayer(engine: Player, val binding: VideoRenderBinding) : ForwardingPlayer(engine) {
        override fun isCommandAvailable(command: Int) = command != Player.COMMAND_SET_VIDEO_SURFACE && super.isCommandAvailable(command)
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .remove(Player.COMMAND_SET_VIDEO_SURFACE).build()
    }

    companion object {
        private const val QUARTER_TURN_DEGREES = 90
        private const val THREE_QUARTER_TURN_DEGREES = 270

        fun texture(player: Player?): TextureView? = (player as? PresentationPlayer)?.binding?.render

        fun engine(player: Player?): Player? = if (player is PresentationPlayer) player.binding.engine else player

        fun bind(view: PlayerView, player: Player?) {
            val old = view.player as? PresentationPlayer
            if (old !== player) old?.binding?.detach(view)
            val next = player as? PresentationPlayer
            next?.binding?.attach(view)
            view.player = player
            next?.binding?.updateAspectRatio(next.binding.engine.videoSize)
            next?.binding?.revealShutter()
        }
    }
}
