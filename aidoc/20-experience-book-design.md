# 20 · AI agent 经验本（Experience Book）设计

> 状态：**仅设计，未实现**（2026-05-13 立项）。
> 上游决策：`13-todo.md` 2.1（agent 改为独立运行、跑完即丢，不再"保存为任务"）后留下的"如何让 agent 越跑越聪明"问题，由本文档承接。
> 实现优先级：放在 `13-todo.md` 2.x follow-up 之后；先把 1 + 2（独立化 + 通知）落地观察一两轮真实使用，再启动经验本编码。

## 1. 动机

当前 `AiAgentSession` 的"短期记忆"只活在一次会话内（`history: List<AiAgentStepRecord>` + `deadTargetsByPkg` + `failedStrategies`），session 结束就一笔勾销。下一次同样在小红书里搜笔记、在抖音里点赞，AI 仍然要从零踩同一批坑（同样的共享 viewId 陷阱、同样的占位 TextView、同样的 launcher 反弹宽容期），导致：

- **token 浪费**：相同的"探路 → 试错 → 修正"反复发生
- **稳定性差**：偶发的 silent-fail / OEM 行为差异，每次都要重新发现一次
- **用户体验差**：用户感觉 AI"不长记性"，同样一句"在抖音搜 X"今天点对了明天又点错

经验本 = **跨 session 跨用户 goal 的长期记忆层**，把每次 session 的关键学习点沉淀成一条结构化的 txt 笔记，下次决策前按相关性召回、注入到 prompt，让 AI 在新会话开局就站在"上次踩过的坑 + 验证可行的路径"基础上。

跟"保存为可重放任务"的本质区别：
| 维度 | 保存为任务（已弃） | 经验本（本设计） |
|---|---|---|
| 沉淀对象 | 完整的 XTask 树（节点 criteria + action 序列） | 自然语言摘要 + 关键学习点 |
| 复用方式 | 严格重放（同节点 → 同动作） | AI 阅读后**重新决策**，只是带着上次的经验 |
| 适应性 | 节点变了就废 | UI 变了 AI 自己重新探，但能避开已知坑 |
| 用户介入 | 需要进编辑器手调 | 完全后台，用户感知为"AI 越用越聪明" |
| 实现复杂度 | 高（task editor 集成） | 中（txt + 简单召回 + prompt 注入） |

## 2. 设计目标与非目标

### 2.1 目标

1. **零侵入沉淀**：每次 agent session 结束自动写入 1 个 txt 文件，用户无感知。
2. **可读可手改可删除**：txt 自然语言为主，用户能在文件管理器里直接看到、改写、删掉某条错误经验。
3. **跨 session 召回**：下次 `nextAction` 时按当前 (App 包名, 用户 goal 关键词) 召回 top-N 条最相关经验，注入 prompt。
4. **隐私安全**：默认不写敏感字段（set_text 实际内容、手机号 / 邮箱 / 卡号等正则命中的 text）。仅本地存储，不上传。
5. **大小可控**：单条经验 ≤ 4 KB，整个目录硬上限 1 MB（约 500-1000 条），超出按 LRU + "失败次数高优先保留" 淘汰。
6. **可手动清空**：AI 配置页加"清空经验本"按钮，让用户能在调试 / 隐私场景下一键重置。

### 2.2 非目标（明确不做）

- **不**做语义检索（embedding / 向量库）。MVP 用关键词 + 包名硬召回，效果不够再做 V2。
- **不**做云端同步。经验本是"这台设备 + 这个用户使用习惯"的产物，跨设备同步价值低、隐私风险高。
- **不**做经验合并 / 摘要 LLM 化（让 AI 自己总结历史经验生成新的"高密度经验"）。这是 V3 优化点，MVP 不碰。
- **不**做与"保存为任务"路径的双向转换。经验本是 agent 的私有记忆，不进编辑器、不影响 task 仓库。
- **不**为 inspector / 草稿模式服务。仅 agent loop 用。

## 3. 数据结构

### 3.1 文件布局

```
${context.filesDir}/ai_agent_experience/
├── index.txt                              # 索引：每行一条 "filename|timestamp|app_pkg|goal_kw1,kw2,...|outcome|step_count|fail_count"
├── 20260513_182501_3f9a.txt               # 单条经验（按写入时间命名）
├── 20260513_184217_a012.txt
├── 20260514_091003_b743.txt
└── ...
```

- `index.txt` 是单文件可全量加载的检索表（10 KB 内可放 ~200 条索引行）；详情按需懒加载对应 txt。
- 单条经验 txt 自包含，支持用户手动编辑后下次自动生效。
- 文件名 `${yyyyMMdd_HHmmss}_${sessionIdShort}.txt`，`sessionIdShort` = `sessionId.takeLast(4)`。
- 路径走 `context.filesDir` 而非 `getExternalFilesDir`：经验内容含可识别用户行为习惯，应留在应用沙箱内。

### 3.2 单条经验 txt 模板（人类可读）

```
# Experience Note
session_id: 3f9a-...
finished_at: 2026-05-13 18:25:01
target_app: com.ss.android.ugc.aweme (抖音)
user_goal: 在抖音里搜索下饭综艺
goal_keywords: 抖音, 搜索, 综艺, 下饭
outcome: Completed
duration_seconds: 32
total_steps: 8
plan_status_distribution: on_track=6 adjusted=1 off_track=1 unknown=0

## Key learning (≤ 5 条，AI 写)
- 抖音首页搜索入口在右上角放大镜（contentDesc="搜索"），不要在底部 navi bar 找。
- 搜索结果页 RecyclerView 加载有 1-2s 延迟，立即 click 容易点空，最好 wait 1.5s。
- 综艺频道入口是顶部 horizontal tab "综艺"（不是首页 feed 的标签）。

## Failure traps avoided (本次踩过 + 已修正的坑)
- viewId="iv_search_icon" 在多个页面共享，单字段匹配会命中错节点；用 contentDesc="搜索" 联合限定可解。
- 顶部 tab "推荐" 是 default tab 不是搜索入口，textContains="推荐" 不要点。

## Final outcome
done: 已经在播放综艺《饭饭》第 1 集

## Raw step trace (debug only, AI 不读)
01 launch_app com.ss.android.ugc.aweme  → OK
02 click desc="搜索"                     → OK
03 set_text "下饭综艺"                   → OK
04 click textEquals="搜索"               → OK
05 wait 2s                               → OK
06 click textContains="综艺"             → OK
07 click textContains="饭饭"             → OK
08 done                                  → OK
```

> 字段说明
>
> - **第一段（metadata + 索引字段）**：固定字段，写入器机器生成，索引解析依赖。
> - **Key learning / Failure traps avoided / Final outcome**：自然语言为主，由"经验提炼器"在 session 结束时合成（详见 §5.2）。AI 在下次决策时**只读这三段**。
> - **Raw step trace**：仅供人类调试 + 出错时复现，AI 不读，可在淘汰文件时优先删除这段以瘦身。

### 3.3 索引行格式（`index.txt`）

```
20260513_182501_3f9a.txt|2026-05-13T18:25:01|com.ss.android.ugc.aweme|抖音,搜索,综艺,下饭|Completed|8|0
20260513_184217_a012.txt|2026-05-13T18:42:17|com.xingin.xhs|小红书,笔记,蛋糕|GivenUp|12|3
...
```

`|` 分隔，6 字段：`filename | iso_ts | pkg | goal_keywords_csv | outcome | step_count | fail_count`。
新条目 append 到末尾；淘汰时直接重写整个文件（10 KB 量级，重写无压力）。

## 4. 与现有代码的接入点

### 4.1 写入侧

**触发位置**：`VoiceCommandService.runAgentFlow` 的 `appendOutcomeRecord(outcome, plan)` + `notifyAgentOutcome(...)` 之后。
（紧接现有"通知用户"步骤，写本地经验是同一个时刻的语义。）

```kotlin
// app/src/main/java/top/xjunz/tasker/voice/VoiceCommandService.kt 大致改动（伪代码）
appendOutcomeRecord(outcome, plan)
notifyAgentOutcome(outcome, plan, userGoal = text)
if (Preferences.aiAgentExperienceBookEnabled) {                       // 新 Pref
    AiAgentExperienceBook.recordSession(                              // 新对象，§5
        userGoal = text,
        plan = plan,
        outcome = outcome,
        scope = scope
    )
}
```

**新模块**：`app/src/main/java/top/xjunz/tasker/ai/agent/experience/`
- `AiAgentExperienceBook.kt`：单例对象，对外暴露 `recordSession(...)` / `recall(query): List<ExperienceEntry>` / `clearAll()`。
- `ExperienceEntry.kt`：data class，对应索引行 + 文件路径，懒加载文件正文。
- `ExperienceFileWriter.kt`：负责构造 §3.2 模板、写入文件、追加索引。
- `ExperienceLearningExtractor.kt`：从 `outcome.history` 提炼 Key learning / Failure traps avoided / Final outcome。
- `ExperienceRedactor.kt`：跑敏感字段正则替换。
- `ExperienceRecaller.kt`：按当前 (pkg, goal) 做关键词 + 加权召回，吐出 prompt 注入用的 markdown 段落。

### 4.2 读取 / 注入侧

**触发位置**：`AiAgentPlanner.buildNextActionPrompt`，新增一段 `experienceSection` 与 `historySection` / `blacklistSection` / `failedSection` 同级。

```kotlin
// app/src/main/java/top/xjunz/tasker/ai/agent/AiAgentPlanner.kt 大致改动（伪代码）
private fun buildNextActionPrompt(
    userGoal: String,
    history: List<AiAgentStepRecord>,
    snapshot: AiUiSnapshot?,
    plan: AiAgentSessionPlan?,
    maxSteps: Int,
    deadTargets: List<String>,
    failedStrategies: List<String>,
    stuckHits: Int,
    stuckThreshold: Int,
    experiences: List<ExperienceEntry> = emptyList()  // ← 新增
): String {
    ...
    val experienceSection = if (experiences.isEmpty()) "" else buildString {
        appendLine()
        appendLine("📚 你以前在类似任务上的经验（仅供参考，不一定适用本次）：")
        experiences.forEach { exp ->
            appendLine("---")
            appendLine("【目标】${exp.userGoal}（${exp.outcome}, ${exp.totalSteps} 步）")
            appendLine("【关键学习】")
            exp.keyLearnings.forEach { appendLine("  - $it") }
            if (exp.failureTraps.isNotEmpty()) {
                appendLine("【已知陷阱】")
                exp.failureTraps.forEach { appendLine("  - $it") }
            }
        }
    }.trimEnd()
    return """...
${if (experienceSection.isEmpty()) "" else "\n$experienceSection\n"}
$historySection
$snapshotSection
..."""
}
```

调用方（`AiAgentSession.runLoop` 内调 `AiAgentPlanner.nextAction`）：
- 在 session 启动时一次性 `experiences = AiAgentExperienceBook.recall(userGoal, scope.targetApps)`，缓存到 session 字段。
- 不在每步重新召回（除非用户开了"激进模式"，先不做）。

### 4.3 配置 / 用户管控

**新 Preference**（`app/src/main/java/top/xjunz/tasker/Preferences.kt`）：
- `aiAgentExperienceBookEnabled: Boolean`（默认 `true`）
- `aiAgentExperienceMaxBytes: Int`（默认 `1024 * 1024`，1 MB 上限）
- `aiAgentExperienceRecallTopN: Int`（默认 `3`，每次注入 prompt 最多多少条）

**AI 配置页 UI**（`app/src/main/java/top/xjunz/tasker/ui/about/...`）：
- "经验本" 子区块：开关 + 大小用量条（已用 X KB / 上限 Y MB）+ "清空经验本" 按钮 + "导出经验本到下载目录"按钮（用户主动备份用）。

**字符串资源**（`app/src/main/res/values/strings.xml`）：
- `ai_agent_experience_section`、`ai_agent_experience_section_desc`、`ai_agent_experience_clear`、`ai_agent_experience_export`、`ai_agent_experience_usage_format`。

## 5. 关键算法

### 5.1 召回（`ExperienceRecaller`）

输入：当前 `userGoal: String`、`targetApps: Set<String>`、`topN: Int`。
输出：`List<ExperienceEntry>`，按相关性降序，最多 `topN` 条。

打分：
```
score = pkg_match * 3
      + goal_keyword_overlap_count * 1
      + outcome_failure_bonus           # GivenUp/AiError/LimitExceeded 给的经验更值得参考，+0.5
      - age_decay                       # 半年前的经验权重砍半（UI 易变）
```

`goal_keyword_overlap_count` 用最朴素的中文分词替代品：先 `userGoal.lowercase().split(Regex("[\\s，。、,.;:?!？！]"))` 切碎，与索引里 `goal_keywords` CSV 求交集大小。

不命中任何条件直接 0 分，0 分项不返回。

### 5.2 经验提炼（`ExperienceLearningExtractor`）

输入：`outcome: AiAgentSessionOutcome` + `history: List<AiAgentStepRecord>`。
输出：`keyLearnings: List<String>`、`failureTraps: List<String>`、`finalOutcome: String`。

MVP 算法（**纯本地启发式，不调 LLM**）：

1. **Key learning** 候选：
   - 取 `history` 中所有 `result.ok=true` 且 `action.planStatus="on_track"` 的步骤
   - 按 (`action.type`, `target.viewId`/`target.contentDescEquals` 关键值) 去重
   - 把 action 描述 + 一句话上下文（基于该步前一个 snapshot 的 activity/text 关键字）拼一行
   - 最多保留 5 条
2. **Failure traps avoided** 候选：
   - 取 `failedStrategies` 列表 + `deadTargetsByPkg` 黑名单
   - 每条转换为 "viewId/text/desc=... 是 X 不是 Y"（如果能从 silent-fail 诊断 message 里抽出对照）
   - 最多保留 5 条
3. **Final outcome**：取 `outcome` 的 baseDetail（与 §VoiceCommandService.notifyAgentOutcome 同源）

V2 备选：把 history + outcome 喂给 LLM 让它自己摘要（成本约 200-500 token / 次 session），作为 toggle 给愿意付费的用户。

### 5.3 淘汰（`ExperienceFileWriter.evictIfOverBudget`）

每次 `recordSession` 写完后检查：

1. 算 `ai_agent_experience/` 总字节
2. 超过 `aiAgentExperienceMaxBytes` 时，按以下顺序淘汰直到回到上限的 80%：
   1. **保护**：最近 7 天内 + outcome 是 `GivenUp/AiError/LimitExceeded` 的（高价值经验）
   2. **优先删**：超过 30 天 + outcome 是 `Completed` 的（常规成功，价值递减）
   3. **再次**：删 Raw step trace 段落（保留 metadata + Key learning，文件瘦身 60%+）
   4. **最后**：按 ts 升序整文件删除

## 6. 隐私 / 安全设计

### 6.1 不写入清单

下列字段在写入前必须 redact / 不写：

| 字段 | 处理 |
|---|---|
| `AiAgentAction.SetText.text` | 全部替换为 `<redacted>`，仅记录"对哪个 target 输了文本"，不存内容 |
| `target.textEquals/textContains` | 跑 `ExperienceRedactor` 正则：手机号 / 邮箱 / 18 位身份证 / 16-19 位卡号 → `<redacted>` |
| `target.contentDescEquals/contentDescContains` | 同上 |
| 节点 viewId | 不 redact（id 是开发者命名，一般不含 PII） |
| 当前 activity 名 | 不 redact |
| 包名 | 不 redact |

### 6.2 用户授权 / 控制

- 默认 `aiAgentExperienceBookEnabled = true`，但**首次写入前**弹一次性提示对话框告知用户"AI agent 现在会在本地把每次任务的关键步骤写成 txt 笔记，下次同类任务能更聪明。这些笔记不上传，可以在 AI 配置页随时关闭和清空。" → "知道了 / 关闭"。
- 配置页有总开关、用量显示、清空按钮、导出按钮（导出到 `Downloads/AutoTask/experience_${ts}.zip`，用户审计或备份用）。
- AndroidManifest 不需要新加权限（`filesDir` 是应用私有沙箱）。

### 6.3 不进 prompt 的"开关"

- 单条经验 txt 文件名以 `_disabled` 结尾的（用户手动改名实现的"暂时禁用"），写入器仍生成、召回器跳过。
- 用户在 txt 文件正文写 `#noai` 标签的，召回器跳过。

## 7. 实现路线（分期）

### Phase 1 · MVP（≈ 1-1.5 天）

- [ ] 新建 `ai/agent/experience/` 包及 6 个文件（§4.1）
- [ ] `Preferences` 加 3 个新键
- [ ] `VoiceCommandService.runAgentFlow` 末尾接入 `recordSession`
- [ ] `AiAgentPlanner.buildNextActionPrompt` 加 `experienceSection`
- [ ] `AiAgentSession` 在 `runLoop` 启动时一次性 `recall` 并透传给 planner
- [ ] AI 配置页加"经验本"区块（开关 + 用量 + 清空）
- [ ] 字符串资源
- [ ] 写一个简单的本地正则 redactor（手机/邮箱/身份证/卡号）

不做：UI 上的导出按钮、`_disabled` 后缀禁用、`#noai` 标签、Raw step trace 自动剥离、LLM 摘要。

### Phase 2 · 生产强化（≈ 0.5-1 天，看实测反馈再决定）

- [ ] 增加导出按钮 / `_disabled` 后缀 / `#noai` 标签支持
- [ ] 召回打分加 age_decay
- [ ] Raw step trace 段落瘦身淘汰
- [ ] 在 `aidoc/15-ai-working-notes.md` 写 1 章节复盘"经验本上线后 token 消耗变化 / silent-fail 减少率"

### Phase 3 · 智能化（暂不排期，看用户付费意愿）

- [ ] LLM 摘要可选开关（写入时让 AI 自己总结，比启发式更高密度）
- [ ] 本地 embedding 召回（`onnxruntime` + 小模型，跨语言关键词无能为力的场景能补救）
- [ ] 经验之间的"冲突检测"（同一 App 上两条经验给出矛盾结论时让 AI 仲裁）

## 8. 实现注意事项 / 易踩坑

1. **不要在主进程做长 IO**：写入 / 召回都用 `Dispatchers.IO`。`recall` 在 session 启动时一次性做，召回结果常驻 session；不要每步去读盘。
2. **txt 编码**：硬指定 UTF-8（`Charsets.UTF_8`），避免在 OEM 默认 charset 上踩坑。
3. **文件锁**：单进程访问，但 `index.txt` 写入用 `synchronized(AiAgentExperienceBook)` 防止并发 session（理论上不会有，但加上更稳）。
4. **Manifest / Backup**：默认 `android:allowBackup` 是开的，要在 `data_extraction_rules.xml` 里 **排除** `ai_agent_experience/`，避免备份到 Google Drive 又泄漏出去。
5. **跟现有 `AiAgentLog` 解耦**：`AiAgentLog` 是 logcat 日志，可以失败不影响功能；经验本写入失败要 catch 掉别让它影响 session 结果通知。
6. **跟"agent 任务独立运行"决策一致**：经验本只读不写**任务仓库**（`TaskStorage` / `LocalTaskManager`），永远不创建 `XTask`。这点是从 `aidoc/13-todo.md` 2.1 决策传承下来的硬约束。
7. **prompt 段落顺序**：`experienceSection` 放在 `stuckSection` 之后、`blacklistSection` 之前（语义递进：长期经验 → 当前 stuck 警示 → 当前 session 黑名单 → 当前 session 失败策略 → history → snapshot）。
8. **召回为空也是合理结果**：第一次跑 / 全新 App，召回为空，prompt 里就**完全不出现** experienceSection（连"暂无经验"这种话都不要写，避免占 token）。

## 9. 验收标准（MVP 完工时）

1. 在小红书 / 抖音 / 一个新 App 各跑 3 次同类 agent 任务，能在 `filesDir/ai_agent_experience/` 看到对应 txt 文件，内容符合 §3.2 模板。
2. 第 4 次跑同样任务时，logcat / 调试 prompt 能看到 `experienceSection` 注入了 prompt（关键学习 / 失败陷阱可读）。
3. 跑一个 set_text "测试密码 123456" 的任务，写出来的 txt 不含字符串 "测试密码 123456"。
4. 在 AI 配置页点"清空经验本"，目录被清空，下次召回返回空。
5. 关掉总开关后跑 agent，目录无新文件、prompt 无 experienceSection。
6. 跑 1000+ 次 session 后总目录 ≤ 1 MB，老文件被淘汰、新文件正常写入。
7. 关闭网络 / AI 不可用情况下，经验本写入逻辑不抛异常、不影响 outcome 通知。

## 10. 跟其他文档的引用关系

- `aidoc/13-todo.md` 2.1：明确"agent 独立运行 / 不再保存为任务"决策的承接者。
- `aidoc/14-ai-integration.md` §7.1（agent loop）：本设计在该 loop 末尾加 `recordSession`、开头加 `recall`，需要在落地后回写 §7.1 流程图。
- `aidoc/15-ai-working-notes.md`：完工后回写一节"经验本上线 N 周后实测变化"。
- `aidoc/19-feature-audit.md`：本文档替代了 19 中已废弃的"A.1 推荐方案"对学习沉淀的承担。
- `aidoc/02-architecture.md`：新增 `ai/agent/experience/` 子包，落地时同步该文件依赖图。
