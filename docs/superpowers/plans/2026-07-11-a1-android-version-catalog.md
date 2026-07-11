# A1 — Android 版本目录迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `agentic-dev-android` 所有依赖与插件从内联声明迁到 Gradle 版本目录 `gradle/libs.versions.toml`，行为零变化（解析出的依赖图逐字节一致）。

**Architecture:** 新增 `gradle/libs.versions.toml`（Gradle 7.4+ 自动发现，无需改 `settings.gradle.kts`），把 `build.gradle.kts`（根）与 `app/build.gradle.kts` 里的版本号/坐标搬进 `[versions]/[libraries]/[plugins]/[bundles]`，两个 build 文件改用 `libs.*` / `alias(libs.plugins.*)` 别名。版本一字不改、兼容性注释原样保留。用"迁移前后 `:app:dependencies` 输出 diff 为空"证明不改行为。

**Tech Stack:** Gradle 8.10.2（wrapper）、AGP 8.7.2、Kotlin 2.0.21、Compose 1.9.0、单 `:app` 模块。

## Global Constraints

以下值从现有 `app/build.gradle.kts` / 根 `build.gradle.kts` 逐字复制，**任何一个都不得改动**：
- `agp = 8.7.2`；`kotlin = 2.0.21`（三个 kotlin 插件同版本）
- `compose = 1.9.0`；`material3 = 1.4.0-alpha18`；`material-icons-extended = 1.7.8`；`adaptive = 1.2.0`
- `activity-compose = 1.9.3`；`lifecycle = 2.8.7`；`navigation-compose = 2.8.4`
- `ktor = 2.3.12`；`kotlinx-serialization-json = 1.7.3`
- `firebase-bom = 33.1.2`；`kotlinx-coroutines-play-services = 1.8.1`
- `junit = 4.13.2`；`kotlinx-coroutines-test = 1.8.1`
- **保留** `app/build.gradle.kts` 中所有兼容性注释（material3 为何锁 1.4.0-alpha18、compose 1.9.0 已验证、lifecycle 2.8.7 lint 禁用等）。
- 不改 `settings.gradle.kts`、不改 `:app` 模块结构、不动 `google-services` 的 TODO 注释块、不动 `signingConfigs`/`buildTypes`/`lint` 块。
- 目标：`./gradlew :app:assembleDebug testDebugUnitTest` 通过，且迁移前后 `:app:dependencies` 输出一致。

---

## 前置：worktree 构建环境

本仓库是 worktree，缺机器本地的构建环境。执行前从主 checkout 软链（依 CLAUDE.md）：

```bash
cd /home/arcatva/src/agentic-worktrees/5a8b295e-d758-4aa8-aa58-a42663018e22/agentic-dev-android
ln -sfn "${AGENTIC_SRC_ROOT:-$HOME/src}/agentic-dev-android/local.properties" local.properties
# 可选：复用主 checkout 的 gradle 缓存以加速（不复用构建输出）
[ -d "${AGENTIC_SRC_ROOT:-$HOME/src}/agentic-dev-android/.gradle" ] && ln -sfn "${AGENTIC_SRC_ROOT:-$HOME/src}/agentic-dev-android/.gradle" .gradle || true
```

`local.properties` 是 gitignored 的 SDK 指针，切勿 `git add` 该软链。

---

## Task 1: 捕获迁移前依赖基线（不改行为的证据）

**Files:**
- Create（临时，不提交）：`/tmp/deps-before.txt`

**Interfaces:**
- Produces：`/tmp/deps-before.txt`（Task 4 用它 diff 证明依赖图未变）

- [ ] **Step 1: 生成迁移前的运行时依赖图**

Run:
```bash
cd /home/arcatva/src/agentic-worktrees/5a8b295e-d758-4aa8-aa58-a42663018e22/agentic-dev-android
./gradlew -q :app:dependencies --configuration debugRuntimeClasspath > /tmp/deps-before.txt
```
Expected: 命令成功退出，`/tmp/deps-before.txt` 非空（含 androidx/ktor/firebase 依赖树）。

- [ ] **Step 2: 确认构建基线为绿**

Run:
```bash
./gradlew -q :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL（既有单测全过）。若此步失败，说明是环境问题，先修环境再继续，不要开始迁移。

---

## Task 2: 新增 `gradle/libs.versions.toml`

**Files:**
- Create: `gradle/libs.versions.toml`

**Interfaces:**
- Produces：版本目录别名 `libs.plugins.{android-application,kotlin-android,kotlin-compose,kotlin-serialization}`、`libs.bundles.{compose,compose-adaptive,lifecycle,ktor}`、以及各库别名（见文件内容）。Task 3/4 消费这些别名。

- [ ] **Step 1: 写入目录文件（版本逐字复制自现有 build 文件）**

Create `gradle/libs.versions.toml`：
```toml
[versions]
# ── 构建工具链 ──────────────────────────────────────────────
# AGP 8.7.x 是 material3 1.4.0-alpha18 / Expressive API 施加的上限
# （1.5.0-alpha 线需 Compose 1.12 → compileSdk 37 → 更新工具链）
agp = "8.7.2"
# Kotlin 2.0.x 在 kotlin-android / compose-compiler / serialization 三插件间保持一致
kotlin = "2.0.21"

# ── Compose ────────────────────────────────────────────────
# 1.9.0 已验证可在 AGP 8.7.2 / compileSdk 35 / Gradle 8.10.2 上构建
compose = "1.9.0"
# material3 1.4.0-alpha18 将 Expressive API 公开暴露；不要升到 1.5.0-alpha
# （需 Compose 1.12 → compileSdk 37 → AGP 升级）
material3 = "1.4.0-alpha18"
materialIconsExtended = "1.7.8"
# adaptive 1.2.0 — 三栏主页的原生 pane 展开拖拽手柄
adaptive = "1.2.0"

# ── AndroidX ───────────────────────────────────────────────
activityCompose = "1.9.3"
# lifecycle 2.8.7 — NullSafeMutableLiveData lint 在 app/build.gradle.kts 中被禁用
# （与 AGP 8.7.2 内置 lint 二进制不兼容）；保持钉版
lifecycle = "2.8.7"
navigationCompose = "2.8.4"

# ── 网络 ───────────────────────────────────────────────────
# Ktor 锁在 2.x（3.x 为破坏性大版本，不在本次范围）
ktor = "2.3.12"
kotlinxSerializationJson = "1.7.3"

# ── Firebase ───────────────────────────────────────────────
firebaseBom = "33.1.2"
kotlinxCoroutinesPlayServices = "1.8.1"

# ── 测试 ───────────────────────────────────────────────────
junit = "4.13.2"
kotlinxCoroutinesTest = "1.8.1"

[libraries]
compose-ui = { group = "androidx.compose.ui", name = "ui", version.ref = "compose" }
compose-foundation = { group = "androidx.compose.foundation", name = "foundation", version.ref = "compose" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview", version.ref = "compose" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling", version.ref = "compose" }
compose-material3 = { group = "androidx.compose.material3", name = "material3", version.ref = "material3" }
compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended", version.ref = "materialIconsExtended" }
compose-material3-adaptive = { group = "androidx.compose.material3.adaptive", name = "adaptive", version.ref = "adaptive" }
compose-material3-adaptive-layout = { group = "androidx.compose.material3.adaptive", name = "adaptive-layout", version.ref = "adaptive" }
compose-material3-adaptive-navigation = { group = "androidx.compose.material3.adaptive", name = "adaptive-navigation", version.ref = "adaptive" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycle" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
ktor-client-content-negotiation = { group = "io.ktor", name = "ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-websockets = { group = "io.ktor", name = "ktor-client-websockets", version.ref = "ktor" }
ktor-client-mock = { group = "io.ktor", name = "ktor-client-mock", version.ref = "ktor" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebaseBom" }
firebase-messaging-ktx = { group = "com.google.firebase", name = "firebase-messaging-ktx" }
kotlinx-coroutines-play-services = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-play-services", version.ref = "kotlinxCoroutinesPlayServices" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesTest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }

[bundles]
# implementation 依赖聚合（ui-tooling 是 debug-only，故不入 bundle）
compose = [
    "compose-ui",
    "compose-foundation",
    "compose-ui-tooling-preview",
    "compose-material3",
    "compose-material-icons-extended",
]
compose-adaptive = [
    "compose-material3-adaptive",
    "compose-material3-adaptive-layout",
    "compose-material3-adaptive-navigation",
]
lifecycle = [
    "androidx-lifecycle-runtime-compose",
    "androidx-lifecycle-viewmodel-compose",
    "androidx-lifecycle-process",
]
ktor = [
    "ktor-client-okhttp",
    "ktor-client-content-negotiation",
    "ktor-serialization-kotlinx-json",
    "ktor-client-websockets",
]
```

- [ ] **Step 2: 校验目录语法（build 仍应绿，此时目录未被引用）**

Run:
```bash
./gradlew -q help
```
Expected: BUILD SUCCESSFUL，无 "Invalid catalog definition" 报错。

- [ ] **Step 3: 提交**

```bash
git add gradle/libs.versions.toml
git commit -m "build(android): add Gradle version catalog (libs.versions.toml)

Mirror all current dep/plugin versions verbatim; not yet referenced.
Behavior-preserving prep for A1 catalog migration."
```

---

## Task 3: 根 `build.gradle.kts` 改用插件别名

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:**
- Consumes：`libs.plugins.{android-application,kotlin-android,kotlin-compose,kotlin-serialization}`（Task 2）

- [ ] **Step 1: 用别名替换内联插件声明**

把根 `build.gradle.kts` 整体替换为：
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

- [ ] **Step 2: 校验插件解析**

Run:
```bash
./gradlew -q help
```
Expected: BUILD SUCCESSFUL（插件从目录解析成功）。

- [ ] **Step 3: 提交**

```bash
git add build.gradle.kts
git commit -m "build(android): use version-catalog plugin aliases in root build"
```

---

## Task 4: `app/build.gradle.kts` 迁到目录别名（保留注释）

**Files:**
- Modify: `app/build.gradle.kts`（仅 `plugins {}` 块与 `dependencies {}` 块；其余不动）

**Interfaces:**
- Consumes：Task 2 的库/bundle/插件别名

- [ ] **Step 1: 替换 `plugins {}` 块**

把 `app/build.gradle.kts` 顶部的 `plugins {}` 块替换为（保留 google-services 的 TODO 注释）：
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // TODO: uncomment once google-services.json is placed in app/ (get it from Firebase Console,
    //       project package dev.agentic).  Also add the matching root-project plugin line — see
    //       the TODO block in AndroidManifest.xml for the full instructions.
    // id("com.google.gms.google-services")
}
```

- [ ] **Step 2: 替换 `dependencies {}` 块（注释保留在对应依赖旁）**

把 `dependencies {}` 整块替换为：
```kotlin
dependencies {
    // Compose —— 版本经由 catalog 统一管理。material3 1.4.0-alpha18 公开 Expressive API 且
    // 依赖 compose 1.8.x（仍以 compileSdk 35 为目标）；compose 1.9.0 已验证在 AGP 8.7.2 上构建。
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)

    // lifecycle 2.8.7：runtime-compose + viewmodel-compose + process（ProcessLifecycleOwner，
    // 用于热返回时强制每个活跃会话重连）。lint 禁用见下方 android{} lint 块。
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.navigation.compose)

    // Material3 adaptive panes（单树 list/detail/extra + 原生 pane 展开拖拽手柄）。
    implementation(libs.bundles.compose.adaptive)

    // networking: Ktor(REST + WebSocket) + kotlinx-serialization
    implementation(libs.bundles.ktor)
    implementation(libs.kotlinx.serialization.json)

    // Firebase Cloud Messaging —— 会话完成推送。
    // TODO: 构建前把 google-services.json 放进 app/ 并启用上方 google-services 插件。
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)
    // kotlinx-coroutines-play-services 提供 Firebase Task<T> 的 .await()
    implementation(libs.kotlinx.coroutines.play.services)

    // 单元测试（JVM）——纯领域变换、仓库（fake Api）、ViewModel。
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // 用脚本化响应驱动 ResumableDownloader（中途断流、206 续传）。
    testImplementation(libs.ktor.client.mock)
}
```

- [ ] **Step 3: 生成迁移后依赖图并 diff（关键：证明不改行为）**

Run:
```bash
./gradlew -q :app:dependencies --configuration debugRuntimeClasspath > /tmp/deps-after.txt
diff /tmp/deps-before.txt /tmp/deps-after.txt && echo "DEPS IDENTICAL"
```
Expected: 打印 `DEPS IDENTICAL`，diff 无输出。若有差异，说明某个坐标/版本迁错了——逐条比对 catalog 与原始声明修正，直到 diff 为空。

- [ ] **Step 4: 全量构建 + 单测**

Run:
```bash
./gradlew :app:assembleDebug testDebugUnitTest
```
Expected: BUILD SUCCESSFUL；单测数量与 Task 1 基线一致。

- [ ] **Step 5: 提交**

```bash
git add app/build.gradle.kts
git commit -m "build(android): migrate app module to version catalog aliases

Dependency graph verified byte-identical (debugRuntimeClasspath diff empty).
Versions and compat comments preserved verbatim."
```

---

## Task 5: 开 PR

- [ ] **Step 1: 推分支并开 PR（不自合并）**

Run:
```bash
git push -u origin "$(git rev-parse --abbrev-ref HEAD)"
gh pr create --fill --title "build(android): 迁移到 Gradle 版本目录 (A1)" \
  --body "把所有依赖/插件迁到 gradle/libs.versions.toml。行为不变：debugRuntimeClasspath 依赖图迁移前后逐字节一致；assembleDebug + 单测通过。版本与兼容性注释原样保留。

路线图见 agentic-dev/docs/superpowers/specs/2026-07-11-enterprise-refactor-design.md 的 §4.5 / A1。"
```
Expected: PR 创建成功，targeting 默认分支。等用户评审，不 merge。

---

## Self-Review（对照 spec §4.5 / A1）

- **Spec 覆盖**：§4.5 要求"迁入所有依赖/插件、版本不改、保留注释、build 瘦身为别名、行为零变化"——Task 2 建目录、Task 3/4 改引用并保留注释、Task 4 Step 3 用 diff 证明零变化。✅
- **占位扫描**：无 TBD/TODO（google-services 的 TODO 是原有业务注释、需原样保留，非计划占位）。✅
- **一致性**：Task 2 定义的别名（`libs.bundles.compose`、`libs.compose.ui.tooling`、`libs.plugins.kotlin.compose` 等）与 Task 3/4 引用一致；bundle 不含 debug-only 的 `compose-ui-tooling`。✅
- **保守约束**：所有版本逐字复制、Ktor 锁 2.x、material3 锁 alpha18；未触碰 `signingConfigs`/`buildTypes`/`lint`/`settings.gradle.kts`。✅
