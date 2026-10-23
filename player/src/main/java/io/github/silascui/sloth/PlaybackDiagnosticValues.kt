package io.github.silascui.sloth

/** Stable diagnostic values. Keep wire values unchanged for existing log consumers. */
internal object PlaybackOutcome {
    const val FEED_EMPTY = "feed_empty"
    const val HIDDEN = "hidden"
    const val SUPERSEDED = "superseded"
    const val CLOSED = "closed"
    const val ALREADY_RENDERED = "already_rendered"
    const val SAME_MEDIA_INFLIGHT = "same_media_inflight"
    const val ERROR = "error"
    const val RENDERED = "rendered"
    const val USER_PAUSED = "user_paused"
    const val FLOATING = "floating"
    const val REVOKED = "revoked"
    const val RELEASED = "released"
}

internal object PlaybackWaitReason {
    const val WAITING_HOST = "waiting_host"
    const val PREPARING = "preparing"
    const val BLOCKED_MEDIA = "blocked_media"
    const val INVALID_MEDIA = "invalid_media"
    const val USER_PAUSED = "user_paused"
    const val BLOCKED_POLICY = "blocked_policy"
}

internal object WarmupReason {
    const val NOT_WARMED = "not_warmed"
    const val RESOURCE_DISABLED = "resource_disabled"
    const val MEDIA_CHANGED = "media_changed"
    const val HOST_CHANGED = "host_changed"
    const val POSITION_CHANGED = "position_changed"
    const val SURFACE_NOT_READY = "surface_not_ready"
    const val SURFACE_RECREATED = "surface_recreated"
    const val WARMUP_ERROR = "warmup_error"
    const val WARM_FRAME_READY = "warm_frame_ready"
    const val ENGINE_PREPARING = "engine_preparing"
}

internal object PreloadReason {
    const val LIVE = "live"
    const val BUFFER_READY = "buffer_ready"
    const val STARTUP_READY = "startup_ready"
    const val PRELOAD_ERROR = "preload_error"
    const val NOT_STARTED = "not_started"
    const val TRACKS_ONLY = "tracks_only"
    const val IN_PROGRESS = "in_progress"
    const val NOT_REGISTERED = "not_registered"
    const val POSITION_MISMATCH = "position_mismatch"
    const val SOURCE_UNAVAILABLE = "source_unavailable"
}

internal object ReadSourceKind {
    const val CACHE = "cache"
    const val NETWORK = "network"
    const val LOCAL = "local"
    const val UNKNOWN = "unknown"
}

internal object MediaLoadEvent {
    const val CANCELED = "canceled"
    const val ERROR_CANCELED = "error_canceled"
    const val ERROR = "error"
    const val COMPLETED = "completed"
}
