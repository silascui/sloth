package io.github.silascui.sloth.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.silascui.sloth.PlaybackBlockReason
import io.github.silascui.sloth.PlaybackController
import io.github.silascui.sloth.PlaybackState
import io.github.silascui.sloth.PlaybackStatus
import java.util.concurrent.TimeUnit

/** Reusable controls. Observes only while attached and never accesses the underlying Player. */
class PlayerControlView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private var controller: PlaybackController? = null
    private var subscription: AutoCloseable? = null
    private var snapshot = PlaybackState()
    private var scrubbing = false
    private var scrubMediaId: String? = null
    private val play: Button
    private val retry: Button
    private val mute: Button
    private val speed: Button
    private val fullscreen: Button
    private val seek: SeekBar
    private val time: TextView
    private val status: TextView
    var onFullScreenClick: (() -> Unit)? = null
        set(value) { field = value; fullscreen.visibility = if (value == null) GONE else VISIBLE }
    var isFullScreen: Boolean = false
        set(value) { field = value; fullscreen.setText(if (value) R.string.sloth_exit_fullscreen else R.string.sloth_fullscreen) }

    init {
        orientation = VERTICAL
        setBackgroundColor(CONTROL_BACKGROUND_COLOR)
        val padding = (CONTROL_PADDING_DP * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        inflate(context, R.layout.sloth_player_controls, this)
        play = findViewById(R.id.sloth_control_play)
        retry = findViewById(R.id.sloth_control_retry)
        mute = findViewById(R.id.sloth_control_mute)
        speed = findViewById(R.id.sloth_control_speed)
        fullscreen = findViewById(R.id.sloth_control_fullscreen)
        seek = findViewById(R.id.sloth_control_seek)
        time = findViewById(R.id.sloth_control_time)
        status = findViewById(R.id.sloth_control_status)
        seek.max = SEEK_PROGRESS_STEPS
        play.setOnClickListener {
            if (snapshot.status == PlaybackStatus.ENDED) controller?.replay() else controller?.togglePlayback()
        }
        retry.setOnClickListener { controller?.retry() }
        mute.setOnClickListener { controller?.setMuted(!snapshot.muted) }
        speed.setOnClickListener { controller?.setSpeed(SPEED_OPTIONS.firstOrNull { it > snapshot.speed } ?: SPEED_OPTIONS.first()) }
        fullscreen.setOnClickListener { onFullScreenClick?.invoke() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { scrubbing = true; scrubMediaId = snapshot.mediaId }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // Keyboard/accessibility changes do not issue touch tracking callbacks.
                if (fromUser && !scrubbing) seekFromProgress(progress)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (snapshot.canSeek && snapshot.mediaId == scrubMediaId) {
                    seekFromProgress(seekBar.progress)
                }
                scrubbing = false
                scrubMediaId = null
                render(snapshot)
            }
        })
        render(snapshot)
    }

    fun bind(controller: PlaybackController?) {
        subscription?.close()
        subscription = null
        this.controller = controller
        scrubbing = false
        scrubMediaId = null
        render(controller?.state ?: PlaybackState())
        if (isAttachedToWindow) subscribe()
    }

    private fun seekFromProgress(progress: Int) {
        if (!snapshot.canSeek || snapshot.durationMs <= 0L) return
        controller?.seekTo((snapshot.durationMs * (progress.toDouble() / SEEK_PROGRESS_STEPS)).toLong())
    }

    private fun subscribe() {
        subscription?.close()
        subscription = controller?.observeState(::render)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); subscribe() }
    override fun onDetachedFromWindow() {
        subscription?.close()
        subscription = null
        scrubbing = false
        super.onDetachedFromWindow()
    }

    private fun render(state: PlaybackState) {
        snapshot = state
        play.setText(when {
            state.status == PlaybackStatus.ENDED -> R.string.sloth_replay
            state.playWhenReady -> R.string.sloth_pause
            else -> R.string.sloth_play
        })
        play.isEnabled = state.canPlay || state.canPause
        retry.visibility = if (state.status == PlaybackStatus.ERROR) VISIBLE else GONE
        retry.isEnabled = state.canRetry
        fullscreen.isEnabled = state.canPlay || state.isPlaying
        val canConfigure = state.mediaId != null && state.status != PlaybackStatus.CLOSED
        mute.isEnabled = canConfigure
        speed.isEnabled = canConfigure
        mute.setText(if (state.muted) R.string.sloth_unmute else R.string.sloth_mute)
        speed.text = context.getString(R.string.sloth_speed, state.speed)
        seek.isEnabled = state.canSeek && state.durationMs > 0L
        if (!scrubbing) seek.progress = if (state.durationMs > 0L) {
            ((state.positionMs.toDouble() / state.durationMs) * SEEK_PROGRESS_STEPS).toInt().coerceIn(0, SEEK_PROGRESS_STEPS)
        } else 0
        seek.secondaryProgress = if (state.durationMs > 0L) {
            ((state.bufferedPositionMs.toDouble() / state.durationMs) * SEEK_PROGRESS_STEPS).toInt().coerceIn(0, SEEK_PROGRESS_STEPS)
        } else 0
        val positionSeconds = TimeUnit.MILLISECONDS.toSeconds(state.positionMs)
        val durationSeconds = TimeUnit.MILLISECONDS.toSeconds(state.durationMs)
        time.text = context.getString(R.string.sloth_time, positionSeconds / SECONDS_PER_MINUTE,
            positionSeconds % SECONDS_PER_MINUTE, durationSeconds / SECONDS_PER_MINUTE, durationSeconds % SECONDS_PER_MINUTE)
        status.text = state.error?.let { context.getString(R.string.sloth_error, it.code) }
            ?: context.getString(statusText(state))
    }

    private fun statusText(state: PlaybackState): Int = when (state.blockReason) {
        PlaybackBlockReason.HIDDEN -> R.string.sloth_hidden
        PlaybackBlockReason.HOST_UNAVAILABLE -> R.string.sloth_waiting_host
        PlaybackBlockReason.MEDIA_UNAVAILABLE -> R.string.sloth_unavailable
        PlaybackBlockReason.POLICY -> R.string.sloth_waiting_policy
        PlaybackBlockReason.INTERRUPTED -> R.string.sloth_interrupted
        PlaybackBlockReason.SCROLLING -> R.string.sloth_scrolling
        PlaybackBlockReason.NONE -> when (state.status) {
            PlaybackStatus.IDLE -> R.string.sloth_idle
            PlaybackStatus.WAITING -> R.string.sloth_waiting
            PlaybackStatus.READY -> R.string.sloth_ready
            PlaybackStatus.BUFFERING -> R.string.sloth_buffering
            PlaybackStatus.PLAYING -> R.string.sloth_playing
            PlaybackStatus.PAUSED -> R.string.sloth_paused
            PlaybackStatus.ENDED -> R.string.sloth_ended
            PlaybackStatus.ERROR -> R.string.sloth_waiting
            PlaybackStatus.CLOSED -> R.string.sloth_closed
        }
    }

    private companion object {
        const val SEEK_PROGRESS_STEPS = 1_000
        const val SECONDS_PER_MINUTE = 60
        const val CONTROL_PADDING_DP = 8
        const val CONTROL_BACKGROUND_COLOR = 0xE6222222.toInt()
        val SPEED_OPTIONS = floatArrayOf(0.5f, 1f, 1.5f, 2f)
    }
}
