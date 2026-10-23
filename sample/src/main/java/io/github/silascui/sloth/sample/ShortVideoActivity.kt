package io.github.silascui.sloth.sample

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.silascui.sloth.VideoHostView
import io.github.silascui.sloth.VideoMedia
import io.github.silascui.sloth.ui.PlayerControlView
import io.github.silascui.sloth.ui.VideoViewHolder
import io.github.silascui.sloth.ui.bindVideoFeed

/** No external URLs, user account, analytics SDK or server is needed to run this example. */
class ShortVideoActivity : AppCompatActivity() {
    private lateinit var pager: ViewPager2
    private val media = listOf(VideoMedia("demo-one", "asset:///demo1.mp4"),
        VideoMedia("demo-two", "asset:///demo2.mp4"), VideoMedia("demo-three", "asset:///demo1.mp4"))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pager = ViewPager2(this).apply { orientation = ViewPager2.ORIENTATION_VERTICAL; offscreenPageLimit = 1 }
        pager.adapter = object : RecyclerView.Adapter<Holder>() {
            override fun getItemCount() = media.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(VideoHostView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(-1, -1)
            })
            override fun onBindViewHolder(holder: Holder, position: Int) {
                // Bind covers and business overlays here; the framework binds playback.
            }
        }
        val controls = PlayerControlView(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            fitsSystemWindows = true
            addView(pager, FrameLayout.LayoutParams(-1, -1))
            addView(Button(context).apply {
                setText(R.string.back_to_demos)
                setOnClickListener { finish() }
            }, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.START))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { setText(R.string.hint); setTextColor(android.graphics.Color.WHITE) })
                addView(controls)
            }, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        })
        val playback = pager.bindVideoFeed(this, media, stateKey = "short-video")
        playback.bindControls(controls, this)
    }
    private class Holder(host: VideoHostView) : VideoViewHolder(host)
}
