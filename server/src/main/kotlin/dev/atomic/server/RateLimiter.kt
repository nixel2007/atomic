package dev.atomic.server

/**
 * Trivial sliding-window rate limiter. Not thread-safe — intended to be
 * owned by a single [Session]; each WebSocket frame is handled on the
 * session's own coroutine in order, so no external synchronisation is
 * needed.
 */
class RateLimiter(
    private val maxPerWindow: Int,
    private val windowMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val timestamps = ArrayDeque<Long>()

    fun tryAcquire(): Boolean {
        val now = clock()
        while (timestamps.isNotEmpty() && timestamps.first() < now - windowMillis) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxPerWindow) return false
        timestamps.addLast(now)
        return true
    }
}
