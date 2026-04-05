package one.wabbit.web.common

import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Per-request timeout values.
 *
 * `null` leaves the corresponding timeout unset.
 * Positive values must be finite whole-millisecond durations.
 */
data class Timeouts(
    val request: Duration? = 15.seconds,
    val connect: Duration? = 15.seconds,
    val socket: Duration? = 15.seconds,
) {
    init {
        request?.let { validateTimeout("request", it) }
        connect?.let { validateTimeout("connect", it) }
        socket?.let { validateTimeout("socket", it) }
    }
}

fun HttpRequestBuilder.applyTimeouts(t: Timeouts) {
    timeout {
        if (t.request != null) requestTimeoutMillis = t.request.inWholeMilliseconds
        if (t.connect != null) connectTimeoutMillis = t.connect.inWholeMilliseconds
        if (t.socket != null) socketTimeoutMillis = t.socket.inWholeMilliseconds
    }
}

private fun validateTimeout(name: String, duration: Duration) {
    require(duration.isFinite()) { "$name timeout must be finite, was $duration" }
    require(duration > Duration.ZERO) { "$name timeout must be > 0, was $duration" }
    require(duration.inWholeMilliseconds.milliseconds == duration) {
        "$name timeout must be a whole-millisecond duration, was $duration"
    }
}
