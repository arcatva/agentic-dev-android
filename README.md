# agentic-dev-android

Android client for the [agentic-dev](https://github.com/arcatva/agentic-dev) platform
(Kotlin + Jetpack Compose, Material 3 Expressive). Talks to the backend over HTTP + WebSocket.

## Install

Download the APK from the GitHub Releases page and sideload it (Android 8.0+ / API 26+).
On first launch, point **Host** at your agentic-dev server (`http://<host>:7420`) and log in
with the server's `AGENTIC_PASSWORD`.

## Build

```bash
./gradlew assembleRelease   # signed if keystore.properties (or KEYSTORE_* env vars) present,
                            # otherwise an -unsigned APK
./gradlew assembleDebug     # debug build
./gradlew testDebugUnitTest # JVM unit tests
```

Requires JDK 17 and the Android SDK (API 35). Release signing reads the gitignored
`keystore.properties` locally, or `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` /
`KEY_PASSWORD` env vars in CI — see `.github/workflows/release.yml`.

## Release

Push a `v*` tag — the release workflow builds the signed APK and attaches it to a GitHub
Release. Keep `versionName`/`versionCode` in `app/build.gradle.kts` in step with the tag.

## Notes

- Push notifications (FCM) are optional and OFF until `google-services.json` is added to `app/`
  and the google-services plugin is uncommented (see the TODOs in `app/build.gradle.kts` and
  `AndroidManifest.xml`). The app builds and runs fine without it.

## License
MIT — see [LICENSE](LICENSE).
