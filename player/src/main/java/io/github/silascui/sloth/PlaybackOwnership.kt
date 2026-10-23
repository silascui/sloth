package io.github.silascui.sloth

/** 由 Runtime 在主线程串行使用。所有权先变更，旧宿主的迟到归还不能撤销新归属。 */
internal class PlaybackOwnership {
    interface Owner {
        val floating: Boolean get() = false
        fun revoke()
    }

    private var owner: Owner? = null

    fun acquire(next: Owner, automatic: Boolean): Boolean {
        if (owner === next) return true
        val previous = owner
        if (automatic && previous?.floating == true) return false
        owner = next
        previous?.revoke()
        return owner === next
    }

    fun owns(candidate: Owner) = owner === candidate

    fun transfer(from: Owner, to: Owner): Boolean {
        if (owner !== from) return false
        owner = to
        return true
    }

    fun release(candidate: Owner): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }
}
