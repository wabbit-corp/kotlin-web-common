// SPDX-License-Identifier: AGPL-3.0-or-later

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
 *
 * These values are applied through Ktor's `HttpTimeout` request configuration and only take
 * effect when that plugin and the current engine support the corresponding timeout type. The
 * default `socket` timeout is tuned for request/response APIs and may be too aggressive for
 * streaming, SSE, or long-polling workloads because it measures inactivity between packets. Use
 * [forStreaming] when you want to preserve the base connect timeout, disable the request timeout,
 * and ensure a longer socket stall timeout for streaming responses.
 */
data class Timeouts(
    /** Overall request timeout, or null to leave it unset. */
    val request: Duration? = 15.seconds,
    /** Connection-establishment timeout, or null to leave it unset. */
    val connect: Duration? = 15.seconds,
    /** Socket inactivity timeout, or null to leave it unset. */
    val socket: Duration? = 15.seconds,
) {
    init {
        request?.let { validateTimeout("request", it) }
        connect?.let { validateTimeout("connect", it) }
        socket?.let { validateTimeout("socket", it) }
    }
}

/**
 * Default socket inactivity timeout used by [forStreaming].
 */
val DefaultStreamingSocketTimeout: Duration = 60.seconds

/**
 * Applies [t] to this request through Ktor's `HttpTimeout` request configuration.
 *
 * The target [io.ktor.client.HttpClient] must have the `HttpTimeout` plugin installed, and the
 * selected Ktor engine must support the requested timeout type.
 */
fun HttpRequestBuilder.applyTimeouts(t: Timeouts) {
    timeout {
        if (t.request != null) requestTimeoutMillis = t.request.inWholeMilliseconds
        if (t.connect != null) connectTimeoutMillis = t.connect.inWholeMilliseconds
        if (t.socket != null) socketTimeoutMillis = t.socket.inWholeMilliseconds
    }
}

/**
 * Derives a streaming-friendly timeout profile from a request/response-oriented base profile.
 *
 * The derived profile:
 * - disables the request timeout
 * - preserves the connect timeout
 * - preserves a larger existing socket timeout, or raises it to [minimumSocketTimeout]
 */
fun Timeouts.forStreaming(minimumSocketTimeout: Duration = DefaultStreamingSocketTimeout): Timeouts {
    validateTimeout("minimumSocketTimeout", minimumSocketTimeout)
    val streamingSocketTimeout =
        when {
            socket == null -> minimumSocketTimeout
            socket < minimumSocketTimeout -> minimumSocketTimeout
            else -> socket
        }
    return copy(request = null, socket = streamingSocketTimeout)
}

private fun validateTimeout(name: String, duration: Duration) {
    require(duration.isFinite()) { "$name timeout must be finite, was $duration" }
    require(duration > Duration.ZERO) { "$name timeout must be > 0, was $duration" }
    require(duration.inWholeMilliseconds.milliseconds == duration) {
        "$name timeout must be a whole-millisecond duration, was $duration"
    }
}
