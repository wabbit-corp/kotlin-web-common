# API Reference

Generate exact signatures locally with:

```bash
./gradlew dokkaGeneratePublicationHtml
```

## Request Helpers

- `Etiquette`: validated `User-Agent`, optional `Referer`, and additional headers.
- `applyEtiquette`: applies an `Etiquette` value to a Ktor `HttpRequestBuilder`.
- `Timeouts`: request, connect, and socket timeout values.
- `DefaultStreamingSocketTimeout`: default socket timeout floor for streaming profiles.
- `applyTimeouts`: applies timeout values through Ktor request timeout configuration.
- `Timeouts.forStreaming`: disables request timeout and raises socket timeout for long-lived streams.

## Schedules And Retry State

- `Schedule`: immutable retry-delay description.
- `Schedule.retries`: bounded exponential schedule used by default HTTP policies.
- `Schedule.forever`, `Schedule.fixed`, `Schedule.exponential`: schedule factories.
- `Schedule.jittered`, `limited`, `capped`, and `cutoff`: schedule combinators.
- `Schedule.compile`: creates a mutable `StatefulSchedule`.
- `StatefulSchedule.next`: returns the next delay or null.
- `RetryAction`: classifier result, either `Stop` or `Retry`.
- `RetryPolicy`: reusable retry classifier plus schedule.
- `RetryRun`: mutable state for one retry execution.
- `runWithRetry`: retries a suspending block for a configured throwable type.

## HTTP Policies

- `HttpRetryOptions`: options shared by throwable and response policies.
- `httpThrowableRetryPolicy`: converts HTTP/transport throwables into retry decisions.
- `httpBroadIdempotentPolicy`: broad default throwable policy.
- `httpStrictTransientPolicy`: narrower throwable policy.
- `httpIdempotentDefaultPolicy`: historical default throwable policy.
- `httpResponseRetryPolicy`: converts returned `HttpResponse` values into retry decisions.
- `httpBroadIdempotentResponsePolicy`: broad response policy.
- `httpStrictTransientResponsePolicy`: narrower response policy.
- `httpIdempotentResponseDefaultPolicy`: historical default response policy.
- `retryingIdempotentHttpCall`: exception-driven retry wrapper.
- `retryingIdempotentHttpResponseCall`: response-status-driven retry wrapper.
- `retryingIdempotentHttpResponseBodyCall`: response-status-driven retry wrapper with transform.

Broad HTTP retry defaults cover `408`, `429`, all `5xx`, timeout/connect exceptions, and generic
`IOException`. Strict defaults cover `408`, `429`, `502`, `503`, `504`, timeout/connect exceptions,
and do not retry generic `IOException`.

## Retry-After And Body Sampling

- `parseRetryAfterHeader`: parses delta seconds and supported HTTP-date forms into a delay.
- `BodyPrefixUtf8Sample`: diagnostic body-prefix value.
- `consumeBodyPrefixUtf8Sample`: destructively samples raw response bytes.
- `consumeRawBodyPrefixUtf8`: string-only wrapper for body-prefix sampling.
- `consumeRawBodyPrefixUtf8OrNull`: best-effort nullable wrapper.
- `responseBodySampleOrNull`: best-effort sampling from Ktor `ResponseException`.
