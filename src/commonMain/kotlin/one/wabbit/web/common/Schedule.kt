@file:OptIn(ExperimentalTime::class)

package one.wabbit.web.common

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

// A schedule can be thought of as a way to represent a finite or infinite
// sequence of time intervals.
@Serializable
sealed interface Schedule {
    @Serializable data object Now : Schedule

    @Serializable data object Never : Schedule

    /**
     * Repeat a fixed interval [times] times (e.g. "retry at most times times").
     */
    @Serializable data class Recurs(val times: Int, val interval: Duration) : Schedule {
        init {
            require(times >= 0) { "times must be >= 0, was $times" }
            require(!interval.isNegative()) { "interval must be >= 0, was $interval" }
        }
    }

    /**
     * Use the fixed sequence [delays]. When exhausted, stop.
     */
    @Serializable data class Fixed(val delays: List<Duration>) : Schedule {
        init {
            for (d in delays) {
                require(!d.isNegative()) { "all delays must be >= 0, was $d" }
            }
        }
    }

    /**
     * Infinite exponential growth: base, base*factor, base*factor^2, ...
     */
    @Serializable data class Exponential(val initialDelay: Duration, val factor: Double) : Schedule {
        init {
            require(!initialDelay.isNegative()) { "initialDelay must be >= 0, was $initialDelay" }
            require(factor > 0) { "factor must be > 0, was $factor" }
        }
    }

    /**
     * Continue while either schedule continues, using the minimum delay.
     */
    @Serializable data class Union(val a: Schedule, val b: Schedule) : Schedule

    /**
     * Continue only while both schedules continue, using the maximum delay.
     */
    @Serializable data class Intersection(val a: Schedule, val b: Schedule) : Schedule

    /**
     * Run [a] until it finishes, then run [b].
     */
    @Serializable data class Sequence(val a: Schedule, val b: Schedule) : Schedule

    /**
     * Apply multiplicative jitter in [minScaler, maxScaler] to delays.
     */
    @Serializable data class Jittered(val schedule: Schedule, val minScaler: Double, val maxScaler: Double) : Schedule {
        init {
            require(minScaler >= 0.0) { "minScaler must be >= 0.0, was $minScaler" }
            require(maxScaler >= minScaler) {
                "maxScaler must be >= minScaler, was $maxScaler (min=$minScaler)"
            }
        }
    }

    /**
     * Stop once cumulative delay would exceed [duration].
     */
    @Serializable data class WithCutoff(val schedule: Schedule, val duration: Duration) : Schedule {
        init {
            require(!duration.isNegative()) { "duration must be >= 0, was $duration" }
        }
    }

    @Serializable data class CapDelay(val schedule: Schedule, val maxDelay: Duration) : Schedule {
        init {
            require(!maxDelay.isNegative()) { "maxDelay must be >= 0, was $maxDelay" }
        }
    }

    fun jittered(jitterFactor: Double): Schedule =
        if (jitterFactor == 0.0) this
        else Jittered(
            schedule = this,
            minScaler = 1.0 - jitterFactor,
            maxScaler = 1.0 + jitterFactor,
        )

    /**
     * Take at most [n] elements from this schedule.
     */
    fun limited(n: Int): Schedule =
        if (n <= 0) Schedule.Never
        else Schedule.Intersection(this, Schedule.Recurs(times = n, interval = Duration.ZERO))

    /**
     * Per-step cap.
     */
    fun capped(maxDelay: Duration): Schedule =
        Schedule.CapDelay(this, maxDelay)

    companion object {
        fun retries(
            /** Total attempts = 1 (initial) + maxRetries. */
            maxRetries: Int = 2,
            baseDelay: Duration = 200.milliseconds,
            maxDelay: Duration = 5.seconds,
            jitterFactor: Double = 0.2,
        ): Schedule {
            require(maxRetries >= 0) { "maxRetries must be >= 0, was $maxRetries" }
            require(jitterFactor in 0.0..1.0) { "jitterFactor must be in [0,1], was $jitterFactor" }

            val core =
                Schedule.Exponential(initialDelay = baseDelay, factor = 2.0)
                    .limited(maxRetries)
                    .capped(maxDelay)
                    .jittered(jitterFactor)

            return core
        }

        fun forever(spaced: Duration): Schedule =
            Schedule.Recurs(Int.MAX_VALUE, spaced)

        fun fixed(delay: Duration, times: Int): Schedule =
            Schedule.Recurs(times, delay)

        fun exponential(
            base: Duration,
            factor: Double = 2.0,
            maxRetries: Int = Int.MAX_VALUE,
            maxDelay: Duration? = null,
            jitterFactor: Double = 0.0,
        ): Schedule {
            var s: Schedule = Schedule.Exponential(base, factor)
            if (maxRetries != Int.MAX_VALUE) s = s.limited(maxRetries)
            if (maxDelay != null) s = s.capped(maxDelay)
            if (jitterFactor != 0.0) s = s.jittered(jitterFactor)
            return s
        }
    }
}

/**
 * One "run" of a schedule. Each call returns the next delay, or null when finished.
 * Not thread-safe; assume you create one per retry loop.
 */
fun interface StatefulSchedule {
    fun next(): Duration?
}

fun Schedule.compile(
    random: Random = Random.Default,
): StatefulSchedule = when (this) {
    Schedule.Now -> {
        var used = false
        StatefulSchedule {
            if (!used) {
                used = true
                Duration.ZERO
            } else {
                null
            }
        }
    }

    Schedule.Never -> StatefulSchedule {
        null
    }

    is Schedule.Recurs -> {
        var remaining = times
        StatefulSchedule {
            if (remaining <= 0) null
            else {
                remaining--
                interval
            }
        }
    }

    is Schedule.Fixed -> {
        val it = delays.iterator()
        StatefulSchedule {
            if (it.hasNext()) it.next() else null
        }
    }

    is Schedule.Exponential -> {
        require(factor > 0) { "factor must be > 0, was $factor" }
        var current = initialDelay
        StatefulSchedule {
            val d = current
            current *= factor
            d
        }
    }

    is Schedule.Union -> {
        val fa = a.compile(random)
        val fb = b.compile(random)
        StatefulSchedule {
            val da = fa.next()
            val db = fb.next()
            if (da == null && db == null) null
            else listOfNotNull(da, db).min()
        }
    }

    is Schedule.Intersection -> {
        val fa = a.compile(random)
        val fb = b.compile(random)
        StatefulSchedule {
            val da = fa.next()
            val db = fb.next()
            if (da == null || db == null) null
            else maxOf(da, db)
        }
    }

    is Schedule.Sequence -> {
        var current: StatefulSchedule = a.compile(random)
        var onFirst = true

        StatefulSchedule {
            val d = current.next()
            if (d != null) {
                d
            } else if (onFirst) {
                onFirst = false
                current = b.compile(random)
                current.next()
            } else {
                null
            }
        }
    }

    is Schedule.Jittered -> {
        val inner = schedule.compile(random)
        StatefulSchedule {
            val base = inner.next() ?: return@StatefulSchedule null
            val scale =
                if (minScaler == maxScaler) minScaler
                else random.nextDouble(minScaler, maxScaler)
            base * scale
        }
    }

    is Schedule.WithCutoff -> {
        val inner = schedule.compile(random)
        var used: Duration = Duration.ZERO
        StatefulSchedule {
            val next = inner.next() ?: return@StatefulSchedule null
            if (used + next > duration) null
            else {
                used += next
                next
            }
        }
    }

    is Schedule.CapDelay -> {
        val inner = schedule.compile(random)
        StatefulSchedule {
            val d = inner.next() ?: return@StatefulSchedule null
            if (d > maxDelay) maxDelay else d
        }
    }
}

sealed interface RetryAction {
    /** Do not retry. */
    data object Stop : RetryAction

    /**
     * Retry. If [overrideDelay] is null, use the schedule; otherwise use [overrideDelay].
     */
    data class Retry(val overrideDelay: Duration? = null) : RetryAction
}

class RetryPolicy<E>(
    private val schedule: Schedule,
    private val classify: (error: E, attempt: Int) -> RetryAction,
) {
    fun newRun(random: Random = Random.Default): RetryRun<E> =
        RetryRun(schedule.compile(random), classify)
}

class RetryRun<E>(
    private val stateful: StatefulSchedule,
    private val classify: (E, Int) -> RetryAction,
) {
    private var attempt: Int = 0

    /**
     * Decide the next delay for [error], or null if we should stop.
     */
    fun nextDelay(error: E): Duration? {
        val action = classify(error, attempt)
        return when (action) {
            RetryAction.Stop -> null
            is RetryAction.Retry -> {
                attempt++
                val scheduleDelay = stateful.next() ?: return null
                action.overrideDelay ?: scheduleDelay
            }
        }
    }
}

suspend inline fun <T, reified E : Throwable> runWithRetry(
    policy: RetryPolicy<E>,
    block: suspend () -> T,
): T {
    val run = policy.newRun()
    while (true) {
        try {
            return block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val e = t as? E ?: throw t
            val delay = run.nextDelay(e) ?: throw e
            kotlinx.coroutines.delay(delay)
        }
    }
}

/**
 * Parse the value of a Retry-After header into a delay Duration.
 *
 * Supports:
 *  - delta-seconds (integer or float)
 *  - HTTP-date in RFC 1123 format (e.g. "Wed, 21 Oct 2015 07:28:00 GMT")
 *
 * Returns null if the value is missing, invalid, or represents a time in the past.
 */
fun parseRetryAfterHeader(headerValue: String?, now: kotlin.time.Instant = Clock.System.now()): Duration? {
    if (headerValue == null) return null
    val trimmed = headerValue.trim()

    // First try: numeric seconds (allowing non-standard fractional seconds)
    trimmed.toDoubleOrNull()?.let { secondsValue ->
        if (secondsValue.isFinite() && secondsValue >= 0.0) {
            return secondsValue.seconds
        }
    }

    // Next: HTTP-date in RFC 1123 format, e.g. "Wed, 21 Oct 2015 07:28:00 GMT"
    // We parse it manually into a LocalDateTime in UTC.
    val afterComma = trimmed.substringAfter(',', missingDelimiterValue = "").trim()
    if (afterComma.isEmpty()) return null

    val parts = afterComma.split(Regex("\\s+"))
    if (parts.size != 5) return null

    val day = parts[0].toIntOrNull() ?: return null
    val month = when (parts[1].lowercase()) {
        "jan" -> 1
        "feb" -> 2
        "mar" -> 3
        "apr" -> 4
        "may" -> 5
        "jun" -> 6
        "jul" -> 7
        "aug" -> 8
        "sep" -> 9
        "oct" -> 10
        "nov" -> 11
        "dec" -> 12
        else  -> return null
    }
    val year = parts[2].toIntOrNull() ?: return null

    val timeParts = parts[3].split(':')
    if (timeParts.size != 3) return null
    val hour = timeParts[0].toIntOrNull() ?: return null
    val minute = timeParts[1].toIntOrNull() ?: return null
    val second = timeParts[2].toIntOrNull() ?: return null

    if (!parts[4].equals("GMT", ignoreCase = true)) return null

    val targetInstant = try {
        LocalDateTime(year, month, day, hour, minute, second)
            .toInstant(TimeZone.UTC)
    } catch (_: IllegalArgumentException) {
        return null
    }

    val diff = targetInstant - now
    return if (diff.isNegative()) null else diff
}


fun httpDefaultPolicy(): RetryPolicy<Throwable> {
    val schedule =
        Schedule.retries(
            maxRetries = 4,
            baseDelay = 200.milliseconds,
            maxDelay = 5.seconds,
            jitterFactor = 0.2,
        )

    return RetryPolicy(schedule) { error, attempt ->
        when (error) {
            is HttpRequestTimeoutException -> {
                // retry using schedule
                RetryAction.Retry()
            }

            is ServerResponseException -> {
                val status = error.response.status.value
                val retryAfter =
                    parseRetryAfterHeader(error.response.headers["Retry-After"])

                if (status in 500..599) {
                    if (retryAfter != null) {
                        RetryAction.Retry(retryAfter)
                    } else {
                        RetryAction.Retry()
                    }
                } else {
                    RetryAction.Stop
                }
            }

            is ClientRequestException -> {
                // 4xx except maybe 429 are usually "don't retry"
                val status = error.response.status.value
                if (status == 408) {
                    // Request Timeout - may retry
                    RetryAction.Retry()
                } else if (status == 429) {
                    val retryAfter =
                        parseRetryAfterHeader(error.response.headers["Retry-After"])
                    RetryAction.Retry(retryAfter)
                } else {
                    RetryAction.Stop
                }
            }

            else -> RetryAction.Stop
        }
    }
}

suspend fun <T> retryingHttpCall(
    policy: RetryPolicy<Throwable> = httpDefaultPolicy(),
    block: suspend () -> T,
): T = runWithRetry(policy, block)
