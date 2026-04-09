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
  `RetryPolicy.newRun`, `runWithRetry`, and `retryingIdempotentHttpCall` can now take a `Random`,
  which makes retry timing tests deterministic.

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

- [x] Remove deprecated body-sampling aliases after workspace migration.
  Once local `./kotlin-web-*` consumers were migrated, the temporary `consumeBodyPrefix(...)` and
  `safeBodyPrefix(...)` shims were removed as breaking cleanup.

- [x] Remove the eager large allocation in body sampling.
  The body sampler now reads in chunks instead of allocating `ByteArray(maxLen)` up front.

- [x] Clarify raw-body sampling docs.
  The KDoc now says the helper reads raw bytes from `bodyAsChannel()`, may see compressed/encoded bytes depending on the pipeline, decodes as UTF-8 for diagnostics, and is destructive.

- [x] Tighten timeout validation.
  `Timeouts` now accepts only positive, finite, whole-millisecond durations. Fractional milliseconds and zero are rejected instead of being silently truncated or treated ambiguously.

- [x] Document timeout/plugin/engine caveats.
  `Timeouts` KDoc now calls out that request timeout configuration depends on Ktor’s `HttpTimeout` behavior and engine support, and that the default socket timeout is request/response-oriented rather than streaming-friendly.

- [x] Add an explicit streaming timeout derivation helper instead of changing the global default.
  `Timeouts.forStreaming(...)` now preserves the base connect timeout, disables request timeout,
  and raises the socket stall timeout floor for quiet streaming responses. This keeps the existing
  request/response default stable for current consumers while giving streaming clients a shared
  escape hatch.

- [x] Prove the streaming timeout helper against a real downstream consumer.
  `kotlin-web-openai` now derives its streaming timeout profile through `Timeouts.forStreaming(...)`
  instead of carrying a private copy of the same policy logic.

- [x] Reuse shared HTTP retry builders in downstream clients where the fit is clean.
  `kotlin-web-yt-transcripts` now uses `httpThrowableRetryPolicy(HttpRetryOptions(...))` instead of
  maintaining its own ad hoc throwable HTTP retry classifier.

- [x] Reuse the shared `Retry-After` parser in downstream clients where the local parser was narrower.
  `kotlin-web-wayback` now routes its rate-limit metadata parsing through
  `parseRetryAfterHeader(...)`, which means Wayback error surfaces now understand HTTP-date and
  fractional `Retry-After` values instead of only integer seconds.

- [x] Reuse the shared response-status retry helper in a consumer that keeps `expectSuccess = false`.
  `kotlin-web-ipapicom` now retries transient `5xx` responses through
  `retryingIdempotentHttpResponseCall(...)` while preserving its existing `429`/`X-Ttl` handling
  and stale-cache fallback logic.

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

- [x] Add a response-inspecting retry helper that works without `expectSuccess`.
  The library now provides `httpIdempotentResponseDefaultPolicy()` and
  `retryingIdempotentHttpResponseCall(...)`, which classify retries from `HttpResponse.status`
  directly, honor `Retry-After` for `429` and `5xx`, and discard retryable responses before
  sleeping and retrying.

- [x] Split the HTTP retry surface into strict, broad, and configurable forms.
  The library now exposes:
  `HttpRetryOptions`, `httpThrowableRetryPolicy(...)`, `httpResponseRetryPolicy(...)`,
  `httpBroadIdempotentPolicy(...)`, `httpStrictTransientPolicy(...)`,
  `httpBroadIdempotentResponsePolicy(...)`, and `httpStrictTransientResponsePolicy(...)`.
  The historical default behavior remains available through `httpIdempotentDefaultPolicy()`.

- [x] Improve the body-sampling API beyond a bare `String`.
  The library now provides `BodyPrefixUtf8Sample` and `consumeBodyPrefixUtf8Sample(...)`, which
  expose decoded text, byte count, and whether the configured limit was reached, while keeping
  `consumeRawBodyPrefixUtf8(...)` as a compatibility text-only wrapper.

- [x] Add best-effort body-sample convenience helpers for downstream error handling.
  `consumeRawBodyPrefixUtf8OrNull(...)` and `ResponseException.responseBodySampleOrNull(...)` now
  centralize the common “sample if possible, otherwise null” pattern instead of making every
  consumer spell it with `runCatching`.

- [x] Confirm how workspace consumers use body sampling.
  The local `./kotlin-web-*` call sites use body sampling for error snippets and diagnostics, not
  as a normal response-decoding API. That supports keeping the helper explicitly raw/destructive
  and treating charset-aware text decoding as a separate concern.

- [x] Add compressed/non-UTF-8 body sampling tests.
  Regression coverage now proves that the helper remains UTF-8 diagnostic only and does not
  transparently reinterpret declared charsets or compressed payloads as decoded text.

- [x] Reuse the new body-sample convenience helpers in downstream clients.
  The repeated `runCatching { response.consumeRawBodyPrefixUtf8(2048) }.getOrNull()` boilerplate
  was removed from the touched `kotlin-web-*` consumers so their error adapters now lean on the
  shared common helper instead of each carrying a slightly different copy.

- [x] Tighten test exception assertions.
  Tests that previously used broad `assertFailsWith<Throwable>` have been narrowed where the concrete exception type matters.

## Intentionally Open

- [x] Make `Retry-After` clamping configurable.
  `HttpRetryOptions` now accepts `maxRetryAfterDelay`, which lets callers cap a server-provided
  `Retry-After` delay without changing the default broad policy behavior.

- [x] Add support for caller-configured `3xx + Retry-After`.
  Current state:
  both the throwable and response retry policies now honor caller-configured redirect statuses.
  `httpThrowableRetryPolicy(...)` classifies `RedirectResponseException`, and
  `httpResponseRetryPolicy(...)` already classified normal `HttpResponse.status` values.
  Scope:
  the default broad/strict presets still do not opt into `3xx`; this is explicit opt-in through
  `HttpRetryOptions.retryableStatuses`, which keeps historical behavior stable while removing the
  mismatch between the two helper styles.

- [x] Decide whether to add charset-aware or decoded-body sampling.
  Decision:
  keep the current helper byte-oriented, destructive, and UTF-8 diagnostic only.
  Rationale:
  the downstream audit showed callers use it for error snippets, not as a normal text-decoding API,
  and the raw/decoded/decompressed contract is too pipeline-dependent to justify a broader helper
  without a concrete consumer. Also: do not generalize tiny string-truncation helpers into common;
  that duplication is acceptable when the semantics are already local and obvious.

- [ ] Decide whether to guard naked exponential overflow.
  Current state:
  `Exponential` now documents that unbounded growth can overflow finite `Duration`.
  Open question:
  should the implementation clamp, stop, or surface a clearer runtime failure once the delay leaves the finite range?

- [x] Revisit the default `socket = 15.seconds` timeout.
  Decision:
  keep the current request/response-oriented default for compatibility, and steer streaming
  consumers toward `Timeouts.forStreaming(...)` instead of weakening the baseline behavior for all
  existing clients.

## Follow-Up Tasks

- [x] Migrate workspace consumers off deprecated body-sampling and retry helpers.
  The `./kotlin-web-*` consumers now use `consumeRawBodyPrefixUtf8(...)` and
  `retryingIdempotentHttpCall(...)` instead of the deprecated `safeBodyPrefix(...)` and
  `retryingHttpCall(...)` names.

- [x] Remove deprecated retry/body helper shims as breaking cleanup.
  The deprecated `safeBodyPrefix(...)`, `consumeBodyPrefix(...)`, `retryingHttpCall(...)`, and
  `httpDefaultPolicy()` entry points have been removed from `kotlin-web-common` after the local
  workspace migration.

- [x] Add a higher-level retry helper for callers who want status-based retries without exceptions.
  The library now provides `retryingIdempotentHttpResponseBodyCall(...)`, which retries from
  `HttpResponse.status`, drains retryable responses, and only hands the final response to the
  caller's transform block.

- [ ] Add policy customization affordances.
  Current state:
  callers can now choose strict vs broad presets and can customize retryable status sets,
  generic-`IOException` handling, schedule, and `Retry-After` handling through `HttpRetryOptions`.
  Remaining opportunity:
  add higher-level predicate-style helpers such as `retryIf`, configurable transport exception
  matching beyond the current preset split, or keep migrating consumers if the existing shared
  builders already cover their needs.

- [ ] Migrate streaming consumers to shared timeout helpers where it reduces duplication.
  Current state:
  `kotlin-web-openai` was moved onto `Timeouts.forStreaming(...)`, and the current workspace audit
  did not turn up another obvious SSE/long-lived HTTP client carrying duplicated timeout-derivation
  logic.
  Remaining opportunity:
  if a future streaming client appears, prefer the common helper over copy-pasted timeout
  profiles instead of widening the default timeout behavior preemptively.

- [ ] Migrate more throwable-HTTP consumers to shared retry builders where it reduces duplication.
  Current state:
  `kotlin-web-yt-transcripts` now uses `HttpRetryOptions` plus `httpThrowableRetryPolicy(...)`.
  Remaining opportunity:
  if other consumers start converging on common transport/status retry rules, move them onto the
  shared helper surface instead of growing one-off classifiers.

- [x] Reuse common `Retry-After` parsing anywhere downstream it clearly fits.
  Current state:
  the workspace audit no longer shows any local `Retry-After` parsers that only understand integer
  seconds. The remaining consumers either use `parseRetryAfterHeader(...)` directly, wrap it for
  local result shaping, or intentionally carry the raw header through a domain error without
  parsing it yet.

- [x] Reuse the shared best-effort body-sampling helpers anywhere downstream they clearly fit.
  Current state:
  the obvious repeated wrappers were moved onto `consumeRawBodyPrefixUtf8OrNull(...)` and
  `responseBodySampleOrNull(...)`, including the last remaining `ResponseException` fallback in
  `kotlin-web-ipapicom`.
  Guardrail:
  keep this reuse at the HTTP/response layer; do not pull tiny `String.take(...)` snippets into
  common just for DRY.

- [ ] Migrate more `expectSuccess = false` consumers onto response-based retry helpers where the status semantics fit.
  Current state:
  `kotlin-web-ipapicom` now uses the shared response helper for transient `5xx`.
  Remaining opportunity:
  if other consumers inspect normal `HttpResponse.status` values and want retries before mapping
  them into domain errors, prefer the shared response-policy path over ad hoc loops. The current
  best candidates from the workspace audit are `kotlin-web-edgar` and `kotlin-web-dexscreener`,
  which both still carry very similar `expectSuccess = false` request loops with manual `429` and
  non-success handling around a normal `HttpResponse`.

- [x] Add more timing-focused tests that avoid real sleeping.
  Current state:
  `HttpPolicySpec` now covers actual helper behavior with virtual time for response-status retries,
  including server-provided `Retry-After`, schedule-derived fallback delays, and `Retry-After`
  clamping. That gives the shared retry helpers regression coverage for the timing behavior
  consumers actually depend on.

- [ ] Review whether data-class defensive-copy patterns should stay as-is.
  Current state:
  `Etiquette` and `Schedule.Fixed` use copy-visibility-compatible constructors to preserve value semantics while preventing mutable-input invariant bypass.
  Open question:
  if this pattern becomes awkward elsewhere, consider a shared style guideline for “public data class with defensive snapshot”.

## Suggested Next Order

- [ ] First: keep folding proven downstream HTTP-layer patterns back into `kotlin-web-common` when they repeat.
- [ ] Second: migrate more `expectSuccess = false` consumers onto response-based retry helpers where the status semantics fit.
- [ ] Third: add policy customization affordances such as predicate-style `retryIf` if migrations stop paying off.
- [ ] Fourth: decide whether to guard naked exponential overflow.

## Release Readiness Plan

This section tracks the work needed before `kotlin-web-common` is a credible public Maven Central release.

Reference audit:
- [`tmp/release-checklist/audit.md`](/Users/wabbit/ws/datatron/kotlin-web-common/tmp/release-checklist/audit.md)

### Immediate Blockers

- [x] Rewrite the README into a real public-facing project README.
  Current state:
  [`README.md`](/Users/wabbit/ws/datatron/kotlin-web-common/README.md) now explains the project
  purpose, intended audience, published coordinates, compatibility expectations, key APIs,
  platform caveats, and support path.
  Verification:
  the minimal and practical examples are mirrored by the standalone smoke consumer under
  `tmp/release-checklist/external-consumer`.

- [x] Establish a changelog and release-notes source of truth.
  Current state:
  [`CHANGELOG.md`](/Users/wabbit/ws/datatron/kotlin-web-common/CHANGELOG.md) is now the explicit
  release-notes source of truth for the repository and includes the `1.1.0` release summary.

- [x] Replace the placeholder published description.
  Current state:
  the generated POM description now comes from `root.clj` and matches the project purpose.

- [x] Clean up published dependency metadata.
  Current state:
  `kotlin-web-common` now publishes the narrow dependency set its source actually needs in
  `commonMain`: `ktor-client-core`, `kotlinx-serialization-core`, and `kotlinx-datetime`.
  The duplicate `ktor-serialization-kotlinx-json` entries are gone, and the JVM-only
  `ktor-client-cio`/content-negotiation/auth/legacy-client-serialization dependencies were
  removed from the common publication metadata.
  Guardrail:
  the generator source of truth in `root.clj` was updated alongside `build.gradle.kts` so this
  does not regress on the next regeneration.

- [x] Prove external consumer usability.
  Current state:
  `tmp/release-checklist/external-consumer` is a standalone Gradle JVM consumer that resolves
  `one.wabbit:kotlin-web-common:1.1.0` from `mavenLocal()` by coordinates, compiles against the
  published artifact, and exercises public APIs in a passing smoke test.
  Notes:
  because `kotlin-web-common` currently publishes `one.wabbit:kotlin-no-globals` transitively,
  the smoke flow also publishes `kotlin-no-globals` to `mavenLocal()` first. That dependency path
  is now part of the verified release checklist instead of an assumption.

### Secondary Release Work

- [ ] Audit the public API surface intentionally.
  Focus:
  confirm that the exported types and extension functions are the names and shapes we actually want to support long-term.

- [x] Document Kotlin and platform compatibility expectations.
  Current state:
  [`README.md`](/Users/wabbit/ws/datatron/kotlin-web-common/README.md) now documents the Kotlin,
  Ktor, JVM toolchain, and current published target expectations, plus the timeout/plugin caveats
  that matter across engines and platforms.

- [x] Add or document support expectations.
  Current state:
  [`README.md`](/Users/wabbit/ws/datatron/kotlin-web-common/README.md) now points users to the
  public issue tracker and maintainer contact, and it explains that the library is maintained as
  shared infrastructure for the `kotlin-web-*` client family.

- [ ] Add dependency-integrity and supply-chain basics.
  Required outcome:
  introduce Gradle dependency verification or equivalent lock/integrity checks,
  and generate an SBOM for release artifacts.

- [ ] Verify publishing/signing operational readiness.
  Required outcome:
  confirm namespace ownership, signing key readiness, CI publish path, and post-release verification steps.

### Recommended Order For Release Work

- [ ] First: eliminate or intentionally accept the remaining release-build warnings.
- [ ] Second: finish the supply-chain and release-operations items needed for Maven Central.
- [ ] Third: decide whether to add a dedicated support / security policy document beyond the README support section.
