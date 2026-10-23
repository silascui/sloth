package io.github.silascui.sloth.ui

internal data class VisibleVideo(val position: Int, val area: Long)

/** Equal areas keep the current item to prevent unnecessary handoffs. */
internal fun selectVisibleVideo(candidates: List<VisibleVideo>, current: Int): Int? = candidates
    .filter { it.position >= 0 && it.area > 0 }
    .maxWithOrNull(compareBy<VisibleVideo> { it.area }.thenBy { it.position == current })
    ?.position
