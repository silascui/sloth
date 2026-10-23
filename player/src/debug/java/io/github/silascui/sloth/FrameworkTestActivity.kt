package io.github.silascui.sloth

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/** Internal debug host, absent from the release library. */
class FrameworkTestActivity : AppCompatActivity() {
    lateinit var root: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); root = FrameLayout(this); setContentView(root)
    }
}
