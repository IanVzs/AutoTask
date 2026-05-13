# 20 · AI agent 经验本（Experience Book）设计与实现

> 状态：**已实现**（2026-05-13 落地）。
> 本文档既是设计稿也是当前实现的事实文档；后续修改 `ai/agent/experience/` 目录或经验本相关
> UI / prompt 注入逻辑时，必须同步本文。

## 0. 跟之前决策的衔接

`13-todo.md` 早期把 follow-up 2.1（agent 跑过的步骤"另存为任务"）标为"agent 改为独立运行 →
不再保存为任务"。**用户在 2026-05-13 18:48 重新决策**：
- agent 仍然**独立运行**（不会自动把每步落成 XTask 进任务库）
- 但需要**经验本**沉淀执行心得给下次 AI 参考
- 而且**成功的经验**支持用户**主动一键转草稿**，进 FlowEditor 继续打磨

因此本设计文档（原本规划的 V1 设计）整体落地，并扩展了原本不在 MVP 范围的"成功经验 → 草稿"
能力。原 13-todo 2.1 改名重启，作为"经验本驱动的可选转换路径"存在。

## 1. 动机

`AiAgentSession` 自带的"短期记忆"只活在一次会话内：
- `history: List<AiAgentStepRecord>`：当前 session 的步骤序列
- `deadTargetsByPkg`：精确 target hash 拉黑
- `failedStrategies`：策略指纹失败计数

session 结束 ↦ 全部清零。下次同样在小红书里搜笔记、抖音里点赞，AI 仍然要从零踩同一批坑
（共享 viewId、占位 TextView、launcher 反弹），导致 token 浪费 + 稳定性差。

**经验本** = 跨 session 跨用户 goal 的长期记忆层：
- 写：每次 session 跑完自动落一份自包含 txt 笔记
- 读：新 session 开局召回最相关 top-N 条注入 prompt
- 转：成功经验可由用户主动一键翻成 XTask 草稿进编辑器

本质区别于"自动生成可重放任务"（已弃方案）：
| 维度 | 自动保存为任务（弃） | 经验本（本设计） |
|---|---|---|
| 沉淀对象 | 完整 XTask 树（criteria + action 序列） | 自然语言摘要 + 结构化步骤记录 |
| 复用方式 | 严格重放（同节点 → 同动作） | AI 阅读后**重新决策**，带着经验 |
| 适应性 | 节点变了就废 | UI 变了 AI 自己重探，但能避开已知坑 |
| 用户介入 | 自动后台静默 | 写入自动；**转草稿用户主动触发** |
| 实现复杂度 | 高（task editor 集成 + 持久化校验） | 中（txt + 启发式提炼 + prompt 注入） |

## 2. 实际实现的目标

### 2.1 已落地

1. **零侵入沉淀**：`VoiceCommandService.runAgentFlow` 末尾自动调
   `AiAgentExperienceBook.recordSession`，写一份 `${ts}_${sid}.txt`（自然语言 markdown +
   结构化 JSON 嵌块）+ 更新 `index.json`，用户无感知。
2. **可读可手改**：txt 自然语言为主，用户能在文件管理器里直接看；用户可手改 markdown 部分
   不影响结构化数据（JSON 块在文件尾部，明示 do not edit）。
3. **跨 session 召回**：`AiAgentSession` 启动时一次性按 (用户 goal, 当前 App) 打分召回 top-N，
   透传到 `AiAgentPlanner.buildNextActionPrompt` 注入 `experienceSection` 段落（位置在
   `userGoal` 之后、`stuckSection` 之前）。
4. **隐私安全**：`ExperienceRedactor` 跑手机/邮箱/身份证/卡号正则脱敏；`set_text` 实际内容
   **永不写盘**（仅 `hasTextInput=true` 占位）。仅本地 `filesDir`，不上传。
5. **大小可控**：单条经验文件没硬上限（实际 ~3-10 KB），整个目录硬上限默认 1 MB；
   超出按"老 Completed 优先删 → 老非-Completed → 兜底按时间升序"三档淘汰，回到 80% 上限。
6. **UI 入口 + 详情 + 转草稿**：人工智能页加经验本卡片，BottomSheet 展开列表，单条详情
   全屏对话框可滚动可复制；成功条目右侧"转任务草稿"按钮 → 调
   `ExperienceToTaskConverter.convert(exp)` → `FlowEditorDialog.initBase(task, false)` →
   走与原文字草稿同款 import 链路。
7. **手动管理**：长按单条 → 删除；列表顶部 → 一键清空；用量 KB/MB 实时显示。

### 2.2 暂不做（V2/V3 候选）

- 配置页 UI 控件（暂无入口；Preferences 三键有默认值，可后续在 AI 配置弹窗加 section）
- LLM 摘要（让 AI 自己合成更高密度经验）
- embedding 语义召回
- 云同步 / 跨设备
- 经验本导出到 Downloads（用户可直接 `adb pull` filesDir/ai_agent_experience）
- `_disabled` 后缀 / `#noai` 标签的逐条停用机制
- `data_extraction_rules.xml` 排除经验本目录（防 Auto Backup）—— 必要时单独提交补
- 召回缓存（多 session 内共用的 hot cache）

## 3. 数据结构（已实现）

### 3.1 文件布局

```
${context.filesDir}/ai_agent_experience/
├── index.json                              # 整目录索引（轻量元数据）
├── 20260513_182501_3f9a.txt               # 单条经验自包含 txt
├── 20260513_184217_a012.txt
└── ...
```

`filesDir` 是应用沙箱，外部访问需 root；adb 调试可 `run-as` 拿到。
路径常量 `AiAgentExperienceBook.DIR_NAME = "ai_agent_experience"`。

### 3.2 单条经验 txt 格式

```
# AI Agent Experience · 2026-05-13 18:25:01

## Metadata
- session_id: 3f9a-...
- finished_at: 2026-05-13 18:25:01
- target_app: com.ss.android.ugc.aweme (抖音)
- user_goal: 在抖音里搜索下饭综艺
- outcome: Completed
- outcome_label: AI agent 已完成任务
- duration_ms: 32480
- total_steps: 8
- fail_steps: 0
- plan_status: ontrack=6 adjusted=1 offtrack=1 unknown=0
- convertible_to_task: true
- goal_keywords: 抖音, 搜索, 综艺, 下饭

## Plan Summary
打开抖音，进入搜索页，搜"下饭综艺"，进入综艺频道。

## Final Outcome
done: 已经在播放综艺《饭饭》第 1 集

## Key Learnings
- [com.ss.android.ugc.aweme] click desc="搜索"（理由：首页右上角的搜索按钮…）
- ...

## Failure Traps
- click id=iv_search_icon → FAIL（命中错节点 RecyclerView…）

## Step Trace
[01] [ontrack] launch_app com.ss.android.ugc.aweme → OK
     observation: 当前在桌面…
     reflection: 先启动抖音…
[02] [ontrack] click desc="搜索" → OK
     ...

## Structured (machine-readable JSON, do NOT edit by hand)
```json
{
  "schema_version": 1,
  "session_id": "...",
  "steps": [...]
}
```
```

- markdown 部分：人类 + AI prompt 阅读用，省 token 不喂全量 step JSON
- json 嵌块：`AiAgentExperienceBook.loadEntry()` / `convertToDraft()` / 召回打分都解析这块

### 3.3 `index.json`

```json
{
  "version": 1,
  "entries": [
    {
      "filename": "20260513_182501_3f9a.txt",
      "session_id": "3f9a-...",
      "finished_at_millis": 1747114501000,
      "target_app_package": "com.ss.android.ugc.aweme",
      "target_app_label": "抖音",
      "user_goal": "在抖音里搜索下饭综艺",
      "goal_keywords": ["抖音","搜索","综艺","下饭"],
      "outcome": "Completed",
      "outcome_label": "AI agent 已完成任务",
      "step_count": 8,
      "fail_count": 0,
      "duration_ms": 32480,
      "convertible_to_task": true,
      "size_bytes": 3420
    }
  ]
}
```

10 KB 量级可全量加载，一次 IO 拿到所有元数据；正文按需懒加载。

### 3.4 关键 Kotlin 类

| 文件 | 职责 |
|------|------|
| `ExperienceModel.kt` | `ExperienceFile` / `ExperienceStep` / `ExperienceIndex` / `ExperienceIndexEntry` / `ExperienceRecallEntry` 全部 DTO |
| `ExperienceRedactor.kt` | 手机/邮箱/身份证/卡号正则脱敏；外部不直接调，writer 用 |
| `ExperienceLearningExtractor.kt` | 从 history + outcome 启发式提炼 keyLearnings / failureTrapsAvoided / planStatusDistribution |
| `ExperienceFileWriter.kt` | 把 session 信息组装成 ExperienceFile + 渲染 markdown + 写盘 + 解析 JSON 嵌块 |
| `ExperienceKeywordExtractor.kt` | 用户 goal 字符串 → 关键词列表（朴素中英文混合） |
| `ExperienceRecaller.kt` | 按 (用户 goal, target Apps) 给索引打分召回 top-N |
| `ExperienceToTaskConverter.kt` | 成功 ExperienceFile → 可编辑 XTask（多步串成 doFlow） |
| `AiAgentExperienceBook.kt` | 单例总门面：ensureInitialized / recordSession / recall / queryAll / loadEntry / convertToDraft / delete / clearAll / usageBytes |

## 4. 与现有代码的接入点（已落地）

### 4.1 写入侧

```text
VoiceCommandService.runAgentFlow
  └─ session.run() 完成
     ├─ appendOutcomeRecord(outcome, plan)
     ├─ notifyAgentOutcome(outcome, plan, userGoal)
     └─ recordOutcomeToExperienceBook(outcome, plan, userGoal, targetApps, sessionId, startedAtMillis)
        └─ AiAgentExperienceBook.recordSession(...)
           ├─ ExperienceFileWriter.build(...)  ← 提炼 keyLearnings/failureTraps + 序列化 steps
           ├─ ExperienceFileWriter.writeToDir(dir, exp)  ← 写 txt
           └─ evictIfOverBudget(...)  + writeIndexToDisk(...)
```

### 4.2 召回侧

```text
VoiceCommandService.runAgentFlow
  ├─ 用户授权后 grantAgent → 准备启动 session
  ├─ AiAgentExperienceBook.recall(this, userGoal=text, targetApps=...) → top-N
  ├─ appendRecord("召回 N 条相关历史")
  └─ AiAgentSession(scope, plan, overlay, picker, callbacks, experiences = recalled)
     └─ runLoop → AiAgentPlanner.nextAction(..., experiences = experiences)
        └─ buildNextActionPrompt 加 experienceSection 注入到 prompt
```

session 内**不**重新召回（避免每步 IO）。一次会话 = 一组经验 = 一段固定 prompt 段落。

### 4.3 UI 入口

- **底栏 Tab**：`task_bottom_bar.xml` 的 `item_voice_command` icon 改 `ic_baseline_auto_awesome_24`，
  title 改 `@string/page_title_ai`（"人工智能"）
- **页面卡片**：`fragment_voice_command.xml` 在文字命令卡片之上加 `card_experience_book`
- **点击行为**：`VoiceCommandFragment.onViewCreated` 注册 `cardExperienceBook.setNoDoubleClickListener` →
  `AiExperienceBookDialog().show(parentFragmentManager)`
- **用量刷新**：`refreshExperienceUsage` 在 `onResume` 与每次 service uiState 变化时调（覆盖
  "session 跑完写经验本 → 卡片摘要立刻反映"）

### 4.4 转草稿

- `AiExperienceBookDialog` 列表项的 "转任务草稿" 按钮 →
  `AiAgentExperienceBook.convertToDraft(ctx, filename)` 拿 XTask →
  `FlowEditorDialog().initBase(task, false).doOnTaskEdited { taskShowcaseViewModel.requestAddNewTasks.value = listOf(task) }.show(parentFragmentManager)`
- 跟 VoiceCommandFragment.openDraftInEditor 走的是**同一条 import 链路**

### 4.5 Preferences

```kotlin
var aiAgentExperienceBookEnabled       // default true
var aiAgentExperienceMaxBytes          // default 1 MB
var aiAgentExperienceRecallTopN        // default 3
```

暂无 UI 入口。后续 AI 配置弹窗里加 section 即可（Phase 2）。

## 5. 关键算法（已实现）

### 5.1 召回（`ExperienceRecaller`）

```
score = (pkg_match * 3 + keyword_overlap + outcome_failure_bonus) * age_decay
- pkg_match: 命中 targetAppPackage in targetApps  +3
- keyword_overlap: 用户 goal 关键词与历史 entry 的交集大小
- outcome_failure_bonus: outcome != Completed → +0.5（失败经验更值得参考）
- age_decay: e^(-age_days / 30)（半衰期约 21 天）
- 阈值：< 0.4 不召回，避免低相关性噪音
```

### 5.2 经验提炼（`ExperienceLearningExtractor`）

`extract(outcome, history)` 返回 (keyLearnings, failureTrapsAvoided, planStatusDistribution, failCount)：

- **keyLearnings**：history 中所有 ok=true 且 plan_status ∈ {OnTrack, Adjusted} 的步骤，
  按 (actionType + 关键 target 字段) 去重，每条 "[pkg] action描述（理由：reflection 前 80 字）"
- **failureTrapsAvoided**：所有 ok=false 或 plan_status=OffTrack 的步骤，
  每条 "[pkg] action描述 → FAIL/off_track（result.message 前 80 字）"
- **planStatusDistribution**：对 history.action.planStatus 做归类计数

各最多保留 8 条，避免单条经验文件膨胀。

### 5.3 关键词抽取（`ExperienceKeywordExtractor`）

不引入正经分词库（`jieba` / `mmseg` 都 200KB+，加大依赖不值）。规则：
- 按常见标点 / 空白切碎
- ASCII 段：长度 ≥ 2 且非纯数字非停用词
- CJK 段：保留 2-6 字整段；4 字以上加 2-3 字滑窗
- 去停用词（"的我了在和与"等）+ 去重 + 限制 16 个

精度有限但够用："抖音搜索下饭综艺" → `[抖音, 搜索, 下饭, 综艺, 抖音, 抖音搜索, ...]`。

### 5.4 淘汰（`AiAgentExperienceBook.evictIfOverBudget`）

按以下顺序删，每次从被命中的集合按 finishedAtMillis 升序删，回到 80% 上限：
1. > 30 天 + Completed
2. > 90 天 + 非 Completed
3. > 180 天 任何
4. 兜底：按 finishedAtMillis 升序硬删

**注意**：实现里没有"瘦身段落"逻辑（删 step trace 段保留 metadata），整文件删除即可。
单条 ~3-10 KB，量级足够 1 MB 容纳几百条。

## 6. 隐私 / 安全

### 6.1 不写入清单

| 字段 | 处理 |
|---|---|
| `AiAgentAction.SetText.text` | **永不写盘**；步骤记 `hasTextInput=true` 占位 |
| `target.textEquals/textContains` | 跑 ExperienceRedactor：手机 / 邮箱 / 18 位身份证 / 13-19 位卡号 → `<redacted>` |
| `target.contentDescEquals/contentDescContains` | 同上 |
| `result.message` / `matchedNodeSummary` | 同上 redact |
| `observation` / `reflection` / `lastActionReview` | 同上 redact |
| viewId / className / packageName / activityName | 不 redact（开发者命名，一般非 PII） |

### 6.2 转草稿时的额外保护

- `set_text` 步骤转出来的 task applet 用占位文本 `<请输入文本>`，强制用户在编辑器里手填
- 草稿任务 description 明确写"输入内容已脱敏，请填实际文本"
- Scroll / Wait / Done / GiveUp / Unknown 不转换

### 6.3 用户控制

- 配置 Preference：默认 enabled = true，max = 1 MB，topN = 3（暂无 UI 入口）
- 列表对话框：长按删一条 / 顶部按钮一键清空 / 实时显示用量
- 详情对话框：完整 markdown + JSON 全文可滚动可复制（让用户能看清 AI 实际拿到什么）

## 7. 验收（实测建议）

1. 在抖音 / 小红书 / 任意一个新 App 跑一次 agent 任务
2. 回到「人工智能」页 → 经验本卡片应显示"已记录 1 条 · 占用 NN KB"
3. 点经验本卡片 → BottomSheet 列表出一条 → 点「查看完整记录」可看 markdown 全文
4. 跑第二次同类任务，检查 logcat：`AiAgent` tag 应出现 `experience.recall` 日志，
   prompt（D 级日志）顶部 `📚 你以前在类似任务上的经验` 段落出现
5. 跑一次 set_text "测试密码 123456" 的任务，开经验本详情 → 不应出现 "测试密码 123456"
6. 找一条 outcome=Completed 的经验 → 点「转任务草稿」→ 编辑器弹起 → 「保存」后任务列表新增一条
7. 长按一条经验 → 弹"删除"对话框 → 删后列表 / 用量同步刷新
8. 顶部「清空」→ 确认 → 列表空 / 用量回 0

## 8. 跟其它文档的引用关系

- `aidoc/13-todo.md` 2.1：决策反转的承接者；本设计落地后 13-todo 同步标 2.1 重启完成
- `aidoc/14-ai-integration.md`、`aidoc/15-ai-working-notes.md`、`aidoc/16-ai-inspector-capability.md`：
  之前为"agent 独立运行 / 不再保存为任务"补的描述需要部分回滚为"经验本驱动"
- `aidoc/19-feature-audit.md` 中已废弃的 A.1 / D 方案在本设计落地后仍然废弃（路径不同）
- 修改 `ai/agent/experience/`、`fragment_voice_command.xml`、`task_bottom_bar.xml`、
  Preferences 经验本 3 键、`AiAgentPlanner.buildNextActionPrompt` 的 experienceSection、
  `AiAgentSession` 的 experiences 字段都要回写本文档
