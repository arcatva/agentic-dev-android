# agentic-dev-android — Claude guidance

The Android client for the agentic-dev platform (Kotlin + Jetpack Compose, Material 3
Expressive). It talks to the backend in `~/src/agentic-dev` over HTTP + WebSocket. UI code is
under `app/src/main/java/dev/agentic/`.

## PR workflow — auto-merge after Codex review
Land changes through a PR, hands-off. **This OVERRIDES the generic session-workflow rule**
("don't merge it yourself / conflicts are the user's call") AND this repo's older "push directly
to master" convention.

1. **Adversarially verify the change BEFORE you commit.** Fan out sub-agents with `delegate`
   (per the model-routing rule in the session CLAUDE.md — leave `model` UNSET so each task is
   cost-routed; give them a `title` + `phase`). Give each worker the diff and a distinct
   *refutation* angle — logic/regression, edge cases & error paths, security, and test/coverage
   gaps — and tell it to actively try to prove the change wrong. Fix anything real before
   committing. This is our own pre-flight gate; it runs in ADDITION to Codex review, not instead.
2. Commit on the session branch, push, open a PR with `gh pr create` (targets `master`). One PR
   per coherent change. Opening a non-draft PR triggers a Codex review automatically; if it was a
   draft, mark it ready (or comment `@codex review`) to trigger one.
3. **Wait for the Codex review** (posts a few minutes after the PR opens). Codex leaves comment(s)
   with suggestions, or — nothing to flag — just reacts with 👍. Poll its output (Codex's bot
   author login contains `codex`):
   `gh api repos/arcatva/agentic-dev-android/issues/<n>/comments`,
   `gh api repos/arcatva/agentic-dev-android/pulls/<n>/comments`,
   `gh api repos/arcatva/agentic-dev-android/issues/<n>/reactions` (to catch a 👍 left with no comment), and
   `gh api repos/arcatva/agentic-dev-android/pulls/<n>/reviews`.
   (No comment + a 👍 = looks good; no response after ~10 min → proceed without it.)
4. **Triage every Codex comment.** Adopt the correct, worthwhile ones (commit + push on the
   branch); skip wrong/stylistic/low-value ones, noting which and why in one line. (You may
   comment `@codex address that feedback` to have Codex fix it, but prefer fixing it yourself.)
5. **Auto-merge:** `gh pr merge <n> --rebase`. Resolve any rebase conflicts yourself (read both
   sides, preserve both intents) and finish — do NOT stop to ask about conflicts.
6. Merge is **aggressive** (user's choice) and does NOT wait on tests. The repo ships a Gradle
   wrapper, so `./gradlew testDebugUnitTest` in the worktree is a cheap pre-merge compile check
   when in doubt; release-signed APKs still come from the main checkout or the release workflow
   (see below).
7. Stop and ask ONLY if Codex flags a genuine correctness/security blocker you cannot
   confidently fix, or a conflict has two truly incompatible intents.

The APK build + delivery below is a separate downstream step (run it when an APK is requested);
it happens in the main checkout after the PR has merged to `master`.

## Build & deliver an APK — do NOT build in the worktree

The repo now ships a Gradle wrapper (`./gradlew`, pinned 8.10.2), so compiling works anywhere —
but your worktree has **no signing keystore** (`release.keystore`/`keystore.properties` are
gitignored and live only in the main checkout), so a worktree `assembleRelease` comes out
UNSIGNED. Release-signed APKs come from the main checkout (below) or from the `release` GitHub
Actions workflow (tag `v*`; signs via ANDROID_KEYSTORE_* repo secrets).

The flow is: **finish in the worktree → land via PR auto-merge (above) → build in the main
checkout → deliver.**

1. **Land your changes via the PR workflow above** (PR → Codex review → auto-merge to
   `master`). The APK is built from `master`, so build only after the PR has merged.

2. **Build the APK in the original source checkout** `~/src/agentic-dev-android` (it has Gradle and
   the release keystore) — not in the worktree:
   ```bash
   cd ~/src/agentic-dev-android && git pull --ff-only origin master
   ~/.local/share/gradle-8.10.2/bin/gradle assembleRelease
   ```
   - `gradle` is NOT on PATH — use the explicit path above.
   - Output: `~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk` (release-signed).
   - Use `assembleDebug` instead only if you specifically need a debug build.

3. **Deliver it to the user**: copy the built APK into your worktree's `outbox/` (the platform shows
   files there to the user for download — see the global outbox note). **Always rename it to
   `<YYYYMMDD-HHMM>.apk`** (build date/time only) so the user can tell builds apart — do NOT deliver
   the bare `app-release.apk`:
   ```bash
   mkdir -p ./outbox && cp ~/src/agentic-dev-android/app/build/outputs/apk/release/app-release.apk \
     "./outbox/$(date +%Y%m%d-%H%M).apk"
   ```

## Notes
- Land changes via the PR workflow above (PR + auto-merge after Codex review); the APK build
  still happens in the main checkout after the merge. (This replaces the old "push directly to
  master" convention.)
- Compose Material3 is pinned to `1.4.0-alpha18` (exposes the Expressive APIs); don't bump it
  without checking the compileSdk/AGP constraints in `app/build.gradle.kts`.

## Releases — on-demand only (user's choice)
Merging PRs never bumps versions or publishes releases (ci.yml only runs unit tests). Cut a
release ONLY when the user asks for one:
1. In the release PR bump BOTH `versionName` and `versionCode` in `app/build.gradle.kts` —
   versionCode must strictly increase or installed devices refuse the upgrade.
2. After merge, tag master `v<versionName>` and push the tag — `.github/workflows/release.yml`
   builds the signed APK (ANDROID_KEYSTORE_* repo secrets) and attaches it to the GitHub
   Release. This replaces the main-checkout gradle build for release delivery; the main-checkout
   flow remains for ad-hoc APKs.
3. Never move or re-push an already-published tag.
