package dev.atomic.app.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-function coverage of the reconnect backoff curve. The rest of
 * MatchClient depends on a live HttpClient and is exercised end-to-end
 * by the server's testApplication suite.
 */
class MatchClientBackoffTest {

    @Test
    fun doublesUntilCapped() {
        assertEquals(1_000L, MatchClient.backoffMillis(1))
        assertEquals(2_000L, MatchClient.backoffMillis(2))
        assertEquals(4_000L, MatchClient.backoffMillis(3))
        assertEquals(8_000L, MatchClient.backoffMillis(4))
        assertEquals(16_000L, MatchClient.backoffMillis(5))
        // Subsequent retries hit the 30s ceiling.
        assertEquals(30_000L, MatchClient.backoffMillis(6))
        assertEquals(30_000L, MatchClient.backoffMillis(MatchClient.MAX_RETRIES))
    }

    @Test
    fun attemptZeroAndNegativeDontCrash() {
        // Should be clamped to the first rung, not negative-shift into oblivion.
        assertEquals(1_000L, MatchClient.backoffMillis(0))
        assertEquals(1_000L, MatchClient.backoffMillis(-1))
    }

    @Test
    fun backoffNeverExceedsCeiling() {
        // Any attempt value produces something in (0, 30_000].
        for (a in 1..50) {
            val ms = MatchClient.backoffMillis(a)
            assertTrue(ms in 1L..30_000L, "backoff($a) = $ms out of range")
        }
    }
}
