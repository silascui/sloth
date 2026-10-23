package io.github.silascui.sloth

import org.junit.Assert.*
import org.junit.Test

class WarmupBufferBudgetTest {
    @Test fun activePlayerAndPreloadManagerKeepTheirOriginalBudget() {
        val budget = WarmupBufferBudget().apply { playerName = "spare" }
        assertTrue(budget.allows("active", 20_000_000L, 100_000_000L))
        assertTrue(budget.allows("preload", 20_000_000L, 100_000_000L))
    }

    @Test fun spareStopsAtEitherTimeOrByteBudget() {
        val budget = WarmupBufferBudget().apply { playerName = "spare" }
        assertTrue(budget.allows("spare", 999_999L, 1L))
        assertFalse(budget.allows("spare", 1_000_000L, 1L))
        assertFalse(budget.allows("spare", 0L, WarmupBufferBudget.MAX_BUFFER_BYTES))
    }

    @Test fun promotionRemovesStandbyLimitsImmediately() {
        val budget = WarmupBufferBudget().apply { playerName = "b" }
        assertFalse(budget.allows("b", 2_000_000L, 1L))
        budget.playerName = null
        assertTrue(budget.allows("b", 2_000_000L, 1L))
    }

    @Test fun roleSwapOnlyLimitsTheNewStandby() {
        val budget = WarmupBufferBudget().apply { playerName = "b" }
        budget.playerName = "a"
        assertFalse(budget.allows("a", 2_000_000L, 1L))
        assertTrue(budget.allows("b", 2_000_000L, 1L))
    }
}
