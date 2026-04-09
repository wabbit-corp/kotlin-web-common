# Changelog

This file is the release-notes source of truth for `kotlin-web-common`.

## 1.1.0 - 2026-04-05

Initial public release candidate of the shared Ktor support layer used by the `kotlin-web-*`
client family.

### Added

- composable retry schedules through `Schedule`
- typed retry policies through `RetryPolicy`, `RetryRun`, and `runWithRetry(...)`
- `Retry-After` parsing for delta-seconds, IMF-fixdate, obsolete RFC 850, and `asctime()`
- exception-based and response-based idempotent HTTP retry helpers
- `Etiquette` plus `applyEtiquette(...)`
- `Timeouts`, `applyTimeouts(...)`, and `Timeouts.forStreaming(...)`
- raw diagnostic body sampling helpers

### Behavior Notes

- timeout inputs are validated as positive, finite, whole-millisecond durations
- response-body sampling is raw, destructive, and UTF-8 diagnostic only
- broad HTTP retry defaults target idempotent calls and include `408`, `429`, `5xx`, timeout/connect failures, and generic `IOException`
- response-based retry helpers work even when callers keep `expectSuccess = false`

### Packaging

- published dependency metadata was narrowed to the common dependencies the library actually uses:
  `ktor-client-core`, `kotlinx-serialization-core`, and `kotlinx-datetime`
- a standalone external Gradle smoke consumer was added under `tmp/release-checklist/external-consumer`
