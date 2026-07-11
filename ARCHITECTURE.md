# Architecture

High-level map of the agentic-dev Android client (Kotlin + Jetpack Compose, Material 3
Expressive). It is a thin, reactive client for the
[agentic-dev](https://github.com/arcatva/agentic-dev) server, which it reaches over HTTP + a
WebSocket stream on the LAN.

## Layers

```
  ui/<feature>/        Compose screens + ViewModels (home, session, login, providers,
      │                workflow, tree, diagnostics, globalsettings, adopt, newrequest)
      │  observes state / calls
      ▼
  domain/              Pure Kotlin: transcript reducers, parsing, search, formatting
      │                (no Android/Compose imports — unit-tested directly)
      ▼
  data/
    repo/              Repositories: the app's source of truth, expose flows to ViewModels
    net/               Ktor client: REST + WebSocket, Models.kt (the server contract),
      │                cert pinning, LAN discovery, resumable downloads
    log/ util/         Logging, polling, small helpers
      ▼
  di/                  AppContainer — manual dependency wiring (no DI framework)
```

- **`ui/`** — one package per feature; each screen has a `ViewModel` exposing UI state as
  `StateFlow`. Navigation is Compose Navigation (`ui/nav/AppNav.kt`); the home uses Material 3
  adaptive panes.
- **`domain/`** — pure transforms (e.g. `TranscriptReducer`, `SessionSearch`, `CommitGraph`).
  Kept free of Android deps so it's covered by fast JVM unit tests.
- **`data/net/`** — the only place that talks to the server: a Ktor client for REST + the session
  WebSocket stream, with `Models.kt` mirroring the server's JSON. TLS uses cert pinning against
  the server's (often self-signed) certificate.
- **`data/repo/`** — repositories mediate between `net`/local stores and the ViewModels, exposing
  cold/hot flows.
- **`di/AppContainer`** — constructs and holds singletons; screens obtain it via the `Application`.
- **`voice/`** — on-device dictation (Sherpa-ONNX), isolated from the rest of the app.

## Data flow (a session)

1. On launch the user configures the server host and logs in (`ui/login` → `AuthRepository` →
   `data/net`), receiving a bearer token stored locally.
2. Screens observe repository flows; opening a session subscribes to its WebSocket stream.
3. `data/net` decodes stream-json events into `domain` model updates; `domain` reducers fold them
   into transcript state; ViewModels expose it to Compose, which re-renders.

## Notes

- Single `:app` module today with clean internal layering; a `:core:*` / `:feature:*` split is a
  planned future step.
- Push notifications (FCM) are optional and off until `google-services.json` is added.
