package io.github.silascui.sloth.ui

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.silascui.sloth.PlayerConfiguration
import io.github.silascui.sloth.VideoHostView
import io.github.silascui.sloth.VideoMedia
import io.github.silascui.sloth.VideoPlaybackRuntime

/** Debug-only host for binding lifecycle tests, excluded from release artifacts. */
class BindingTestActivity : AppCompatActivity() {
    lateinit var pager: ViewPager2
    lateinit var binding: VideoFeedBinding
    lateinit var recycler: RecyclerView
    var media = List(3) { VideoMedia("test-$it", "asset:///unused.mp4") }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VideoPlaybackRuntime.isInitialized) {
            VideoPlaybackRuntime.initialize(applicationContext, PlayerConfiguration(autoplayAllowed = { false }))
        }
        val listMode = intent.getBooleanExtra("list", false)
        if (listMode) media = List(12) { VideoMedia("test-$it", "asset:///unused.mp4") }
        val videoAdapter = object : RecyclerView.Adapter<VideoViewHolder>() {
            override fun getItemCount() = media.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VideoViewHolder(VideoHostView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(-1, if (listMode) (300 * resources.displayMetrics.density).toInt() else -1)
            })
            override fun onBindViewHolder(holder: VideoViewHolder, position: Int) = Unit
        }
        if (listMode) {
            recycler = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = videoAdapter
            }
            setContentView(recycler)
            binding = recycler.bindVideoFeed(this, media, stateKey = "fixture")
        } else {
            pager = ViewPager2(this).apply {
                orientation = ViewPager2.ORIENTATION_VERTICAL
                adapter = videoAdapter
            }
            recycler = pager.getChildAt(0) as RecyclerView
            setContentView(pager)
            pager.setCurrentItem(intent.getIntExtra("initial-page", 0), false)
            binding = pager.bindVideoFeed(this, media, stateKey = "fixture")
        }
    }
}
