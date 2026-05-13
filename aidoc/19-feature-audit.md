# 19 — Feature 逐条核查清单（inspector 真链路 vs AI agent 我的链路）

> **背景**：5/11 用户明确指出我 7 次"是 a11y 的问题"全是误诊，真因都是我**自己抄 inspector 时漏了字段 / 写错了 RPC / 调错了 API**。
> 用户立的硬约束："写一份文档，一条功能一条功能去核查，inspector 本来怎么实现 → 任务记录里存什么 → 执行时怎么调；AI 这边走的什么；字段级对照"。
> 这样每次只装一条功能在脑子里，**上下文不会漂**。
>
> **使用方法**：用户每次说"core check 第 N 条"，我按本文档相应 section 去逐字段对账，不许扩散到别的功能。

## 0. 待核查功能清单

按 AI agent action 类型分组：

- [x] **F1 click**（点击节点） ✅ 已修（剥掉过严状态字段 criteria，只用语义字段 id/text/desc/className）
- [x] **F2 long_click**（长按节点） ✅ 共享 F1 管道，自动享受 F1 修复
- [x] **F3 set_text**（输入框写文字） ✅ 已修（dispatchShellViaTask 加空 ifFlow，paste fallback 真能跑了）
- [x] **F4 scroll / swipe**（节点上滑动） ⚠️ 共享 F1 管道（task 树 OK），但 target 兜底 className 不全（待 4.4 修）
- [x] **F5 launch_app**（启动指定 App） ✅ 有意不走 task 管道（绕开 inspector "假成功" bug），AI agent 自己实现 + 启动验证
- [x] **F6 global_back**（系统返回键） ✅ 已修真因（task 树缺空 ifFlow → doFlow 永远 skip → pressBack 没跑；撤销 shell hack）
- [x] **F7 global_home**（系统 Home 键） ✅ 同 F6
- [x] **F8 wait**（等待秒数） ✅ 不走 task pipeline，主进程 coroutine delay
- [x] **F9 done / give_up**（终止 session，no-op） ✅ Session 层处理终止

每条按统一模板核查：

1. **Inspector 真链路**（用户手挑节点 → 保存 task → 执行）：
   - 入口：用户点哪里触发？
   - 任务里存了什么 applet 树？字段级 dump
   - 执行时 applet 怎么 apply？最终调什么系统 API？
2. **AI agent 我的链路**：
   - AI 输出 JSON 长啥样
   - `AiAgentExecutor.execute(action)` 走哪条 case
   - 派给谁执行（执行端进程 / 主进程 / shell）
   - 最终 task 树字段级 dump（如果走 task pipeline）
3. **字段对照表**：inspector 那边的 applet 字段 / 引用 / referent / criteria → AI 链路有没有逐项对应
4. **核查结论**：✅ 完全一致 / ⚠️ 有差异 / ❌ 错
5. **修法**：差异 / 错的部分对应改什么文件 + 验证方式

---

## F1. click（点击节点）

> 状态：核查中（5/11）
> 目标：彻底验证 AI agent 的 click 跟 inspector 「自动点击」执行结果**字段级一致**。

### 1.1 Inspector 真链路

**入口**：浮窗 inspector 选中节点 → 「自动点击」按钮 → `AppletSelectorViewModel.acceptAppletsFromAutoClick(applets)`

**保存的 applet 树**（来源：`AppletSelectorViewModel.kt:126-153` + `NodeToActionAssembler.wrapAsContainsUiObject`）：

```
RootFlow
├── isCertainApp(packageName = <当前 App 包名>)        ← 包名限定
│   └── reference[0] = "current_top_app"
├── activityCollection(activity = <当前 Activity 短类名>)  ← 可选 Activity 限定
│   └── reference[0] = "current_top_app"
└── containsUiObject (默认 REL_AND)
    ├── 完整 11+ 字段 criteria（由 NodeCriteriaExtractor.extract 抽出，详见下表）
    ├── reference[0] = "current_window"               ← 拿当前焦点窗口 root
    └── referent[0] = "matched_ui_object"             ← 命中节点暴露给后续 action
└── click action（UiObjectActionRegistry.click.yield()）
    └── reference[0] = "matched_ui_object"            ← 拿命中节点
```

**criteria 完整字段清单**（来源：`NodeCriteriaExtractor.kt:39-96`）：

| 字段 | 来自节点哪个属性 | 默认是否勾选 |
|---|---|---|
| isType(className) | node.className | ✅ |
| withId(viewIdResourceName) | node.viewIdResourceName | ✅ |
| textEquals(text) | node.text | ✅ |
| contentDesc(contentDescription) | node.contentDescription | ✅ |
| isClickable | node.isClickable=true | ✅ |
| isLongClickable | node.isLongClickable=true | ✅ |
| isEnabled (反向) | node.isEnabled=false | ✅ |
| isCheckable | node.isCheckable=true | ✅ |
| isChecked | node.isChecked / isCheckable | ✅ |
| isEditable | node.isEditable=true | ✅ |
| isSelected (反向) | node.isSelected=false | ❌ 默认不勾 |
| isScrollable (反向) | node.isScrollable=false | ❌ 默认不勾 |
| childCount(node.childCount, node.childCount) | node.childCount | ❌ 默认不勾 |

**执行时**（来源：`UiObjectActionRegistry.kt:111-125`）：

```kotlin
val click = simpleUiObjectActionOption(R.string.format_perform_click) {
    it.ensureRefresh()
    if (it.isClickable) {
        it.performAction(AccessibilityNodeInfo.ACTION_CLICK)              // ← a11y 标准点击
    } else {
        if (isPrivilegedProcess) {
            uiDevice.wrapUiObject(it).click()                              // ← Shizuku 模式：InputManager 注入
        } else {
            uiDevice.wrapUiObject(it).click(5)                             // ← a11y 模式：StrokeDescription
        }
        true
    }
}
```

**最终系统 API 调用**：
- 节点 isClickable=true → `node.performAction(ACTION_CLICK)`
- 节点 isClickable=false（自绘子节点）→ `uiAutomation.injectInputEvent(MotionEvent.ACTION_DOWN/UP)` 物理触摸事件

### 1.2 AI agent 我的链路

**AI 输出**：
```json
{ "type": "click", "target": { "viewId": "...", "textEquals": "...", ... } }
```

**`AiAgentExecutor.execute(Click)`**（来源：`AiAgentExecutor.kt:77-80`）：
```kotlin
is AiAgentAction.Click -> dispatchAgentActionByTarget(
    AiAgentTaskAssembler.ACTION_CLICK, action.target, null,
    actionLabel = "click"
)
```

→ 调 `currentService.executeAgentActionByTarget(actionType=1, targetJson, "", callback)` 跨进程到执行端

**执行端进程 `AgentActionDispatcher.dispatch`**（来源：`AgentActionDispatcher.kt:55-100`）：
1. parseTarget JSON → AiUiTarget
2. captureRoot → `uiAutomation.rootInActiveWindow`
3. **findRealNode**：BFS root 子树，按 target 弱字段（viewId/text/desc/className）匹配，返回真节点
4. **preCheckActionSemantics**：click 不强制要求 isClickable，pass
5. `AiAgentTaskAssembler.buildTaskFromRealNode(node, ACTION_CLICK)` 组 task

**`buildTaskFromRealNode` 组的 applet 树**（来源：`AiAgentTaskAssembler.kt:87-148`）：

```
RootFlow
├── preloadFlow                                       ← 提供 current_window referent
│   └── referent[3] = REFERENT_CURRENT_WINDOW (字面量 "_ai_agent_current_window")
└── containsUiObject (强制 REL_ANYWAY)
    ├── 完整 criteria（NodeCriteriaExtractor.extract，去掉 defaultUncheckedIndices）
    ├── reference[0] = REFERENT_CURRENT_WINDOW       ← 拿 root
    └── referent[0] = REFERENT_MATCHED_UI_OBJECT     ← 暴露命中节点
└── click action（UiObjectActionRegistry.click.yield()）
    └── reference[0] = REFERENT_MATCHED_UI_OBJECT
```

执行时 → 同样走 `UiObjectActionRegistry.click` → 同样 `node.performAction(ACTION_CLICK)` 或 `injectInputEvent`。

### 1.3 字段对照表（重写——之前 5 分钟糊弄版被用户当场抓，这版逐字段对源码确认）

| # | 维度 | Inspector | AI agent | 状态 |
|---|---|---|---|---|
| 1 | **包名限定** isCertainApp | **首次加节点时**才加；后续节点不再加 | **从不**加 | ⚠️ **真差异**——inspector 任务跨 App 自动失效；agent 不会，靠 session 层 outOfScope 检查兜底 |
| 2 | **Activity 限定** activityCollection | 首次加且 Activity 已知时加 | **从不**加 | ⚠️ 同上 |
| 3 | **containsUiObject relation** | REL_AND（默认） | **强制 REL_ANYWAY** | ⚠️ 有意差异——绕开 preloadFlow 空子节点的 relation gate 陷阱 |
| 4 | **reference / referent 名字** | `R.string.current_window.str` / `R.string.matched_ui_object.str` | 字面量 `_ai_agent_current_window` / `_ai_agent_matched_ui_object` | ✅ 等价（且必须不同——service 进程没 `app.getString` 不能调 .str） |
| 5 | **criteria 字段集** | `NodeCriteriaExtractor.extract` | 同款 | ✅ 共享代码 |
| 6 | **criteria 用户偏好** | inspector UI 让用户**改勾**任意条 | 写死用**默认勾选全集**（filter 掉 defaultUncheckedIndices） | ⚠️ **真差异**——见下面 1.4 潜在问题 |
| 7 | **click action** | `UiObjectActionRegistry.click.yield()` | 同款 | ✅ |
| 8 | **执行系统 API** | `performAction(ACTION_CLICK)` 或 `wrapUiObject.click()` | 同款（不修改 UiObjectActionRegistry） | ✅ |

### 1.4 核查结论（重写——不再说"完全一致"）

**⚠️ 3 处真差异 + 2 个潜在问题 + 1 个真 bug（5/11 19:50 抓到）**：

#### P3-P5. 5/11 小红书测试抓到的 3 个深层 bug（**纠正之前的错诊断和错图**）

**现场（修正）**：小红书搜索结果页，**屏幕实际只有 3 张笔记卡片**（不是我之前编的 6 张），AI 想点的就是**左上第 1 张**「🎂6 寸戚风」。

实际 snapshot 关键节点（5/11 19:50 step=7 prompt 原文）：
```
#3 [FrameLayout] [CL] viewId=...obfuscated  bounds=(17,640,600,1766)    ← 左上 卡片1（🎂蛋糕，AI 想点的）
  └─ #4 [TextView] [-] text="🎂6寸戚风超详细步骤..." bounds=(45,1452,572,1606)
#5 [FrameLayout] [CL] viewId=...obfuscated  bounds=(616,640,1199,1768)  ← 右上 卡片2
  └─ #6 [TextView] [-] text="咔咔一顿搅..."
#7 [FrameLayout] [CL] viewId=...obfuscated  bounds=(17,1783,600,2640)   ← 左下 卡片3
```

注意 viewId 是**所有节点共享的同一个 obfuscated id**，连 RecyclerView/Button/View 都是同 id，全屏 30+ 个节点共享。

#### P3. snapshot 节点编号 #N 跟 matchIndex 完全不是一回事，AI 误用（**真 bug 之 1**）

**Step 7 现场**：AI 在 reflection 写「目标节点#4 的父容器#3 有[CL]标记，应点击父容器 FrameLayout」 → AI 想点的是 **#3** FrameLayout（卡片本身）。

AI 输出：
```json
{ "type": "click", "target": { "viewId": "...obfuscated", "matchIndex": 3 } }
```

**AI 把 snapshot 里的 `#3` 编号当成 `matchIndex=3` 了**——这两个数字含义完全不同：
- `#N` = AI 看到的 prompt 节点列表里的第 N 行（仅展示用）
- `matchIndex` = "在所有满足 target 字段筛选条件的节点中取第几个"

我之前从来**没在 prompt 里说清楚这两者的区别**，AI 自然就误用了。

**修法**：prompt 加一段说明：「`#N` 是 snapshot 列表展示用的行号，跟 `matchIndex` 没关系。`matchIndex` 是「按你的 target 字段筛选完之后取第几个」。如果你想点 snapshot 里 #3 那一行，应该想办法让 target 字段唯一锁定它（用 text/desc + className 联合），别用 matchIndex。」

#### P4. dispatcher.findRealNode 在共享 viewId 海里 BFS 顺序无法预测（**真 bug 之 2**）

即使 AI 写对了 matchIndex，**屏幕上 30+ 个节点共享同一个 obfuscated viewId**——`AgentActionDispatcher.collectMatches` BFS 取所有匹配节点，按子树遍历顺序排序：
- BFS 取到的"第 4 个" 跟 AI 看到的 snapshot 顺序**没法保证一致**——AiNodeTreeCompactor 已经做过节点压缩 + 屏幕外节点丢弃，AI 看到的节点编号跟执行端 root 上的 BFS 顺序**结构不一样**。

step 7 dispatcher 报命中 RecyclerView，就是这个问题——`viewId only` 匹配范围太宽（30+ 节点），matchIndex=3 在乱序里取的是 RecyclerView 不是 AI 想要的笔记 FrameLayout。

**根因 = AI target 字段太宽**：viewId 不是唯一字段，靠 matchIndex 兜底是脆弱的。

**修法**：跟 P3 同源——prompt 强制要求 AI 选 target 时**必须用语义字段唯一锁定**（text/desc + className 联合），不允许只给共享 viewId + matchIndex。

#### P5. task 二次匹配 textEquals 用完整长 text 含 emoji 容易丢节点（**真 bug 之 3**）

**Step 6 现场**：AI 用文本匹配（**做对了**）：
```json
{ "type": "click", "target": { "textContains": "6寸戚风超详细步骤" } }
```

- dispatcher.findRealNode 用 textContains 匹中 X = #4 TextView ✅
- 红框画在 bounds=(45,1452,572,1606) ← 文本 label 位置（正确）
- 但 task 跑失败，dispatcher 报：「节点 命中了但 perform 没生效，可能 RN 自绘控件不响应 a11y action」

**这个错误诊断 message 是我之前写的死消息**——实际 task isSuccessful=false 不一定是 perform 失败，**更可能是 task 二次匹配根本没找到节点**：

`AiAgentTaskAssembler.buildTaskFromRealNode(#4)` 抽 criteria：
- viewId = obfuscated（共享）
- textEquals = 完整长 text **含 🎂 emoji**（150+ 字符）
- className = TextView

task 跑到执行端 `containsUiObject.findFirst` 用 `viewId AND textEquals AND className` 匹中：
- viewId 全屏共享，单字段匹中 30+ 节点
- textEquals 全字匹配——如果两次抓取间小红书 refresh 了节点 text（比如截断、加省略号、emoji 编码归一化），完整长 text 就匹不中了
- className=TextView 也共享

任意一个字段微小变化 → containsUiObject 找不到节点 → task isSuccessful=false → click action 被 relation gate 跳过 → callback false。

**根因**：
1. AiAgentTaskAssembler 把 X 的完整长 text 直接用作 textEquals，遇到瞬时 text 变化就匹不中
2. 我代码里 dispatcher.dispatch 的 wrapped callback 错把 task 失败一律报成"命中了但 perform 没生效"，**误导我自己好多天**（一直以为是 RN/OEM 不响应，其实是二次匹配丢节点）

#### P3+P4+P5 修法

3 个 bug 互相纠缠，但**根因都是同一句话**：「task pipeline 走"找节点 → 包 task → 执行端再找一次"的双匹配链路，第二次找的稳定性远远不如第一次」。

| 方案 | 内容 | 优 | 缺 |
|---|---|---|---|
| **A (推荐，2026-05-13 决策)** | 跳过 task 二次匹配，dispatcher 用 inspector 的 `UiObjectActionRegistry.click` 同款 fallback 逻辑直接对 X 调 `performAction` / `wrapUiObject.click()` | 最 KISS，3 个 bug 一次根治；与"agent 独立运行 / 跑完即丢"定位一致 | 不走 task pipeline——AI agent 只能**当场**执行 |
| ~~A.1~~ | A + 同时把执行步骤生成 task 草稿写进 step record → 用户点"保存为任务"时合并草稿弹给用户在 task editor 里手工调整 | 实时 work + 草稿可保存 | **已废弃**：agent 改为独立运行、不再生成草稿（见 `aidoc/13-todo.md` 2.1） |
| ~~D~~ | 新增 `DynamicUiObjectFinder` applet，task 里存"基础条件 + 选择策略"，每次重跑时按策略动态定位 | 实时 work + 真持久化 + 用户可调策略 | **已弃置**：同上，agent 不再追求重跑能力 |

执行心得通过"经验本"沉淀让 AI 越来越聪明（见 `aidoc/20-experience-book-design.md`），不再依赖"保存为任务"路径。

**修复诊断 message**：不论选哪个方案，`AgentActionDispatcher.dispatch` 的 wrapped callback 那段 message 必须改——不能再把 task 失败死消息写成"命中了但 perform 没生效，可能 RN 不响应"。改成更准的：「task 二次匹配未命中节点（target 字段在执行端 root 上找不到匹配），可能是节点 text 变化或字段范围太宽」。

#### 其他 4 项不变（A/B/C 真差异 + P1/P2 已修）

#### 真差异（功能上工作正常，但理解链路时要知道）
- A. ❌ AI agent 没加 `isCertainApp` / `activityCollection` 包名 + Activity 限定 → 靠 session 层 outOfScope 检查兜底
- B. ⚠️ `containsUiObject` 用 REL_ANYWAY 而非 REL_AND → 绕开 preloadFlow 空子节点导致的 relation gate 跳过陷阱
- C. ⚠️ referent 名字用字面量而非 i18n 字符串 → service 进程不能调 `app.getString`

#### 潜在问题（需要测试验证才能确认是否真出 bug）
**P1. AI agent 用「默认勾选全集」生成 criteria 可能比 inspector 用户实操更严苛**
- 例：节点 `isLongClickable=true` 时 criteria 强制 AND `isLongClickable` 条件
- inspector 用户挑节点时**经常会取消** `isLongClickable` / `isCheckable` / `isChecked` 这些勾选才能可靠命中
- AI agent 没法做这种"用户偏好取消"，criteria 比 inspector 实操更严

**P2. `NodeCriteriaExtractor` 的 `isChecked` 陷阱**（源码 `:69`）
```kotlin
if (node.isChecked || node.isCheckable) {
    criteria.add(registry.isChecked.yield())   // ← 注意：yield() 默认是 isChecked=true
}
```
- 节点 `isCheckable=true && isChecked=false` 也会加 `isChecked.yield()` criteria
- 这条要求"被点节点必须 isChecked=true"——但实际节点 isChecked=false → **永远匹不中**
- inspector 用户挑复选框节点时**必须手动取消**这条勾选；AI agent 做不到

### 1.5 已知 click 失败 case 重新归因

| 现象 | 之前归因（可能错） | 重新归因（更准） |
|---|---|---|
| `click(text=综合)` × 4 ok=false | "AI 选错节点" | **可能真因是 P1**——AI 给 `cls=TextView`，extract 出来的 criteria 含 `isLongClickable=true`（如果节点是 LongClickable）但 task 执行时同节点状态可能变了 → matchUiObject 不再匹中 |
| `click(desc=返回)` ok=false 节点已消失 | "global_back 没生效" | 是真的——见 F6 |

### 1.6 修法（**未实施**——等用户拍板再动手，避免又凭推理瞎写）

针对 P1 + P2，候选方案：

**方案 A**：在 `AiAgentTaskAssembler.buildTaskFromRealNode` 里**进一步剥掉过严 criteria**——只保留语义字段（isType / withId / textEquals / contentDesc），剥掉所有状态字段（isClickable / isLongClickable / isCheckable / isChecked / isEditable / isEnabled）。

理由：findRealNode 已经按弱字段在 root 上匹中过节点了；到 task 执行时再次定位（containsUiObject）只需要"语义足够区分该节点"，状态字段反而是噪音。

**方案 B**：保持现状，先验证 P1/P2 是否真在测试现场出现——如果跑日志里没看到 criteria 太严导致 findUiObject 失败的明确证据，就不改。

**我推荐 B → 验证后决定 A**。理由：方案 A 改了核心逻辑，要承担"剥掉的字段是否原本必要"的风险；先验证再动手符合你立的硬约束。

### 1.7 状态

- ✅ 字段对照已诚实写完
- ⏳ 修法未实施
- ⏳ 验证未做：需要重测看 task 执行时 `containsUiObject.findFirst` 是不是真的因为状态字段过严而失败

---

## F6 + F7. global_back / global_home（系统返回 / Home 键）

> 状态：**✅ 已修真因（5/11 18:30 重大诊断纠正）**
> 之前 3 次诊断全错——「silent-fail 延迟」「OEM 限制 a11y connection」「shell ok 不等于真触发」全是凭推理瞎猜。
> 用户硬约束「不要乱猜 OEM hack，原代码标准方案就够」促使我重审 task 引擎源码，**真因是我的 task 树构造少了空 ifFlow**，跟 OEM / a11y / shell 完全无关。本节诚实记录诊断历史 + 真因。

### 6.1 Inspector 真链路（含 task editor 手工添加）

**Inspector 没有专门的「按返回键 / Home 键」直选入口**——用户在 task editor 里手工添加 `GlobalActionRegistry.pressBack` / `pressHome` applet。

**保存的 applet**（源码 `GlobalActionRegistry.kt:30-64`）：
```kotlin
private fun globalActionOption(title: Int, action: Int): AppletOption {
    return appletOption(title) {
        emptyArgAction {
            uiAutomation.performGlobalAction(action)   // ← 注意：返回 boolean，被 emptyArgAction 当 isSuccessful
        }
    }
}
val pressBack = globalActionOption(R.string.press_back, AccessibilityService.GLOBAL_ACTION_BACK)
val pressHome = globalActionOption(R.string.press_home, AccessibilityService.GLOBAL_ACTION_HOME)
```

`emptyArgAction` 实现（`ActionCreator.kt:32`）：
```kotlin
fun emptyArgAction(block: suspend (TaskRuntime) -> Boolean): Action {
    return createAction { _, runtime -> block(runtime) }
}
```
→ task isSuccessful = `uiAutomation.performGlobalAction(action)` 的 boolean 返回值。

**`uiAutomation.performGlobalAction()` 真实行为**（Android frameworks/base/UiAutomation.java:682）：
```java
public final boolean performGlobalAction(int action) {
    final IAccessibilityServiceConnection connection = ...;
    if (connection != null) {
        try { return connection.performGlobalAction(action); }
        catch (RemoteException re) { Log.w(LOG_TAG, "Error", re); }
    }
    return false;
}
```
→ 本质是通过 `IAccessibilityServiceConnection`（a11y system service）执行。

**两种模式下 `uiAutomation` 来源**：

| 模式 | uiAutomation 来源 | 是不是真 a11y service | performGlobalAction 应该 work？ |
|---|---|---|---|
| **A11y 模式** | `A11yAutomatorService.uiAutomation = uiAutomationHidden.casted()`，`uiAutomationHidden` 是 a11y framework 给的 | ✅ 是真 a11y service | ✅ 应该 work |
| **Shizuku 模式** | `ShizukuAutomatorService.uiAutomation = uiAutomationHidden.casted()`，`uiAutomationHidden = UiAutomationHidden(looper, UiAutomationConnection())` | ⚠️ **是 IAccessibilityServiceConnection 但不是用户在系统设置里启用的 a11y service**——通过 UiAutomationConnection 拿的特权 connection | **不一定**——OEM 系统可能对此做限制 |

### 6.2 真因（重审 task 引擎源码后定位）

**关键源码 1**：`Do.kt:16`
```kotlin
override fun shouldBeSkipped(runtime: TaskRuntime): Boolean {
    return runtime.ifSuccessful != true   // ← 看 ifSuccessful，跟 Do.relation 完全无关
}
```

**关键源码 2**：`TaskRuntime.kt:108-113`
```kotlin
var isSuccessful = true       // ← 初值 true（不是我之前误判的 false）
var ifSuccessful: Boolean? = null   // ← 初值 null（没有 If 跑过永远是 null）
```

**关键源码 3**：`If.kt:26-29`
```kotlin
override fun onPostApply(runtime: TaskRuntime) {
    super.onPostApply(runtime)
    runtime.ifSuccessful = runtime.isSuccessful   // ← If 跑完才把 isSuccessful 写进 ifSuccessful
}
```

**inspector 用户的 task（`generateDefaultFlow` else 分支）真实跑通流程**：
```
RootFlow
├── preloadFlow (空)              → emptyResult(isSuccessful=true)
├── ifFlow (空)                   → emptyResult(true) → If.onPostApply: ifSuccessful=true
└── doFlow                        → shouldBeSkipped: ifSuccessful != true → false → 跑！
    └── pressBack/pressHome      → uiAutomation.performGlobalAction(BACK/HOME) → 系统真触发
```

**我的 `AiActionToTask.translate(GlobalBack)` 之前的 task 树**：
```
RootFlow
├── preloadFlow (空)              → isSuccessful=true
└── doFlow (REL_ANYWAY 我误以为能救)  → shouldBeSkipped: ifSuccessful != true → null != true → **true 跳过！**
    └── pressBack                 → 永远没跑！
```

**真因**：我**少加了空 ifFlow**——ifSuccessful 永远是 null → doFlow 永远被 skip → pressBack 永远没跑 → task callback 因为 doFlow 跳过被算"完成"返回 ok=true → silent-fail 检测发现屏幕没变。

跟 OEM / a11y framework / Shizuku UiAutomation 限制**完全无关**。inspector 用户的 task 在同一台 vivo 机器上能 work 就是铁证——`uiAutomation.performGlobalAction(BACK)` 标准方案在 vivo 上没问题，是**我自己组的 task 树没让 pressBack 跑起来**。

### 6.3 修法（5/11 18:25 提交）

**只改两处**：

`AiActionToTask.translate`（加 1 行 + 撤销错的 REL_ANYWAY 注释）：
```kotlin
// 加空 ifFlow，让 ifSuccessful=true，doFlow 才能跑
val ifFlow = factory.flowRegistry.ifFlow.yield()
root.add(ifFlow)
val doFlow = factory.flowRegistry.doFlow.yield() as Do
root.add(doFlow)   // ← 不再设 REL_ANYWAY，因为 Do.shouldBeSkipped 跟 relation 无关
```

`AiAgentExecutor.execute(GlobalBack/Home)`（撤销 shell hack，回到标准）：
```kotlin
is AiAgentAction.GlobalBack -> dispatchViaTask(action)
is AiAgentAction.GlobalHome -> dispatchViaTask(action)
```

**撤销的 hack**：
- ❌ 删 `dispatchGlobalKey` 函数（shell input keyevent + 二级 snapshot 验证）
- ❌ 删 `KEYCODE_BACK/HOME` 常量
- ❌ 删 prompt 准则 6「global_back/home 有 1-2s 延迟」
- 保留 prompt 准则 7「global_home 之后必须 launch_app」（这条本身没错）

### 6.4 字段对照表（修复后）

| 维度 | Inspector | AI agent | 状态 |
|---|---|---|---|
| Applet 类型 | `GlobalActionRegistry.pressBack/pressHome` | 同款 | ✅ |
| 系统 API | `uiAutomation.performGlobalAction(GLOBAL_ACTION_BACK/HOME)` | 同款 | ✅ |
| Task 树结构 | `RootFlow → preloadFlow → ifFlow(空) → doFlow → pressBack` | **同款（5/11 18:25 修复后）** | ✅ |
| 任何 hack | 无 | 无（撤销 shell input keyevent） | ✅ |

### 6.5 已知 case 重新归因（最终版）

| 现象 | 第 1 次诊断 | 第 2 次诊断 | 第 3 次诊断 | **真因** |
|---|---|---|---|---|
| `global_back/home` 报 OK 但屏幕没变 | silent-fail 延迟 | Shizuku uiAutomation 不是 a11y service | OEM 限制 a11y connection | **AiActionToTask 缺空 ifFlow → doFlow 永远被 skip → pressBack 没跑** |

### 6.6 教训

**3 次错诊断的共同病因**：凭日志现象 + 推理就下结论，不去读 task 引擎源码确认 task 树是怎么真跑的。

每次都是"看到 task 报 OK 屏幕没变 → 推理 → 找一个外部因素背锅（延迟 / OEM / shell）→ 加 hack 兜底"。
**正确流程**应该是："task 报 OK 但屏幕没变 → 检查我的 applet 是否真跑了（去看 shouldBeSkipped 的判断、ifSuccessful 的赋值时机）→ 发现没跑就修 task 树构造"。

5/11 18:25 后只要看到 inspector 同款功能能 work 我的 AI agent 不能 work，**第一时间去读 inspector 的 task 树构造**，逐字段对照——不再凭猜测下结论。

### 6.7 状态

- ✅ 真因定位（task 树缺空 ifFlow）
- ✅ 修法（加空 ifFlow + 撤销 shell hack + prompt 准则 6）
- ⏳ 装机验证（设备已就绪，等用户重测确认 global_back/home 真跑通）

---

## F2. long_click（长按节点）

> 状态：✅ Audit 完成（5/11）—— **跟 F1 click 共享同一条管道**，F1 修过的 P1+P2 自动享受。

### 2.1 Inspector 真链路

跟 F1 完全一致，区别仅在 action applet：
- click action → `UiObjectActionRegistry.click.yield()`
- long_click action → `UiObjectActionRegistry.longClick.yield()`

执行时（`UiObjectActionRegistry.kt:128-136`）：
```kotlin
val longClick = simpleUiObjectActionOption(R.string.format_perform_long_click) {
    it.ensureRefresh()
    if (it.isLongClickable) {
        it.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)   // a11y 标准长按
    } else {
        uiDevice.wrapUiObject(it).longClick()                       // 坐标长按 fallback
        true
    }
}
```

### 2.2 AI agent 我的链路

`AiAgentExecutor.execute(LongClick)` (`AiAgentExecutor.kt:81-84`)：
```kotlin
is AiAgentAction.LongClick -> dispatchAgentActionByTarget(
    AiAgentTaskAssembler.ACTION_LONG_CLICK, action.target, null,
    actionLabel = "long_click"
)
```

→ 同款 RPC → `AiAgentTaskAssembler.buildTaskFromRealNode(node, ACTION_LONG_CLICK)` (`AiAgentTaskAssembler.kt:148-149`)：
```kotlin
ACTION_LONG_CLICK -> actionRegistry.longClick.yield()
```

### 2.3 字段对照表

跟 F1 完全相同（task 树结构 / criteria filter / RPC 路径），唯一差异：

| 维度 | Inspector | AI agent | 状态 |
|---|---|---|---|
| Action applet | `UiObjectActionRegistry.longClick.yield()` | 同款 | ✅ |
| 节点 isLongClickable=true | `performAction(ACTION_LONG_CLICK)` | 同款 | ✅ |
| 节点 isLongClickable=false | `uiDevice.wrapUiObject.longClick()` 坐标长按 | 同款 | ✅ |

### 2.4 核查结论

**✅ 共享 F1 管道，自动享受 F1 修复（剥状态字段 criteria / isChecked 陷阱修法）**。

无独立问题。

---

## F3. set_text（输入框写文字）

> 状态：**⚠️ 抓到一个真 bug + 1 个 reasonable 复杂性**（5/11）
> Audit 中又抓到 dispatchShellViaTask 缺空 ifFlow 的同款 doFlow 跳过 bug——
> 意味着 setText paste fallback **从来没真生效过**！立刻已修。

### 3.1 Inspector 真链路

跟 F1 完全一致，区别仅在 action applet：
- click action → `UiObjectActionRegistry.click.yield()`
- set_text action → `UiObjectActionRegistry.setText.yield(1 to text)`（slot 1 = 文本字符串）

执行时（`UiObjectActionRegistry.kt:172-186`）：
```kotlin
val setText = appletOption(R.string.format_perform_input_text) {
    doubleArgsAction<AccessibilityNodeInfo, String> { node, text, _ ->
        requireNotNull(node)
        node.ensureRefresh()
        if (!node.isEditable) {
            false   // 不可编辑直接 fail
        } else {
            uiDevice.wrapUiObject(node).setText(text)
            // ↑ CoroutineUiObject.setText 调 node.performAction(ACTION_SET_TEXT, args)
            //    返回 boolean（之前曾有 bug 永远返 Unit；已修）
        }
    }
}
```

### 3.2 AI agent 链路（3 层 fallback）

`AiAgentExecutor.execute(SetText)` → `setTextWithFallback(action)`：

**第 1 层：标准 a11y SET_TEXT**（共享 F1 RPC 路径）
```kotlin
val first = dispatchAgentActionByTarget(
    AiAgentTaskAssembler.ACTION_SET_TEXT, action.target, action.text, ...
)
```
→ RPC 到 service 端 → AiAgentTaskAssembler.buildTaskFromRealNode(node, ACTION_SET_TEXT)
→ task 树跟 click 同款，action = `actionRegistry.setText.yield(1 to text)`
→ 跑 `UiObjectActionRegistry.setText` → `node.performAction(ACTION_SET_TEXT, ...)`

**第 2 层：verify 检测 silent-fail**
```kotlin
if (first.ok) {
    delay(250)
    if (verifyTextWritten(action)) return ok
    // 否则 fall through 到第 3 层
}
```

`verifyTextWritten` 逻辑：抓新 snapshot，找 target 命中的节点（用 viewId/textEquals/className 匹配，不中退化到第一个 editable 节点），看 `node.text.contains(needle)`。这能识别 RN/Compose 自绘控件的 a11y SET_TEXT 假成功。

**第 3 层：剪贴板 + KEYCODE_PASTE**
```kotlin
writeClipboard(action.text)         // ClipboardManager 主线程写
val focusClick = dispatchAgentActionByTarget(ACTION_CLICK, target, ...)
delay(200)
val pasteResult = dispatchShellViaTask("input keyevent 279")  // KEYCODE_PASTE
```

### 3.3 抓到的真 bug：dispatchShellViaTask 缺空 ifFlow

之前的 task 树：
```
RootFlow
├── preloadFlow (空)              → isSuccessful=true
└── doFlow (REL_ANYWAY 没用)      → shouldBeSkipped: ifSuccessful != true → true 跳过！
    └── shellRegistry.executeShellCmd  → 永远没跑
```

跟 F6 GlobalBack 同款 bug——`Do.shouldBeSkipped` 看 `runtime.ifSuccessful != true`，没 If 跑过 ifSuccessful=null → doFlow 永远被 skip → shellCmd **从来没真跑过**。

但 task 跑空也返回 ok=true（doFlow skip 不算失败）→ callback 报 OK → setTextWithFallback 以为 paste 成功了。

**意味着 setText paste fallback 一直是假成功**。a11y SET_TEXT 失败时 verify 检测出 silent fail 后，paste fallback 跑了个寂寞，AI 看 history 显示 OK 以为 EditText 已写入，下一步去点搜索按钮——搜索的还是空字符串。

**已修**（5/11 18:30）：dispatchShellViaTask 加空 ifFlow，跟 inspector default flow 一致。

### 3.4 字段对照表（修复后）

| 维度 | Inspector | AI agent | 状态 |
|---|---|---|---|
| Action applet | `setText.yield(1 to text)` | 同款 | ✅ |
| Pre-check isEditable=true | UiObjectActionRegistry.setText 内部检查 | 同款 + service 端 preCheckActionSemantics 也检查（aidoc/18 §3.D） | ✅ 双重保险 |
| 系统 API | `node.performAction(ACTION_SET_TEXT, args)` | 同款（第 1 层） | ✅ |
| Silent-fail verify | 无（inspector task 一次性，不需要 verify） | 第 2 层 verifyTextWritten | ✅ AI agent 增量功能 |
| Paste fallback | 无 | 第 3 层 shell `input keyevent 279` | ✅ AI agent 增量功能 |
| Task 树（任何子层） | `RootFlow → preloadFlow → ifFlow → doFlow → applet` | **同款（修后）** | ✅ |

### 3.5 reasonable 复杂性

setText 的 3 层 fallback 是**合理设计**——RN/Compose 自绘控件确实有 a11y SET_TEXT silent fail 的真问题，paste fallback 是必要 backup。但层数多就要保证每层都真 work，bug 累积一次就让整条 fallback 链失效（之前 dispatchShellViaTask 那个）。

### 3.6 核查结论

- ✅ 第 1 层 a11y SET_TEXT 跟 F1 共享管道，自动享受 F1 修复
- ✅ verifyTextWritten 逻辑正确（target 模糊匹配 + needle.contains）
- ✅ 第 3 层 paste fallback 修后真能跑了（dispatchShellViaTask 加了空 ifFlow）
- ⏳ 装机验证：需要在 deepseek 等 RN App 实测 paste fallback 是否真写入

---

## F4. scroll / swipe（节点上滑动）

> 状态：⚠️ Audit 完成（5/11）—— **共享 F1 管道，无 doFlow bug；但有 1 个 reasonable 隐患（target 兜底 className 不全）**

### 4.1 Inspector 真链路

跟 F1 完全一致，区别仅在 action applet：
- click action → `UiObjectActionRegistry.click.yield()`
- swipe action → `UiObjectActionRegistry.swipe.yieldWithFirstValue(SwipeMetrics.compose())`

执行时（`UiObjectActionRegistry.kt:155-169`）：
```kotlin
val swipe = uiObjectActionOption<Long>(R.string.format_swipe_ui_object) { node, v ->
    check(v != null)
    node.ensureRefresh()
    val swipe = SwipeMetrics.parse(v)
    uiDevice.wrapUiObject(node).swipe(swipe.direction, swipe.percent, swipe.speed)
    true
}
```

`SwipeMetrics` 把 (direction, percent, speed) 三元组打包成 Long（BitwiseValueComposer），跨 task 序列化。

### 4.2 AI agent 链路

`AiAgentExecutor.execute(Scroll)` → `dispatchScroll(action)` (`AiAgentExecutor.kt:122-135`)：
```kotlin
val target = action.target ?: AiUiTarget(className = "ScrollView")  // ← 兜底
val extra = "${action.direction.lowercase()}:0.5:1000"  // direction:percent:speed
return dispatchAgentActionByTarget(ACTION_SWIPE, target, extra, ...)
```

→ RPC → `AiAgentTaskAssembler.buildTaskFromRealNode(node, ACTION_SWIPE)` (`:151-160`)：
```kotlin
ACTION_SWIPE -> {
    val (dir, pct, spd) = parseSwipeExtra(extraText)
    val swipeLong = SwipeMetrics(dir, pct, spd).compose()
    actionRegistry.swipe.yieldWithFirstValue(swipeLong)
}
```

### 4.3 字段对照表

| 维度 | Inspector | AI agent | 状态 |
|---|---|---|---|
| Action applet | `swipe.yieldWithFirstValue(Long)` | 同款 | ✅ |
| SwipeMetrics 编码 | direction(2bit) + percent + speed | 同款（用 SwipeMetrics.compose） | ✅ |
| Task 树 | `RootFlow → preloadFlow → containsUiObject → swipe` | 同款（buildTaskFromRealNode 路径） | ✅ |
| 系统 API | `uiDevice.wrapUiObject(node).swipe(...)` → InteractionController.swipe → InputManager 注入 | 同款 | ✅ |

### 4.4 reasonable 隐患

**target 兜底 className 不全**：AI 没指定 target 时 fallback 用 `className="ScrollView"`——但实际 App 里可滚动节点常见的有：
- ScrollView / NestedScrollView ✅
- RecyclerView ❌（兜底匹不中）
- ListView ❌
- ViewPager / ViewPager2 ❌
- HorizontalScrollView ❌

如果当前 App 主要 scroll 容器是 RecyclerView（小红书 / 微信列表都是），AI 没指定 target 时 scroll 会报 ok=false。

**修法**（暂未做，等用户拍板）：
- 方案 A：扩展兜底——按顺序尝试 ScrollView / RecyclerView / NestedScrollView / ViewPager 多个 className
- 方案 B：改成 `findRealNode` 时**忽略 className**，改用 `node.isScrollable=true` 直接找——但 AiUiTarget 没有 isScrollable 字段，需要扩展 schema
- 方案 C：让 AI prompt 里说"scroll 优先指定 target 用 [S] 标记的节点 viewId，避免依赖兜底"

### 4.5 核查结论

- ✅ task 树 + 字段对照跟 inspector 一致
- ✅ 不受 F6 doFlow skip bug 影响（走 buildTaskFromRealNode，不用 doFlow）
- ⚠️ scroll 没指定 target 时兜底范围窄（4.4 隐患）

---

## F5. launch_app（启动指定 App）

> 状态：✅ Audit 完成（5/11）—— **跟 inspector 路径有意不同**（AI agent 多了启动验证），无 bug

### 5.1 Inspector 真链路

`ApplicationActionRegistry.launchApp` (`:46-57`)：
```kotlin
val launchApp = appletOption(R.string.format_launch) {
    simpleSingleArgAction<Any?> {
        requireNotNull(it)
        val pkg = if (it is String) it else (it as ComponentInfoWrapper).packageName
        ActivityManagerBridge.startComponent(
            PackageManagerBridge.getLaunchIntentFor(pkg)?.component!!
        )
        true   // ← hardcoded true，不验证启动是否真成功
    }
}
```

**inspector launchApp 是已知的"假成功" applet**——`ActivityManagerBridge.startComponent` 调用后立刻返回 true，不论 App 真启动了没。这在 inspector 用户场景下问题不大（用户跑完看屏幕就知道有没有进 App）；但 AI agent 会盲信 callback 走下一步。

### 5.2 AI agent 链路（有意不同）

`AiAgentExecutor.launchApp(pkg)` (`:401-433`)：
```kotlin
1. 主进程拿 PackageManager launchIntent + FLAG_ACTIVITY_NEW_TASK
2. app.startActivity(intent) 直接启动
3. 轮询 foreground package（间隔 200ms，最多 2500ms）：
   - 切到 pkg 了 → ok=true "已启动并切换到 $pkg"
   - 没切 → 仍 ok=true 但 message 写"已发起但前台仍是 X，请下一步 wait 再观察"
```

**有意不走 task 管道**——绕开 inspector launchApp 的"假成功"陷阱。

### 5.3 字段对照表

| 维度 | Inspector | AI agent | 状态 |
|---|---|---|---|
| 启动 API | `ActivityManagerBridge.startComponent`（特权进程） | `app.startActivity(intent)`（主进程） | ⚠️ **有意不同** |
| 验证启动结果 | ❌ 没有（hardcoded true） | ✅ 轮询前台 pkg 切换 | ⚠️ AI agent 增量功能 |
| FLAG_ACTIVITY_NEW_TASK | startComponent 内部处理 | 显式加（主进程必需） | ✅ 等价 |
| 失败兜底 | 无 | 启动失败 / 找不到 launchIntent → ok=false 带 message | ✅ AI agent 更友好 |

### 5.4 核查结论

**✅ 有意不复用 inspector 的"假成功"applet，AI agent 自己实现真实验证**。这是**用户 5/9-10 已经验证过 work** 的设计（之前在 deepseek/小红书测试中 launch 一直能跑）。

无 bug。

---

## F8. wait（等待秒数）

> 状态：✅ Audit 完成（5/11）—— **不走 task 管道**，主进程直接 delay，无 bug

### 8.1 实现

`AiAgentExecutor.kt:66-68`：
```kotlin
is AiAgentAction.Wait -> {
    delay(action.seconds * 1000L)
    AiAgentStepResult(ok = true, message = "已等待 ${action.seconds}s")
}
```

### 8.2 字段对照

| 维度 | Inspector | AI agent | 状态 |
|---|---|---|---|
| Inspector 同款 applet | `ControlActionRegistry.suspension`（task editor 里的 Wait 节点） | **不复用**——AI agent 在主进程 coroutine delay | ⚠️ 不同但合理 |

### 8.3 核查结论

**✅ 不需要 task pipeline**——wait 本质是 coroutine delay，session 在主进程跑，直接 delay 比包成 task 跑过 RPC 简单且等价。

无 bug。

---

## F9. done / give_up / unknown（终止 session 类）

> 状态：✅ Audit 完成（5/11）—— no-op，session 层处理终止

### 9.1 实现

`AiAgentExecutor.kt:100-102`：
```kotlin
is AiAgentAction.Done,
is AiAgentAction.GiveUp,
is AiAgentAction.Unknown -> AiAgentStepResult(ok = true, message = "no-op")
```

`AiAgentSession.runLoop` 在调 `execute(action)` 之前就拦了 Done/GiveUp/Unknown：
- Done → `AiAgentSessionOutcome.Completed(action.summary, ...)` 终止
- GiveUp → `AiAgentSessionOutcome.GivenUp(action.reason, ...)` 终止
- Unknown → 触发 AI 重试（aidoc/18 §3.B），重试 2 次仍失败 → `AiAgentSessionOutcome.AiError`

### 9.2 核查结论

**✅ Session 层职责清晰**——这三种 action 不是"动作"是"会话状态指令"，executor 不需要做任何事。

无 bug。

---

## §10 全 audit 总结（5/11 19:00）

### 真 bug 修复表

| Feature | 真 bug | 修法 | 状态 |
|---|---|---|---|
| F1 click | criteria 包含状态字段（isClickable/Editable/Checkable/Checked 等）让二次定位太严；isChecked 陷阱 | 加 `Result.semanticIndices`，AiAgentTaskAssembler 只用语义字段 | ✅ |
| F3 set_text | dispatchShellViaTask 缺空 ifFlow → doFlow 永远 skip → paste fallback 假成功 | 加空 ifFlow | ✅ |
| F6 global_back | AiActionToTask 缺空 ifFlow → doFlow 永远 skip → pressBack 没跑（之前误诊为 OEM 限制 → shell hack） | 加空 ifFlow + 撤销 shell hack + 撤销错的 prompt 准则 | ✅ |
| F7 global_home | 同 F6 | 同 F6 | ✅ |

### 待优化（非 bug）

- F4 scroll：AI 没指定 target 时兜底 className="ScrollView" 太窄（漏 RecyclerView/ListView/ViewPager）

### 之前所有"OEM/a11y/shell" 误诊全部撤销

7 次"a11y/OEM 限制"的诊断全是错的——真因都是我自己代码 bug。教训写在 §6.6。

### 跟 inspector 一致性总结

| Feature | task 树跟 inspector 是否一致 | 备注 |
|---|---|---|
| F1 click / F2 long_click / F3 set_text / F4 scroll | ✅ 字段级一致（共享 NodeCriteriaExtractor + UiObjectActionRegistry） | criteria 用 semanticIndices 子集；reference/referent 用字面量 |
| F5 launch_app | ⚠️ 有意不一致（绕开 inspector 假成功 bug） | AI agent 自己 startActivity + 验证 |
| F6/F7 global_back/home | ✅ 字段级一致（撤销 shell hack 后） | 加空 ifFlow 让 doFlow 真跑 |
| F8 wait | ⚠️ 不走 task pipeline | 主进程 coroutine delay |
| F9 done/giveup/unknown | N/A | 不是动作，session 层终止

