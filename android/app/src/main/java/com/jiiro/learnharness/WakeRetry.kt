package com.jiiro.learnharness

/**
 * Cold-start handling. The server can scale to zero (Cloud Run), and the first load after idle
 * may fail with 429/5xx or a timeout for about 3-10 s while an instance starts. Those failures
 * retry with this backoff (about 30 s in all) before the error screen shows.
 */
object WakeRetry {
    private val DELAYS_MS = longArrayOf(2_000, 3_000, 5_000, 8_000, 12_000)

    /** Delay before retry number [attempt] (0-based), or null once retries are exhausted. */
    fun delayMs(attempt: Int): Long? = DELAYS_MS.getOrNull(attempt)

    /** Statuses a starting or saturated instance returns; worth retrying. */
    fun isWakingStatus(status: Int): Boolean = status == 429 || status == 500 || status in 502..504

    /** A wrong or missing token: never retried. */
    fun isAuthStatus(status: Int): Boolean = status == 401 || status == 403
}
