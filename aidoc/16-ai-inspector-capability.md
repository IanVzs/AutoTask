# 16 · AI 屏幕感知与 Inspector 接入

本文件记录 AutoTask AI 接入第二阶段最关键、也是用户明确判定为"决定 AI 是否形同虚设"的能力：**让 AI 复用悬浮助手（Floating Inspector）已有的控件识别能力，看见当前屏幕的可交互节点，并据此生成可被现有 Applet 引擎执行的动作**。

> 用户原话（2026-05-08）：
> "涉及到 app 内操作 我希望 ai 可以使用悬浮助手识别控件的能力 这点你记录一下 这个能力至关重要 如果没有这个能力那 AI 就是形同虚设。"

设计草案以 `14-ai-integration.md` 为总纲，沟通纪要见 `15-ai-working-notes.md` §10。本文件聚焦在"屏幕感知"这一专项。

## 1. 为什么这件事至关重要

### 1.1 当前 AI 能做什么、不能做什么

当前已落地（见 `app/src/main/java/top/xjunz/tasker/ai/`）：

- AI 能把自然语言映射到 `RunExistingTask` 或 `CreateTaskDraft` 两类意图。
- AI 生成的草稿能被 `AiTaskDraftConverter` 转成 `XTask`，但只支持三类能力：`launch_app`、`wait_seconds`、`toast`。
- AI 调用一律先经过 `AiActionGate`，命中 `AiCenter.defaultGrants()` 才能放行。

当前**做不到**：

- AI 看不到当前屏幕上有什么按钮、输入框、列表。
- AI 无法生成"在微信里点一下『发送』按钮""在浏览器地址栏输入 xxx""往下滑直到看见某文本"这种真正具备 App 内自动化价值的步骤。
- 即便用户口头说"在淘宝里搜手机"，AI 只能输出 `launch_app(淘宝)`，剩下的全靠用户自己去编辑器拼。

也就是说：**AI 现在只是一个"语音任务调度器 + Toast 写手"**。这与"AI 驱动"的目标差距巨大。

### 1.2 现有 Inspector 已经把"看屏幕"做完了

AutoTask 自带 Floating Inspector（`task/inspector/FloatingInspector.kt`）就是为了让人类用户**用眼睛看 + 手动选**节点。它已经实现了：

- 通过 `A11yAutomatorService.rootInActiveWindow` 获取当前焦点窗口的根节点。
- `AccessibilityNodeInfo.freeze()` 冻结成稳定快照 `StableNodeInfo`，过滤不可见 / 无 className 子节点。
- `LayoutInspectorView` 在屏幕上画框、计算 global rect、支持触摸 / 十字键选中。
- `NodeInfoOverlay.collectProperties()` 把选中节点映射成一组**可勾选的 `Applet` 候选**（`isType` / `withId` / `textEquals` / `contentDesc` / `isClickable` 等）。
- 通过 `EventCenter.routeEvent(EVENT_NODE_INFO_SELECTED, applets)` 把这些 Applet 发回 `AppletSelectorDialog`。
- `AppletSelectorViewModel.acceptApplets` / `acceptAppletsFromAutoClick` 把它们包成 `containsUiObject` Flow，落进编辑流。

也就是说，"识别 + 转 Applet"这条管道**人类用户已经在用了**，AI 只需要复用同一套能力，把"人类用手指点"换成"AI 用结构化输出选"。

> 这正是必须接入的根本理由：节省 80% 的工程量，同时保证 AI 生成的节点和用户手选的节点走完全一致的执行路径。

## 2. 现有 Inspector 能力地图

下表汇总后续接入需要的所有入口（行号以当前仓库为准；改动接口时请同步更新）。

### 2.1 数据来源

| 用途 | 入口 | 说明 |
|------|------|------|
| 当前焦点窗口根节点 | `A11yAutomatorService.rootInActiveWindow`（`AccessibilityService` 自带） | 必须 `A11yAutomatorService.get() != null` |
| 冻结成快照 | `StableNodeInfo.Companion.freeze()`（`task/inspector/StableNodeInfo.kt:30`） | 跳过 `!isVisibleToUser` 与 `className == null` 子节点 |
| 节点字段读取 | `StableNodeInfo.source: AccessibilityNodeInfo` | text、contentDescription、viewIdResourceName、className、isClickable、isLongClickable、isEditable、isCheckable、isChecked、isEnabled、isSelected、isScrollable、childCount |
| 可见 bounds | `AccessibilityNodeInfo.getVisibleBoundsIn(global)`（项目内扩展） | 与 `LayoutInspectorView` 用同一全局 rect |
| 截屏配合（可选） | `A11yAutomatorService.takeScreenshot(...)`（仅 Android R+） | Inspector 已用，AI 暂不依赖 |

### 2.2 行动管道

| 用途 | 入口 |
|------|------|
| "节点 → Criterion Applet 候选集合" | `task/inspector/overlay/NodeInfoOverlay.kt::collectProperties` |
| Inspector → 编辑器事件 | `EventCenter.routeEvent(FloatingInspector.EVENT_NODE_INFO_SELECTED, applets)` |
| "选中节点列表 → 加进当前 Flow" | `ui/task/selector/AppletSelectorViewModel.kt::acceptApplets` |
| "节点 → 自动点击（包 `containsUiObject`）" | 同文件 `acceptAppletsFromAutoClick` |
| 当前前台 App / Activity | `a11yAutomatorService.a11yEventDispatcher.getCurrentComponentInfo()` |

### 2.3 Registry 中 AI 第一批应该用的能力

第一批先选**风险可控、参数明确、不依赖手势复杂度**的能力。所有候选都已存在，无需新增 Registry。

| 类别 | Registry / 字段 | 参数 | 风险 | 备注 |
|------|----------------|------|------|------|
| 容器 | `UiObjectFlowRegistry.containsUiObject` | 根节点引用 + 子条件 scope | 中 | AI 必备：所有"在某节点上做事"都先包它 |
| 条件 | `UiObjectCriterionRegistry.withId` | resource id | 低 | 首选定位手段 |
| 条件 | `UiObjectCriterionRegistry.textEquals` | 文本 | 低 | 次选定位手段 |
| 条件 | `UiObjectCriterionRegistry.contentDesc` | contentDescription | 低 | 无 text 时使用 |
| 条件 | `UiObjectCriterionRegistry.isType` | className | 低 | 用于辅助过滤（如 `EditText`） |
| 动作 | `UiObjectActionRegistry.click` | 节点引用 | 中 | 单击命中节点 |
| 动作 | `UiObjectActionRegistry.longClick` | 节点引用 | 中 | 长按 |
| 动作 | `UiObjectActionRegistry.setText` | 节点引用 + 文本 | 中-高 | 写入需 `isEditable`，文本可能含敏感数据 |
| 动作 | `UiObjectActionRegistry.clickIfExits` | 嵌套条件 | 中 | "如果存在就点击"，AI 推理失败时的安全回退 |
| 动作 | `UiObjectActionRegistry.clickUiObjectWithText` | 显示文本 | 中 | 不依赖屏幕快照的兜底动作 |

**非第一批**（暂不接入）：滚动、拖拽、滑动、手势录制、坐标点击、`UiObjectActionRegistry.setText` 写入密码字段。

### 2.4 前置条件

要让 AI 拿到"当前控件树快照"，必须满足：

1. `A11yAutomatorService` 处于已连接状态（用户已开启无障碍）。
2. 不需要悬浮窗权限——不用真正打开 Inspector UI，只需要复用 `rootInActiveWindow + freeze()`。
3. 不需要 Shizuku，因为节点树来自 Accessibility 而不是 UiAutomation；但如果用户给了 Shizuku，`UiObjectActionRegistry.click` 在非 clickable 节点上会走 `uiDevice.wrapUiObject(...).click()` 路径而不是 5ms 模拟手势，体验更稳。

## 3. 设计目标

让 AI 拥有"看一眼当前屏幕、对其中某个节点做事"的能力，**不打开 Floating Inspector UI**，**不绕过现有授权与 Applet 管道**。具体目标：

1. AI 可以请求 `read_screen_snapshot` capability，得到一份压缩后的节点树。
2. AI 可以输出 `click_ui_object_by` / `set_text_in_ui_object_by` / `wait_for_ui_object` 等结构化步骤，每步描述如何**定位**一个节点（id / text / contentDesc / className 任意组合），以及对它做什么。
3. `AiTaskDraftConverter` 把这些步骤翻译成现有 `containsUiObject + Criterion + Action` 组合，复用 `acceptApplets` 同款管道。
4. 整个过程经过 `AiActionGate`，新增能力对应新的 `AiCapability`，默认不放行；用户首次启用要明确授权。
5. 决策记录里能完整看到 AI 当时看到的屏幕子集和它选择的节点，用于复盘和误操作排查。

## 4. 顶层流程

```text
用户语音 / 文字
    ↓
VoiceAiInterpreter
    ↓ (AI 决定需要操作 UI)
请求 capability: read_screen_snapshot
    ↓
ScreenSnapshotProvider
    - 读取 A11yAutomatorService.rootInActiveWindow
    - StableNodeInfo.freeze()
    - AiNodeTreeCompactor 压缩成 AiUiSnapshot
    ↓
把 AiUiSnapshot + 用户目标 一起喂给模型
    ↓
模型返回 AiActionPlan(steps=[click_ui_object_by(...), set_text_in_ui_object_by(...), ...])
    ↓
AiActionGate.review(plan)  ← 命中新的 InspectScreen / ClickUi / InputText 授权
    ↓
AiTaskDraftConverter
    - 对每一步生成 containsUiObject + 对应 Criterion + Action
    - 不能转换的步骤标记 Unsupported
    ↓
现有 FlowEditor / 一次性执行管道
    ↓
AiAuditStore 记录：屏幕快照子集 + AI 选中的节点 + 转换结果
```

## 5. 数据结构（建议）

以下结构均放在 `app/src/main/java/top/xjunz/tasker/ai/inspector/` 新包，避免污染现有 model 包。

### 5.1 `AiUiSnapshot`

给模型看的"屏幕子集"。原始 `StableNodeInfo` 节点过多（动辄几百个），必须压缩。

```kotlin
@Serializable
data class AiUiSnapshot(
    val captureTimeMillis: Long,
    val packageName: String?,        // 当前前台包名，便于模型理解上下文
    val activityName: String?,       // 当前 Activity 短名（可选）
    val nodes: List<AiUiNode>        // 已压缩的节点列表
)

@Serializable
data class AiUiNode(
    val id: Int,                     // 在本次 snapshot 中的稳定编号，AI 用这个 id 引用节点
    val parentId: Int? = null,
    val className: String,           // 已 short class name，例如 android.widget.Button
    val viewId: String? = null,      // viewIdResourceName，如 com.example:id/btn_send
    val text: String? = null,
    val contentDesc: String? = null,
    val bounds: AiBounds,            // global 坐标
    val flags: AiUiNodeFlags
)

@Serializable
data class AiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable
data class AiUiNodeFlags(
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val focused: Boolean
)
```

### 5.2 压缩策略 `AiNodeTreeCompactor`

模型上下文有限，且 token 越少响应越稳。**压缩规则的初版**：

1. 只保留 `clickable || longClickable || editable || scrollable || (text != null) || (contentDesc != null)` 的节点。
2. 对每个保留节点，记录其在原树中的 `parentId`（可能是被剪掉的容器对应保留祖先），便于 AI 推理父子关系。
3. 文本字段做长度截断（默认 80 字符，可调）。
4. 节点总数硬上限（默认 60 个）；超出按"屏幕中心 + 大面积优先"排序保留。
5. 屏幕外节点（visible bounds 与屏幕无交集）丢弃。
6. 敏感节点（`viewId` 命中 `password` / `cvv` / `bank` 等关键词）显式标 `redacted = true`，不上传 text。

> 注意：上面这些参数都应该有 Preferences 入口，或者至少集中在 `ScreenSnapshotConfig`，避免散落硬编码。

### 5.3 `AiUiTarget` —— AI 描述一个节点

AI 不直接发回"压缩树里 id=12 的节点"，而是发回**定位条件**，由本地代码在最新的真实节点树里再找一遍。这样可以容忍：

- AI 看到的快照与执行时刻的屏幕之间的微小差异（典型场景：AI 想完后界面刷新了）。
- 模型偶尔幻觉 id 数字。

```kotlin
@Serializable
data class AiUiTarget(
    val viewId: String? = null,
    val textEquals: String? = null,
    val textContains: String? = null,
    val contentDescEquals: String? = null,
    val className: String? = null,
    val matchIndex: Int = 0          // 同时多个匹配时取第几个
)
```

转换时由 `AiTaskDraftConverter` 把 `AiUiTarget` 翻译成一组 `UiObjectCriterion` 的 AND 组合，再包进 `containsUiObject`。

### 5.4 新增 capability 枚举

加到 `app/src/main/java/top/xjunz/tasker/ai/model/AiCapability.kt` 已有枚举里：

- `InspectScreen`：读取当前屏幕节点树。**这是分水岭权限**，必须默认拒绝、首次手动授权。
- `ClickUi`：基于 `AiUiTarget` 发起点击 / 长按。
- `InputTextInUi`：基于 `AiUiTarget` 写入文本。复用现有 `InputText` 也可，但建议拆分以便单独控制风险。
- `WaitForUi`：等待某节点出现 / 消失（基于 `clickIfExits` 思路扩展）。

风险等级建议（在 `AiRiskAssessor.DEFAULT_CAPABILITY_RISKS` 里登记）：

| Capability | 默认风险 | 默认 grant |
|-----------|---------|-----------|
| `InspectScreen` | Medium | 不放行，首次授权 |
| `ClickUi` | Medium | 不放行，首次授权 |
| `InputTextInUi` | High | 不放行，每次确认 |
| `WaitForUi` | Low | 可加入默认 grants |

### 5.5 新增 `AiTaskCapability` 条目

加到 `app/src/main/java/top/xjunz/tasker/ai/capability/AiTaskCapabilityCatalog.kt`，让 prompt 自动列出这些动作。建议第一批：

| capabilityId | 参数 | 描述（给模型看的） |
|--------------|------|-------------------|
| `read_screen_snapshot` | 无 | 请求一次当前屏幕快照（由系统注入，不真的进 Applet） |
| `click_ui_object_by` | `target: AiUiTarget` | 点击目标节点 |
| `long_click_ui_object_by` | `target: AiUiTarget` | 长按目标节点 |
| `set_text_in_ui_object_by` | `target: AiUiTarget`, `text: String` | 在目标输入框写入文本 |
| `wait_for_ui_object` | `target: AiUiTarget`, `timeout_seconds: Int` | 等待目标节点出现，超时算失败 |

`read_screen_snapshot` 与其他不同：它**不是任务里的一步**，而是**模型推理过程中的一次系统提供 tool call**。第一阶段为简化实现，可以走"先抓快照、再连同 prompt 一起发给模型"的固定流程，不引入真正的多轮 tool calling。

## 6. 转换链路：AI 步骤 → Applet

以 `click_ui_object_by(target=AiUiTarget(viewId="com.tencent.mm:id/send_btn"))` 为例，目标产物（语义伪代码）：

```text
RootFlow
  └─ Do
      └─ UiObjectFlowRegistry.containsUiObject (根节点引用 = 当前根)
            ├─ UiObjectCriterionRegistry.withId("com.tencent.mm:id/send_btn")
            └─ UiObjectActionRegistry.click  (绑定 referent = 上面 contains 命中的节点)
```

具体落地建议：

1. 在 `AiTaskDraftConverter` 里新增 `convertClickUiObject` / `convertLongClickUiObject` / `convertSetTextInUiObject` / `convertWaitForUiObject`。
2. 引入辅助 `AiUiTargetToCriterionApplets`：把 `AiUiTarget` 各字段翻译成对应 Criterion，复用 `NodeInfoOverlay.collectProperties()` 中相同的 `yieldWithFirstValue` 调用风格，保持序列化兼容性。
3. 复用 `AppletSelectorViewModel.acceptAppletsFromAutoClick` 已经验证过的"包 `containsUiObject` + 设置 referent + 拼接当前 App 条件"逻辑——但要把里面的 `flow.add(...)` 改成"返回一个新 Flow 节点"，避免直接绑死到 ViewModel。建议抽一个 `UiObjectFlowAssembler` 工具类，让 Inspector UI 与 AI 转换器都调用它，减少两份实现漂移的风险。

## 7. UI 与授权交互

### 7.1 第一次启用

新增"AI 屏幕感知"开关，放在现有 AI 配置弹窗里，**默认关闭**。开启时弹出一个解释卡片：

- 说明 AI 会在哪些时机读取当前界面节点树（仅在你触发语音/文字命令、且 AI 决定需要操作 UI 时）。
- 说明哪些字段会被发送：className / viewId / 短文本 / 边界，不发送截图、不发送 password 字段文本。
- 提供"启用"和"始终关闭"两个按钮；启用后写入 `AiCapabilityGrant(InspectScreen, scope = AiScope.Session, maxRisk = Medium)`。

### 7.2 执行时

- 决策记录里展示一段"AI 看到的子集摘要"，例如`看到 12 个可点击节点 + 2 个输入框，最终选中 viewId=com.tencent.mm:id/send_btn`。
- 高风险动作（`InputTextInUi` 写入文本）保持每次确认弹窗，弹窗中展示**目标节点定位条件**和**待写入文本预览**。

### 7.3 失败回退

- 节点未找到 → 标记 step 为 Unsupported，转给用户在编辑器里手动补齐。不要让 AI 自动"再请求一次屏幕"，避免循环。
- A11y 服务未连接 → 抓取阶段直接拒绝，记录 `providerError = "a11y_disabled"`，引导用户去启用无障碍。

## 8. 风险与边界

| 风险 | 处置 |
|------|------|
| 节点树包含个人信息（聊天文本、地址、手机号） | 压缩阶段截断长度；命中敏感 viewId 字段时 `redacted = true`，不发送 text；提供"完全禁止上传 text"的隐私模式 |
| 模型幻觉一个不存在的节点 | 转换时再校验一次匹配数；找不到就标 Unsupported，不静默执行 |
| 节点位置变化导致执行失败 | 用 `containsUiObject` + Criterion 重定位，而不是用快照里的 bounds 直接点坐标 |
| 用户不知道 AI 已经"看了屏幕" | 决策记录强制保留快照子集；状态卡片同步显示"AI 正在读取屏幕"动画 |
| Inspector UI 与 AI 同时争夺 `InspectorViewModel` | AI 链路只用 `rootInActiveWindow + freeze()`，**不实例化 `FloatingInspector` 或 `InspectorViewModel`**，避免对 UI 状态的副作用 |
| 执行被悬浮窗自身遮挡 | AI 流程不打开 Floating Inspector，无遮挡问题；如未来加屏幕高亮提示，需用独立小尺寸 overlay |
| Shizuku 运行模式差异 | `UiObjectActionRegistry.click` 已自带分支，AI 侧不需要感知；记录里附 `runtimeMode` 便于排查 |

## 9. 分阶段路线

第二阶段拆为三步走，不要一次推完：

### Phase 2.A · 只读快照（最小可用）

- 新增 `ScreenSnapshotProvider`、`AiNodeTreeCompactor`、`AiUiSnapshot` 模型。
- 新增 `AiCapability.InspectScreen` + 默认风险 + 授权弹窗。
- VoiceAiInterpreter 在 prompt 中追加"如需操作 UI，可在请求时声明 `requires_screen=true`"；当声明为 true 时，先抓一次快照塞进 prompt 再调用模型。
- **不**支持 AI 真正生成 click / setText 步骤；只让 AI 输出"当前屏幕上有 X，可以做 Y"的解释，作为可视化验证。

> 验收点：在微信 / 浏览器随便打开一个页面，问 AI"现在屏幕上有什么按钮"，AI 能列出主要按钮并说明 viewId / 文本。

### Phase 2.B · 可执行节点

- 新增 capability `click_ui_object_by` / `wait_for_ui_object` 与对应 `AiCapability.ClickUi` / `WaitForUi`。
- `AiTaskDraftConverter` 实现对应 `convert*` 方法，复用抽出的 `UiObjectFlowAssembler`。
- 决策记录里展示完整的"AI 看到 → AI 选择 → 转成的 Applet"。
- 仍保留默认 `requires confirmation`，避免 AI 没看清就点错。

> 验收点：说"打开微信，点开第一个会话"，AI 生成的草稿能在编辑器里直接执行成功。

### Phase 2.C · 写入 + 等待 + 兜底

- 加 `set_text_in_ui_object_by`，敏感字段 redact 必须有效。
- 加 `clickUiObjectWithText` / `clickIfExits` 等不依赖快照的兜底 capability，让 AI 对查不到节点的场景有降级方案。
- 引入"成本与频率限制"：每次 AI 请求屏幕快照都消耗 token 配额，需要写入 `AiCostLimit`。
- 评估是否要做"多轮 tool calling"——只在第一阶段确认收益后再上。

> 验收点：说"在微信搜索框里输入 hello 并发送"，AI 草稿能正确生成搜索框定位 + setText + 点击发送，且写入文本展示在确认弹窗。

## 10. 与现有文档的关联

- `07-ui-architecture.md`：当 Inspector 相关流程被复用为 AI 数据源时，需要在 Inspector 章节加一个"AI 复用"小节，并指向本文件。
- `04-feature-catalog.md`：第二阶段任何新增 Registry / Capability 都需登记。
- `09-development-guide.md`：补充"如何让 AI 学会一个新的 UI 操作 capability"的标准步骤。
- `10-troubleshooting.md`：补充 `a11y_disabled` / `screen_snapshot_too_large` / `ui_target_not_found` 三种典型错误。

## 11. 待决问题

第二阶段开工前需要先讨论清楚：

1. AI 只读快照阶段，是否复用语音页同一卡片展示"AI 看到了什么"，还是新增独立调试入口？
2. 节点压缩硬上限（默认 60）是否可调？是否应该做按 App 的差异化默认值（聊天 App 文本多，电商 App 节点多）？
3. 隐私模式（不上传任何 text）是否需要做成独立开关？
4. 是否在第一次开启 `InspectScreen` 时跑一个引导教程（截图 + 解释）？
5. 决策记录里保留快照子集需要多久？默认与现有 `AiAuditStore` 容量一致还是单独配？
6. 后续若引入截图能力，截图阶段是否走 Shizuku UiAutomation `takeScreenshot()`，避免 A11y `takeScreenshot` 的 SDK / 鉴权限制？

## 12. 给下一轮实现的提醒

- 不要直接打开 `FloatingInspector` 来获取节点树，**必须**走 `A11yAutomatorService.rootInActiveWindow` + `StableNodeInfo.freeze()`，否则会污染用户当前 Inspector 状态。
- AI 链路里抓节点树要在 `Dispatchers.Default` 上做，不要阻塞主线程；`freeze()` 里有递归 `dup()`。
- 节点树压缩要带单测：固定一棵假树 → 验证只剩可交互节点 + 父链一致。
- 转换器要带单测：AI 输出的每个 capability → 对应 Applet 树形结构 + Registry id 与现有 `acceptAppletsFromAutoClick` 一致。
- 任何新代码文件用 `Copyright (c) 2026 IanVzs. All rights reserved.`，包路径放 `top.xjunz.tasker.ai.inspector` / `top.xjunz.tasker.ai.draft`。
- 文档同步：第二阶段任意一步落地都要回头更新 `14-ai-integration.md` §5 表格、`13-todo.md` 进度、本文件 §9 验收状态。

## 13. 关键转向：从 Phase 2.A/B/C 三段式 → Agent Loop（2026-05-09 决策）

### 13.1 触发原因

Phase 2.A/B/C 把"读屏 / 点击 / 输入"分成三个阶段，每阶段都要走完一轮"开发 → 用户验证 → 再开发"。第一阶段验证完后用户判断：

> **用户原话**：
> "因为很多任务都是打开 app 然后才开始具体的任务内容 如果权限只有打开某 app 那么 AI 没有任何意义！不能操作控件 自动识别控件了解控件功能那么就无法完成用户诉求 这样的 AI 也是毫无意义！！！全部可以用规则取代 不对吗？"
> "你好好考虑下如何将 AI 可以根据用户需求探索式的建立一个一步步可执行的 渐进式的 可以试错可以改正 可以探索的 建立可以完成任务的任务"
> "开始做吧 放开手脚 先将 AI 从笼子里放出来 然后在后续我的使用中再去给他设定一些限制"

判断成立：planner-only 模式（AI 一次性出几步 Applet 描述）做不到"看屏幕、再决定下一步"，那就只是规则的低配版。**真正的护城河必须是 agent loop**：循环里反复"读屏 → 决策 → 执行 → 看结果 → 再决策"。

### 13.2 路线调整

Phase 2.A 只读快照 + Phase 2.B 可执行节点 + Phase 2.C 写入兜底，**合并成一次性落地的 agent loop 闭环**。三个原阶段被新结构吸收：

| 原 Phase | 现在的位置 |
|---------|-----------|
| 2.A 只读快照 | `ScreenSnapshotProvider` + `AiUiSnapshot` 已实现，第一时间被 `AiAgentSession` 使用 |
| 2.B 可执行节点 | `AiAgentExecutor` + `AiUiTarget` 直接执行 click / set_text / scroll / launch / global，**不再分批** |
| 2.C 写入与兜底 | set_text 默认开放；兜底容错下放给 agent loop 自身（AI 看到失败就调整下一步） |

边界"四条铁律"也调整为更简的"任务级授权 + 步数 / 时长 / App scope"，不再做每步阻断式弹窗。

### 13.3 实际落地（2026-05-09）

新建文件（`app/src/main/java/top/xjunz/tasker/ai/agent/`）：

- `AiUiSnapshot.kt`：节点压缩 DTO + `AiUiTarget` 定位条件
- `ScreenSnapshotProvider.kt`：从 `A11yAutomatorService.rootInActiveWindow` + `StableNodeInfo.freeze()` 抓取并压缩快照
- `AiAgentAction.kt`：`AiAgentActionDto` + `sealed class AiAgentAction` + `AiAgentStepResult/Record`
- `AiAgentExecutor.kt`：用最新真实节点树二次定位 → `performAction(ACTION_CLICK / LONG_CLICK / SET_TEXT / SCROLL_FORWARD / SCROLL_BACKWARD)` / `performGlobalAction(BACK / HOME)` / `startActivity(Intent)`
- `AiAgentPlanner.kt`：两段 prompt —— `planSession` 出会话规划、`nextAction` 出每步动作（携带 history + snapshot）
- `AiAgentSession.kt`：循环 `读屏 → askAi → execute → 反馈 → 边界检查`；7 种 outcome（Completed / GivenUp / LimitExceeded / OutOfScope / PermissionDenied / AiError / Cancelled）
- `AiTaskScope.kt`：任务级授权数据结构 + `AiTaskScopeStore`（in-memory，session 级）

接入：

- `Preferences` 新增 `aiAgentEnabled`（默认 **true**，符合用户"先放开"要求）+ `aiAgentMaxSteps` (30) + `aiAgentMaxSeconds` (90)
- `VoiceCommandService.runAiInterpretation` 在 `CreateTaskDraft` 分支检测 `aiAgentEnabled`，启动 `runAgentFlow(text)`：
  1. 调 `AiAgentPlanner.planSession`
  2. 暴露 `pendingAgent: AgentRequestPayload` 给 UI 等用户授权
  3. `CompletableDeferred` 等用户决策（60s 超时算拒绝）
  4. 用户允许 → 写 `AiTaskScope` 进 store → `AiAgentSession.run()` 跑
  5. 每步通过 `Callbacks.onStep` 回调追加到 records
  6. outcome 写一条总结记录
- `VoiceCommandFragment` 监听 `pendingAgent` 弹任务级授权 `MaterialAlertDialog`（列出目标 / App / 边界 / 能力，"允许这次 / 拒绝"）
- `AboutFragment` 的 AI 配置弹窗加 "启用 AI agent 模式" 复选框 + 解释段
- 新增 25+ 条 strings 描述会话生命周期（planning / planned / denied / started / step / completed / given_up / limit / out_of_scope / permission_denied / ai_error / cancelled）

### 13.4 边界设计（仅四条，不阻塞执行）

1. **任务级授权**：每个新会话弹一次对话框列出"目标 / App / 步数 / 时长 / 能力"，一键允许整段会话；session 内不再有任何打断。
2. **App scope 锁**：`AiAgentSession` 每步抓快照后检查当前包名是否在 `scope.targetApps` 内；切到非授权 App 立即停止并报告 `OutOfScope`。`launcher / systemui / android` 等过场包名豁免。
3. **步数 / 时长上限**：默认 30 步 / 90 秒，到顶即停。
4. **未授权能力即停**：每步执行前检查 `action.requiredCapability()` 是否在 `scope.capabilities` 内；不在则 `PermissionDenied`，不静默跳过。

### 13.5 已知 follow-up（不阻塞验证）

- agent 任务定位为**独立运行、跑完即丢**，结束**不**自动生成可重放 XTask（2026-05-13）。改为：① 通过通知告知用户成功 / 失败结果；② 执行心得用"经验本" (`ai/agent/experience/`) 沉淀，下次同类任务开局召回注入 prompt；③ 成功经验（outcome=Completed）支持用户在 UI 主动点击"一键转任务草稿"，由 `ExperienceToTaskConverter` 翻成 XTask 弹 FlowEditor 让用户审核（见 `aidoc/20-experience-book-design.md`）。
- 节点压缩规则比较粗暴（`MAX_NODES = 80`、`MAX_TEXT_LEN = 80`），没做按 App 类别差异化；密码 / 银行卡 / CVV 等敏感字段也尚未做白名单 redact，仅依赖 `give_up` 行为指南让 AI 自己回避。
- Plan 阶段 `targetAppPackage` 完全信任 AI；如果 AI 给了错的包名，scope 检查后续会拦下来，但用户体验是"开了一次会话，第一步就 OutOfScope"。下一轮可以加"`PackageManagerBridge` 校验包名实际存在 + 弹窗里允许用户改"。
- 没做"取消运行中 session"的 UI；用户当前只能停掉语音监听服务来强制中断（`scope.cancel()` 会让 `coroutineScope` 抛出 CancellationException → 走到 `AiError` 分支）。下一轮加一个"中止 agent"按钮。

### 13.8 架构对齐：用现有 Applet 管道执行 agent（2026-05-09 第三次补强 / KISS 重构）

**这一节是承担"我之前犯的架构错"的复盘 + 修复方案。请后续做 AI / 自动化相关功能的 agent 必读。**

#### 复盘：之前为什么走偏了

`aidoc/09-development-guide.md §9 「新增 AI 能力」` 里其实写得清清楚楚：

> AI 功能先作为现有任务系统的上层协作者，**不直接绕过 `XTask` / `Applet` / `AutomatorService`**。

我无视了这条警告。前几轮我把 `AiAgentExecutor` 写成"主进程直接调 `A11yAutomatorService.rootInActiveWindow` + `node.performAction(...)`"，造成两个根本错误：

1. **进程模型违反**：a11y service 跑在主进程，Shizuku service 跑在 :service 特权进程。我的代码在主进程访问 `currentService.uiAutomatorBridge` 在 Shizuku 模式下会触发 `ensurePrivilegedProcess()` 抛 `IllegalStateException`，这就是用户实测"啥也检测不到"的根因。
2. **与现有 Applet 体系并行**：AutoTask 已经有一套完整的"`UiObjectFlowRegistry.containsUiObject` + `UiObjectCriterionRegistry` + `UiObjectActionRegistry` + `scheduleOneshotTask` 派进特权进程跑"管道，被 inspector / 任务执行使用。我无视它，自己发明 `AiUiTarget` + `matchesNode` + `performNodeAction` 第二套体系，违反 DRY。

**根因**：开工前没读 `aidoc/02 §2 进程模型`、`aidoc/05 §2 两种实现`、`aidoc/09 §9 AI 警告`，凭"看见 inspector 用什么就抄什么"做决定。

**今后硬约束**：碰任何"读屏 / 执行 UI 动作 / 跨进程"的代码，**开工前必读** `aidoc/02 §2`、`aidoc/05`、`aidoc/03`、`aidoc/09`，并在 PR / todo 里加一条"已对照 aidoc"。这条写进 `aidoc/README.md` 顶部 + `aidoc/13-todo.md`。

#### 新方案：方案 K（KISS，复用现有任务管道）

**核心想法**：agent 每一步 AI 输出的动作，**翻译成一个最小的临时单步 `XTask`**，通过现有的 `currentService.scheduleOneshotTask(task, callback)` 派进特权进程跑。复用项目原本就有的所有能力。

```
┌─ 主进程 ────────────────────────────────┐    ┌─ :service / a11y service ─┐
│                                          │    │                           │
│  AiAgentSession.run() 循环              │    │  PrivilegedTaskManager    │
│   ① 抓屏 (走 AIDL)                      │    │   或 LocalTaskManager     │
│   ② AI 决策 (LLM)                       │    │   ↓                       │
│   ③ 决策面板 (用户介入)                 │    │   现有 Applet 管道执行    │
│   ④ AiActionToTask.translate(action)   │    │   - containsUiObject       │
│      → 临时单步 XTask                   │    │   - withId / textEquals    │
│   ⑤ scheduleOneshotTask(task, cb)─────────→│   - click / setText / ...  │
│   ⑥ ← callback ok/fail ───────────────────│   - launchApp / suspension │
│   ⑦ history += step                     │    │                           │
└──────────────────────────────────────────┘    └───────────────────────────┘
```

#### `AiAgentAction` ↔ 现有 Registry 映射表

每条 AI 动作翻译成**一棵最小 XTask 树**：`RootFlow → Do → (containsUiObject → Criterion + Action)`，或 `RootFlow → Do → 单 Action`。

| AiAgentAction | 翻译目标 |
|---|---|
| `LaunchApp(pkg)` | `RootFlow → Do → ApplicationActionRegistry.launchApp(pkg)` |
| `Click(target)` | `RootFlow → Do → UiObjectFlowRegistry.containsUiObject{ Criterion(target) ; UiObjectActionRegistry.click }` |
| `LongClick(target)` | 同上，最后 action 换 `longClick` |
| `SetText(target, text)` | 同上，最后 action 换 `setText` 带文本参数 |
| `Scroll(target?, direction)` | 若 target 非空：`containsUiObject` 包；direction → `UiObjectActionRegistry.scrollForward/Backward`；target null 时找当前 scrollable 容器（暂用 `forEachUiScrollable` 或最简：先要求 target） |
| `Wait(seconds)` | `RootFlow → Do → ControlActionRegistry.suspension(seconds)` |
| `GlobalBack` | `RootFlow → Do → GlobalActionRegistry.pressBack` |
| `GlobalHome` | `RootFlow → Do → GlobalActionRegistry.pressHome` |
| `Done` / `GiveUp` / `Unknown` | session 终止，**不翻译为 task**，直接走 outcome |

`AiUiTarget` 翻译为 Criterion：
- `viewId` → `UiObjectCriterionRegistry.withId.yieldWithFirstValue(viewId)`
- `textEquals` → `textEquals.yieldWithFirstValue(text)`
- `textContains` → `textContains.yieldWithFirstValue(text)`
- `contentDescEquals` → `contentDesc.yieldWithFirstValue(desc)`
- `contentDescContains` → `contentDesc` 的 contains 变体（看 Registry 实际可用方法）
- `className` → `isType.yieldWithFirstValue(className)`

多字段 AND 关系靠 `containsUiObject` 内多 Criterion + 默认 `REL_AND`。

#### 新模块 `ai/translator/AiActionToTask`（新增）

```kotlin
object AiActionToTask {
    /**
     * 把一个 AiAgentAction 翻译成一棵最小 XTask 树。
     * 返回的 XTask 已经设置好 metadata.checksum，可以直接 LocalTaskManager.addOneshotTaskIfAbsent + scheduleOneshotTask。
     * Done/GiveUp/Unknown/Wait 之外的所有 action 都翻译成 RootFlow → Do → ... 结构。
     * Done/GiveUp/Unknown 调用方应该自己处理（不调 translate）。
     */
    fun translate(action: AiAgentAction): XTask?
}
```

- 内部用 `AppletOptionFactory.uiObjectFlowRegistry.containsUiObject.yield()` / `uiObjectRegistry.withId.yieldWithFirstValue(...)` 等 DSL 直接拼。
- 复用 `task/inspector/shared/NodeToActionAssembler.wrapAsContainsUiObject(...)` 包 containsUiObject。
- 引用编辑：`AppletReferenceEditor` 设 reference / referent，让 Action 能拿到 `containsUiObject` 命中节点。
- `metadata.title = "AI Step #${stepIndex}: ${action.kind}"`，`taskType = TYPE_ONESHOT`，`checksum = 内容 MD5`。

#### AIDL 变更（仅 1 个新方法，末尾追加保兼容）

`app/src/main/aidl/top/xjunz/tasker/service/IRemoteAutomatorService.aidl`：

```aidl
// 新增：让主进程拿到当前屏幕节点树压缩 JSON。
// 在特权进程里调本地 uiAutomation.rootInActiveWindow + StableNodeInfo.freeze + AiNodeTreeCompactor。
// 返回 JSON（AiUiSnapshot 序列化），主进程反序列化即用。
String captureUiSnapshotJson(int maxNodes, int maxTextLen) = 17;
```

按 `aidoc/05` 的兼容策略，**末尾追加**而非修改签名，老服务 / 老客户端互相兼容（旧服务调用此方法会返回默认/空，新客户端能 graceful degrade）。

#### A11y 模式怎么办

A11y 模式下 `currentService` 不是 `ShizukuAutomatorService.Stub` 而是 `A11yAutomatorService` 自己（同一进程），`AutomatorService` interface 默认方法行为不需要 AIDL。

**最小代价**做法：在 `AutomatorService` interface 上加一个**默认方法** `captureUiSnapshotJson(maxNodes, maxTextLen): String`，A11y 实现里直接调 `rootInActiveWindow + freeze + AiNodeTreeCompactor`；ShizukuAutomatorService 实现里走 IRemoteAutomatorService AIDL（特权进程内仍然是同样实现）。这样主进程统一调 `currentService.captureUiSnapshotJson(...)`，分发由 `currentService` 自动处理。

#### 删除清单

- `app/src/main/java/top/xjunz/tasker/ai/agent/AiAgentExecutor.kt` 大幅瘦身：
  - 删 `performNodeAction` / `performClick` / `performLongClick` / `performSetText` / `performScroll` / `performGlobal` 整个执行链
  - 删 `matchesNode` / `findFirstScrollable` / `summarize` / `currentForegroundPackage` / `traverseAndCollect`（如还有）
  - 仅保留 `execute(action)` 入口，内部一律走 `AiActionToTask.translate(action)` → `LocalTaskManager.addOneshotTaskIfAbsent(task)` → `currentService.scheduleOneshotTask(task, callback)` → `await callback`
- `app/src/main/java/top/xjunz/tasker/task/inspector/shared/UiTreeQuery.kt` 大幅瘦身：
  - 删 `rawRoot` / `findNodes` / `findStableNodes`
  - 仅保留 `captureFrozenRoot()` 但内部改成调 `currentService.captureUiSnapshotJson(...)` 反序列化（或这个能力直接挪到 `ScreenSnapshotProvider` 内部，删 `UiTreeQuery.captureFrozenRoot`）
- `app/src/main/java/top/xjunz/tasker/ai/agent/ScreenSnapshotProvider.kt` 大幅瘦身：
  - 删 `compactNode` / `toAiUiNode`（这套逻辑搬到执行端进程作为 `AiNodeTreeCompactor`）
  - `capture()` 改成"调 `currentService.captureUiSnapshotJson(...)` → `AiJson.decodeFromString<AiUiSnapshot>(json)`"
- 新建 `app/src/main/java/top/xjunz/tasker/ai/snapshot/AiNodeTreeCompactor.kt`（执行端用，`AiUiSnapshot.kt` 维持 `ai.agent` 包不动作为跨进程 DTO）

#### 实施顺序

1. AIDL 加方法（aidoc/05 §7 兼容策略）
2. `AutomatorService` interface 加默认方法
3. `ShizukuAutomatorService` / `A11yAutomatorService` 各自实现（共用一套压缩逻辑）
4. 主进程 `ScreenSnapshotProvider` 改走 RPC
5. 新建 `AiActionToTask`
6. 重写 `AiAgentExecutor` 走 `scheduleOneshotTask`
7. 清理删除列表里的代码
8. `make debug` 编译验证 + Shizuku 模式实测

### 13.7 公共能力抽取与决策面板（2026-05-09 第二次补强）

继续解决"AI 与悬浮助手没真正打通"的根本问题，本轮一次性把 A→B 两条线一起落地：

#### A. 公共能力抽取（`task/inspector/shared/`）

把"节点级"的纯 data 操作集中到一处，让 inspector 和 agent 走同一份代码：

| 新建文件 | 职责 | 谁会调用 |
|---|---|---|
| `UiTreeQuery.kt` | 抓 root + freeze + DFS 查找 | `ScreenSnapshotProvider`（已迁）/ `AiAgentExecutor`（已迁）/ 未来 inspector 自身可迁 |
| `NodeCriteriaExtractor.kt` | 节点 → Criterion Applet 候选（从 `NodeInfoOverlay.collectProperties` 抽，**语义和顺序严格对齐**） | `NodeInfoOverlay.collectProperties`（已迁）/ `AiAgentTaskAssembler.buildTaskFromRealNode`（一次性临时 task） |
| `NodeToActionAssembler.kt` | Criterion 候选 → `containsUiObject` Flow（从 `acceptAppletsFromAutoClick` 抽 wrap 部分） | `AppletSelectorViewModel.acceptAppletsFromAutoClick`（已迁）/ `AiActionToTask` 旧草稿翻译路径 / `ExperienceToTaskConverter` 经验本→草稿 |
| `AiUiTargetExtractor.kt` | 真节点 → `AiUiTarget`（"换一个"反向通道） | `CandidateListPicker`（V1）/ 未来 V2 inspector 接管 |

调用方迁移后，**inspector 现有"用户手选节点 → Criterion / 自动点击"行为不变**，只是底层走公共代码。

#### B. Agent 决策面板（`ai/agent/overlay/`）

每步 agent action 在执行前都通过悬浮窗征询用户：**同意 / 拒绝 / 换一个**，倒计时到默认同意。

| 新建文件 | 职责 |
|---|---|
| `AiAgentDecision.kt` | 决策结果 sealed interface：`ApprovedManual` / `ApprovedAuto` / `Rejected(source)` / `Replaced(newTarget)` / `Skipped`；`AiAgentConfirmMode` 枚举 4 种偏好 |
| `AiAgentNodePicker.kt` | "换一个"抽象接口 + `CandidateListPicker`（V1，从快照按相似度排序前 5 候选）+ `InspectorPickerStub`（V2 占位，未来调起 FloatingInspector 让用户直接点屏） |
| `AiAgentOverlayController.kt` | WindowManager 生命周期 + 程序化构造的决策卡片（标题 / 动作 / 思考 / 倒计时 ProgressBar / 3 个按钮 / 候选列表展开）+ 节点高亮（半透明红框 1s 淡出）|

接入：

- `AiAgentSession` 构造函数加 `overlay: AiAgentOverlayController?` + `picker: AiAgentNodePicker`；每步 nextAction 后 capability 校验通过、execute 之前 await `overlay.requestDecision`。
- 决策结果直接影响 execute：`Replaced` 把 action.target 换成新 target 再执行；`Rejected` 写一条 FAIL 反馈记录跳过；其他直接执行。
- `AiAgentStepRecord` 加 `userIntervention: AiAgentDecision?` 字段，写入 history。
- `AiAgentPlanner.buildHistorySection` 把 `[用户同意]` / `[用户拒绝(原因)]` / `[用户换为...]` 标签塞进 prompt 喂给 AI，实现**自我学习**；尾部加一行 `（累计：用户拒绝 X 步，用户换节点 Y 步——请反思你的选择是否经常偏离用户意图）` 做行为引导。
- `Preferences` 加 `aiAgentConfirmMode` (默认 `auto_approve`) / `aiAgentConfirmSeconds` (3) / `aiAgentConfirmAllowReplace` (true)。
- `VoiceCommandService.runAgentFlow` session 创建时实例化 overlay，结束时 dismiss。

#### V1 → V2 过渡空间

- `AiAgentNodePicker` 接口设计的最大价值：V2 把 `CandidateListPicker` 换成 `FloatingInspectorPicker` 时，**决策面板 UI / `AiAgentDecision` / session 集成全都不动**，只换实现类。
- V2 实现要点（写在 `InspectorPickerStub` 注释里）：弹起 `FloatingInspector(mode=UI_OBJECT)` → 监听 `EVENT_NODE_INFO_SELECTED` → `AiUiTargetExtractor.extract` → resume Continuation 返回 target。

#### B3 配置 UI 推到下一轮

`Preferences.aiAgentConfirmMode/Seconds/AllowReplace` 已有默认值，绝大多数用户用默认配置即可。完整 "AI agent 决策" 子页面 UI（弹窗 spinner / 滑杆 / checkbox 三件套）排到下一轮做。

#### 不可妥协边界（写在代码与文档）

- 公共能力的方法**必须**纯 data，不依赖 UI / ViewModel / LiveData / a11y service 状态。
- AI agent 链路调"换一个"反向通道时，必须经过 `AiUiTargetExtractor`，禁止自己手写"节点 → AiUiTarget"。
- 决策面板 overlay 的视图全部程序化构造，不引入 XML / databinding，便于未来微调。
- overlay 必须优雅降级：用户没授 SYSTEM_ALERT_WINDOW 时所有 overlay 操作 noop，agent 仍可自动跑（等同 `Disabled` 模式）。

### 13.6 plan 自检机制（2026-05-09 补强）

第二阶段 backend 闭环跑通后立刻补上的关键能力：让 AI 在 agent loop 里**对照原 plan 自检"我有没有偏轨"**，并把这个评分喂回上层用于审计、未来可触发"主动询问用户"等扩展。

#### 触发原因

> **用户原话**：
> "AI 可以在每一步获取到页面的可点击控件和输入框这些信息 对自己的草稿规划进行纠正吗？"

诊断后明确：每步抓屏 + history 反馈早已具备，AI 已经能根据屏幕实情纠正下一步动作；但**初始 plan 在进入 loop 后被丢弃**，AI 没法对照原 plan 显式自评，导致：

- 中等复杂任务里偶尔兜圈（连续点错却不知道自己已经偏离）。
- 用户审计时只能看动作流水，看不出"AI 是否还在原方向上"。

#### 设计

1. **`AiAgentActionDto` 加 `plan_status` 字段**（`SerialName("plan_status")`），AI 必须每步返回 `on_track / adjusted / off_track / unknown` 之一，`AiAgentAction` 各 sealed 分支同步加 `planStatus` 字段透传。
2. **`AiAgentPlanStatus` 枚举 + `parse(raw)`**：把 AI 返回字符串归一化（兼容 `on-track` / `ontrack` / 大小写），无法识别即 `Unknown`。
3. **`AiAgentPlanner.nextAction` 新签名**接收 `plan: AiAgentSessionPlan?`；prompt 增加 `buildPlanSection`，向 AI 明示：
   - 原计划摘要 + 估计步数 + 已用步数 + 上限
   - 必须每步填 `plan_status`，行为准则里加上"连续两步 off_track 且看不到回轨迹象就 give_up"
4. **`buildHistorySection` 把每步的 plan_status 一起喂回去**：AI 回看时能看到自己之前怎么自评，便于跨轮一致性。
5. **`AiAgentSession` 构造函数新增 `plan: AiAgentSessionPlan?` 字段**，每轮调 `nextAction` 时透传；`VoiceCommandService.runAgentFlow` 把 `planResult.plan` 传进去。
6. **`VoiceCommandService` UI 反馈**：
   - 步骤标题前缀显示 `[在轨]/[微调]/[偏轨]/[未自评]` 标签
   - `off_track` 步骤即使 OK 也用 `FAILURE` 颜色（红条），便于审计一眼挑出
   - outcome 总结附加一行 `实际 N 步 / 估计 M 步 · 在轨 A · 微调 B · 偏轨 C · 未自评 D` 统计

#### 现在的链路

```
用户："帮我在 X 里 Y"
    ↓
planSession → plan(targetApp, summary, estimated_steps, capabilities)
    ↓
任务级授权对话框 → 用户允许
    ↓
AiAgentSession(scope, plan, ...) 循环：
    1. ScreenSnapshotProvider.capture()
    2. AiAgentPlanner.nextAction(userGoal, history, snapshot, plan, maxSteps)
       prompt 包含：原 plan + history(含历史 plan_status) + 当前屏幕节点
    3. AI 输出 action（含 plan_status 自评）
    4. AiAgentExecutor.execute(action)
    5. record(plan_status)，UI 显示 [在轨]/[微调]/[偏轨]
    6. 边界检查 + 终止判定
    ↓
outcome：附带"实际 N 步 / 估计 M 步 · 在轨/微调/偏轨/未自评"统计
```

这一改动对 token 消耗的影响：plan section 大约 50-100 tokens，每步多一个 plan_status 字段约 5-10 tokens；总体增长在 5% 以内，但换来了"AI 自我导航"的关键能力。
