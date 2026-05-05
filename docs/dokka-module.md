# Module kotlin-web-common

`kotlin-web-common` is a Kotlin Multiplatform support library for Ktor-based HTTP clients.

It provides shared request etiquette headers, per-request timeout helpers, retry schedules,
`Retry-After` parsing, idempotent HTTP retry policies, and destructive response-body prefix sampling
for diagnostics.

## Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("one.wabbit:kotlin-web-common:1.1.0")
}
```

Choose and add the Ktor engine appropriate for your target platform.

## Quick Start

```kotlin
import one.wabbit.web.common.Schedule
import one.wabbit.web.common.compile
import one.wabbit.web.common.parseRetryAfterHeader
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

val retryAfter = parseRetryAfterHeader("2.5")
check(retryAfter == 2.5.seconds)

val run = Schedule.retries(maxRetries = 1).compile(random = Random(1))
check(run.next() != null)
check(run.next() == null)
```

## Main Areas

- `Etiquette` and `applyEtiquette` set validated `User-Agent`, `Referer`, and extra headers.
- `Timeouts`, `applyTimeouts`, and `Timeouts.forStreaming` configure Ktor request timeouts.
- `Schedule`, `RetryPolicy`, `RetryRun`, and `runWithRetry` model reusable retry loops.
- `HttpRetryOptions` and HTTP policy helpers classify retryable Ktor exceptions or responses.
- `consumeBodyPrefixUtf8Sample` and related helpers destructively sample response bodies for logs.

## Important Semantics

- `retryingIdempotentHttpCall` is exception-driven; use response-based helpers when `expectSuccess`
  is false.
- `Retry-After` can override schedule delays and can be capped with `maxRetryAfterDelay`.
- Body sampling consumes the response channel and decodes bytes as UTF-8 diagnostics, not as
  charset-aware text.
- Request timeout settings require Ktor's `HttpTimeout` plugin and engine support.
