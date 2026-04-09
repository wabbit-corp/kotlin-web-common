# kotlin-web-common

`kotlin-web-common` is a Kotlin Multiplatform support library for Ktor-based HTTP clients.
It packages the pieces that tend to get reimplemented across small client wrappers:
retry schedules, `Retry-After` parsing, per-request timeout helpers, request etiquette headers,
and destructive body-prefix sampling for diagnostics.

The library is meant for people building their own API clients on top of Ktor, not for people
looking for a complete HTTP client by itself. It does not choose an engine for you and it does not
hide Ktor. Instead, it gives you a small shared toolkit for the repetitive parts around Ktor
requests.

## Coordinates

```kotlin
dependencies {
    implementation("one.wabbit:kotlin-web-common:1.1.0")

    // Choose your own Ktor engine.
    implementation("io.ktor:ktor-client-cio:3.3.0") // JVM / Android example
}
```

If you are targeting Apple platforms, use the appropriate Darwin engine instead of CIO.

## What It Includes

- `Etiquette` plus `applyEtiquette(...)` for `User-Agent`, `Referer`, and validated extra headers
- `Timeouts`, `applyTimeouts(...)`, and `Timeouts.forStreaming(...)` for per-request Ktor timeout configuration
- `Schedule`, `RetryPolicy`, `runWithRetry(...)`, and `parseRetryAfterHeader(...)`
- exception-based and response-based HTTP retry helpers:
  `retryingIdempotentHttpCall(...)`,
  `retryingIdempotentHttpResponseCall(...)`,
  `retryingIdempotentHttpResponseBodyCall(...)`
- raw diagnostic body sampling:
  `consumeBodyPrefixUtf8Sample(...)`,
  `consumeRawBodyPrefixUtf8(...)`,
  `consumeRawBodyPrefixUtf8OrNull(...)`,
  `responseBodySampleOrNull(...)`

## Compatibility

- Built with Kotlin `2.3.10`
- Built against Ktor `3.3.0`
- JVM builds use toolchain `21`
- Published KMP targets currently include JVM, Android, iOS Arm64, iOS Simulator Arm64, and macOS Arm64

`kotlin-web-common` configures request-level timeouts through Ktor's `HttpTimeout` plugin, so those
timeouts only take effect when that plugin is installed and when the selected Ktor engine supports
the timeout type you are setting.

## Minimal Example

This is the smallest useful slice of the library: parsing `Retry-After` and compiling a retry
schedule.

```kotlin
import one.wabbit.web.common.Schedule
import one.wabbit.web.common.compile
import one.wabbit.web.common.parseRetryAfterHeader
import kotlin.random.Random

val retryAfter = parseRetryAfterHeader("2.5")
check(retryAfter != null)

val run = Schedule.retries(maxRetries = 1).compile(random = Random(1))
val firstDelay = run.next()
check(firstDelay != null)
```

This example is exercised by the standalone smoke consumer in
[`tmp/release-checklist/external-consumer`](./tmp/release-checklist/external-consumer).

## Practical Example

Wrap a Ktor request so you get shared etiquette, shared timeouts, and response-status-based retry
behavior even when the client does not throw on `429` or `5xx`.

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import one.wabbit.web.common.Etiquette
import one.wabbit.web.common.Timeouts
import one.wabbit.web.common.applyEtiquette
import one.wabbit.web.common.applyTimeouts
import one.wabbit.web.common.retryingIdempotentHttpResponseBodyCall

suspend fun fetchText(
    client: HttpClient,
    url: String,
): String =
    retryingIdempotentHttpResponseBodyCall(
        request = {
            client.get(url) {
                applyEtiquette(
                    Etiquette(
                        userAgent = "example-client/1.0 (+https://example.com/contact)",
                    ),
                )
                applyTimeouts(Timeouts())
            }
        },
        transform = { response ->
            response.bodyAsText()
        },
    )
```

That shape is also exercised by the standalone smoke consumer.

## Sharp Edges

- `retryingIdempotentHttpCall(...)` is exception-driven. It only retries HTTP statuses when the
  wrapped Ktor call throws response exceptions, which usually means `expectSuccess = true` or
  custom response validation.
- If you keep `expectSuccess = false`, use `retryingIdempotentHttpResponseCall(...)` or
  `retryingIdempotentHttpResponseBodyCall(...)` instead.
- The default HTTP retry presets are for idempotent calls. The broad default retries `408`, `429`,
  all `5xx`, timeout/connect failures, and generic `IOException`.
- `consumeBodyPrefixUtf8Sample(...)` and the string wrappers are destructive. They consume from
  `bodyAsChannel()`, sample raw bytes, and decode as UTF-8 for diagnostics. They are not
  charset-aware text helpers.
- `Timeouts()` defaults are request/response-oriented. For SSE, long-polling, or quiet streaming
  responses, prefer `Timeouts.forStreaming(...)`.
- `Schedule.Exponential` can overflow finite `Duration` values if you leave it unbounded.

## Release Notes

The release-notes source of truth for this repository is [`CHANGELOG.md`](./CHANGELOG.md).

## Support

- Public issue tracker: [github.com/wabbit-corp/kotlin-web-common/issues](https://github.com/wabbit-corp/kotlin-web-common/issues)
- Maintainer contact: [wabbit@wabbit.one](mailto:wabbit@wabbit.one)

This library is maintained as shared infrastructure for the broader `kotlin-web-*` client family,
so changes are driven by real client usage first and then documented here as the public surface
stabilizes.
