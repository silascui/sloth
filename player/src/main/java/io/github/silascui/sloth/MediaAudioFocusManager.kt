/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.silascui.sloth

import android.os.Build
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

@UnstableApi
internal class MediaAudioFocusManager(context: Context, private var playerControl: PlayerControl?) {
    /** Interface to allow MediaAudioFocusManager to give commands to a player.  */
    interface PlayerControl {

        fun setVolumeMultiplier(volumeMultiplier: Float)

        /**
         * Called when a command must be executed on the player.
         *
         * @param playerCommand The command that must be executed.
         */
        fun executePlayerCommand(playerCommand: Int)
    }

    private val audioManager = Assertions.checkNotNull(
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    )
    private val focusListener: AudioFocusListener = AudioFocusListener()
    private var audioAttributes: AudioAttributes = AudioAttributes.Builder().run {
        setUsage(AudioAttributes.USAGE_MEDIA)
        setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        build()
    }

    private var audioFocusState: Int
    private var focusGainToRequest = AUDIOFOCUS_GAIN

    /** Gets the current player volume multiplier.  */
    var volumeMultiplier: Float = VOLUME_MULTIPLIER_DEFAULT

    private var audioFocusRequest: AudioFocusRequest? = null
    private var rebuildAudioFocusRequest = false

    init {
        this.audioFocusState = AUDIO_FOCUS_STATE_NO_FOCUS
    }

    /**
     * 改变焦点
     */
    fun updateAudioFocus(playWhenReady: Boolean, playbackState: @Player.State Int): Int {
        PlayerLog.d("updateAudioFocus ,playWhenReady:$playWhenReady ,playbackState:$playbackState")
        if (shouldAbandonAudioFocusIfHeld(playbackState)) {
            abandonAudioFocusIfHeld()
            return if (playWhenReady) PLAYER_COMMAND_PLAY_WHEN_READY else PLAYER_COMMAND_DO_NOT_PLAY
        }
        return if (playWhenReady) requestAudioFocus() else PLAYER_COMMAND_DO_NOT_PLAY
    }

    /**
     * Called when the manager is no longer required. Audio focus will be released without making any
     * calls to the [PlayerControl].
     */
    fun release() {
        playerControl = null
        abandonAudioFocusIfHeld()
    }

    // Internal methods.
    @VisibleForTesting
    fun getFocusListener(): OnAudioFocusChangeListener {
        return focusListener
    }

    private fun shouldAbandonAudioFocusIfHeld(playbackState: @Player.State Int): Boolean {
        return playbackState == Player.STATE_IDLE || focusGainToRequest != AUDIOFOCUS_GAIN
    }

    /**
     * 请求焦点
     */
    private fun requestAudioFocus(): Int {
        PlayerLog.d(TAG, "requestAudioFocus , audioFocusState:$audioFocusState")
        if (audioFocusState == AUDIO_FOCUS_STATE_HAVE_FOCUS) {
            return PLAYER_COMMAND_PLAY_WHEN_READY
        }
        val requestResult = if (Util.SDK_INT >= Build.VERSION_CODES.O) requestAudioFocusV26() else requestAudioFocusDefault()
        if (requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            setAudioFocusState(AUDIO_FOCUS_STATE_HAVE_FOCUS)
            return PLAYER_COMMAND_PLAY_WHEN_READY
        } else {
            setAudioFocusState(AUDIO_FOCUS_STATE_NO_FOCUS)
            return PLAYER_COMMAND_DO_NOT_PLAY
        }
    }

    /**
     * 放弃焦点
     */
    private fun abandonAudioFocusIfHeld() {
        if (audioFocusState == AUDIO_FOCUS_STATE_NO_FOCUS) {
            return
        }
        if (Util.SDK_INT >= Build.VERSION_CODES.O) {
            abandonAudioFocusV26()
        } else {
            abandonAudioFocusDefault()
        }
        setAudioFocusState(AUDIO_FOCUS_STATE_NO_FOCUS)
    }

    /**
     * 请求焦点
     */
    private fun requestAudioFocusDefault(): Int {
        return audioManager.requestAudioFocus(
            focusListener,
            Util.getStreamTypeForAudioUsage(Assertions.checkNotNull(audioAttributes).usage),
            focusGainToRequest
        )
    }

    /**
     * 请求焦点
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocusV26(): Int {
        if (audioFocusRequest == null || rebuildAudioFocusRequest) {
            val builder =
                if (audioFocusRequest == null
                ) {
                    AudioFocusRequest.Builder(focusGainToRequest)
                } else {
                    AudioFocusRequest.Builder(audioFocusRequest!!)
                }

            val willPauseWhenDucked = willPauseWhenDucked()
            audioFocusRequest =
                builder
                    .setAudioAttributes(audioAttributes)
                    .setWillPauseWhenDucked(willPauseWhenDucked)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()

            rebuildAudioFocusRequest = false
        }
        return audioManager.requestAudioFocus(audioFocusRequest!!)
    }

    /**
     * 放弃焦点
     */
    private fun abandonAudioFocusDefault() {
        audioManager.abandonAudioFocus(focusListener)
    }

    /**
     * 放弃焦点
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun abandonAudioFocusV26() {
        if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest!!)
        }
    }

    private fun willPauseWhenDucked(): Boolean {
        return audioAttributes != null && audioAttributes!!.contentType == C.AUDIO_CONTENT_TYPE_SPEECH
    }

    private fun setAudioFocusState(audioFocusState: Int) {
        if (this.audioFocusState == audioFocusState) {
            return
        }
        this.audioFocusState = audioFocusState

        val volumeMultiplier =
            if ((audioFocusState == AUDIO_FOCUS_STATE_LOSS_TRANSIENT_DUCK)
            ) {
                VOLUME_MULTIPLIER_DUCK
            } else {
                VOLUME_MULTIPLIER_DEFAULT
            }
        if (this.volumeMultiplier == volumeMultiplier) {
            return
        }
        this.volumeMultiplier = volumeMultiplier
        playerControl?.setVolumeMultiplier(volumeMultiplier)
    }

    fun handlePlatformAudioFocusChange(focusChange: Int) {
        PlayerLog.d("handlePlatformAudioFocusChange ,focusChange:$focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                setAudioFocusState(AUDIO_FOCUS_STATE_HAVE_FOCUS)
                executePlayerCommand(PLAYER_COMMAND_PLAY_WHEN_READY)
                return
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                executePlayerCommand(PLAYER_COMMAND_DO_NOT_PLAY)
                abandonAudioFocusIfHeld()
                return
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || willPauseWhenDucked()) {
                    executePlayerCommand(PLAYER_COMMAND_WAIT_FOR_CALLBACK)
                    setAudioFocusState(AUDIO_FOCUS_STATE_LOSS_TRANSIENT)
                } else {
                    setAudioFocusState(AUDIO_FOCUS_STATE_LOSS_TRANSIENT_DUCK)
                }
                return
            }

            else -> {
            }
        }
    }

    private fun executePlayerCommand(playerCommand: Int) {
        playerControl?.executePlayerCommand(playerCommand)
    }

    // Internal audio focus listener.
    private inner class AudioFocusListener : OnAudioFocusChangeListener {
        override fun onAudioFocusChange(focusChange: Int) {
            handlePlatformAudioFocusChange(focusChange)
        }
    }

    companion object {
        /** Do not play.  */
        const val PLAYER_COMMAND_DO_NOT_PLAY: Int = -1

        /** Do not play now. Wait for callback to play.  */
        const val PLAYER_COMMAND_WAIT_FOR_CALLBACK: Int = 0

        /** Play freely.  */
        const val PLAYER_COMMAND_PLAY_WHEN_READY: Int = 1

        /** No audio focus is currently being held.  */
        private const val AUDIO_FOCUS_STATE_NO_FOCUS = 0

        /** The requested audio focus is currently held.  */
        private const val AUDIO_FOCUS_STATE_HAVE_FOCUS = 1

        /** Audio focus has been temporarily lost.  */
        private const val AUDIO_FOCUS_STATE_LOSS_TRANSIENT = 2

        /** Audio focus has been temporarily lost, but playback may continue with reduced volume.  */
        private const val AUDIO_FOCUS_STATE_LOSS_TRANSIENT_DUCK = 3


        /**
         * @see AudioManager.AUDIOFOCUS_GAIN
         */
        private const val AUDIOFOCUS_GAIN = AudioManager.AUDIOFOCUS_GAIN

        private const val TAG = "MediaAudioFocusManager"

        private const val VOLUME_MULTIPLIER_DUCK = 0.2f
        private const val VOLUME_MULTIPLIER_DEFAULT = 1.0f
    }
}
