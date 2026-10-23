package io.github.silascui.sloth.sample

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.silascui.sloth.VideoHostView
import io.github.silascui.sloth.VideoMedia
import io.github.silascui.sloth.ui.PlayerControlView
import io.github.silascui.sloth.ui.VideoViewHolder
import io.github.silascui.sloth.ui.bindVideoFeed

/** RecyclerView hosts only render views; a single controller chooses the active video. */
class FeedListActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private val media = List(12) { index ->
        VideoMedia("feed-$index", "asset:///demo${index % 2 + 1}.mp4")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = VideoAdapter()
        }
        val controls = PlayerControlView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(Button(context).apply {
                setText(R.string.back_to_demos)
                setOnClickListener { finish() }
            })
            addView(TextView(context).apply { setText(R.string.feed_hint); setPadding(dp(16), dp(8), dp(16), dp(8)) })
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls)
        }
        setContentView(root)
        val playback = list.bindVideoFeed(this, media, stateKey = "feed-list")
        playback.bindControls(controls, this)
    }

    private inner class VideoAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = media.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                setPadding(dp(12), dp(8), dp(12), dp(16))
            }
            val title = TextView(parent.context).apply { textSize = 18f }
            val host = VideoHostView(parent.context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            card.addView(title)
            card.addView(host, LinearLayout.LayoutParams(-1, dp(240)))
            card.addView(TextView(parent.context).apply { setText(R.string.muted_hint) })
            return Holder(card, host, title)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.title.text = getString(R.string.video_title, position + 1)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private class Holder(card: LinearLayout, host: VideoHostView, val title: TextView) : VideoViewHolder(host, card)
}
