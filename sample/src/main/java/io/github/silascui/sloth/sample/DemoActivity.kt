package io.github.silascui.sloth.sample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DemoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                setText(R.string.demo_intro)
                textSize = 22f
            })
            addView(Button(context).apply {
                setText(R.string.feed_demo)
                setOnClickListener { startActivity(Intent(context, FeedListActivity::class.java)) }
            })
            addView(Button(context).apply {
                setText(R.string.short_video_demo)
                setOnClickListener { startActivity(Intent(context, ShortVideoActivity::class.java)) }
            })
        })
    }
}
