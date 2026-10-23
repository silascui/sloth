package io.github.silascui.sloth.sample

import android.app.Application
import io.github.silascui.sloth.VideoPlaybackRuntime

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Production apps initialize after their own privacy/consent flow.
        VideoPlaybackRuntime.initialize(this)
    }
}
