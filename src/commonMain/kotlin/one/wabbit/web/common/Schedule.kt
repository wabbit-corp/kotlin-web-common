// SPDX-License-Identifier: AGPL-3.0-or-later

@file:OptIn(ExperimentalTime::class)

package one.wabbit.web.common

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.discardRemaining
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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

/**
 * Declarative finite or infinite sequence of retry delays.
 *
 * Schedules are immutable descriptions. Use [compile] to create a mutable [StatefulSchedule] for a
 * single retry loop.
 */
@Serializable
sealed interface Schedule {
    /** Emit one immediate retry delay of [Duration.ZERO], then stop. */
    @Serializable data object Now : Schedule

    /** Emit no retry delays. */
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

    /**
     * Clamp each delay emitted by [schedule] to at most [maxDelay].
     */
    @Serializable data class CapDelay(val schedule: Schedule, val maxDelay: Duration) : Schedule {
        init {
            requireFiniteNonNegativeDuration("maxDelay", maxDelay)
        }
    }

    /**
     * Applies symmetric multiplicative jitter around each emitted delay.
     *
     * A [jitterFactor] of `0.2` scales each delay by a random value in `[0.8, 1.2)`.
     */
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

    /** Factory methods for common retry schedules. */
    companion object {
        /**
         * Standard bounded exponential retry schedule.
         *
         * Total attempts for a retry loop are one initial attempt plus [maxRetries] scheduled retry
         * delays. Jitter is applied before [maxDelay] caps each emitted delay.
         */
        fun retries(
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

        /** Repeats [spaced] forever. */
        fun forever(spaced: Duration): Schedule =
            Schedule.Forever(spaced)

        /** Emits [times] retry delays, each equal to [delay]. */
        fun fixed(delay: Duration, times: Int): Schedule =
            Schedule.Recurs(times, delay)

        /**
         * Exponential retry schedule with optional retry limit, delay cap, and jitter.
         */
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
    /** Returns the next delay, or null when the schedule is exhausted. */
    fun next(): Duration?
}

/**
 * Compiles this immutable schedule into a mutable single-use [StatefulSchedule].
 *
 * The [random] source is used only by [Schedule.Jittered] nodes.
 */
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

/**
 * Classification result for one retryable error or response.
 */
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

/**
 * Retry policy for errors or responses of type [E].
 *
 * @param schedule delay schedule consumed by each retry run.
 * @param classify classifies an error/response and zero-based retry attempt into a [RetryAction].
 */
class RetryPolicy<E>(
    private val schedule: Schedule,
    private val classify: (error: E, attempt: Int) -> RetryAction,
) {
    /** Creates a fresh mutable retry run backed by this policy. */
    fun newRun(random: Random = Random.Default): RetryRun<E> =
        RetryRun(schedule.compile(random), classify)
}

/**
 * Mutable state for one execution of a [RetryPolicy].
 */
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

/**
 * Runs [block] until it succeeds or [policy] declines to retry a thrown [E].
 *
 * [CancellationException] and throwables that are not [E] are rethrown immediately.
 */
suspend inline fun <T, reified E : Throwable> runWithRetry(
    policy: RetryPolicy<E>,
    block: suspend () -> T,
): T = runWithRetry(policy, Random.Default, block)

/**
 * Runs [block] with retry support using [random] for jittered schedules.
 */
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
 * Configures how idempotent HTTP retries are classified.
 *
 * This is used by both exception-driven helpers and response-driven helpers. The default preset
 * favors broad compatibility with existing callers. Use [HttpRetryOptions.strictTransient] when you
 * want a narrower likely-transient-only policy. Callers may include `3xx` statuses in
 * [retryableStatuses] when they want to honor `Retry-After` on redirects.
 *
 * @property schedule retry schedule used by policies created from these options.
 * @property retryOnGenericIoException whether generic [IOException] values should be retried.
 * @property retryableStatuses HTTP status codes that should be retried.
 * @property respectRetryAfter whether a `Retry-After` header can override the schedule delay.
 * @property maxRetryAfterDelay optional cap for `Retry-After` delays.
 */
@ConsistentCopyVisibility
data class HttpRetryOptions private constructor(
    val schedule: Schedule,
    val retryOnGenericIoException: Boolean,
    val retryableStatuses: Set<Int>,
    val respectRetryAfter: Boolean,
    val maxRetryAfterDelay: Duration?,
    private val copied: Boolean = true,
) {
    constructor(
        schedule: Schedule = defaultHttpRetrySchedule(),
        retryOnGenericIoException: Boolean = true,
        retryableStatuses: Set<Int> = defaultBroadRetryableStatuses,
        respectRetryAfter: Boolean = true,
        maxRetryAfterDelay: Duration? = null,
    ) : this(
        schedule = schedule,
        retryOnGenericIoException = retryOnGenericIoException,
        retryableStatuses = retryableStatuses.toSet(),
        respectRetryAfter = respectRetryAfter,
        maxRetryAfterDelay = maxRetryAfterDelay,
        copied = true,
    )

    init {
        require(retryableStatuses.all { it in 100..599 }) {
            "retryableStatuses must contain valid HTTP status codes, was $retryableStatuses"
        }
        maxRetryAfterDelay?.let { requireFiniteNonNegativeDuration("maxRetryAfterDelay", it) }
    }

    /** Preset constructors for idempotent HTTP retry options. */
    companion object {
        /**
         * Broad idempotent preset: retries `408`, `429`, all `5xx`, timeout/connect exceptions, and
         * generic [IOException].
         */
        fun broadIdempotent(
            schedule: Schedule = defaultHttpRetrySchedule(),
            respectRetryAfter: Boolean = true,
            maxRetryAfterDelay: Duration? = null,
        ): HttpRetryOptions =
            HttpRetryOptions(
                schedule = schedule,
                retryOnGenericIoException = true,
                retryableStatuses = defaultBroadRetryableStatuses,
                respectRetryAfter = respectRetryAfter,
                maxRetryAfterDelay = maxRetryAfterDelay,
            )

        /**
         * Strict transient preset: retries `408`, `429`, `502`, `503`, `504`, timeout/connect
         * exceptions, but not generic [IOException].
         */
        fun strictTransient(
            schedule: Schedule = defaultHttpRetrySchedule(),
            respectRetryAfter: Boolean = true,
            maxRetryAfterDelay: Duration? = null,
        ): HttpRetryOptions =
            HttpRetryOptions(
                schedule = schedule,
                retryOnGenericIoException = false,
                retryableStatuses = defaultStrictRetryableStatuses,
                respectRetryAfter = respectRetryAfter,
                maxRetryAfterDelay = maxRetryAfterDelay,
            )
    }
}

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
 * return a normal [io.ktor.client.statement.HttpResponse] for retryable statuses will not be
 * retried by this policy unless the caller converts those statuses into exceptions. If
 * [HttpRetryOptions.retryableStatuses] includes redirect codes, this policy also classifies
 * [RedirectResponseException].
 */
fun httpThrowableRetryPolicy(options: HttpRetryOptions): RetryPolicy<Throwable> =
    RetryPolicy(options.schedule) { error, _ ->
        when (error) {
            is HttpRequestTimeoutException -> RetryAction.Retry()

            is ConnectTimeoutException,
            is SocketTimeoutException -> RetryAction.Retry()

            is IOException -> if (options.retryOnGenericIoException) RetryAction.Retry() else RetryAction.Stop

            is ServerResponseException -> {
                classifyHttpRetryFromStatus(error.response.status.value, error.response.headers, options)
            }

            is RedirectResponseException -> {
                classifyHttpRetryFromStatus(error.response.status.value, error.response.headers, options)
            }

            is ClientRequestException -> {
                classifyHttpRetryFromStatus(error.response.status.value, error.response.headers, options)
            }

            else -> RetryAction.Stop
        }
    }

/**
 * Broad idempotent retry policy for thrown HTTP failures and transport exceptions.
 *
 * This matches the historical default surface: `408`, `429`, all `5xx`, timeout exceptions, and
 * generic [IOException].
 */
fun httpBroadIdempotentPolicy(
    options: HttpRetryOptions = HttpRetryOptions.broadIdempotent(),
): RetryPolicy<Throwable> =
    httpThrowableRetryPolicy(options)

/**
 * Narrower preset for failures that are usually transient across HTTP clients and intermediaries.
 *
 * This retries `408`, `429`, `502`, `503`, `504`, and timeout/connect exceptions, but not generic
 * [IOException] and not the entire `5xx` range.
 */
fun httpStrictTransientPolicy(
    options: HttpRetryOptions = HttpRetryOptions.strictTransient(),
): RetryPolicy<Throwable> =
    httpThrowableRetryPolicy(options)

/**
 * Historical default throwable policy for idempotent HTTP operations.
 */
fun httpIdempotentDefaultPolicy(): RetryPolicy<Throwable> =
    httpBroadIdempotentPolicy()

/**
 * Default retry policy for idempotent HTTP calls that return [HttpResponse] objects directly.
 *
 * Unlike [httpIdempotentDefaultPolicy], this policy inspects [HttpResponse.status] instead of
 * relying on Ktor response exceptions. The broad default retries `408`, `429`, and `5xx`
 * responses, but callers may supply any retryable status set through [HttpRetryOptions]. When
 * enabled, `Retry-After` is honored for any configured retryable status.
 */
fun httpResponseRetryPolicy(options: HttpRetryOptions): RetryPolicy<HttpResponse> =
    RetryPolicy(options.schedule) { response, _ ->
        classifyHttpRetryFromStatus(response.status.value, response.headers, options)
    }

/**
 * Broad idempotent retry policy for returned [HttpResponse] values.
 */
fun httpBroadIdempotentResponsePolicy(
    options: HttpRetryOptions = HttpRetryOptions.broadIdempotent(),
): RetryPolicy<HttpResponse> =
    httpResponseRetryPolicy(options)

/**
 * Narrower transient retry policy for returned [HttpResponse] values.
 */
fun httpStrictTransientResponsePolicy(
    options: HttpRetryOptions = HttpRetryOptions.strictTransient(),
): RetryPolicy<HttpResponse> =
    httpResponseRetryPolicy(options)

/**
 * Historical default response policy for idempotent HTTP operations.
 */
fun httpIdempotentResponseDefaultPolicy(): RetryPolicy<HttpResponse> =
    httpBroadIdempotentResponsePolicy()

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

/**
 * Run an idempotent HTTP operation with the default throwable policy and explicit [random] source.
 */
suspend fun <T> retryingIdempotentHttpCall(
    random: Random,
    block: suspend () -> T,
): T = retryingIdempotentHttpCall(httpIdempotentDefaultPolicy(), random, block)

/**
 * Run an idempotent HTTP operation with explicit throwable [policy] and [random] source.
 */
suspend fun <T> retryingIdempotentHttpCall(
    policy: RetryPolicy<Throwable>,
    random: Random,
    block: suspend () -> T,
): T = runWithRetry(policy, random, block)

/**
 * Run an idempotent HTTP operation that returns [HttpResponse] with retry support based on
 * [HttpResponse.status], even when the call does not throw for non-success statuses.
 *
 * Retryable responses are drained with [discardRemaining] before the helper waits and retries so
 * the underlying connection can be reused when possible.
 */
suspend fun retryingIdempotentHttpResponseCall(
    policy: RetryPolicy<HttpResponse> = httpIdempotentResponseDefaultPolicy(),
    block: suspend () -> HttpResponse,
): HttpResponse = retryingIdempotentHttpResponseCall(policy, Random.Default, block)

/**
 * Run an idempotent response-returning operation with the default response policy and explicit
 * [random] source.
 */
suspend fun retryingIdempotentHttpResponseCall(
    random: Random,
    block: suspend () -> HttpResponse,
): HttpResponse = retryingIdempotentHttpResponseCall(httpIdempotentResponseDefaultPolicy(), random, block)

/**
 * Run an idempotent response-returning operation with explicit response [policy] and [random] source.
 */
suspend fun retryingIdempotentHttpResponseCall(
    policy: RetryPolicy<HttpResponse>,
    random: Random,
    block: suspend () -> HttpResponse,
): HttpResponse {
    val run = policy.newRun(random)
    while (true) {
        val response = block()
        val delay = run.nextDelay(response) ?: return response
        runCatching { response.discardRemaining() }
        kotlinx.coroutines.delay(delay)
    }
}

/**
 * Run an idempotent HTTP operation with response-status-based retry support and transform the
 * final response into a caller-defined result.
 *
 * This helper retries based on [HttpResponse.status] exactly like
 * [retryingIdempotentHttpResponseCall], but hides the intermediate [HttpResponse] from the caller
 * until a non-retryable response is reached. Retryable responses are drained with
 * [discardRemaining] before retrying. The [transform] block runs only for the final response.
 */
suspend fun <T> retryingIdempotentHttpResponseBodyCall(
    policy: RetryPolicy<HttpResponse> = httpIdempotentResponseDefaultPolicy(),
    request: suspend () -> HttpResponse,
    transform: suspend (HttpResponse) -> T,
): T = retryingIdempotentHttpResponseBodyCall(policy, Random.Default, request, transform)

/**
 * Run an idempotent response-body operation with the default response policy and explicit [random]
 * source.
 */
suspend fun <T> retryingIdempotentHttpResponseBodyCall(
    random: Random,
    request: suspend () -> HttpResponse,
    transform: suspend (HttpResponse) -> T,
): T = retryingIdempotentHttpResponseBodyCall(httpIdempotentResponseDefaultPolicy(), random, request, transform)

/**
 * Run an idempotent response-body operation with explicit response [policy] and [random] source.
 */
suspend fun <T> retryingIdempotentHttpResponseBodyCall(
    policy: RetryPolicy<HttpResponse>,
    random: Random,
    request: suspend () -> HttpResponse,
    transform: suspend (HttpResponse) -> T,
): T =
    transform(retryingIdempotentHttpResponseCall(policy, random, request))

private fun requireFiniteNonNegativeDuration(name: String, duration: Duration) {
    require(duration.isFinite()) { "$name must be finite, was $duration" }
    require(!duration.isNegative()) { "$name must be >= 0, was $duration" }
}

private fun requireFinitePositiveDouble(name: String, value: Double) {
    require(value.isFinite()) { "$name must be finite, was $value" }
    require(value > 0.0) { "$name must be > 0, was $value" }
}

private fun defaultHttpRetrySchedule(): Schedule =
    Schedule.retries(
        maxRetries = 4,
        baseDelay = 200.milliseconds,
        maxDelay = 5.seconds,
        jitterFactor = 0.2,
    )

private fun classifyHttpRetryFromStatus(
    status: Int,
    headers: Headers,
    options: HttpRetryOptions,
): RetryAction =
    if (status in options.retryableStatuses) {
        val retryAfter =
            if (options.respectRetryAfter) parseRetryAfterHeader(headers[HttpHeaders.RetryAfter]) else null
        RetryAction.Retry(clampRetryAfterDelay(retryAfter, options.maxRetryAfterDelay))
    } else {
        RetryAction.Stop
    }

private fun clampRetryAfterDelay(retryAfter: Duration?, maxRetryAfterDelay: Duration?): Duration? =
    when {
        retryAfter == null -> null
        maxRetryAfterDelay == null -> retryAfter
        retryAfter > maxRetryAfterDelay -> maxRetryAfterDelay
        else -> retryAfter
    }

private val defaultStrictRetryableStatuses = setOf(408, 429, 502, 503, 504)

private val defaultBroadRetryableStatuses = buildSet {
    add(408)
    add(429)
    addAll(500..599)
}
