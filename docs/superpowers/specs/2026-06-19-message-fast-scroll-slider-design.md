# 设计:会话用户消息快速滚动滑块(Message fast-scroll slider)

日期:2026-06-19
范围:`ui/session` 的会话转录页(`Transcript.kt`)。

## 目标

在会话转录列表上叠加一个可抓取的滚动滑块(thumb)。用户按住 thumb 上下拖动,可以
**逐条离散贴靠到自己发出的消息**(转录中的 `PromptNode`),快速在多轮对话里跳转。

## 行为(已与用户确认)

1. **离散贴靠**:按住 thumb 向上拖 → 上一条(更旧)用户消息;向下拖 → 下一条(更新)用户消息。
   thumb 在各个用户消息锚点之间“一格一格”跳,不是按像素连续滚动。被选中的用户消息
   **停靠到视口顶部**,这样它下面的回复可见。
2. **预览气泡**:拖动时,thumb 旁浮出一个 MD3 气泡,显示当前目标用户消息的前若干字
   + 序号(如 `2/5`),便于定位。仅拖动时显示。
3. **自动淡出**:静止时不可见;滚动或抓取时淡入;松手延迟约 1.2s 后淡出。
4. **同时是滚动条**:不拖动时 thumb 反映当前滚动位置(按可见区间比例)。

## 不做(YAGNI)

- 不做字母索引 / 分组 header 索引(这是聊天,不是通讯录)。
- 不做水平滚动条。
- 不做对 `AttachmentNode`/工具调用等非用户节点的贴靠——只贴靠用户消息。

## 关键约束:reverseLayout

转录是 `reverseLayout = true` 的 `LazyColumn`:LazyColumn 的 item index 0 在**底部**(最新)。
而锚点是按时间顺序的节点下标。两者要换算:

```
revIndex = nodes.size - 1 - chronoIndex
```

视觉上轨道**顶部 = 最旧消息**,**底部 = 最新消息**,所以拖动比例→锚点的映射、以及 thumb 静止
位置的计算都要做这层反转。这是本功能最容易出错、最需要健壮性的地方,因此把它做成
**纯函数并单测**。

顶部停靠:`reverseLayout` 下 `scrollToItem(index)` 默认把目标贴到底边,需要读 `layoutInfo`
(视口高 − item 高)再用带 `scrollOffset` 的 `scrollToItem` 把目标推到顶部。

## 结构(隔离、可测试)

### 1. `ui/session/TranscriptScrollbarMath.kt` —— 纯逻辑,无 Compose 依赖

可在 JVM 单测里直接测:

- `data class MsgAnchor(val chronoIndex: Int, val revIndex: Int, val preview: String)`
- `fun userMessageAnchors(nodes: List<Node>): List<MsgAnchor>`
  抽取所有 `text.isNotBlank()` 的 `PromptNode`(与渲染条件一致),按时间顺序(旧→新)返回,
  附 revIndex 与单行预览文本。
- `fun nearestAnchorOrdinal(fraction: Float, count: Int): Int`
  把 0..1 的轨道比例(0=顶=最旧)映射到锚点序号,四舍五入并夹取边界。
- `data class ThumbBounds(val startFraction: Float, val endFraction: Float)`
- `fun thumbBounds(firstVisibleRevIndex: Int, visibleCount: Int, totalCount: Int): ThumbBounds`
  由可见 reverse-index 区间算出 thumb 在轨道上的起止比例(0=顶)。
- `fun anchorOrdinalFraction(ordinal: Int, count: Int): Float`
  锚点序号→轨道比例(均匀分布,用于拖动时 thumb 的吸附位置)。

### 2. `ui/session/TranscriptScrollbar.kt` —— UI 叠加层

- `@Composable fun MessageFastScrollbar(state: LazyListState, anchors: List<MsgAnchor>, modifier)`
  - 右侧边缘渲染 MD3 胶囊 thumb;触摸区 ≥48dp(MD3 触摸目标)。
  - `draggable(Vertical)` 手势:拖动时按比例算最近锚点,锚点序号变化时 `scrollToItem` 顶部停靠;
    thumb 吸附到锚点比例位置(离散“跳格”手感);弹出预览气泡。
  - 可见性:`state.isScrollInProgress || dragging` 时显示,停手 delay 后淡出(`animateFloatAsState`)。
  - 边界:`anchors` 为空、内容不可滚动(`!canScrollForward && !canScrollBackward`)时不渲染。
  - MD3 token:thumb 选中态 `primary`、普通态 `onSurfaceVariant`;气泡 `secondaryContainer` /
    `shapes.large`;形状用圆角胶囊;动效用标准缓动。

### 3. 接入 `Transcript.kt`

用 `Box` 包住现有 `SelectionContainer { LazyColumn }`,`remember(nodes)` 算锚点,叠加
`MessageFastScrollbar(listState, anchors, Modifier.align(End).fillMaxHeight())`。复用现有 `listState`,
不改既有滚动/autoscroll 逻辑。

### 4. 单测 `ui/session/TranscriptScrollbarTest.kt`

覆盖:锚点抽取(跳过空白 prompt、忽略非用户节点、reverse-index 正确)、最近锚点映射
(含边界与单锚点)、thumbBounds(满屏/部分可见/全部可见)、anchorOrdinalFraction(均匀/单锚点)。

## 依据

MD3 没有官方移动端滚动条/快速滚动组件,因此遵循 MD3 形状/动效/颜色 token + 业界事实标准的
Compose 可拖拽滚动条模式(`nanihadesuka/LazyColumnScrollbar`:`Box` 叠加 + 读 `layoutInfo` +
`scrollToItem` 执行滚动),不生造假“MD3 组件”。滑块语义专门化为“贴靠用户消息”。

## 测试与交付

worktree 无 gradle wrapper,本地不跑测试;红/绿在主 checkout
(`~/src/agentic-dev-android`,`gradle testDebugUnitTest`)验证,随后 `assembleRelease` 构建 APK,
改名投递到 `outbox/`。
