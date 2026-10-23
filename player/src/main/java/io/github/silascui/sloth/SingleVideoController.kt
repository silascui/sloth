package io.github.silascui.sloth

/** A detail page shares the feed command/state protocol and process ownership. */
class SingleVideoController(initiallyMuted: Boolean = false, loop: Boolean = false) : PlaybackController {
    private val feed = FeedPlaybackController(initiallyMuted = initiallyMuted, loop = loop)
    private val token = Any()
    override val state: PlaybackState get() = feed.state
    val currentHandle: PlaybackHandle? get() = feed.currentHandle
    fun load(media: VideoMedia, target: FeedRenderTarget) {
        feed.unregisterHost(token)
        feed.submitFeed(listOf(media))
        feed.registerHost(token, 0, media, target)
    }
    fun setVisible(visible: Boolean) = feed.setVisible(visible)
    fun saveState(): FeedPlaybackState = feed.saveState()
    fun restoreState(state: FeedPlaybackState) = feed.restoreState(state)
    override fun observeState(observer: (PlaybackState) -> Unit) = feed.observeState(observer)
    override fun play() = feed.play()
    override fun pause() = feed.pause()
    override fun retry() = feed.retry()
    override fun replay() = feed.replay()
    fun toggle() = togglePlayback()
    override fun seekTo(positionMs: Long) = feed.seekTo(positionMs)
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun setMuted(muted: Boolean) = feed.setMuted(muted)
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun setSpeed(speed: Float) = feed.setSpeed(speed)
    override fun close() = feed.close()
}
