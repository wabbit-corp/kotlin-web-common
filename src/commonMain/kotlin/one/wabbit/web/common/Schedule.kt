@file:OptIn(ExperimentalTime::class)

package one.wabbit.web.common

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.io.IOException
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
     * Repeat a fixed interval forever.
     */
    @Serializable data class Forever(val interval: Duration) : Schedule {
        init {
            requireFiniteNonNegativeDuration("interval", interval)
        }
    }

    /**
     * Repeat a fixed interval [times] times (e.g. "retry at most times times").
     */
    @Serializable data class Recurs(val times: Int, val interval: Duration) : Schedule {
        init {
            require(times >= 0) { "times must be >= 0, was $times" }
            requireFiniteNonNegativeDuration("interval", interval)
        }
    }

    /**
     * Use the fixed sequence [delays]. When exhausted, stop.
     */
    @ConsistentCopyVisibility
    @Serializable data class Fixed private constructor(
        val delays: List<Duration>,
        private val copied: Boolean = true,
    ) : Schedule {
        constructor(delays: List<Duration>) : this(delays.toList(), true)

        init {
            for (d in delays) {
                requireFiniteNonNegativeDuration("delay", d)
            }
        }
    }

    /**
     * Infinite exponential growth: base, base*factor, base*factor^2, ...
     *
     * Growth stops only when composed with another schedule (for example [limited], [capped], or
     * [cutoff]). Callers that leave it unbounded should be aware that multiplying a finite
     * [Duration] by [factor] can eventually overflow the finite duration range.
     */
    @Serializable data class Exponential(val initialDelay: Duration, val factor: Double) : Schedule {
        init {
            requireFiniteNonNegativeDuration("initialDelay", initialDelay)
            requireFinitePositiveDouble("factor", factor)
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
            require(minScaler.isFinite()) { "minScaler must be finite, was $minScaler" }
            require(maxScaler.isFinite()) { "maxScaler must be finite, was $maxScaler" }
            require(minScaler >= 0.0) { "minScaler must be >= 0.0, was $minScaler" }
            require(maxScaler >= minScaler) {
                "maxScaler must be >= minScaler, was $maxScaler (min=$minScaler)"
            }
        }
    }

    /**
     * Stop once cumulative scheduled delay would exceed [duration].
     *
     * This is based on the sum of emitted delays, not wall-clock elapsed time.
     */
    @Serializable data class WithCutoff(val schedule: Schedule, val duration: Duration) : Schedule {
        init {
            requireFiniteNonNegativeDuration("duration", duration)
        }
    }

    @Serializable data class CapDelay(val schedule: Schedule, val maxDelay: Duration) : Schedule {
        init {
            requireFiniteNonNegativeDuration("maxDelay", maxDelay)
        }
    }

    fun jittered(jitterFactor: Double): Schedule =
        when {
            jitterFactor == 0.0 -> this
            jitterFactor !in 0.0..1.0 ->
                throw IllegalArgumentException("jitterFactor must be in [0,1], was $jitterFactor")
            else -> Jittered(
                schedule = this,
                minScaler = 1.0 - jitterFactor,
                maxScaler = 1.0 + jitterFactor,
            )
        }

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

    /**
     * Stop once cumulative scheduled delay would exceed [duration].
     */
    fun cutoff(duration: Duration): Schedule =
        Schedule.WithCutoff(this, duration)

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
                    .jittered(jitterFactor)
                    .capped(maxDelay)

            return core
        }

        fun forever(spaced: Duration): Schedule =
            Schedule.Forever(spaced)

        fun fixed(delay: Duration, times: Int): Schedule =
            Schedule.Recurs(times, delay)

        fun exponential(
            base: Duration,
            factor: Double = 2.0,
            maxRetries: Int = Int.MAX_VALUE,
            maxDelay: Duration? = null,
            jitterFactor: Double = 0.0,
        ): Schedule {
            require(jitterFactor in 0.0..1.0) { "jitterFactor must be in [0,1], was $jitterFactor" }
            var s: Schedule = Schedule.Exponential(base, factor)
            if (maxRetries != Int.MAX_VALUE) s = s.limited(maxRetries)
            if (jitterFactor != 0.0) s = s.jittered(jitterFactor)
            if (maxDelay != null) s = s.capped(maxDelay)
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

    is Schedule.Forever -> StatefulSchedule {
        interval
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
     *
     * An override delay bypasses schedule-derived delay shaping such as caps or cutoffs for that
     * retry step.
     */
    data class Retry(val overrideDelay: Duration? = null) : RetryAction {
        init {
            if (overrideDelay != null) {
                require(overrideDelay.isFinite()) {
                    "overrideDelay must be finite, was $overrideDelay"
                }
                require(!overrideDelay.isNegative()) {
                    "overrideDelay must be >= 0, was $overrideDelay"
                }
            }
        }
    }
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
     *
     * If the classifier returns [RetryAction.Retry] with an override delay, that override is used
     * verbatim after consuming a schedule step.
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
): T = runWithRetry(policy, Random.Default, block)

suspend inline fun <T, reified E : Throwable> runWithRetry(
    policy: RetryPolicy<E>,
    random: Random,
    block: suspend () -> T,
): T {
    val run = policy.newRun(random)
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
 *  - HTTP-date in IMF-fixdate, obsolete RFC 850, and ANSI C asctime() formats
 *
 * Returns null if the value is missing, invalid, or represents a time in the past.
 */
fun parseRetryAfterHeader(headerValue: String?, now: kotlin.time.Instant = Clock.System.now()): Duration? {
    if (headerValue == null) return null
    val trimmed = headerValue.trim()

    // First try: numeric seconds (allowing non-standard fractional seconds)
    trimmed.toDoubleOrNull()?.let { secondsValue ->
        if (secondsValue.isFinite() && secondsValue >= 0.0) {
            val duration = secondsValue.seconds
            if (duration.isFinite()) return duration
        }
    }

    val targetInstant = parseHttpDate(trimmed, now) ?: return null

    val diff = targetInstant - now
    return if (diff.isFinite() && !diff.isNegative()) diff else null
}

private fun parseHttpDate(value: String, now: kotlin.time.Instant): kotlin.time.Instant? =
    parseImfFixdate(value)
        ?: parseRfc850Date(value, now)
        ?: parseAsctimeDate(value)

private fun parseImfFixdate(value: String): kotlin.time.Instant? {
    val commaIndex = value.indexOf(',')
    if (commaIndex <= 0) return null
    val dayOfWeek = parseShortDayName(value.substring(0, commaIndex)) ?: return null

    val parts = value.substring(commaIndex + 1).trim().split(httpDateWhitespace)
    if (parts.size != 5) return null

    val day = parts[0].toIntOrNull() ?: return null
    val month = parseHttpMonth(parts[1]) ?: return null
    val year = parts[2].toIntOrNull() ?: return null
    val time = parseHttpTime(parts[3]) ?: return null
    if (!parts[4].equals("GMT", ignoreCase = true)) return null

    return toInstantOrNull(year, month, day, time, dayOfWeek)
}

private fun parseRfc850Date(value: String, now: kotlin.time.Instant): kotlin.time.Instant? {
    val commaIndex = value.indexOf(',')
    if (commaIndex <= 0) return null
    val dayOfWeek = parseLongDayName(value.substring(0, commaIndex)) ?: return null

    val parts = value.substring(commaIndex + 1).trim().split(httpDateWhitespace)
    if (parts.size != 3) return null

    val dateParts = parts[0].split('-')
    if (dateParts.size != 3) return null

    val day = dateParts[0].toIntOrNull() ?: return null
    val month = parseHttpMonth(dateParts[1]) ?: return null
    val shortYear = dateParts[2].toIntOrNull() ?: return null
    if (shortYear !in 0..99) return null

    val time = parseHttpTime(parts[1]) ?: return null
    if (!parts[2].equals("GMT", ignoreCase = true)) return null

    val year = resolveRfc850Year(shortYear, month, day, time, now)
    return toInstantOrNull(year, month, day, time, dayOfWeek)
}

private fun parseAsctimeDate(value: String): kotlin.time.Instant? {
    val parts = value.trim().split(httpDateWhitespace)
    if (parts.size != 5) return null
    val dayOfWeek = parseShortDayName(parts[0]) ?: return null

    val month = parseHttpMonth(parts[1]) ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    val time = parseHttpTime(parts[3]) ?: return null
    val year = parts[4].toIntOrNull() ?: return null

    return toInstantOrNull(year, month, day, time, dayOfWeek)
}

private fun resolveRfc850Year(
    shortYear: Int,
    month: Int,
    day: Int,
    time: ParsedTime,
    now: kotlin.time.Instant,
): Int {
    var fullYear = (now.toLocalDateTime(TimeZone.UTC).year / 100) * 100 + shortYear
    if (appearsMoreThanFiftyYearsInFuture(fullYear, month, day, time, now)) {
        fullYear -= 100
    }
    return fullYear
}

private fun appearsMoreThanFiftyYearsInFuture(
    year: Int,
    month: Int,
    day: Int,
    time: ParsedTime,
    now: kotlin.time.Instant,
): Boolean =
    toInstantOrNull(year, month, day, time)?.let { candidate ->
        candidate > now.plus(50, DateTimeUnit.YEAR, TimeZone.UTC)
    } ?: false

private fun parseHttpMonth(value: String): Int? = when (value.lowercase()) {
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
    else  -> null
}

private fun parseHttpTime(value: String): ParsedTime? {
    val timeParts = value.split(':')
    if (timeParts.size != 3) return null

    val hour = timeParts[0].toIntOrNull() ?: return null
    val minute = timeParts[1].toIntOrNull() ?: return null
    val second = timeParts[2].toIntOrNull() ?: return null
    if (hour !in 0..23) return null
    if (minute !in 0..59) return null
    if (second !in 0..60) return null
    if (second == 60 && (hour != 23 || minute != 59)) return null

    return ParsedTime(hour = hour, minute = minute, second = second)
}

private fun toInstantOrNull(
    year: Int,
    month: Int,
    day: Int,
    time: ParsedTime,
    dayOfWeek: DayOfWeek? = null,
): kotlin.time.Instant? = try {
    if (year < 1900) return null
    val localDate = LocalDate(year, month, day)
    if (dayOfWeek != null && localDate.dayOfWeek != dayOfWeek) return null
    val baseInstant =
        LocalDateTime(year, month, day, time.hour, time.minute, time.second.coerceAtMost(59))
            .toInstant(TimeZone.UTC)
    if (time.second == 60) baseInstant + 1.seconds else baseInstant
} catch (_: IllegalArgumentException) {
    null
}

private fun parseShortDayName(value: String): DayOfWeek? =
    shortDayNames[value]

private fun parseLongDayName(value: String): DayOfWeek? =
    longDayNames[value]

private data class ParsedTime(
    val hour: Int,
    val minute: Int,
    val second: Int,
)

private val httpDateWhitespace = Regex("\\s+")

private val shortDayNames = mapOf(
    "Mon" to DayOfWeek.MONDAY,
    "Tue" to DayOfWeek.TUESDAY,
    "Wed" to DayOfWeek.WEDNESDAY,
    "Thu" to DayOfWeek.THURSDAY,
    "Fri" to DayOfWeek.FRIDAY,
    "Sat" to DayOfWeek.SATURDAY,
    "Sun" to DayOfWeek.SUNDAY,
)

private val longDayNames = mapOf(
    "Monday" to DayOfWeek.MONDAY,
    "Tuesday" to DayOfWeek.TUESDAY,
    "Wednesday" to DayOfWeek.WEDNESDAY,
    "Thursday" to DayOfWeek.THURSDAY,
    "Friday" to DayOfWeek.FRIDAY,
    "Saturday" to DayOfWeek.SATURDAY,
    "Sunday" to DayOfWeek.SUNDAY,
)


/**
 * Default retry policy for idempotent HTTP operations.
 *
 * This retries selected HTTP statuses and common transport exceptions. It also retries generic
 * [IOException] because some Ktor engines surface connection resets and similar network failures
 * through that type. If the wrapped block can throw unrelated [IOException] values, provide a
 * narrower custom policy instead of relying on this default.
 *
 * Status-based retries only apply when the wrapped HTTP call throws a Ktor response exception,
 * which usually means `expectSuccess = true` or an installed response validator. Calls that
 * return a normal [io.ktor.client.statement.HttpResponse] for 4xx/5xx statuses will not be
 * retried by this policy unless the caller converts those statuses into exceptions.
 */
fun httpIdempotentDefaultPolicy(): RetryPolicy<Throwable> {
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

            is ConnectTimeoutException,
            is SocketTimeoutException,
            is IOException -> {
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

@Deprecated(
    message = "This policy is intended for idempotent HTTP calls only. Use httpIdempotentDefaultPolicy instead.",
    replaceWith = ReplaceWith("httpIdempotentDefaultPolicy()"),
)
fun httpDefaultPolicy(): RetryPolicy<Throwable> =
    httpIdempotentDefaultPolicy()

/**
 * Run an idempotent HTTP operation with retry support.
 *
 * Transport exceptions are retried directly. HTTP status retries depend on the wrapped call
 * throwing a Ktor response exception, which usually means `expectSuccess = true` or an installed
 * response validator. Calls that return a normal response object for 4xx/5xx statuses complete
 * normally and are not retried by this helper.
 */
suspend fun <T> retryingIdempotentHttpCall(
    policy: RetryPolicy<Throwable> = httpIdempotentDefaultPolicy(),
    block: suspend () -> T,
): T = retryingIdempotentHttpCall(policy, Random.Default, block)

suspend fun <T> retryingIdempotentHttpCall(
    random: Random,
    block: suspend () -> T,
): T = retryingIdempotentHttpCall(httpIdempotentDefaultPolicy(), random, block)

suspend fun <T> retryingIdempotentHttpCall(
    policy: RetryPolicy<Throwable>,
    random: Random,
    block: suspend () -> T,
): T = runWithRetry(policy, random, block)

@Deprecated(
    message = "This helper assumes the wrapped HTTP call is idempotent. Use retryingIdempotentHttpCall instead.",
    replaceWith = ReplaceWith("retryingIdempotentHttpCall(policy, block)"),
)
suspend fun <T> retryingHttpCall(
    policy: RetryPolicy<Throwable> = httpIdempotentDefaultPolicy(),
    block: suspend () -> T,
): T = retryingIdempotentHttpCall(policy, block)

@Deprecated(
    message = "This helper assumes the wrapped HTTP call is idempotent. Use retryingIdempotentHttpCall instead.",
    replaceWith = ReplaceWith("retryingIdempotentHttpCall(random, block)"),
)
suspend fun <T> retryingHttpCall(
    random: Random,
    block: suspend () -> T,
): T = retryingIdempotentHttpCall(random, block)

@Deprecated(
    message = "This helper assumes the wrapped HTTP call is idempotent. Use retryingIdempotentHttpCall instead.",
    replaceWith = ReplaceWith("retryingIdempotentHttpCall(policy, random, block)"),
)
suspend fun <T> retryingHttpCall(
    policy: RetryPolicy<Throwable>,
    random: Random,
    block: suspend () -> T,
): T = retryingIdempotentHttpCall(policy, random, block)

private fun requireFiniteNonNegativeDuration(name: String, duration: Duration) {
    require(duration.isFinite()) { "$name must be finite, was $duration" }
    require(!duration.isNegative()) { "$name must be >= 0, was $duration" }
}

private fun requireFinitePositiveDouble(name: String, value: Double) {
    require(value.isFinite()) { "$name must be finite, was $value" }
    require(value > 0.0) { "$name must be > 0, was $value" }
}
