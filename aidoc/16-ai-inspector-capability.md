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
