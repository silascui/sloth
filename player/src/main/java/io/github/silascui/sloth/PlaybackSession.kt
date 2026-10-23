package io.github.silascui.sloth

/** 内容恢复状态不持有页面、视图或播放器；时间统一为毫秒。 */
class PlaybackSession(val mediaId: String, initialPositionMs: Long = 0L) {
    var positionMs = initialPositionMs.coerceAtLeast(0L)
        private set
    var userPaused = false
    var speed = 1F
    var muted = true

    fun savePosition(positionMs: Long, ended: Boolean = false) {
        this.positionMs = if (ended) 0L else positionMs.coerceAtLeast(0L)
    }
}
