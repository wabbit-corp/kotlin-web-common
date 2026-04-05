package one.wabbit.web.common

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ScheduleSpec {
    @Test
    fun `parseRetryAfterHeader handles numeric and imf fixdate forms`() {
        assertEquals(2.5.seconds, parseRetryAfterHeader("2.5"))

        val parsed =
            parseRetryAfterHeader(
                "Wed, 21 Oct 2037 07:28:00 GMT",
                now = Instant.parse("2037-10-21T07:27:30Z"),
            )

        assertNotNull(parsed)
        assertEquals(30.seconds, parsed)
        assertNull(parseRetryAfterHeader("Wed, 21 Oct 2015 07:28:00 GMT", now = Instant.parse("2037-10-21T07:27:30Z")))
    }

    @Test
    fun `parseRetryAfterHeader handles obsolete http date forms`() {
        val now = Instant.parse("1994-11-06T08:49:00Z")

        assertEquals(
            37.seconds,
            parseRetryAfterHeader("Sunday, 06-Nov-94 08:49:37 GMT", now = now),
        )
        assertEquals(
            37.seconds,
            parseRetryAfterHeader("Sun Nov  6 08:49:37 1994", now = now),
        )
    }

    @Test
    fun `parseRetryAfterHeader applies the rfc850 two digit year rollover rule`() {
        val now = Instant.parse("2043-11-06T08:49:00Z")

        assertNotNull(parseRetryAfterHeader("Sun, 06 Nov 2094 08:49:37 GMT", now = now))
        assertNull(parseRetryAfterHeader("Sunday, 06-Nov-94 08:49:37 GMT", now = now))
    }

    @Test
    fun `parseRetryAfterHeader applies the rfc850 rollover rule using the full timestamp`() {
        val boundaryNow = Instant.parse("2045-01-01T00:00:00Z")
        assertNull(parseRetryAfterHeader("Sunday, 31-Dec-95 23:59:59 GMT", now = boundaryNow))

        val exactBoundaryNow = Instant.parse("2045-12-31T23:59:59Z")
        assertNotNull(parseRetryAfterHeader("Sunday, 31-Dec-95 23:59:59 GMT", now = exactBoundaryNow))
    }

    @Test
    fun `parseRetryAfterHeader accepts leap second timestamps`() {
        val now = Instant.parse("1994-11-06T08:49:00Z")

        assertEquals(
            60.seconds,
            parseRetryAfterHeader("Sun, 06 Nov 1994 08:49:60 GMT", now = now),
        )
    }

    @Test
    fun `parseRetryAfterHeader rejects impossible time components`() {
        val now = Instant.parse("1994-11-06T08:49:00Z")

        assertNull(parseRetryAfterHeader("Sun, 06 Nov 1994 24:49:37 GMT", now = now))
        assertNull(parseRetryAfterHeader("Sun, 06 Nov 1994 08:60:37 GMT", now = now))
        assertNull(parseRetryAfterHeader("Sun, 06 Nov 1994 08:49:61 GMT", now = now))
    }

    @Test
    fun `parseRetryAfterHeader applies the rfc850 rollover rule to leap second boundaries`() {
        val exactBoundaryNow = Instant.parse("2046-01-01T00:00:00Z")
        assertNotNull(parseRetryAfterHeader("Sunday, 31-Dec-95 23:59:60 GMT", now = exactBoundaryNow))

        val justBeforeBoundaryNow = Instant.parse("2045-12-31T23:59:59Z")
        assertNull(parseRetryAfterHeader("Sunday, 31-Dec-95 23:59:60 GMT", now = justBeforeBoundaryNow))
    }

    @Test
    fun `retries keeps maxDelay as a hard ceiling after jitter`() {
        val nextDelay =
            Schedule.retries(
                maxRetries = 1,
                baseDelay = 5.seconds,
                maxDelay = 5.seconds,
                jitterFactor = 0.2,
            ).compile(random = MaxRandom).next()

        assertNotNull(nextDelay)
        assertTrue(nextDelay <= 5.seconds)
    }

    @Test
    fun `exponential keeps maxDelay as a hard ceiling after jitter`() {
        val nextDelay =
            Schedule.exponential(
                base = 5.seconds,
                factor = 2.0,
                maxRetries = 1,
                maxDelay = 5.seconds,
                jitterFactor = 0.2,
            ).compile(random = MaxRandom).next()

        assertNotNull(nextDelay)
        assertTrue(nextDelay <= 5.seconds)
    }

    @Test
    fun `exponential validates jitter factor before constructing schedule`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                Schedule.exponential(base = 1.seconds, jitterFactor = 1.1)
            }

        assertContains(error.message ?: "", "jitterFactor")
    }

    @Test
    fun `retry action rejects negative override delay`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                RetryAction.Retry((-1).seconds)
            }

        assertContains(error.message ?: "", "overrideDelay")
    }

    @Test
    fun `jittered rejects factors outside zero to one`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                Schedule.fixed(1.seconds, 1).jittered(1.1)
            }

        assertContains(error.message ?: "", "jitterFactor")
    }

    @Test
    fun `override delay bypasses schedule caps`() {
        val policy =
            RetryPolicy<Throwable>(
                schedule = Schedule.fixed(10.seconds, 1).capped(5.seconds),
            ) { _, _ ->
                RetryAction.Retry(10.seconds)
            }

        assertEquals(10.seconds, policy.newRun().nextDelay(IllegalStateException("boom")))
    }

    private object MaxRandom : Random() {
        override fun nextBits(bitCount: Int): Int =
            -1 ushr (Int.SIZE_BITS - bitCount)
    }
}
