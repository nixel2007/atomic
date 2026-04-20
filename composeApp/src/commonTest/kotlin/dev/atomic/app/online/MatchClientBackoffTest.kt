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
        // given — attempt numbers 1..5 map to 1s..16s, the rest hit the ceiling
        val expected = mapOf(
            1 to 1_000L,
            2 to 2_000L,
            3 to 4_000L,
            4 to 8_000L,
            5 to 16_000L,
            6 to 30_000L,
            50 to 30_000L
        )

        // when / then — every attempt maps to its expected rung
        for ((attempt, ms) in expected) {
            assertEquals(ms, MatchClient.backoffMillis(attempt), "attempt=$attempt")
        }
    }

    @Test
    fun attemptZeroAndNegativeDontCrash() {
        // given — edge-case attempt numbers that would underflow `shl`
        val edgeCases = listOf(0, -1)

        // when / then — both clamp to the first rung (1s) instead of exploding
        for (attempt in edgeCases) {
            assertEquals(1_000L, MatchClient.backoffMillis(attempt), "attempt=$attempt")
        }
    }

    @Test
    fun backoffNeverExceedsCeiling() {
        // given — a wide sweep of attempt numbers
        // when  — we compute the backoff for each
        // then  — every result is in (0, 30_000]
        for (a in 1..50) {
            val ms = MatchClient.backoffMillis(a)
            assertTrue(ms in 1L..30_000L, "backoff($a) = $ms out of range")
        }
    }
}
