# agentic-dev-android MVVM 重写 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 agentic-dev-android 重写为分层 MVVM(Compose 纯展示 / ViewModel / Repository / Api 接口 / 纯 domain),业务行为不变,并入聊天渲染最佳实践。

**Architecture:** UI(Compose, 接收 `StateFlow<UiState>` + 发事件)→ ViewModel(`viewModelScope`)→ Repository(Auth/Sessions/Workflows/Files,单一真相源 + 轮询 + 缓存)→ `AgenticApi` 接口(Ktor 实现)→ domain(纯函数变换 + TranscriptReducer + BackendMode + 状态分类)。DI 用手写 `AppContainer`。

**Tech Stack:** Kotlin, Jetpack Compose, Material3 1.4.0-alpha18, Ktor 2.3.12(REST+WS), kotlinx-serialization, kotlinx-coroutines, navigation-compose 2.8.4, lifecycle-viewmodel-compose, JUnit + kotlinx-coroutines-test。

## Global Constraints

- compileSdk/AGP/toolchain 与 Compose pin 不变:Material3 `1.4.0-alpha18`、compose `1.8.2`;不升级。
- 服务端 API / 协议 / WS `since` 语义 / stream-json 解析规则 / DTO 线格式:**不改**。
- 深链 scheme:`agentic://session/<id>`,保持可用。
- 不在 worktree 内构建(无 gradle wrapper/keystore);单测与 APK 均在主 checkout 跑/出。
- 包根:`dev.agentic`。新代码与旧代码并存,到对应屏幕重写完成后再删旧文件(保持每步可编译)。
- 每个 ViewModel 暴露 `val uiState: StateFlow<XUiState>`,`UiState` 为不可变 data class。
- 纯 domain 函数无 Android/Compose/Ktor 依赖(纯 Kotlin,可 JVM 单测)。
- 提交粒度:每个 Task 末尾 commit;commit message 末尾加 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。

---

## 文件结构(决策锁定)

```
dev/agentic/
  AgenticApp.kt                  Application,持 AppContainer
  MainActivity.kt                仅托管 NavHost(重写)
  di/AppContainer.kt             所有单例 + Api 生命周期
  di/ViewModelFactory.kt         ViewModelProvider.Factory(读 AppContainer)
  domain/
    Node.kt                      sealed Node 模型(从 SessionScreen 迁出)
    Transcript.kt                buildFromLog/applyEvent/appendText/appendThinking/splitAttachments
    TranscriptGrouping.kt        groupTools/interleaveShared/markAnsweredAsks + 常量
    AskParsing.kt                parseAskQuestions
    ToolDisplay.kt               toolSummary/toolDetail
    BackendMode.kt               BackendMode + Session.backendMode()
    Status.kt                    StatusVisual/statusVisual/TERMINAL/isActive/WORKFLOW_DONE
    TranscriptReducer.kt         增量折叠(history + live tail)
    Template.kt                  applyTemplate {{var}} 替换(纯)
  data/
    net/AgenticApi.kt            interface
    net/KtorAgenticApi.kt        实现(现 Api.kt 搬迁)
    net/Models.kt                DTO(迁移,线格式不变;领域谓词移走)
    net/Outcome.kt               Outcome<T> = Success/Failure(AppError)
    SettingsStore.kt             token/host 持久化,token 暴露 StateFlow
    repo/AuthRepository.kt
    repo/SessionsRepository.kt
    repo/WorkflowsRepository.kt
    repo/FilesRepository.kt
    util/Polling.kt              pollFlow(intervalMs){...}
  ui/
    nav/AppNav.kt                NavHost + 类型安全路由 + 深链
    components/                  Theme/Markdown/FadingEdge/StatusIndicator/VoiceDictationField(共享)
    login/    LoginScreen.kt   LoginViewModel.kt
    home/     HomeScreen.kt    HomeViewModel.kt
    newrequest/ NewRequestScreen.kt NewRequestViewModel.kt
    session/  SessionScreen.kt SessionViewModel.kt  Transcript.kt(transcript 列表 composables)
    workflow/ WorkflowScreen.kt WorkflowViewModel.kt  AgentTranscriptPane.kt
    diff/     DiffPane.kt      DiffViewModel.kt
    attachments/ Attachments.kt
test/ (app/src/test/java/dev/agentic/)
  domain/*Test.kt   data/repo/*Test.kt   ui/*ViewModelTest.kt
```

---

## 阶段总览
- **Phase 0** 依赖 + Application/AppContainer 脚手架
- **Phase 1** domain 纯函数 + 单测(锁正确性)
- **Phase 2** AgenticApi 接口 + Ktor 实现 + Outcome + SettingsStore
- **Phase 3** Repository(Auth/Sessions/Workflows/Files)+ 单测
- **Phase 4** ViewModel + UiState(逐屏)+ 单测
- **Phase 5** 导航统一(类型安全路由 + 深链)
- **Phase 6** 各屏 Compose 改造为纯展示 + SessionScreen 渲染最佳实践
- **Phase 7** FCM 改用 AuthRepository、删旧文件、push、出 APK、验证

---

## Phase 0 — 脚手架

### Task 0.1: 加依赖 + 包目录

**Files:**
- Modify: `app/build.gradle.kts`(deps 块)
- Modify: `app/src/main/AndroidManifest.xml`(注册 `AgenticApp`)

**Interfaces:**
- Produces: 可用的 `androidx.lifecycle:lifecycle-viewmodel-compose`、`kotlinx-coroutines-test`(testImpl)、`junit`。

- [ ] **Step 1: 加依赖**

在 `app/build.gradle.kts` 的 dependencies 块加:
```kotlin
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```

- [ ] **Step 2: 注册 Application**

`AndroidManifest.xml` 的 `<application>` 加 `android:name=".AgenticApp"`。

- [ ] **Step 3: 提交**
```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml
git commit -m "build: add viewmodel-compose + test deps; register AgenticApp"
```

### Task 0.2: AgenticApp + 空 AppContainer

**Files:**
- Create: `app/src/main/java/dev/agentic/AgenticApp.kt`
- Create: `app/src/main/java/dev/agentic/di/AppContainer.kt`

**Interfaces:**
- Produces: `class AgenticApp : Application { val container: AppContainer }`;`class AppContainer(app: Application)`(后续阶段往里加字段)。

- [ ] **Step 1: 写 AppContainer 壳**
```kotlin
package dev.agentic.di
import android.app.Application
class AppContainer(val app: Application) {
    // 后续阶段填充:api / settings / repositories;并负责 api.close()
}
```
- [ ] **Step 2: 写 AgenticApp**
```kotlin
package dev.agentic
import android.app.Application
import dev.agentic.di.AppContainer
class AgenticApp : Application() {
    lateinit var container: AppContainer; private set
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}
```
- [ ] **Step 3: 提交**
```bash
git add app/src/main/java/dev/agentic/AgenticApp.kt app/src/main/java/dev/agentic/di/AppContainer.kt
git commit -m "feat: AgenticApp + empty AppContainer"
```

---

## Phase 1 — domain(纯函数 + 单测)

> 把 `SessionScreen.kt:118-368` 的纯逻辑搬到 `domain/`,**算法逐字不变**,只改包/可见性(顶层 `fun`)。
> 旧 `SessionScreen.kt` 暂保留其内部副本以保持编译,Phase 6 重写该屏时删除副本。
> `Json { ignoreUnknownKeys = true }` 在 domain 内私有持有。

### Task 1.1: Node 模型

**Files:**
- Create: `domain/Node.kt`
- Test: 无(纯数据类)

**Interfaces:**
- Produces: `sealed interface Node` 及全部 data class(`PromptNode(text:String, at:Long=0)`、`TextNode(text:String, reasoning:Boolean=false)`、`AnswerNode(text)`、`ThinkingNode(text)`、`ToolNode(name,summary,detail)`、`StepGroupNode(items:List<Node>)`、`SkillNode(name)`、`SpawnNode(type,desc)`、`WorkflowNode(name)`、`AttachmentNode(path,fromUser:Boolean=false,at:Long=0)`、`AskNode(question:AskQuestion, answered:Boolean=false, answer:String="")`)。

- [ ] **Step 1:** 从 `SessionScreen.kt:120-132` 原样复制全部 Node 定义到 `domain/Node.kt`,包名 `dev.agentic.domain`,`import dev.agentic.data.net.AskQuestion`。
- [ ] **Step 2:** 提交 `git commit -m "feat(domain): Node transcript model"`。

### Task 1.2: ToolDisplay(toolSummary/toolDetail)

**Files:**
- Create: `domain/ToolDisplay.kt`
- Test: `test/domain/ToolDisplayTest.kt`

**Interfaces:**
- Produces: `fun toolSummary(name:String, input:JsonObject?):String`、`fun toolDetail(name:String, input:JsonObject?):String`(逻辑同 `SessionScreen.kt:158-182`)。

- [ ] **Step 1: 写失败测试**
```kotlin
package dev.agentic.domain
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
class ToolDisplayTest {
  private fun obj(vararg p: Pair<String,String>) = JsonObject(p.associate { it.first to JsonPrimitive(it.second) })
  @Test fun read_summary_is_basename() =
    assertEquals("File.kt", toolSummary("Read", obj("file_path" to "/a/b/File.kt")))
  @Test fun bash_summary_first_line_trimmed() =
    assertEquals("echo hi", toolSummary("Bash", obj("command" to "  echo hi\nmore")))
  @Test fun bash_detail_full_command() =
    assertEquals("echo hi\nmore", toolDetail("Bash", obj("command" to "echo hi\nmore")))
}
```
- [ ] **Step 2: 跑测,确认失败**:`gradle test --tests dev.agentic.domain.ToolDisplayTest` → FAIL(unresolved)。
- [ ] **Step 3: 实现**:从 `SessionScreen.kt:158-182` 复制 `toolSummary`/`toolDetail` 到 `domain/ToolDisplay.kt`(顶层 fun,`import kotlinx.serialization.json.*`)。
- [ ] **Step 4: 跑测通过**。
- [ ] **Step 5: 提交** `git commit -m "feat(domain): tool summary/detail + tests"`。

### Task 1.3: AskParsing(parseAskQuestions)

**Files:**
- Create: `domain/AskParsing.kt`
- Test: `test/domain/AskParsingTest.kt`

**Interfaces:**
- Consumes: `AskQuestion`(Phase 2 的 Models;此处先 `import dev.agentic.data.net.AskQuestion`——若 Phase 2 未到,临时把 `AskQuestion(text:String, options:List<String>)` 放 domain 并在 Phase 2 统一。**决定:`AskQuestion` 归 domain**,见 Task 2.3 备注)。
- Produces: `fun parseAskQuestions(arr: JsonArray?): List<AskQuestion>`(逻辑同 `SessionScreen.kt:248-257`)。

- [ ] **Step 1: 失败测试**:新旧两种 schema 各一例(`{question,options:[{label}]}` 与 `{text,options:[string]}`)→ 期望解析出 text+options;空数组→空 list。
```kotlin
package dev.agentic.domain
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AskParsingTest {
  @Test fun parses_current_schema() {
    val arr = buildJsonArray { addJsonObject {
      put("question", "Pick"); putJsonArray("options"){ addJsonObject{ put("label","A") }; addJsonObject{ put("label","B") } } } }
    val q = parseAskQuestions(arr).single()
    assertEquals("Pick", q.text); assertEquals(listOf("A","B"), q.options)
  }
  @Test fun parses_legacy_schema() {
    val arr = buildJsonArray { addJsonObject { put("text","Q"); putJsonArray("options"){ add("x") } } }
    assertEquals(listOf("x"), parseAskQuestions(arr).single().options)
  }
  @Test fun empty_is_empty() = assertTrue(parseAskQuestions(null).isEmpty())
}
```
- [ ] **Step 2:** 跑测失败。
- [ ] **Step 3:** 实现(复制 `SessionScreen.kt:248-257`)。
- [ ] **Step 4:** 跑测通过。
- [ ] **Step 5:** 提交 `git commit -m "feat(domain): parseAskQuestions + tests"`。

### Task 1.4: Transcript(buildFromLog/applyEvent/appendText/appendThinking/splitAttachments)

**Files:**
- Create: `domain/Transcript.kt`
- Test: `test/domain/TranscriptTest.kt`

**Interfaces:**
- Consumes: `Node`、`toolSummary/toolDetail`、`parseAskQuestions`。
- Produces:
  - `fun appendText(nodes:List<Node>, text:String):List<Node>`
  - `fun appendThinking(nodes:List<Node>, text:String):List<Node>`
  - `fun splitAttachments(text:String): Pair<String, List<String>>`(现 `Attachments.kt` 的 `splitAttachments`,迁到 domain;查证当前定义并原样迁移)
  - `fun buildFromLog(log:List<String>):List<Node>`(同 `:272-314`)
  - `fun applyEvent(nodes:List<Node>, frame:String): Pair<List<Node>, Boolean>`(同 `:317-357`)
  - `fun frameBusy(frame:String):Boolean?`(同 `:362-368`)

- [ ] **Step 1: 失败测试**(覆盖关键行为):
```kotlin
package dev.agentic.domain
import org.junit.Assert.*
import org.junit.Test
class TranscriptTest {
  @Test fun text_deltas_accumulate_into_one_reasoning_node() {
    var n = appendText(emptyList(), "Hel"); n = appendText(n, "lo")
    assertEquals(1, n.size); assertEquals("Hello", (n[0] as TextNode).text); assertTrue((n[0] as TextNode).reasoning)
  }
  @Test fun applyEvent_prompt_then_tool() {
    val (a,_) = applyEvent(emptyList(), """{"kind":"prompt","text":"hi","at":5}""")
    assertTrue(a[0] is PromptNode)
    val (b,_) = applyEvent(a, """{"kind":"tool","name":"Read","input":{"file_path":"/x/Y.kt"}}""")
    assertEquals("Y.kt", (b[1] as ToolNode).summary)
  }
  @Test fun result_frame_appends_answer() {
    val (n,_) = applyEvent(emptyList(), """{"kind":"result","text":"done"}""")
    assertEquals("done", (n[0] as AnswerNode).text)
  }
  @Test fun frameBusy_true_for_content_false_for_result() {
    assertEquals(true, frameBusy("""{"kind":"tool"}""")); assertEquals(false, frameBusy("""{"kind":"result"}"""))
    assertNull(frameBusy("""{"kind":"other"}"""))
  }
  @Test fun buildFromLog_parses_prompt_and_result() {
    val nodes = buildFromLog(listOf(
      """{"type":"agentic_prompt","text":"go","at":1}""",
      """{"type":"result","result":"ok"}"""))
    assertTrue(nodes.first() is PromptNode); assertTrue(nodes.last() is AnswerNode)
  }
}
```
- [ ] **Step 2:** 跑测失败。
- [ ] **Step 3:** 实现:复制 `SessionScreen.kt:140-153, 272-368` 的函数;`splitAttachments` 从其当前位置(grep `fun splitAttachments`)迁来;`markAnsweredAsks` 移到 Task 1.5,`buildFromLog` 末尾改为调用 `markAnsweredAsks`(跨文件同包可见)。私有 `J = Json{ ignoreUnknownKeys=true }`。
- [ ] **Step 4:** 跑测通过。
- [ ] **Step 5:** 提交 `git commit -m "feat(domain): transcript build/apply + tests"`。

### Task 1.5: TranscriptGrouping(groupTools/interleaveShared/markAnsweredAsks)

**Files:**
- Create: `domain/TranscriptGrouping.kt`
- Test: `test/domain/TranscriptGroupingTest.kt`

**Interfaces:**
- Consumes: `Node`(含 `AttachmentNode`、`StepGroupNode`)。
- Produces:
  - `const GROUP_THRESHOLD=5`、`const LIVE_TAIL=3`、`fun groupable(n:Node):Boolean`
  - `fun groupTools(nodes:List<Node>):List<Node>`(同 `:224-242`)
  - `fun interleaveShared(display:List<Node>, shared:List<AttachmentNode>):List<Node>`(同 `:188-205`)
  - `fun markAnsweredAsks(nodes:List<Node>):List<Node>`(同 `:262-269`)

- [ ] **Step 1: 失败测试**:
```kotlin
package dev.agentic.domain
import org.junit.Assert.*
import org.junit.Test
class TranscriptGroupingTest {
  private fun tools(k:Int) = (1..k).map { ToolNode("Read","f$it","") as Node }
  @Test fun closed_run_over_threshold_folds_entirely() {
    val nodes = tools(7) + AnswerNode("end")     // 7 tools then a non-groupable
    val out = groupTools(nodes)
    assertTrue(out.first() is StepGroupNode); assertEquals(7, (out.first() as StepGroupNode).items.size)
  }
  @Test fun live_trailing_run_keeps_last_3_expanded() {
    val out = groupTools(tools(10))              // no closer → live tail reserved
    assertTrue(out.first() is StepGroupNode); assertEquals(3, out.count { it is ToolNode })
  }
  @Test fun short_run_not_folded() { assertTrue(groupTools(tools(4)).none { it is StepGroupNode }) }
  @Test fun interleave_appends_when_no_turn_times() {
    val d = listOf(AnswerNode("a") as Node); val f = listOf(AttachmentNode("x.png", at=1))
    assertEquals(2, interleaveShared(d, f).size)
  }
}
```
- [ ] **Step 2-4:** 失败 → 复制实现(`:188-205, 207-269`)→ 通过。
- [ ] **Step 5:** 提交 `git commit -m "feat(domain): grouping/interleave/markAnswered + tests"`。

### Task 1.6: BackendMode + Status + Template

**Files:**
- Create: `domain/BackendMode.kt`、`domain/Status.kt`、`domain/Template.kt`
- Test: `test/domain/StatusTest.kt`、`test/domain/TemplateTest.kt`

**Interfaces:**
- Consumes: `Session`、`WorkflowRun`(Phase 2 DTO)。**注:此 Task 依赖 Task 2.3 的 Models**;若先做 Phase 1,把 `BackendMode/Status` 排到 Phase 2 之后。推荐执行顺序:1.1–1.5 → Phase 2 → 1.6。
- Produces:
  - `enum class BackendMode { STREAMING, CLASSIC }`;`fun Session.backendMode() = if (awaitingInput != null) BackendMode.STREAMING else BackendMode.CLASSIC`
  - `enum class StatusVisual { DONE, FAILED, RUNNING, IDLE, PENDING }`;`fun statusVisual(status:String, awaitingInput:Boolean?):StatusVisual`(整合 `App.kt:110-119`)
  - `val TERMINAL = setOf("done","failed","killed")`;`val WORKFLOW_DONE = setOf(...)`(从 `Models.kt:124-131` 迁来);`fun WorkflowRun.isActive():Boolean`
  - `fun applyTemplate(body:String, vars:Map<String,String>):String`(从 `NewRequestScreen.kt:392-394` 的 `{{var}}` 替换抽出)

- [ ] **Step 1: 失败测试**(Status + Template):
```kotlin
// StatusTest
@Test fun running_awaiting_is_idle() = assertEquals(StatusVisual.IDLE, statusVisual("running", true))
@Test fun running_busy_is_running() = assertEquals(StatusVisual.RUNNING, statusVisual("running", false))
@Test fun failed_maps_failed() = assertEquals(StatusVisual.FAILED, statusVisual("failed", null))
// TemplateTest
@Test fun substitutes_vars() = assertEquals("hi bob", applyTemplate("hi {{name}}", mapOf("name" to "bob")))
```
- [ ] **Step 2-4:** 失败 → 实现 → 通过。
- [ ] **Step 5:** 提交 `git commit -m "feat(domain): backendMode/status/template + tests"`。

### Task 1.7: TranscriptReducer(增量折叠)

**Files:**
- Create: `domain/TranscriptReducer.kt`
- Test: `test/domain/TranscriptReducerTest.kt`

**Interfaces:**
- Consumes: `buildFromLog`、`applyEvent`、`groupTools`、`interleaveShared`、`markAnsweredAsks`。
- Produces:
  ```kotlin
  class TranscriptReducer {
    fun seedFromLog(log: List<String>)            // 一次性构建 history
    fun applyFrame(frame: String): Boolean        // 折叠一帧,返回 ended
    fun setShared(shared: List<AttachmentNode>)
    val raw: List<Node>                           // 未分组(供持久/调试)
    fun display(): List<Node>                     // groupTools∘interleaveShared∘markAnswered(供 UI)
  }
  ```
  增量约束:`applyFrame` 只对尾部做 `groupTools`/`interleaveShared` 不必要的全量重算——实现可先用全量(行为正确),Phase 6 性能验证时再优化为尾部增量;**正确性测试先行**。

- [ ] **Step 1: 失败测试**:seed 两行日志 → display 非空;applyFrame 一个 tool → display 末尾出现 ToolNode;applyFrame result → 返回 false 且出现 AnswerNode;ended 来自 `other.engineExit`。
- [ ] **Step 2-4:** 失败 → 实现(内部持 `var nodes:List<Node>`,`applyFrame` 调 `applyEvent`)→ 通过。
- [ ] **Step 5:** 提交 `git commit -m "feat(domain): TranscriptReducer + tests"`。

---

## Phase 2 — 数据源(Api 接口 / Ktor 实现 / Outcome / SettingsStore)

### Task 2.1: Outcome 错误模型

**Files:**
- Create: `data/net/Outcome.kt`
- Test: `test/data/OutcomeTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface AppError { data class Network(val cause:Throwable):AppError; object Unauthorized:AppError; data class Http(val code:Int):AppError; data class Unknown(val cause:Throwable):AppError }
  sealed interface Outcome<out T> { data class Success<T>(val value:T):Outcome<T>; data class Failure(val error:AppError):Outcome<Nothing> }
  inline fun <T> runCatchingOutcome(block:()->T):Outcome<T>   // 把异常映射成 AppError(401→Unauthorized)
  ```
- [ ] **Step 1-5:** 写 `runCatchingOutcome` 把 `UnauthorizedException`→`Unauthorized`、其它→`Unknown/Network` 的测试 → 实现 → 通过 → 提交。

### Task 2.2: AgenticApi 接口

**Files:**
- Create: `data/net/AgenticApi.kt`

**Interfaces:**
- Produces: `interface AgenticApi`,方法签名**逐一对应**现 `Api.kt`(`sessions/session/usage/workflows/workflowAgent/repos/skills/create/login/followUp/uploadFile/fileBytes/outbox/kill/delete/registerDevice/getTemplates/putTemplates/diffFiles/discard/stream`),外加:
  ```kotlin
  var baseUrl: String; var token: String?
  var onUnauthorized: (() -> Unit)?
  suspend fun stream(id:String, since:Int?, onLine: suspend (String)->Unit)
  fun close()
  ```
- [ ] **Step 1:** 按 `Api.kt:59-144` 的公开方法声明接口(只签名)。
- [ ] **Step 2:** 提交 `git commit -m "feat(data): AgenticApi interface"`。

### Task 2.3: KtorAgenticApi 实现 + Models 迁移

**Files:**
- Create: `data/net/KtorAgenticApi.kt`(= 现 `Api.kt` 的实现体,改为 `class KtorAgenticApi(...) : AgenticApi`)
- Create: `data/net/Models.kt`(从 `net/Models.kt` 迁;**领域谓词 `TERMINAL/WORKFLOW_DONE/isActive` 删除——已在 domain/Status**)
- Modify: 旧 `net/Api.kt`、`net/Models.kt` 暂保留(旧屏幕仍用),Phase 6/7 删除。

**Interfaces:**
- Consumes: `AgenticApi`、DTO。
- Produces: `KtorAgenticApi` 实现全部方法;`AskQuestion` 定义归 `data/net/Models.kt`(domain 已 import 它——确认 import 路径 `dev.agentic.data.net.AskQuestion`)。
- 备注:`login()` 内部仍写 `token`(保持现行为),但 **AuthRepository 才是 token 真相源**(Task 3.1)。

- [ ] **Step 1:** 复制 `Api.kt` 实现为 `KtorAgenticApi : AgenticApi`(`override` 各方法)。Models 复制,去掉领域谓词。
- [ ] **Step 2:** 编译通过(新类与旧类并存)。
- [ ] **Step 3:** 提交 `git commit -m "feat(data): KtorAgenticApi impl + Models"`。

### Task 2.4: SettingsStore(token 暴露 StateFlow)

**Files:**
- Create: `data/SettingsStore.kt`
- Test: `test/data/SettingsStoreTest.kt`(用 Robolectric 或抽象出纯逻辑;**决定:抽一个 `interface SettingsStore` + `SharedPrefsSettingsStore(ctx)` impl + `FakeSettingsStore` 测试用**,纯逻辑测 Fake)

**Interfaces:**
- Produces:
  ```kotlin
  interface SettingsStore {
    val token: StateFlow<String?>; val host: String
    fun setToken(t: String?); fun setHost(h: String)
  }
  ```
- [ ] **Step 1-5:** Fake 测 setToken→token.value 更新 → 实现 SharedPrefs 版(从 `Store.kt` 扩展,内部 MutableStateFlow)→ 通过 → 提交。

### Task 2.5: util/Polling

**Files:**
- Create: `data/util/Polling.kt`
- Test: `test/data/PollingTest.kt`(`runTest` + 虚拟时间)

**Interfaces:**
- Produces: `fun <T> pollFlow(intervalMs:Long, block: suspend ()->T): Flow<T>`(每隔 interval emit,首个立即);供 list/usage/workflow 复用。
- [ ] **Step 1-5:** `runTest` 推进虚拟时间验证 emit 次数 → 实现(`flow{ while(true){ emit(block()); delay(interval) } }`)→ 通过 → 提交。

---

## Phase 3 — Repository

### Task 3.1: AuthRepository

**Files:**
- Create: `data/repo/AuthRepository.kt`
- Test: `test/data/repo/AuthRepositoryTest.kt`(Fake AgenticApi + Fake SettingsStore)
- Modify: `di/AppContainer.kt`(实例化 api/settings/authRepo)

**Interfaces:**
- Consumes: `AgenticApi`、`SettingsStore`、`Outcome`。
- Produces:
  ```kotlin
  class AuthRepository(private val api: AgenticApi, private val settings: SettingsStore, scope: CoroutineScope) {
    val token: StateFlow<String?>                       // = settings.token
    val isLoggedIn: StateFlow<Boolean>
    suspend fun login(host:String, password:String): Outcome<Unit>   // 设 api.baseUrl/token + 持久化
    fun logout()                                         // 清 settings + api.token
    suspend fun registerFcm(fcmToken:String)
    // 构造时 api.onUnauthorized = { logout() }
  }
  ```
- [ ] **Step 1: 失败测试**:login 成功 → token.value 非空、api.token 被设;onUnauthorized 触发 → token.value=null。
- [ ] **Step 2-4:** 失败 → 实现 → 通过。AppContainer 里 `val api = KtorAgenticApi(settings.host, settings.token.value)`,`val authRepo = AuthRepository(api, settings, appScope)`,`appScope = CoroutineScope(SupervisorJob()+Dispatchers.Default)`,并在容器加 `fun close(){ api.close() }`。
- [ ] **Step 5:** 提交 `git commit -m "feat(repo): AuthRepository + container wiring + tests"`。

### Task 3.2: SessionsRepository

**Files:**
- Create: `data/repo/SessionsRepository.kt`
- Test: `test/data/repo/SessionsRepositoryTest.kt`
- Modify: `di/AppContainer.kt`

**Interfaces:**
- Consumes: `AgenticApi`、`pollFlow`、domain(`TranscriptReducer`、`Node`、`AttachmentNode`、`Session.backendMode`)。
- Produces:
  ```kotlin
  class SessionsRepository(private val api: AgenticApi, private val scope: CoroutineScope) {
    fun sessionsStream(): Flow<List<Session>>            // pollFlow 2s
    suspend fun usage(): Usage
    suspend fun session(id:String): Outcome<SessionDetail>
    suspend fun create(req: NewSessionReq): Outcome<String>
    suspend fun followUp(id:String, prompt:String, setTitle:Boolean=true): Outcome<Int>
    suspend fun kill(id:String); suspend fun delete(id:String)
    /** 单一真相源:history 一次性 build + 实时流合并,内部用 TranscriptReducer。 */
    fun transcript(id:String): Flow<TranscriptState>     // data class TranscriptState(nodes:List<Node>, busy:Boolean, session:Session?, ended:Boolean)
    // 进程级 transcriptCache 的替代:repo 内 LinkedHashMap<String, List<Node>>(上界 N=20,LRU)
    var pendingPrompt: MutableMap<String,String>          // 列表→详情 乐观提示桥接
  }
  ```
  `transcript(id)` 内部:`session(id)` → `reducer.seedFromLog` → 若非 terminal `api.stream(id, log.size){ reducer.applyFrame(it); emit }`;把"打开瞬间帧涌入"收到这一处(单一真相源,UI 不再 3 回调改写)。
- [ ] **Step 1: 失败测试**(Fake api 回放固定 log + 固定 stream 帧):`transcript(id)` 首个 emit 含 history,后续 emit 追加;busy 随 frameBusy 变化;cache 命中时 session() 仍刷新。
- [ ] **Step 2-4:** 失败 → 实现 → 通过。
- [ ] **Step 5:** 提交 `git commit -m "feat(repo): SessionsRepository (single-source transcript) + tests"`。

### Task 3.3: WorkflowsRepository + FilesRepository

**Files:**
- Create: `data/repo/WorkflowsRepository.kt`、`data/repo/FilesRepository.kt`
- Test: `test/data/repo/WorkflowsRepositoryTest.kt`
- Modify: `di/AppContainer.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class WorkflowsRepository(api, scope) {
    fun runsStream(id:String): Flow<List<WorkflowRun>>   // pollFlow 2.5s,isActive() 停止规则
    suspend fun agentTranscript(id,runId,agentId:String): String
    fun outboxStream(id:String): Flow<List<AttachmentNode>>
  }
  class FilesRepository(api) {
    suspend fun upload(id:String, bytes:ByteArray, name:String): Outcome<String>
    suspend fun fileBytes(id:String, path:String, onProgress:((Float?)->Unit)?=null): ByteArray
    suspend fun diffFiles(id:String): Outcome<List<DiffFile>>; suspend fun discard(id:String)
  }
  ```
  详情页 rail 与 WorkflowScreen 共用 `WorkflowsRepository`(由 AppContainer 提供同一实例,按 id 取流),消除双重轮询。
- [ ] **Step 1-5:** runsStream 用虚拟时间测停止规则(全 done 后停)→ 实现 → 通过 → 提交。

---

## Phase 4 — ViewModel + UiState

> 每个 VM 构造注入对应 Repository;经 `ViewModelFactory` 创建。UiState 不可变。
> 单测用 Fake Repository + `runTest`,断言 `uiState.value` 转移。

### Task 4.0: ViewModelFactory

**Files:**
- Create: `di/ViewModelFactory.kt`

**Interfaces:**
- Produces: `class ViewModelFactory(private val c: AppContainer, private val handle: SavedStateHandle? = null) : ViewModelProvider.Factory`(`create` 中 when(modelClass) 构造各 VM)。Compose 侧用 `viewModel(factory = LocalContext.appContainer.vmFactory(...))` 辅助函数。
- [ ] **Step 1:** 实现 Factory + 一个 `@Composable fun appContainer(): AppContainer`(读 `LocalContext` → `AgenticApp`)。
- [ ] **Step 2:** 提交。

### Task 4.1: LoginViewModel

**Files:** Create `ui/login/LoginViewModel.kt`;Test `test/ui/LoginViewModelTest.kt`
**Interfaces:**
- Produces: `data class LoginUiState(host:String, password:String, busy:Boolean=false, error:String?=null, done:Boolean=false)`;`class LoginViewModel(authRepo)`:`onHost/onPassword/submit()`;submit→`authRepo.login`→成功 `done=true` 并触发 `registerFcm`。
- [ ] **Step 1-5:** 测 submit 成功置 done、失败置 error → 实现 → 通过 → 提交。

### Task 4.2: HomeViewModel

**Files:** Create `ui/home/HomeViewModel.kt`;Test `test/ui/HomeViewModelTest.kt`
**Interfaces:**
- Produces: `data class HomeUiState(sessions:List<Session> =…, usage:Usage?=null, filter:…, loading:Boolean)`;`class HomeViewModel(sessionsRepo)`:收集 `sessionsStream()` 入 state;`delete(id)`、`create(...)`(或交给 NewRequest);`pendingPrompt` 写入 repo。
- [ ] **Step 1-5:** 测 stream 更新 → state.sessions;delete 调 repo → 通过 → 提交。

### Task 4.3: NewRequestViewModel

**Files:** Create `ui/newrequest/NewRequestViewModel.kt`;Test
**Interfaces:**
- Produces: `data class NewRequestUiState(...全部表单字段...)`;事件 `setRepos/setSkills/setModel/setEffort/setMode/setPrompt/applyTemplate(t)/submit()`;`applyTemplate` 调 domain `applyTemplate`(纯)。submit→`sessionsRepo.create`→返回新 id(经一次性事件)。
- [ ] **Step 1-5:** 测 applyTemplate 填充 prompt、submit 成功发 id 事件 → 实现 → 通过 → 提交。

### Task 4.4: SessionViewModel(核心)

**Files:** Create `ui/session/SessionViewModel.kt`;Test `test/ui/SessionViewModelTest.kt`
**Interfaces:**
- Consumes: `SessionsRepository`、`WorkflowsRepository`、`SavedStateHandle`(id, initialPrompt)、domain(`BackendMode`、`Node`)。
- Produces:
  ```kotlin
  data class SessionUiState(
    nodes: List<Node> = emptyList(), session: Session? = null, busy: Boolean = false,
    backend: BackendMode? = null, input: String = "", sending: Boolean = false,
    queued: String? = null, inputLocked: Boolean = false, canSend: Boolean = false,
    composable: Boolean = false, runs: List<WorkflowRun> = emptyList(),
    shared: List<AttachmentNode> = emptyList(), connecting: Boolean = true, /* …diff/rail flags… */
  )
  class SessionViewModel(sessionsRepo, workflowsRepo, handle) {
    val uiState: StateFlow<SessionUiState>
    fun onInput(s:String); fun submit(); fun stop(); fun answerAsk(node:AskNode, answer:String)
    fun resume(); fun cancelQueued()
  }
  ```
  输入状态机(locked/composable/canSend/queued)**集中在此一处**计算(替代 `SessionScreen.kt:757-794` 散落 val);transcript 来自 `sessionsRepo.transcript(id)`(单一真相源);乐观提示从 `repo.pendingPrompt[id]` seed。
- [ ] **Step 1: 失败测试**:① 收集 transcript 流 → state.nodes 更新且 busy 同步;② streaming(awaitingInput!=null)空闲时 submit 立即发、忙时 queued;③ classic terminal 时 submit 走 followUp;④ answerAsk 乐观标记 answered。
- [ ] **Step 2-4:** 失败 → 实现 → 通过。
- [ ] **Step 5:** 提交 `git commit -m "feat(vm): SessionViewModel input state machine + tests"`。

### Task 4.5: WorkflowViewModel + DiffViewModel

**Files:** Create `ui/workflow/WorkflowViewModel.kt`、`ui/diff/DiffViewModel.kt`;Test 各一
**Interfaces:**
- Produces:`WorkflowUiState(runs, selectedAgent, agentTranscript…)` + `WorkflowViewModel(workflowsRepo, handle)`;`DiffUiState(files, loading)` + `DiffViewModel(filesRepo, handle)`:`load()`、`discard()`。
- [ ] **Step 1-5:** 测 runsStream→state、selectAgent→fetch transcript;diff load→files → 通过 → 提交。

---

## Phase 5 — 导航统一

### Task 5.1: 类型安全路由 + NavHost

**Files:**
- Create: `ui/nav/AppNav.kt`
- Modify: `MainActivity.kt`(只 setContent { AppTheme { AppNav(...) } })

**Interfaces:**
- Produces: `@Serializable` route 对象 `Login`、`Home`、`NewRequest`、`Session(id:String, initialPrompt:String?=null)`、`Workflow(id:String)`、`Diff(id:String)`;`@Composable fun AppNav(container, deepLinkId:String?)` 建 `NavHost`,起点由 `authRepo.isLoggedIn` 决定(可观察);`Session` 路由注册 `navDeepLink { uriPattern = "agentic://session/{id}" }`。
- [ ] **Step 1:** 写 AppNav + 路由;list/detail 宽屏用 list-detail 布局承载 Home+Session(或先单栏,Phase 6 接宽屏)。
- [ ] **Step 2:** MainActivity 改为读 intent 的深链 id 传给 AppNav(过渡)或直接靠 navDeepLink;删除 `pendingSessionId` Activity 字段(若 navDeepLink 完全替代)。
- [ ] **Step 3:** 编译/手验导航(主 checkout 出 debug 包冒烟)。
- [ ] **Step 4:** 提交 `git commit -m "feat(nav): unified type-safe NavHost + deep link"`。

---

## Phase 6 — 各屏 Compose 改造(纯展示)+ 渲染最佳实践

> 每屏:删旧屏内的状态/副作用/api 调用,改为 `val s by vm.uiState.collectAsStateWithLifecycle()` + 回调。
> 纯 view 组件(Markdown/FadingEdge/Theme/StatusIndicator/VoiceDictationField)移到 `ui/components/` 并去重
> (语音听写两份合一:`SessionScreen.kt:408-443` 与 `NewRequestScreen.kt:216-314`)。

### Task 6.1: 共享组件归位 + StatusIndicator 用 domain
**Files:** Move `ui/Markdown.kt`、`ui/FadingEdge.kt`、`ui/Theme.kt`→`ui/components/`;Create `ui/components/VoiceDictationField.kt`(合并两份);Modify `App.kt` 的 `StatusIndicator/statusColor` 改用 `domain.statusVisual`。
- [ ] **Step 1:** 迁移 + 合并语音组件(`@Composable VoiceDictationField(value, onValueChange, modifier)`)。
- [ ] **Step 2:** StatusIndicator 读 `statusVisual(status, awaitingInput)`。
- [ ] **Step 3:** 提交。

### Task 6.2: LoginScreen + HomeScreen + NewRequestScreen 纯展示
**Files:** Create `ui/login/LoginScreen.kt`、`ui/home/HomeScreen.kt`、`ui/newrequest/NewRequestScreen.kt`(从旧 `App.kt`/`Home.kt`/`NewRequestScreen.kt` 移植 UI,去掉业务)。
- [ ] **Step 1:** 各屏接 `XUiState` + 回调;导航用 NavController。原渲染逐项移植(列表卡片、usage、表单、模板选择)。
- [ ] **Step 2:** 编译 + 冒烟。
- [ ] **Step 3:** 提交 `git commit -m "feat(ui): login/home/newrequest as stateless screens"`。

### Task 6.3: SessionScreen 重写 + 渲染最佳实践(核心)
**Files:** Create `ui/session/SessionScreen.kt`、`ui/session/Transcript.kt`(transcript 列表 + 卡片 composables:PromptNode/TextNode/ToolNode/StepGroupNode/AskCardView/AttachmentView 等,从旧文件移植外观)。
**Interfaces:** Consumes `SessionUiState` + `SessionViewModel` 回调。
- [ ] **Step 1:** transcript 用 `LazyColumn(reverseLayout = true)`,数据为 `s.nodes.asReversed()`;`items(items, key = { it.stableId() })` 加稳定 key(给 `Node` 加 `stableId`:位置/turn 基,流式 TextNode 用稳定 id)。
- [ ] **Step 2:** **删除** pinnedOnce/alpha 遮罩/12 帧 scrollToItem 循环/withFrameNanos/canScrollForward/pinning spinner。新开会话自然停底部;新帧到达时若用户在底部用 `animateScrollToItem(0)`(reverse 下 0=最新)。
- [ ] **Step 3:** 输入框:`s.session != null` 即渲染(seed 自 repo 缓存)→ 输入框秒出;输入状态机字段全来自 `s`(不再屏内重算)。
- [ ] **Step 4:** rail/WorkflowScreen 共用 `WorkflowsRepository`(经各自 VM 同一实例)。
- [ ] **Step 5:** 编译 + 主 checkout 冒烟三场景(活跃会话 / 长会话 / 输入框秒出)。
- [ ] **Step 6:** 提交 `git commit -m "feat(ui): SessionScreen rewrite + chat rendering best practices"`。

### Task 6.4: WorkflowScreen + DiffPane + Attachments 纯展示
**Files:** Create `ui/workflow/WorkflowScreen.kt`、`ui/workflow/AgentTranscriptPane.kt`(单一实现,供 rail+全屏复用)、`ui/diff/DiffPane.kt`、`ui/attachments/Attachments.kt`(下载 IO 移 FilesRepository,Toast 经一次性事件)。
- [ ] **Step 1-3:** 移植 UI、接 VM、去重 AgentTranscriptPane → 编译 → 提交。

---

## Phase 7 — 收尾

### Task 7.1: FCM 服务改用 AuthRepository
**Files:** Modify `AgenticMessagingService.kt`(读 `AgenticApp.container.authRepo` 注册 token,删除自建 Api+Store 第 2 份)。
- [ ] **Step 1-2:** 改 + 编译 + 提交。

### Task 7.2: 删旧文件 + 全量编译
**Files:** Delete 旧 `net/Api.kt`、`net/Models.kt`(确认已无引用)、`data/Store.kt`、旧 `ui/App.kt`/`ui/Home.kt`/`ui/NewRequestScreen.kt`/`ui/SessionScreen.kt`/`ui/WorkflowScreen.kt`/`ui/DiffPane.kt`/`ui/Attachments.kt` 中被新文件取代的部分;清理 SessionScreen 内 domain 副本。
- [ ] **Step 1:** grep 确认零引用后删除;`gradle assembleDebug`(主 checkout)编译通过。
- [ ] **Step 2:** `gradle test`(主 checkout)全绿。
- [ ] **Step 3:** 提交 `git commit -m "chore: remove legacy pre-MVVM files"`。

### Task 7.3: push + 出 APK + 验证
- [ ] **Step 1:** `git push origin HEAD:master`(worktree 内;冲突则 `git pull --rebase origin master`)。
- [ ] **Step 2:** 主 checkout:`cd ~/src/agentic-dev-android && git pull --ff-only origin master && ~/.local/share/gradle-8.10.2/bin/gradle assembleRelease`。
- [ ] **Step 3:** `cp app/build/outputs/apk/release/app-release.apk worktree/outbox/$(date +%Y%m%d-%H%M).apk`。
- [ ] **Step 4:** 真机验证三场景 + 登录/列表/新建/workflow/diff/附件/语音 回归。

---

## Self-Review(对照 spec)
- **覆盖**:spec §3 分层→Phase 0-6;§4 domain→Phase 1;§5 数据/Repo→Phase 2-3;§6 VM/导航→Phase 4-5;§7 渲染最佳实践→Task 6.3;§8 测试→各 Task 的 test;§9 YAGNI→Global Constraints;§10 风险→Task 6.3 步骤。无遗漏。
- **占位符**:UI 移植类 Task(6.2/6.4)以"移植旧 file:lines + 接 UiState"描述而非逐行代码——因外观为机械移植、且每 Task 末尾有编译/冒烟门禁;基础层(domain/data/repo/vm)给了真实签名与测试。执行用 subagent-driven,每 Task 独立可验。
- **类型一致**:`Outcome`/`AppError`、`AgenticApi`、`TranscriptState`、各 `XUiState` 与 VM 签名跨 Task 统一;`statusVisual`/`backendMode`/`isActive` 命名一致。
- **执行顺序注意**:Task 1.6 依赖 Phase 2 Models → 推荐 1.1–1.5 → Phase 2 → 1.6 → Phase 3+。
