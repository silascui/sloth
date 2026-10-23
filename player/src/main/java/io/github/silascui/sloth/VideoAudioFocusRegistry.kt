package io.github.silascui.sloth

import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.silascui.sloth.MediaAudioFocusManager.Companion.PLAYER_COMMAND_DO_NOT_PLAY
import io.github.silascui.sloth.MediaAudioFocusManager.Companion.PLAYER_COMMAND_PLAY_WHEN_READY

/**
 * @author zhuo
 * @date 2024/12/16
 */
@UnstableApi
internal object VideoAudioFocusRegistry {

    val hashMap = HashMap<String, MediaAudioFocusManager>()
    private var currentFocusManager: MediaAudioFocusManager? = null
    fun createAudioFocusManager(key: String, manager: MediaAudioFocusManager) {
        hashMap[key] = manager
    }

    fun updateAudioFocus(key: String, playWhenReady: Boolean, playbackState: @Player.State Int): Int {
        PlayerLog.d("updateAudioFocus ========= key:$key")
        val focusManager = hashMap[key]
        val focus = focusManager?.updateAudioFocus(playWhenReady, playbackState) ?: PLAYER_COMMAND_DO_NOT_PLAY
        when (focus) {
            PLAYER_COMMAND_PLAY_WHEN_READY -> {
                PlayerLog.d("updateAudioFocus ========= $PLAYER_COMMAND_PLAY_WHEN_READY")
                if (focusManager != currentFocusManager) {
                    currentFocusManager?.handlePlatformAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
                    currentFocusManager = focusManager
                }
            }

            else -> Unit
        }
        return focus
    }

    fun pauseForGlobalAudio() {
        hashMap.values.toList().forEach { it.handlePlatformAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS) }
        currentFocusManager = null
    }

    fun removeAudioFocus(key: String) {
        val removed = hashMap.remove(key)
        if (currentFocusManager === removed) currentFocusManager = null
        removed?.release()
    }

    fun getAudioFocusManager(key: String): MediaAudioFocusManager? {
        return hashMap[key]
    }

}
