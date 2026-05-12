# 17 — Inspector vs AI Agent 链路对照与对齐方案

> **背景**：5 月 9 日—10 日多次测试 deepseek 自动操作均失败；用户反复指出
> 「悬浮助手挑节点 → 保存 task → 执行」这条路径**实测能 work**，AI agent 应当走同款链路。
> 本文档对两条链路做**逐字段**对比，记录差异与对齐方案，作为后续重构的锚定决策。
>
> 之前注释里写「AI agent 跟 inspector 完全对齐」是不实陈述——本次彻底审计纠正。

## 1. Inspector「挑节点 → 保存 → 执行」链路（实测 work，作为基准）

### 1.1 用户操作
1. 浮窗 inspector 模式下选中目标节点（例如 deepseek 输入框 EditText）
2. 点「自动点击」/ 「自动输入」之类入口
3. inspector 调 `NodeCriteriaExtractor.extract(node)` 抽出**完整 criteria**
4. `NodeToActionAssembler.wrapAsContainsUiObject` 包成 `containsUiObject` Flow
5. `AppletSelectorViewModel.acceptAppletsFromAutoClick` 在 flow 头部加 `isCertainApp` + `activityCollection`
6. 用户保存 task

### 1.2 保存下来的 applet 树（典型）

```
RootFlow
├── isCertainApp(packageName = com.deepseek.chat)
├── activityCollection(activity = MainActivity)         ← optional 但常见
└── containsUiObject (默认 AND)
    ├── isType(className)                               ← 等于（字符串完全匹配）
    ├── withId(viewIdResourceName)                      ← 等于
    ├── textEquals(text)                                ← 等于（不是 contains）
    ├── contentDesc(contentDescription)                 ← 等于
    ├── isClickable                                     ← 必须为 true
    ├── isLongClickable                                 ← 必须为 true（如果原节点是）
    ├── isEnabled (可能反向)
    ├── isCheckable / isChecked
    ├── isEditable                                      ← 必须为 true（如果原节点是）
    ├── isSelected (反向，默认未勾选)
    ├── isScrollable (反向，默认未勾选)
    └── childCount (默认未勾选)
    ├── reference[0] = "current_window"
    └── referent[0] = "matched_ui_object"
└── click action
    └── reference[0] = "matched_ui_object"
```

**关键性质**：
- criteria 多达 **11+ 字段**联合 AND，把节点从屏幕上一堆相似节点里**精准锁定到唯一**
- `containsUiObject` 内部用 `root.findFirst { 所有 criteria 都满足 }`，命中率高，误命中率近零
- 包名 + Activity 双重限定，跨页面/跨 App 自动失效

### 1.3 执行链路

`OneshotTaskScheduler.scheduleTask(task, callback)`（特权进程）→
`Flow.applyFlow` 走 RootFlow children →
1. `isCertainApp` 检查包名 → 不对就 fail（短路）
2. `activityCollection` 检查 Activity → 不对就 fail
3. `containsUiObject.applyFlow`：
   - 调 `getUiObjectSearchRoot(runtime)` → 拿 `current_window` 引用 = `uiAutomation.rootInActiveWindow`
   - `root.findFirst { criteria 全部满足 }` → 命中 `node` → 暴露 `UiObjectReferent(node)` 给 `matched_ui_object`
4. `click action`：
   - 拿 `matched_ui_object` 引用 = `node`
   - 走 `UiObjectActionRegistry.click`：
     - `node.isClickable=true` → `performAction(ACTION_CLICK)`（标准 a11y）
     - `node.isClickable=false` → `uiDevice.wrapUiObject(node).click()`
       → 进 `PrivilegedInteractionController.clickAndSync(x, y)`
       → `uiAutomation.injectInputEvent(MotionEvent.ACTION_DOWN/UP)`
       → **物理触摸事件**注入，绕过 a11y 协议

**为什么对 deepseek 这种 RN App 也能 work**：
- criteria 锁定的是真节点（带 isClickable / isEditable 状态），不会误命中外观相似的占位 View
- `wrapUiObject.click()` 走的是 InputManager 注入，等同于真手指触摸，RN/Compose 必然响应

## 2. AI Agent 当前链路（修了 5 次仍失败）

### 2.1 翻译阶段

`AiActionToTask.translate(action)` 在 main 进程组装 task：

```
RootFlow
├── preloadFlow                                       ← 多余（inspector 没有）
└── Do(REL_ANYWAY)
    └── containsUiObject (5 字段最多)
        ├── withId（如果 AI 给了）
        ├── textEquals 或 textContains
        ├── contentDesc
        └── isType（如果 AI 给了 className）
        ├── reference[0] = "current_window"
        └── referent[0] = "matched_ui_object"
    └── click action（reference = matched_ui_object）
```

**与 inspector 对照的差距**：

| 维度 | inspector | AI agent |
|---|---|---|
| 包名约束 (`isCertainApp`) | ✅ 有 | ❌ 没有 |
| Activity 约束 (`activityCollection`) | ✅ 常有 | ❌ 没有 |
| `isClickable=true` | ✅ 有（如果原节点是） | ❌ 完全没考虑 |
| `isEditable=true` | ✅ 有（如果原节点是） | ❌ 完全没考虑 |
| `isLongClickable` / `isCheckable` / `isEnabled` | ✅ 全套 | ❌ 完全没考虑 |
| `isScrollable` 反向 / `childCount` | ✅ 默认勾选 / 不勾 | ❌ 完全没考虑 |
| `preloadFlow` 包裹 | ❌ 不需要 | ❌ 多余加上 |

### 2.2 后果

- AI 给的 target = `{textEquals="发消息", className="EditText"}`
- AI agent 的 `containsUiObject` 仅用 textEquals + className 筛
- deepseek 屏幕上**多个**节点匹中：占位 TextView "发消息或按住说话" + 真 EditText "发消息"
- `findFirst` 返回**先找到的**——占位 TextView 排在前面 → click 它没用 → silent fail
- silent-fail 拉黑、二次重试、setText paste fallback ... 全部都是在补这个根因的下游症状

### 2.3 之前的注释假象

`AiActionToTask.kt` line 33-35 写：
> 「这棵 task 跟 inspector 自动点击的 task 树**完全一致**」

**这是不实陈述**。inspector 的 criteria 比这棵 task 丰富一倍，关键状态约束（isClickable/isEditable）全没复制。

## 3. 修法决策（KISS，不重构）

### 3.1 核心策略

**让 AI agent 复用 inspector 已 work 的整套链路**：

1. AI 仍然输出 `target = AiUiTarget`（弱字段定位）
2. 不在 main 进程组 task；改成调一个**新 AIDL 方法**让特权进程就地完成：
   - 特权端用 target 在 `uiAutomation.rootInActiveWindow` 上 `findFirst` 找真节点
   - 拿到真节点后调 `NodeCriteriaExtractor.extract(node)` → **完整 11+ 字段 criteria**
   - 用 `NodeToActionAssembler.wrapAsContainsUiObject` 包成 flow
   - 加 `isCertainApp(node.packageName)` + click/setText action
   - `PrivilegedTaskManager.addOneshotTaskIfAbsent` + `scheduleTask`
   - 跑完回报成败 + 命中节点 summary

3. **AI 看不到 click 实现细节**——只感知"target 命中 / 没命中 / silent fail"

### 3.2 工作量

| 改动 | 文件 | 估行 |
|---|---|---|
| AIDL 加 `executeAgentActionByTarget(actionType, targetJson, extraText, callback)` | `IRemoteAutomatorService.aidl` | +5 |
| `AutomatorService.kt` 抽象方法 | | +5 |
| `A11yAutomatorService` / `ShizukuAutomatorService` 实现 | 两端各一份 | +120 |
| `AiAgentExecutor` 把 click/longClick/setText 改成调新 RPC | | -50 +30 |
| `AiActionToTask.buildCriteria` 删除（不再需要） | | -50 |
| **总计** | | ~110 净增 |

时间估：**1 天内可落地**。

### 3.3 风险

- **0 风险动核心 applet**：不动 `UiObjectActionRegistry` / `ContainsUiObject` / `NodeCriteriaExtractor`。
- **0 风险动 inspector 路径**：完全不碰 `NodeInfoOverlay` / `AppletSelectorViewModel.acceptAppletsFromAutoClick`。
- 主要风险点：特权端 findFirst 的弱匹配规则要跟现有 `NodeCriteriaExtractor` 抽完后命中率一致——
  写一个 `findFirstByLooseTarget(root, target)` 工具，规则跟 `AiAgentOverlayController.previewTargetBounds` 已有的预匹配实现完全一致。

### 3.4 留下的兜底机制

silent-fail 检测、二次拉黑、setText paste fallback、AI timeout retry 这些 patch
**保留但作用层下降**——它们不再是"补 criteria 不准"的脏补丁，
而是真正意义上的"基础设施成熟时的边界兜底"。

## 4. 教训

- 注释/文档里写「跟 X 一致」时**必须列字段对照清单**，不能口头声称。
- 抽公共代码（如 `NodeCriteriaExtractor`）后，**所有引用方都必须真用它**，不能自己再写一份残缺版。
- 失败 5 次都修不好的事，先停下来 audit 链路差异，不要继续叠 patch。
