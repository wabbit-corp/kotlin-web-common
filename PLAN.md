# kotlin-web-common Review Checklist

This file consolidates the review feedback for `kotlin-web-common` as of 2026-04-05.
It is meant to answer three questions:

1. What review items have already been addressed?
2. What is still intentionally open?
3. What should be tackled next if another pass is requested?

## Completed

- [x] Make retry caps real hard ceilings after jitter.
  The retry schedule now applies jitter first and caps last, so `maxDelay` is enforced as an actual ceiling instead of a best-effort target.

- [x] Validate jitter inputs consistently.
  `Schedule.retries`, `Schedule.exponential`, and raw `Schedule.jittered(...)` now reject out-of-range jitter factors instead of relying on deeper constructors to fail later.

- [x] Validate retry override delays.
  `RetryAction.Retry(overrideDelay)` now rejects negative or non-finite delays instead of silently turning them into immediate retries or runtime surprises.

- [x] Document and test the override-delay semantics.
  The current design is explicit: an override delay consumes one schedule step and bypasses shaping like caps/cutoffs for that retry step, but schedule exhaustion still stops retries.

- [x] Add deterministic retry randomness injection.
  `RetryPolicy.newRun`, `runWithRetry`, `retryingIdempotentHttpCall`, and the deprecated `retryingHttpCall` can now take a `Random`, which makes retry timing tests deterministic.

- [x] Enforce finite schedule invariants.
  Schedule constructors now reject non-finite durations or factors where appropriate:
  `Recurs.interval`, `Fixed.delays`, `Exponential.initialDelay`, `Exponential.factor`, `Jittered` scalers, `WithCutoff.duration`, and `CapDelay.maxDelay`.

- [x] Make `forever()` actually forever.
  `Schedule.forever(...)` no longer expands to `Int.MAX_VALUE` repeats. It now has a dedicated infinite `Schedule.Forever` variant.

- [x] Add a `cutoff(...)` convenience.
  `Schedule.WithCutoff` now has a matching helper function so cutoff composition is symmetrical with `capped(...)`.

- [x] Clarify cutoff semantics.
  `WithCutoff` is now documented as cumulative scheduled delay, not wall-clock elapsed time.

- [x] Add missing schedule behavior tests.
  Tests now cover `Sequence`, `Now`, `Never`, `Forever`, finite-input rejection, fixed-list defensive copying, jitter ceiling behavior, and multiple `Retry-After` edge cases.

- [x] Make reserved `Etiquette` header checks case-insensitive.
  `extraHeaders` can no longer sneak in `user-agent`, `USER-AGENT`, `referer`, etc.

- [x] Defensively copy `Etiquette.extraHeaders`.
  Passing a mutable map no longer allows post-construction mutation that bypasses constructor validation.

- [x] Validate `Etiquette` header names and values with Ktor rules.
  Header names and values are now checked up front with Ktor’s header validators instead of assuming all caller input is already valid.

- [x] Use header constants for `User-Agent` and `Referer`.
  The implementation now uses Ktor header constants instead of repeating string literals.

- [x] Rename the destructive body sampler to say what it actually does.
  The new canonical name is `consumeRawBodyPrefixUtf8(...)`. It explicitly communicates:
  raw bytes, UTF-8 diagnostic decoding, and destructive consumption.

- [x] Keep compatibility aliases for body sampling.
  `consumeBodyPrefix(...)` and `safeBodyPrefix(...)` still exist as deprecated aliases so downstream users do not break immediately.

- [x] Remove the eager large allocation in body sampling.
  The body sampler now reads in chunks instead of allocating `ByteArray(maxLen)` up front.

- [x] Clarify raw-body sampling docs.
  The KDoc now says the helper reads raw bytes from `bodyAsChannel()`, may see compressed/encoded bytes depending on the pipeline, decodes as UTF-8 for diagnostics, and is destructive.

- [x] Tighten timeout validation.
  `Timeouts` now accepts only positive, finite, whole-millisecond durations. Fractional milliseconds and zero are rejected instead of being silently truncated or treated ambiguously.

- [x] Document timeout/plugin/engine caveats.
  `Timeouts` KDoc now calls out that request timeout configuration depends on Ktor’s `HttpTimeout` behavior and engine support, and that the default socket timeout is request/response-oriented rather than streaming-friendly.

- [x] Expand `Retry-After` parsing beyond IMF-fixdate only.
  The parser now supports numeric values, IMF-fixdate, obsolete RFC 850, and ANSI C `asctime()` forms.

- [x] Implement RFC 850 two-digit-year rollover correctly.
  The rollover logic now uses the real “more than 50 years in the future” rule against normalized instants instead of crude year-only comparison.

- [x] Handle leap seconds narrowly and correctly.
  Only `23:59:60` is accepted. Other `hh:mm:60` forms are rejected.

- [x] Enforce semantic HTTP-date validity.
  Parsed dates now reject:
  mismatched weekday/date combinations, years earlier than 1900, impossible times, and invalid calendar dates.

- [x] Make malformed or absurd `Retry-After` values return `null`.
  Oversized numeric inputs such as `1e100` no longer leak into infinite `Duration` values that fail later in retry classification.

- [x] Add strong `Retry-After` parser coverage.
  Tests now cover numeric values, obsolete date formats, rollover boundaries, leap seconds, invalid time fields, weekday mismatch, pre-1900 years, and oversized numeric values.

- [x] Document the hidden Ktor contract for status retries.
  The default retry policy and helper KDoc now explain that HTTP status retries only happen when the wrapped call throws Ktor response exceptions, which usually requires `expectSuccess = true` or response validation.

- [x] Add an executable test for the no-exception status case.
  There is now a test proving that `retryingIdempotentHttpCall { client.get(...).bodyAsText() }` does not retry a `429` when the call returns normally.

- [x] Tighten test exception assertions.
  Tests that previously used broad `assertFailsWith<Throwable>` have been narrowed where the concrete exception type matters.

## Intentionally Open

- [ ] Add a response-inspecting retry helper that works without `expectSuccess`.
  Current state:
  the default helper is still exception-driven.
  Consequence:
  callers that do not enable `expectSuccess` or response validation will not retry `408`, `429`, `503`, etc.
  Candidate solution:
  add a higher-level helper for `HttpResponse` that classifies by `response.status` directly instead of requiring exceptions as control flow.

- [ ] Decide whether to narrow the default HTTP retry policy.
  Current state:
  `httpIdempotentDefaultPolicy()` still retries generic `kotlinx.io.IOException`, `408`, `429`, and all `5xx`.
  Risk:
  some `IOException` values may come from caller code rather than transport turbulence, and some `5xx` failures are not transient.
  Candidate solution:
  either keep the broad default and document it aggressively, or split it into stricter presets plus a `retryIf` customization path.

- [ ] Decide whether `Retry-After` should bypass the default 5-second cap forever.
  Current state:
  `Retry-After` is allowed to override the schedule cap entirely.
  Risk:
  a small helper can unexpectedly sleep for a very long time.
  Candidate solution:
  make `Retry-After` clamping configurable, with the current behavior as an explicit opt-in or documented default.

- [ ] Add support for `3xx + Retry-After` if desired.
  Current state:
  the default policy does not classify redirect exceptions for retries.
  Context:
  HTTP allows `Retry-After` on some redirects, but the library currently ignores that path.

- [ ] Revisit the body sampler API shape.
  Current state:
  `consumeRawBodyPrefixUtf8(...)` is honest about raw bytes and UTF-8 decoding, but still returns only a `String`.
  Missing options:
  a richer diagnostic result could include:
  whether sampling truncated the body, how many raw bytes were consumed, and possibly the raw bytes themselves.

- [ ] Add charset-aware or decoded-body sampling only if the pipeline contract is acceptable.
  Current state:
  the helper is intentionally raw-byte oriented.
  Tradeoff:
  charset-aware text diagnostics are useful, but only if the helper can clearly define whether it works on raw bytes, decoded bytes, decompressed bytes, or some engine/plugin-specific combination.

- [ ] Add compressed/non-UTF-8 body sampling tests.
  Current state:
  the docs now warn about raw/compressed/UTF-8-only behavior, but there is still no dedicated regression test for compressed bytes or non-UTF-8 payloads.

- [ ] Decide whether to guard naked exponential overflow.
  Current state:
  `Exponential` now documents that unbounded growth can overflow finite `Duration`.
  Open question:
  should the implementation clamp, stop, or surface a clearer runtime failure once the delay leaves the finite range?

- [ ] Revisit the default `socket = 15.seconds` timeout.
  Current state:
  the docs call out that it is request/response oriented and too aggressive for quiet streaming workloads.
  Open question:
  should the default remain opinionated for snappy APIs, or should `socket` default to `null` to avoid surprising long-lived connections?

## Follow-Up Tasks

- [ ] Migrate downstream `safeBodyPrefix(...)` call sites to `consumeRawBodyPrefixUtf8(...)`.
  Current state:
  many `kotlin-web-*` clients still import and call the deprecated alias.
  Goal:
  keep the alias for compatibility in the short term, but migrate downstream code so the behavior is obvious at call sites.

- [ ] Add a higher-level retry helper for callers who want status-based retries without exceptions.
  Suggested API shape:
  a helper specialized for `HttpResponse` or Ktor request builders that inspects `status`, honors `Retry-After`, and can close/discard failed responses safely before retrying.

- [ ] Add policy customization affordances.
  Suggested options:
  `retryIf`, configurable retryable status sets, configurable transport exception matching, configurable `Retry-After` clamp, and possibly separate presets for “strict transient” vs “broad idempotent”.

- [ ] Add more timing-focused tests that avoid real sleeping.
  Current state:
  most policy tests are already cheap, but more deterministic tests around retry delay selection, especially for HTTP statuses and `Retry-After`, would keep future refactors safer.

- [ ] Review whether data-class defensive-copy patterns should stay as-is.
  Current state:
  `Etiquette` and `Schedule.Fixed` use copy-visibility-compatible constructors to preserve value semantics while preventing mutable-input invariant bypass.
  Open question:
  if this pattern becomes awkward elsewhere, consider a shared style guideline for “public data class with defensive snapshot”.

## Suggested Next Order

- [ ] First: add a response-based retry helper or another explicit API for status-driven retries without `expectSuccess`.
- [ ] Second: split the default retry policy into stricter/configurable presets.
- [ ] Third: improve the raw-body diagnostic API beyond `String`.
- [ ] Fourth: revisit timeout defaults for streaming workloads.
