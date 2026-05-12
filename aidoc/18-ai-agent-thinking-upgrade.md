# 18 — AI Agent 思考模式根本性升级（参考 ReAct / Reflexion / Cursor agent loop）

> **触发**：5/11 多次测试发现 AI 决策有反复出现的"病火"——
> 不反思失败、不读当前快照、死循环不换思路、跨页面不重 verify。
> 一味叠 prompt 规则越叠越复杂越无效，注意力被稀释。
> 用户明确要求："**借鉴 OpenClaude / Cursor 这些成熟 agent 的思考模式做根本性升级**"。
>
> 本文档锁定 5 模块升级方案 + 严格复用清单 + 必须废弃的旧 patch 清单。
> 工程量不限，目标是质变。

## 1. 病灶分析（基于 5/11 15:47 小红书"晚上吃啥"日志）

| step | AI 输出 | 病火 |
|---|---|---|
| 6-7 | `click(text=晚上吃啥不会胖还解馋, cls=TextView)` 连续 ok=false | className 字段定太具体反而匹不中真节点 |
| 9-12 | **连续 4 次** `click(text=综合)` 全部 ok=false | AI 完全不反思失败，thought 还重复"点击综合标签" |
| 9-12 thought | 「**当前在'综合'标签页**，点击综合标签...」 | AI 知道在综合页还点综合按钮——**完全无视当前快照** |
| 13-14 | `scroll(down)` ok=false `scroll 暂未通过 task 管道实现` | 真 bug：scroll executor 没实现 |

**根本原因**：现在的 prompt 已经堆了 11 条行为准则（含 0/0.5/1.5/1.7 等专项），LLM 注意力被稀释——
你越想用 prompt 加更多规则约束 AI，AI 越倾向忽略规则。

**这是 prompt 工程的本质天花板**——不是写 prompt 的水平问题，是 in-context reasoning 的极限。

## 2. 升级目标：从「单层 ReAct + prompt 规则堆砌」改成「结构化思考 + 工具层硬拦」

成熟 agent 框架的共性：
- **结构化思考流程**（ReAct / Reflexion）：每步显式 Observation → Reflection → Action 三段
- **工具层硬约束**（Anthropic Computer Use / Cursor）：AI 选错时执行端就拦住，不浪费一次 perform
- **长期失败记忆**（Reflexion / BabyAGI）：把"我已经尝试过且失败的策略"显式喂回 AI
- **死循环检测**（LangGraph stuck detection）：连续无进展强制 forced replan，AI 必须给新方向才能继续

## 3. 升级方案 5 模块（A+B+C+D+E）

### A. Structured ReAct —— prompt schema 强制结构化输出

**现在**：AI 输出 `{action, thought, plan_status}`，thought 是事后解释，没强制读快照。

**改**：AI 必须按以下 schema 输出，否则 parser 拒收（视为 Unknown，触发重试）：
```json
{
  "observation": "<必填，3-5 句话>当前快照里我看到了什么？activityName=? 关键节点=? 上一步 action 之后 UI 有没有变化？",
  "last_action_review": "<上一步执行结果对照预期，1-2 句>",
  "reflection": "<基于 observation + last_action_review 推理出的下一步方向，2-4 句>",
  "action": { "type": "...", ... },
  "plan_status": "on_track|adjusted|off_track|unknown"
}
```

**作用**：
- AI 必须**先**写 observation 才能写 action——强制读快照
- AI 必须**先**写 last_action_review 才能写 action——强制反思
- LLM 在自然推理顺序里，先写的内容比后写的更"想清楚"，比规则塞在准则里有效

**配套删除**：
- 准则 0.5「核心心法」——已被 schema 强制 observation 替代
- 准则 7「silent-fail 二次重试」——AI 自己看 last_action_review 决定，规则减负
- 准则 8「flag 是参考」——降级成动作字段说明里的一行
- 总目标：行为准则**从 11 条精简到 5 条以内**，schema 承担行为指引

### B. Stuck Detection —— forced replan

**现在**：silent-fail 二次拉黑只覆盖完全相同 target hash；AI 改 className 字段就绕过。

**改**：session 维护 `consecutiveUnproductiveSteps`：
- 任何 ok=false / silent-fail / deadTarget 拒收都 +1
- 任何 ok=true 且屏幕签名变化 → 清零
- ≥ `STUCK_THRESHOLD=3` → executor 拒收下一个 action（不论 AI 输出什么），强制 AI 输出 rethink 类型：
  ```json
  {
    "type": "rethink",
    "stuck_summary": "<我意识到我连续 N 步无进展，原因是 ...>",
    "alternatives": ["<方向 1：...>", "<方向 2：...>", "<方向 3：...>"],
    "chosen_direction": <0/1/2>
  }
  ```
- 只有 AI 给出 rethink 才能恢复正常 action 输出

**复用**：现有 `silentFailHitsByPkgAndTarget` / `deadTargetsByPkg` 机制，扩成更宽的"无进展计数"。

### C. Long-term Memory —— failed_strategies

**现在**：history 只有原始 step 列表，AI 自己得 inference 哪条策略失败了。

**改**：session 维护 `failedStrategies: List<{description, attemptCount}>`，每次 ok=false / silent-fail 时自动累加：
```kotlin
failedStrategies += FailedStrategy(
    description = "click TextView('综合') @ GlobalSearchActivity",
    attemptCount = 4
)
```

prompt 顶部"禁选目标"段升级成更具语义的"失败策略警示":
```
⛔ 你已尝试过且明确失败的策略（绝对不要再选）：
  - 「click TextView('综合') @ GlobalSearchActivity」连续失败 4 次（屏幕签名前后未变）
  - 「click(cls=TextView) 搜索建议项」连续失败 2 次（节点匹配失败，可能真节点 className 不是 TextView）
```

### D. Tool-Level Guardrails —— execution-end 硬约束

**现在**：AI 输出错的 action 直接派出去执行，错了再说。

**改**：service 端 RPC `executeAgentActionByTarget` 进入 dispatcher 后，**先做预检查**：
- `ACTION_SET_TEXT`：findRealNode 后检查 node.isEditable=true；不满足直接 callback(false) + 详细原因「节点 X 不可编辑」
- `ACTION_CLICK`：findRealNode 完全找不到时，回传匹配尝试的「最接近节点 + 哪些字段不符」（不只是笼统的"未找到匹中"）
- 拒收的所有 action 都通过新的 callback 通道回传**完整拒收原因**到 main，写进 history 喂给 AI 反思

**复用**：service 端已经有 `findRealNode` + `NodeCriteriaExtractor`；只是把"找到 → 直接派 task"改成"找到 → 校验语义 → 派或拒"。

### E. 真 bug 修复

**E.1 scroll 实现**：
- 现状：`AiAgentExecutor.execute(Scroll) → 直接报「scroll 暂未通过 task 管道实现」`
- 修法：复用 `UiObjectActionRegistry.swipe`（已 work）—— 把 scroll 走跟 click 同款的「executeAgentActionByTarget(actionType=4, ...)」路径，service 端用 `NodeToActionAssembler` + swipe action 包成 task
- 严格按 inspector 路径，**0 行新执行代码**

**E.2 click/setText 失败原因细化**：
- service 端 `findRealNode` 失败时记录详细诊断："匹中 className=X 但 text=Y 不符" / "完全找不到 viewId=X 的节点" / "找到节点但 isClickable=false 且 parent 也不可点"
- 通过 callback 回传 main 写进 step.result.message

## 4. 严格复用清单（按用户硬约束）

**任何改动必须先 audit 这些是否已有现成实现**：
- scroll → `UiObjectActionRegistry.swipe`（不许自己写 swipe gesture）
- 节点匹配 → `findRealNode` + `NodeCriteriaExtractor.extract`（不许自己写 BFS criteria 抽取）
- task 组装 → `NodeToActionAssembler.wrapAsContainsUiObject`（不许自己 setReference/setReferent）
- 派 task → `AgentActionDispatcher.dispatch`（不许新建 RPC 或新进程通信）

**禁止**：
- 写「新的 silent fail 机制」—— 已有的 `silentFailHitsByPkgAndTarget` 直接扩
- 写「新的 dispatcher」—— 已有的 `AgentActionDispatcher` 加分支
- 写「新 prompt 规则」—— A 落地后准则要砍一半，新增规则必须给 schema 增益

## 5. 必须废弃的旧 patch 清单（升级后保留只会冲突）

A 落地后这些都得删/改：
- 准则 0.5「核心心法 - 当下快照为准」→ 被 schema observation 强制替代，整条删
- 准则 1.5「搜索框 hint 作用域」→ 改成 reflection 阶段的 self-prompt 提示
- 准则 1.7「Activity 切换归零」→ 同上，进 reflection
- 准则 7「silent-fail 二次重试」→ 改成 stuck detection 触发条件，整条删
- 准则 8「flag 是参考」→ 进 click action 字段说明，准则段删

C 落地后：
- 现有 `deadTargetsByPkg` 兼并入 `failedStrategies`，统一管理
- prompt 顶部"禁选目标"升级成"失败策略警示"

D 落地后：
- 现有 main 端的 `dispatchAgentActionByTarget` 不再做 verify（service 端硬拦），代码精简

## 6. 实施顺序（依赖关系）

```
E.1 scroll (1h) ← 0 依赖，先修真 bug
E.2 失败原因细化 (2h) ← 修 service 端，独立
   ↓
D guardrails (3h) ← 复用 E.2 的细化原因，service 端预检查
   ↓
A Structured ReAct (5h) ← schema/dto/parser/prompt 全改，最大改动
   ↓
C failed_strategies (3h) ← 在 A 的 schema 上加内存
   ↓
B Stuck Detection (4h) ← 复用 C 的失败计数，加 forced replan
   ↓
装机验证 (2h)
```

总工时 ≈ 20h（2-3 天专注工作）。今天能做完 E.1+E.2+D 三块（≈ 6h）。

## 7. 验证标准

升级后必须通过 5 个回归 case：
1. **小红书"晚上吃啥"**：从启动 → 进首页 → 进全局搜索 → 输入 → 出结果，全程不需要 user Replaced，AI 自己反思
2. **deepseek 问问题**：与 1 同款链路，但有 RN 自绘控件
3. **微信发消息**：含跨页面（联系人列表 → 聊天页 → 输入框）
4. **搜索建议点不到时**：AI 应该 ≤ 2 次尝试后换策略（点搜索按钮 + 等待结果）
5. **stuck 触发**：人为构造 4 次 ok=false 场景，AI 必须输出 rethink 才能恢复
