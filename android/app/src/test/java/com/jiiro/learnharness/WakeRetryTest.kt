package com.jiiro.learnharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRetryTest {
    @Test fun backoffRunsAboutThirtySecondsThenStops() {
        val delays = generateSequence(0) { it + 1 }.map(WakeRetry::delayMs).takeWhile { it != null }.toList()
        assertEquals(listOf(2_000L, 3_000L, 5_000L, 8_000L, 12_000L), delays)
        assertEquals(30_000L, delays.sumOf { it!! })
        assertNull(WakeRetry.delayMs(5))
    }

    @Test fun retriesColdStartStatusesOnly() {
        for (status in listOf(429, 500, 502, 503, 504)) assertTrue("$status", WakeRetry.isWakingStatus(status))
        for (status in listOf(200, 401, 403, 404, 501, 505)) assertFalse("$status", WakeRetry.isWakingStatus(status))
    }

    @Test fun authStatusesAreNotRetried() {
        assertTrue(WakeRetry.isAuthStatus(401))
        assertTrue(WakeRetry.isAuthStatus(403))
        assertFalse(WakeRetry.isAuthStatus(503))
        assertFalse(WakeRetry.isWakingStatus(401))
    }
}
