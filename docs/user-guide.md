# User Guide

## Request Etiquette

Use `Etiquette` when every request from a client wrapper should carry a validated `User-Agent` and
optional contact metadata.

```kotlin
import io.ktor.client.request.HttpRequestBuilder
import one.wabbit.web.common.Etiquette
import one.wabbit.web.common.applyEtiquette

fun HttpRequestBuilder.applyClientHeaders() {
    applyEtiquette(
        Etiquette(
            userAgent = "example-client/1.0 (+https://example.com/contact)",
            referer = "https://example.com",
            extraHeaders = mapOf("X-Client" to "example"),
        ),
    )
}
```

`extraHeaders` is defensively copied and cannot contain `User-Agent` or `Referer`.

## Timeouts

`Timeouts` stores request, connect, and socket timeout values as Kotlin `Duration`s.

```kotlin
import io.ktor.client.request.HttpRequestBuilder
import one.wabbit.web.common.Timeouts
import one.wabbit.web.common.applyTimeouts
import kotlin.time.Duration.Companion.seconds

fun HttpRequestBuilder.applyApiTimeouts() {
    applyTimeouts(
        Timeouts(
            request = 30.seconds,
            connect = 10.seconds,
            socket = 30.seconds,
        ),
    )
}
```

The Ktor client must install `HttpTimeout` for these settings to take effect. For SSE,
long-polling, or other quiet streams, derive a streaming profile:

```kotlin
import one.wabbit.web.common.Timeouts

val streamingTimeouts = Timeouts().forStreaming()
```

## Retry Schedules

`Schedule` is an immutable description of retry delays. Compile it once per retry loop.

```kotlin
import one.wabbit.web.common.Schedule
import one.wabbit.web.common.compile
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val schedule =
    Schedule.exponential(
        base = 200.milliseconds,
        maxRetries = 4,
        maxDelay = 5.seconds,
        jitterFactor = 0.2,
    )

val run = schedule.compile(random = Random(1))
val firstDelay = run.next()
```

`Schedule.retries()` is the standard bounded exponential preset used by the HTTP helpers.

## Retry-After

Use `parseRetryAfterHeader` for server-provided retry hints.

```kotlin
import one.wabbit.web.common.parseRetryAfterHeader
import kotlin.time.Duration.Companion.seconds

check(parseRetryAfterHeader("2.5") == 2.5.seconds)
```

The parser accepts delta seconds and the HTTP-date forms used by `Retry-After`: IMF-fixdate,
obsolete RFC 850, and ANSI C `asctime()`.

## HTTP Retry Helpers

Use exception-driven retries when your Ktor call throws for retryable statuses, usually with
`expectSuccess = true`.

```kotlin
import one.wabbit.web.common.retryingIdempotentHttpCall

suspend fun <T> retryThrownFailures(block: suspend () -> T): T =
    retryingIdempotentHttpCall(block = block)
```

Use response-driven retries when the call returns `HttpResponse` objects directly.

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import one.wabbit.web.common.retryingIdempotentHttpResponseBodyCall

suspend fun fetchText(client: HttpClient, url: String): String =
    retryingIdempotentHttpResponseBodyCall(
        request = { client.get(url) },
        transform = { response -> response.bodyAsText() },
    )
```

The response helpers discard retryable intermediate responses before delaying and retrying.

## Body Sampling

Body sampling is for diagnostics after errors. It consumes the response body channel.

```kotlin
import io.ktor.client.plugins.ResponseException
import one.wabbit.web.common.responseBodySampleOrNull

suspend fun ResponseException.loggableBody(): String? =
    responseBodySampleOrNull(maxLen = 2048)
```

The sample is byte-limited and decoded as UTF-8. It is not charset-aware and should not be used as a
normal response text reader.
