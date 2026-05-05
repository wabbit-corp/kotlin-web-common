# Development

`kotlin-web-common` is a Kotlin Multiplatform library. In the monorepo workspace, build and test it
through the `dev` tooling:

```bash
./dev build kotlin-web-common
```

From a standalone checkout, run Gradle directly:

```bash
./gradlew build
```

## Documentation

Build generated API docs with:

```bash
./gradlew dokkaGeneratePublicationHtml
```

The hand-written docs should explain behavior, failure modes, and copy-pasteable examples. Generated
Dokka output remains the source of truth for exact signatures and platform availability.

## KDoc Standards

Public KDoc should make these details explicit:

- whether a retry helper is exception-driven or response-status-driven
- whether `Retry-After` can override schedule delays
- whether a timeout is request, connect, or socket inactivity based
- whether body sampling consumes the response stream
- which headers are validated or reserved by `Etiquette`
