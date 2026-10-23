package io.github.silascui.sloth

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Process-scoped cache owner; resource groups never compete for the same directory lock. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal object VideoCacheStore {
    private const val DEFAULT_CACHE_DIRECTORY_NAME = "ai_video_cache"

    private var cache: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null
    fun obtain(context: Context, configuration: PlayerConfiguration): SimpleCache {
        cache?.let { return it }
        val db = StandaloneDatabaseProvider(context.applicationContext)
        database = db
        return SimpleCache(configuration.cacheDirectory ?: File(context.cacheDir, DEFAULT_CACHE_DIRECTORY_NAME),
            LeastRecentlyUsedCacheEvictor(configuration.cacheBytes), db).also { cache = it }
    }
    fun release() {
        cache?.release(); cache = null
        database?.close(); database = null
    }
}
