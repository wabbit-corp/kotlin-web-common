// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.common

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ScheduleSpec {
    @Test
    fun `parseRetryAfterHeader handles numeric and imf fixdate forms`() {
        assertEquals(2.5.seconds, parseRetryAfterHeader("2.5"))
        assertNull(parseRetryAfterHeader("1e100"))

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

        assertNotNull(parseRetryAfterHeader("Sat, 06 Nov 2094 08:49:37 GMT", now = now))
        assertNull(parseRetryAfterHeader("Sunday, 06-Nov-94 08:49:37 GMT", now = now))
    }

    @Test
    fun `parseRetryAfterHeader applies the rfc850 rollover rule using the full timestamp`() {
        val boundaryNow = Instant.parse("2045-01-01T00:00:00Z")
        assertNull(parseRetryAfterHeader("Sunday, 31-Dec-95 23:59:59 GMT", now = boundaryNow))

        val exactBoundaryNow = Instant.parse("2045-12-31T23:59:59Z")
        assertNotNull(parseRetryAfterHeader("Saturday, 31-Dec-95 23:59:59 GMT", now = exactBoundaryNow))
    }

    @Test
    fun `parseRetryAfterHeader accepts leap second timestamps`() {
        val now = Instant.parse("1994-11-06T23:59:00Z")

        assertEquals(
            60.seconds,
            parseRetryAfterHeader("Sun, 06 Nov 1994 23:59:60 GMT", now = now),
        )
    }

    @Test
    fun `parseRetryAfterHeader rejects impossible time components`() {
        val now = Instant.parse("1994-11-06T08:49:00Z")

        assertNull(parseRetryAfterHeader("Sun, 06 Nov 1994 24:49:37 GMT", now = now))
        assertNull(parseRetryAfterHeader("Sun, 06 Nov 1994 08:60:37 GMT", now = now))
        assertNull(parseRetryAfterHeader("Sun, 06 Nov 1994 08:49:60 GMT", now = now))
        assertNull(parseRetryAfterHeader("Sun, 06 Nov 1994 08:49:61 GMT", now = now))
    }

    @Test
    fun `parseRetryAfterHeader applies the rfc850 rollover rule to leap second boundaries`() {
        val exactBoundaryNow = Instant.parse("2046-01-01T00:00:00Z")
        assertNotNull(parseRetryAfterHeader("Saturday, 31-Dec-95 23:59:60 GMT", now = exactBoundaryNow))

        val justBeforeBoundaryNow = Instant.parse("2045-12-31T23:59:59Z")
        assertNull(parseRetryAfterHeader("Sunday, 31-Dec-95 23:59:60 GMT", now = justBeforeBoundaryNow))
    }

    @Test
    fun `parseRetryAfterHeader rejects semantically invalid dates`() {
        val now = Instant.parse("1994-11-06T08:49:00Z")

        assertNull(parseRetryAfterHeader("Mon, 06 Nov 1994 08:49:37 GMT", now = now))
        assertNull(parseRetryAfterHeader("Sun Nov  6 08:49:37 0899", now = now))
        assertNull(parseRetryAfterHeader("Sun, 06 Nov 0899 08:49:37 GMT", now = now))
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

    @Test
    fun `runWithRetry supports legacy and explicit random overloads`() = runBlocking {
        val policy =
            RetryPolicy<IllegalStateException>(
                schedule = Schedule.fixed(Duration.ZERO, 1),
            ) { _, _ ->
                RetryAction.Retry()
            }

        var attempts = 0
        val legacyResult =
            runWithRetry(policy) {
                attempts++
                if (attempts == 1) throw IllegalStateException("boom")
                "legacy"
            }

        assertEquals("legacy", legacyResult)

        attempts = 0
        val explicitRandomResult =
            runWithRetry(policy, Random(1)) {
                attempts++
                if (attempts == 1) throw IllegalStateException("boom")
                "random"
            }

        assertEquals("random", explicitRandomResult)
    }

    @Test
    fun `forever uses a truly infinite fixed interval schedule`() {
        val schedule = Schedule.forever(1.seconds)
        assertTrue(schedule is Schedule.Forever)

        val run = schedule.compile()
        repeat(3) {
            assertEquals(1.seconds, run.next())
        }
    }

    @Test
    fun `sequence runs the second schedule after the first is exhausted`() {
        val run =
            Schedule.Sequence(
                Schedule.Fixed(listOf(1.seconds, 2.seconds)),
                Schedule.Fixed(listOf(3.seconds)),
            ).compile()

        assertEquals(1.seconds, run.next())
        assertEquals(2.seconds, run.next())
        assertEquals(3.seconds, run.next())
        assertNull(run.next())
    }

    @Test
    fun `now and never compile as single step and empty schedules`() {
        val nowRun = Schedule.Now.compile()
        assertEquals(Duration.ZERO, nowRun.next())
        assertNull(nowRun.next())

        val neverRun = Schedule.Never.compile()
        assertNull(neverRun.next())
    }

    @Test
    fun `fixed defensively copies its delay list`() {
        val delays = mutableListOf(1.seconds)
        val schedule = Schedule.Fixed(delays)

        delays[0] = (-1).seconds

        assertEquals(1.seconds, schedule.compile().next())
    }

    @Test
    fun `schedule constructors reject non finite durations and factors`() {
        assertFailsWith<IllegalArgumentException> {
            Schedule.Recurs(times = 1, interval = Duration.INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            Schedule.Fixed(listOf(Duration.INFINITE))
        }
        assertFailsWith<IllegalArgumentException> {
            Schedule.Exponential(initialDelay = Duration.INFINITE, factor = 2.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Schedule.Exponential(initialDelay = 1.seconds, factor = Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            Schedule.Jittered(Schedule.Now, minScaler = 0.0, maxScaler = Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            Schedule.WithCutoff(Schedule.Now, Duration.INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            Schedule.CapDelay(Schedule.Now, Duration.INFINITE)
        }
    }

    private object MaxRandom : Random() {
        override fun nextBits(bitCount: Int): Int =
            -1 ushr (Int.SIZE_BITS - bitCount)
    }
}
