package one.wabbit.web.common

import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class Timeouts(
    val request: Duration? = 15.seconds,
    val connect: Duration? = 15.seconds,
    val socket: Duration? = 15.seconds,
)

fun HttpRequestBuilder.applyTimeouts(t: Timeouts) {
    timeout {
        if (t.request != null) requestTimeoutMillis = t.request.inWholeMilliseconds
        if (t.connect != null) connectTimeoutMillis = t.connect.inWholeMilliseconds
        if (t.socket != null) socketTimeoutMillis = t.socket.inWholeMilliseconds
    }
}
