package io.github.silascui.sloth

import org.junit.Assert.*
import org.junit.Test

class PlaybackOwnershipTest {
    private class Owner(override val floating: Boolean = false, private val callback: () -> Unit = {}) : PlaybackOwnership.Owner {
        var revocations = 0
        override fun revoke() { revocations++; callback() }
    }

    @Test fun takeoverRevokesPreviousBeforeReturningNewGrant() {
        val registry = PlaybackOwnership()
        val events = mutableListOf<String>()
        val first = Owner { events += "old revoked" }
        val next = Owner()
        registry.acquire(first, true)
        assertTrue(registry.acquire(next, true))
        events += "new granted"
        assertEquals(listOf("old revoked", "new granted"), events)
    }

    @Test fun oldReleaseCannotClearCurrentOwner() {
        val registry = PlaybackOwnership()
        val first = Owner()
        val next = Owner()
        registry.acquire(first, true)
        registry.acquire(next, true)
        assertFalse(registry.release(first))
        assertTrue(registry.owns(next))
    }

    @Test fun repeatedAcquireIsIdempotent() {
        val registry = PlaybackOwnership()
        val owner = Owner()
        registry.acquire(owner, true)
        registry.acquire(owner, true)
        assertEquals(0, owner.revocations)
    }

    @Test fun automaticPageCannotInterruptFloatingOwner() {
        val registry = PlaybackOwnership()
        val floating = Owner(true)
        registry.acquire(floating, false)
        assertFalse(registry.acquire(Owner(), true))
        assertTrue(registry.owns(floating))
        assertEquals(0, floating.revocations)
    }

    @Test fun explicitPlayCanReplaceFloatingOwner() {
        val registry = PlaybackOwnership()
        val floating = Owner(true)
        val explicit = Owner()
        registry.acquire(floating, false)
        assertTrue(registry.acquire(explicit, false))
        assertEquals(1, floating.revocations)
    }

    @Test fun transferDoesNotRevokeAndOldOwnerCannotRelease() {
        val registry = PlaybackOwnership()
        val page = Owner()
        val floating = Owner(true)
        registry.acquire(page, true)
        assertTrue(registry.transfer(page, floating))
        assertFalse(registry.release(page))
        assertEquals(0, page.revocations)
        assertTrue(registry.owns(floating))
    }

    @Test fun staleTransferIsRejected() {
        val registry = PlaybackOwnership()
        val first = Owner()
        val next = Owner()
        registry.acquire(next, true)
        assertFalse(registry.transfer(first, Owner(true)))
        assertTrue(registry.owns(next))
    }

    @Test fun reentrantTakeoverDoesNotGrantSupersededRequest() {
        val registry = PlaybackOwnership()
        val latest = Owner()
        val first = Owner { registry.acquire(latest, false) }
        registry.acquire(first, true)
        assertFalse(registry.acquire(Owner(), true))
        assertTrue(registry.owns(latest))
    }
}
