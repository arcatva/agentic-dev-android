# agentic-dev Android client — design (v1 core parity)

**Goal:** A native Android app (Kotlin + Jetpack Compose, Material 3 Expressive) for the agentic-dev
platform, talking to the existing Fastify REST + WebSocket backend on the LAN. MD3E is fully realized
only on Android/Compose — this is why we go native rather than approximating in web MUI.

## Scope (v1 — core parity)
- **Login** → password → token (stored).
- **Session list** — search + status filter, live status (poll 2s), status dots, prompt + repos + time.
- **Session detail** — live transcript (WebSocket stream), status, Kill / Delete, follow-up, Resume.
- **New request** — repos (multi), skills (checkboxes), model + effort + workflows toggle, prompt → launch.
- Deferred: workflow agent drill-down, groups, usage meters, multi-agent master-detail.

## Backend API (unchanged; base `http://<host>:7420`)
- `POST /api/login {password}` → `{token}`. Bearer token on all `/api/*`.
- `GET /api/repos` → `{local, remote}`; `GET /api/skills` → `[{name,description}]`.
- `GET /api/sessions` → `{sessions:[Session]}`; `GET /api/sessions/:id` → `{session, log:[string]}`.
- `POST /api/sessions {repos,skills,prompt,model,effort,mode}` → `{id}`.
- `POST /api/sessions/:id/messages {prompt}` → `{ok,since}` (follow-up/resume).
- `DELETE /api/sessions/:id` (kill); `POST /api/sessions/:id/delete`.
- WS `GET /api/sessions/:id/stream?token=&since=` — emits ClaudeEvents (kind: text/prompt/skill/ask/
  agent/workflow/result/retry/agentResult, …). Parse `kind:"text"` (parentToolUseId), `kind:"prompt"`,
  `kind:"result"`, plus the `{kind:"other", raw:{engineExit}}` close signal.

## Architecture
- **Networking** `net/Api.kt`: Ktor `HttpClient(OkHttp)` + ContentNegotiation(json) for REST; Ktor
  WebSockets for the stream. Base URL configurable (a Settings screen / first-run host field); token
  kept in DataStore/SharedPreferences. Cleartext allowed (LAN http) via manifest.
- **Models** `model/`: `@Serializable` data classes mirroring the API (Session, SkillInfo, RepoList,
  ClaudeEvent sealed-ish via JsonElement). The transcript is built from events much like the web
  `renderFromLog`: a list of turns of text parts (markdown rendered via a Compose markdown lib or a
  minimal renderer).
- **UI** `ui/`: Compose screens with Navigation-Compose. `MaterialExpressiveTheme` (material3 1.4.x
  expressive) once validated; dynamic color from wallpaper. Screens: Login, SessionList, SessionDetail,
  NewRequest. ViewModels (lifecycle) hold state + the WS connection.
- **Streaming**: SessionDetail opens the WS; appends text deltas to the current turn; closes on the
  engineExit event; re-fetches session on close. Follow-up posts then reopens with `since`.

## Build / verify
- Toolchain installed locally (cmdline-tools, platform-35, build-tools-35, emulator, system image);
  Gradle 8.10.2. Build `assembleDebug`; verify on a headless emulator (KVM) via `adb` install +
  `screencap`, driving the app to each screen against a running backend.

## Phases
1. Toolchain + minimal Compose app builds + runs on emulator (validate the loop).
2. Material3 Expressive theme + Login + Api(login) → token.
3. Session list (REST poll) + New request (REST).
4. Session detail + WebSocket live transcript + follow-up/kill/delete/resume.
5. Polish MD3E (components, motion, shapes); verify each screen on the emulator.
