# agentic-dev-android — MVVM 重写设计

日期:2026-06-19 · 状态:待用户批准 · 目标:整体一次性重写到可出包

## 1. 目标与边界

把整个 Android 客户端(Kotlin + Compose, Material3, ~3735 行)重做成清晰的 MVVM
分层,遵循聊天软件最佳实践。**约束:**

- **服务端 API / 协议 / 所有可观察行为保持不变**(REST 端点、WebSocket `since` 语义、
  stream-json 解析规则、深链 `agentic://session/<id>`、推送)。
- **业务逻辑不变**:登录、轮询、流式、跟进、resume、kill、workflow、diff、附件、语音听写
  的行为与现状一致。
- **唯一有意的行为改进 = 聊天渲染最佳实践**(见 §7),因为这正是"按聊天最佳实践重做"的本意。
- 交付:worktree 内完成 → push master → 主 checkout 出 release APK → 放 `outbox/`。

### 决策(已拍板)
- **DI:手写 `AppContainer`**(无 Hilt/Koin;零注解处理、编译最快、完全可测)。
- **状态:每屏一个不可变 `UiState` data class,经 `StateFlow` 暴露。**
- **测试:为纯领域变换 + 关键 ViewModel 写 JVM 单测**(用假 `AgenticApi`)。

---

## 2. 现状最大问题(来自架构映射,带证据)

1. **没有数据层接缝**:`Api` 是具体类,在 `App.kt:152` 构造后按引用 prop-drill 进 ~7 个
   composable,FCM 服务还另建第 2 个(`AgenticMessagingService.kt:51`)。→ 全 app 不可测。
2. **`SessionScreen.kt`(1026 行)是上帝对象**:领域模型 + 9 个纯变换 + ~25 个 `remember` +
   4 个并发网络 `LaunchedEffect` + 进程级全局 `transcriptCache`(`:138`)+ 输入状态机;
   `nodes` 被 3 个回调各自改写,无单一真相源。
3. **backend-mode(streaming vs classic)= `awaitingInput != null`**,一个没命名的领域概念,
   散在 ~6 处内联重算(`:461,510,520,539,763-794`)。
4. **状态 composition 作用域、易丢**:几乎全是 `remember`,无 ViewModel/SavedStateHandle;
   进程死亡 / 配置变化即丢。`transcriptCache` 是这缺层的补丁。
5. **token/auth 无主**:Store、`Api.token`、unauthorized flag、FCM 服务 4 处各持一份,手动同步。
6. **两套导航** + 用 `MainActivity` 公有字段当深链事件总线(`MainActivity.kt:21`)。

---

## 3. 目标分层

```
┌─ UI (Jetpack Compose) ──────────────────────────────────────┐
│  纯展示:接收 UiState、发事件给 VM。无 api.* 调用、无网络副作用。 │
│  保留纯 view:Markdown, FadingEdge, Theme, 状态→色彩 mapper。   │
├─ ViewModel (每屏一个) ───────────────────────────────────────┤
│  暴露 val uiState: StateFlow<XUiState> + 事件函数;viewModelScope。│
│  导航参数/可恢复状态走 SavedStateHandle。                       │
├─ Repository ────────────────────────────────────────────────┤
│  AuthRepository(App 级)  SessionsRepository(App 级)           │
│  WorkflowsRepository(会话级)  FilesRepository                  │
│  单一真相源、轮询、缓存、流合并、错误归一。                       │
├─ Data source ───────────────────────────────────────────────┤
│  AgenticApi (interface) ← KtorAgenticApi (impl, 现 Api 的搬迁) │
├─ Domain (纯) ───────────────────────────────────────────────┤
│  Node 模型 + 9 个纯变换 + TranscriptReducer + BackendMode      │
│  + 状态分类(TERMINAL/isActive/status→visual)。全部可单测。    │
└─────────────────────────────────────────────────────────────┘
```

### DI:AppContainer
- 新增 `AgenticApp : Application`,构造唯一 `AppContainer`,持有:`AgenticApi`、
  `AuthRepository`、`SessionsRepository`、`WorkflowsRepository`、`FilesRepository`、
  `SettingsStore`。负责 `Api`/OkHttp/WS 的生命周期(`close()`)——补上现在 UI 端无人关闭的缺口。
- ViewModel 经一个 `ViewModelProvider.Factory`(读 `AppContainer`)创建;Compose 里用
  `viewModel(factory = …)`。新增依赖 `androidx.lifecycle:lifecycle-viewmodel-compose`。

---

## 4. 领域层(domain/)

把 `SessionScreen.kt:118-368` 的纯逻辑整体搬出,**不改算法**:

- `Node` 密封模型(PromptNode/TextNode/AnswerNode/ThinkingNode/ToolNode/StepGroupNode/
  SkillNode/SpawnNode/WorkflowNode/AttachmentNode/AskNode)。
- 纯函数:`buildFromLog`、`applyEvent`、`frameBusy`、`groupTools`、`interleaveShared`、
  `markAnsweredAsks`、`parseAskQuestions`、`toolSummary`、`toolDetail`、`appendText/appendThinking`、
  `splitAttachments`。
- `enum BackendMode { STREAMING, CLASSIC }` + `fun Session.backendMode()`(`awaitingInput != null`
  → STREAMING),替代散落的内联判断。
- 状态分类统一成一个 mapper(替代 `App.kt:110-119` / `DiffPane.statusLetter` / `WorkflowScreen`
  各自的字符串匹配);`TERMINAL`、`WorkflowRun.isActive()`、`WORKFLOW_DONE` 迁到 domain。
- `TranscriptReducer`:把"持久日志一次性构建 + 实时帧增量折叠"封装成一个可测的归约器,
  作为 §7 增量渲染的核心。

---

## 5. 数据层(data/)

- `AgenticApi`(interface):抽取 `Api.kt` 全部方法(login/sessions/session/usage/workflows/
  workflowAgent/repos/skills/create/followUp/uploadFile/fileBytes/outbox/kill/delete/
  registerDevice/templates/diffFiles/discard/stream)。`KtorAgenticApi` 为实现。
- `Models.kt` 的 DTO 原样保留(线格式不动);领域谓词迁到 domain。
- **`login()` 的隐藏副作用**(`Api.kt:63` 内部写 `token`)收归 `AuthRepository`,接口返回纯 token,
  由 repo 决定写入。

### Repository
- **`AuthRepository`(App 级,单一真相源)**:`val token: StateFlow<String?>`;`login(host,pw)`、
  `logout()`、`onUnauthorized()`(401 集中处理,替代每次重组重挂的回调)、`registerFcm()`;
  持久化经 `SettingsStore`(沿用 SharedPreferences,但 token 以 StateFlow 暴露)。FCM 服务也改用它,
  消除第 4 份 token。
- **`SessionsRepository`(App 级)**:
  - `sessions: Flow<List<Session>>`(内部统一轮询);`usage()`。
  - `session(id)`、`create()`、`followUp()`、`resume()`、`kill()`、`delete()`。
  - **持有 transcript 缓存**(替代进程级全局 map,生命周期归 repo,带上界淘汰)。
  - `transcript(id): Flow<List<Node>>` —— 一次性 `buildFromLog` + 单一实时流合并,
    **单一真相源**(消除 3 回调各改 `nodes` 的问题),内部用 `TranscriptReducer` 增量更新。
  - 持有 `pendingPrompts`(乐观提示),供列表页发起、详情页消费(替代 shell 的桥接 map)。
- **`WorkflowsRepository`(会话级)**:`runs(id): Flow<List<WorkflowRun>>`、`agentTranscript(id,runId,agentId)`、
  `outbox(id)`。详情页 rail 与 WorkflowScreen **共用一份**,不再各自轮询。
- **`FilesRepository`**:upload/download/fileBytes/diffFiles/discard。
- **横切**:统一 `Result`/`AppError` + 一次性事件流(`SharedFlow<UiEvent>` 驱动 toast/snackbar),
  替代当前到处吞异常;一个 `pollFlow(intervalMs, stopWhen)` 复用 4 处轮询。

---

## 6. ViewModel + 导航

每屏一个 VM + 一个 `UiState`:
- `LoginViewModel` / `LoginUiState`(host/pw 为 view 输入,busy/err 在 state)。
- `HomeViewModel` / `HomeUiState`(列表、usage、筛选;轮询走 repo)。
- `NewRequestViewModel` / `NewRequestUiState`(整个表单 hoist;`applyTemplate` 的 `{{var}}`
  替换抽成纯 UseCase 单测)。
- `SessionViewModel` / `SessionUiState`(transcript、session、backendMode、输入状态机:
  locked/composable/canSend/queued 都成 state 字段,一处计算)。
- `WorkflowViewModel`、`DiffViewModel`。
- 详情页 rail 与 WorkflowScreen 共享同一 `WorkflowsRepository` 实例(按会话 scope)。

**导航统一**:单一 `NavHost` + 类型安全路由(Navigation-Compose 2.8 `@Serializable` route):
`Login`、`Home`、`NewRequest`、`Session(id, initialPrompt?)`、`Workflow(id)`、`Diff(id)`。
- 删除 `Home.kt:179-203` 的字符串目标状态机,改用 NavController;宽屏 list+detail 用
  list-detail 布局承载两个目的地。
- 深链改成 `Session` 路由上的真正 `navDeepLink("agentic://session/{id}")`,
  **废除 Activity 字段总线**。

---

## 7. 聊天渲染最佳实践(并入,修上轮诊断的卡顿)

1. **`reverseLayout = true`** 的 LazyColumn:底部即原点 → **删除**整套 pin 机制
   (`pinnedOnce`、12 帧 `scrollToItem` 循环、`withFrameNanos`、`canScrollForward`、
   `alpha(0f)` 遮罩、pinning spinner)。
2. **稳定 per-node key**:位置/turn 基的稳定 id;流式 TextNode 用稳定 id 而非内容做 key
   (修折叠状态绑错卡片)。
3. **增量 transcript**:repo 的 `TranscriptReducer` 增量追加/原地更新(SnapshotStateList),
   `groupTools`/`interleaveShared` 改增量(只处理尾部),消除每帧全量 O(n) rescan。
4. **输入框秒出**:`session` 从 repo 缓存 seed → 输入框立即渲染(修"输入框最后出现");
   输入框渲染与 transcript 加载解耦。
5. **单一真相源**:transcript 只由 repo 这一处更新,消除 3 回调竞态与可见重新 pin。

---

## 8. 测试

JVM 单测(`app/src/test/`,主 checkout `gradle test` 跑):
- domain 9 个纯变换 + `TranscriptReducer`(各种事件序列 → 期望节点)+ `backendMode()` +
  状态分类 + 模板 `{{var}}` 替换。
- ViewModel 状态逻辑用假 `AgenticApi`(输入状态机、轮询停止规则、乐观提示、ask 已答标记)。

---

## 9. 不做(YAGNI)
- 不换网络栈(Ktor 保留)、不换序列化、不引入 Room/分页/多模块。
- 不改服务端、不改 DTO 线格式、不动 Compose/M3 版本钉子。
- 不做与本目标无关的重构。

---

## 10. 风险
- `reverseLayout` 翻转顺序,`groupTools`/`interleaveShared` 假定自然序 → 喂反序或改写并单测覆盖。
- 稳定 key 必须用位置/turn 基(流式 TextNode 内容会变)。
- `Collapsible` 的 `animateContentSize` 配 reverseLayout 展开可能上跳 → 实测。
- worktree 不能构建 → 只能在主 checkout 验证;先靠单测兜底领域层。

---

## 11. 落地顺序(实现阶段细化为 plan)
1. domain 抽离 + 单测(纯函数,先锁正确性)。
2. `AgenticApi` 接口 + Ktor 实现;`AppContainer` + `AgenticApp`。
3. Repository(Auth → Sessions → Workflows → Files)+ 单测。
4. ViewModel 逐屏 + UiState。
5. 导航统一(类型安全路由 + 深链)。
6. 各屏 Compose 改造为纯展示;SessionScreen 拆分 + 并入 §7 渲染最佳实践。
7. FCM 服务改用 AuthRepository。
8. push master → 主 checkout 出 APK → outbox 验证三场景(活跃会话、长会话、输入框秒出)。
