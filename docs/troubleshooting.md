# Troubleshooting

## Timeouts Do Not Fire

`applyTimeouts` configures request timeout attributes. The Ktor client still needs the `HttpTimeout`
plugin installed, and the selected Ktor engine must support the requested timeout type.

If a streaming endpoint is quiet for longer than the socket timeout, use:

```kotlin
import one.wabbit.web.common.Timeouts

val streaming = Timeouts().forStreaming()
```

## HTTP Statuses Are Not Retried

`retryingIdempotentHttpCall` retries thrown failures. It sees HTTP status codes only when Ktor throws
response exceptions, usually with `expectSuccess = true` or custom response validation.

When your client keeps `expectSuccess = false`, use `retryingIdempotentHttpResponseCall` or
`retryingIdempotentHttpResponseBodyCall` so retries are based on returned `HttpResponse.status`.

## Retry-After Is Too Large

Enable `maxRetryAfterDelay` in `HttpRetryOptions` to cap server-provided delays:

```kotlin
import one.wabbit.web.common.HttpRetryOptions
import one.wabbit.web.common.Schedule
import kotlin.time.Duration.Companion.seconds

val options =
    HttpRetryOptions.broadIdempotent(
        schedule = Schedule.retries(),
        maxRetryAfterDelay = 30.seconds,
    )
```

## Body Samples Are Garbled

Body sampling reads raw bytes and decodes them as UTF-8 diagnostics. It ignores the response
charset, and compressed bodies may look like binary noise unless the Ktor pipeline has already
decoded them.

Sampling is destructive: it consumes and cancels the response body channel.

## Header Validation Fails

`Etiquette` validates header names and values with Ktor's header validators. It also rejects
`User-Agent` and `Referer` inside `extraHeaders`; use the dedicated constructor parameters for those
headers.
