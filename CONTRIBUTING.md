# Contributing

Thanks for helping improve the agentic-dev Android client (Kotlin + Jetpack Compose, Material 3
Expressive). The server it talks to lives in
[`agentic-dev`](https://github.com/arcatva/agentic-dev).

## Prerequisites

- JDK 17 and the Android SDK (compileSdk 35).
- No global Gradle install needed — use the wrapper (`./gradlew`).

## Build & test

```bash
./gradlew assembleDebug      # debug build
./gradlew testDebugUnitTest  # JVM unit tests (domain transforms, repositories, ViewModels)
./gradlew assembleRelease    # release APK (signed if keystore.properties / KEYSTORE_* present)
```

CI (`.github/workflows/ci.yml`) compiles the app and runs `testDebugUnitTest` on every PR — that
is also the compile gate.

## Dependencies

Dependency versions are pinned in the Gradle build and being centralized into the
`gradle/libs.versions.toml` version catalog (referenced via `libs.*` aliases in
`build.gradle.kts`). Some versions are deliberately pinned for compatibility — keep the
explanatory comments when touching them.

## Project layout (keep responsibilities separated)

- `data/` — the outside world: `net/` (Ktor REST + WebSocket client, `Models.kt`), `repo/`
  (repositories), plus logging and small utilities.
- `domain/` — pure Kotlin transforms (parsing, reducers, formatting); no Android/Compose deps.
- `ui/<feature>/` — Compose screens + ViewModels, one package per feature.
- `di/` — the manual dependency container (`AppContainer`).

Keep `domain/` free of Android imports so it stays unit-testable, and treat `data/net/Models.kt`
as the server contract — changing it must track the backend API.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the high-level map.

## Pull requests

- One coherent change per PR; keep behavior-preserving changes (moves, formatting, version-catalog
  migration) separate from logic changes so diffs stay reviewable.
- Note how you verified it (build + unit tests; screenshots for UI).
