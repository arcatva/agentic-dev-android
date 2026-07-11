# Security Policy

## Reporting a vulnerability

Please report security issues **privately**, not in public issues or PRs:

- Open a [GitHub security advisory](https://github.com/arcatva/agentic-dev-android/security/advisories/new), or
- email the maintainer (see the GitHub profile for `@arcatva`).

Include repro steps and the app version/commit. Please allow time for a fix before public
disclosure.

## Security model

This is the Android client for a local, operator-run [agentic-dev](https://github.com/arcatva/agentic-dev)
server; it connects over the LAN.

- **Transport** — HTTPS + secure WebSocket. The server typically uses a self-signed certificate,
  which the app **pins** (`data/net/CertPinning.kt`) after first trust, so a swapped cert on the
  same host is rejected.
- **Authentication** — the user enters the server's login password (`AGENTIC_PASSWORD`); the app
  exchanges it at `/api/login` for a bearer token and stores the token locally (app-private
  storage). The password is not persisted in plaintext beyond what's needed to obtain the token.
- **No secrets in the repo** — the release keystore (`*.keystore`, `keystore.properties`) and
  `google-services.json` are gitignored and injected at build time (locally, or via CI secrets —
  see `.github/workflows/release.yml`). Never commit them or paste tokens/hosts into issues.

## Supply chain

Dependency versions are pinned in the Gradle build (being centralized into the
`gradle/libs.versions.toml` version catalog) and kept current with Dependabot. Native libraries under `app/src/main/jniLibs/` (sherpa-onnx / onnxruntime)
are third-party binaries — provenance and license notices are tracked with the voice module.
