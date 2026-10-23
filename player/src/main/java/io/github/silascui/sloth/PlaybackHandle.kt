package io.github.silascui.sloth

/** 一次使用权的凭证。转交后旧句柄的命令、解绑和关闭都失效。 */
class PlaybackHandle internal constructor(
    internal val session: PlaybackSession,
    internal var onPlayRequest: (() -> Unit)?,
    internal var onEnded: (() -> Unit)?,
    internal var onRevoked: (() -> Unit)?,
    internal val floating: Boolean = false,
) {
    internal val owner = object : PlaybackOwnership.Owner {
        override val floating get() = this@PlaybackHandle.floating
        override fun revoke() = VideoPlaybackRuntime.revoke(this@PlaybackHandle)
    }
    internal val startupStages = PlaybackStartupTrace.Stages()
    internal var startupAttempt: PlaybackAttempt? = null
    internal var onStateChanged: (() -> Unit)? = null
    internal var target: PlaybackRenderTarget? = null
    internal var closed = false
    val isActive get() = VideoPlaybackRuntime.isActive(this)
    val positionMs get() = VideoPlaybackRuntime.position(this)
    val durationMs get() = VideoPlaybackRuntime.duration(this)
    val videoSize get() = VideoPlaybackRuntime.videoSize(this)
    val isPlaying get() = VideoPlaybackRuntime.isPlaying(this)

    fun bind(target: PlaybackRenderTarget) = VideoPlaybackRuntime.bind(this, target)
    fun unbind(target: PlaybackRenderTarget) = VideoPlaybackRuntime.unbind(this, target)
    fun togglePlayback() {
        if (!isActive) return
        if (!VideoPlaybackRuntime.hasError(this) && (isPlaying || VideoPlaybackRuntime.wantsToPlay(this))) pause() else onPlayRequest?.invoke()
    }
    fun pause() = VideoPlaybackRuntime.pause(this, user = true)
    fun seekTo(positionMs: Long) = VideoPlaybackRuntime.seek(this, positionMs)
    fun setSpeed(speed: Float) = VideoPlaybackRuntime.setSpeed(this, speed)
    fun setMuted(muted: Boolean) = VideoPlaybackRuntime.setMuted(this, muted)
    fun close() = VideoPlaybackRuntime.release(this)


    internal fun clearCallbacks() {
        onStateChanged = null
        onPlayRequest = null
        onEnded = null
        onRevoked = null
    }
}
