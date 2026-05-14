# 12 · 发版说明

## 2.1.0-alpha.1 / versionCode 210 起 · AI 接入第二阶段（持续累积）

发布日期：2026-05-09 起持续；版本号见 `gradle.properties` `APP_VERSION_NAME` / `APP_VERSION_CODE`。

> 本节按时间线列出 alpha.1 开版后的所有重要变更（含尚未单独发版但已合入 `feature/ai-integration` 的内容）。
> 详细设计与决策见 `aidoc/14-20`。

### 2026-05-09 · AI agent loop 一次落地

- 新增 `app/src/main/java/top/xjunz/tasker/ai/agent/`：`AiUiSnapshot` / `ScreenSnapshotProvider` / `AiAgentAction` / `AiAgentExecutor` / `AiAgentPlanner` / `AiAgentSession` / `AiTaskScope`，加上 `overlay/AiAgentOverlayController` 决策面板。
- agent 改为复用现有 Applet 管道执行单步动作（详见 `aidoc/16` §13.8 KISS 重构、`aidoc/17` 路径审计）；不直接绕过 `XTask` / `Applet` / `AutomatorService`。
- ReAct 三段思考升级（observation / last_action_review / reflection 强制三段输出，详见 `aidoc/18`）；stuck 检测 + 失败策略长期记忆 + silent-fail 二次拉黑机制。
- 每步决策面板（默认 3 秒倒计时自动同意，可改 `aiAgentConfirmMode` 为 `wait_for_user` / `disabled`）。
- 任务级授权 + App scope 锁 + 步数 / 时长上限 + 未授权能力即停 四条边界。
- `aidoc/19-feature-audit.md` 抓到并修了一批 task 树构造 / 节点定位真因。

### 2026-05-13 · agent / 经验本 / 草稿生成 三模块解耦架构

- **agent 任务定位调整**：agent 执行模块（`AiAgentSession`）每步**自动**沉淀到经验本，agent 自身不感知"草稿"概念；草稿能力被解耦到独立模块。
- **新增 AI 经验本**（`app/src/main/java/top/xjunz/tasker/ai/agent/experience/` 8 个文件）：自然语言 markdown + 结构化 JSON 嵌块写到 `${filesDir}/ai_agent_experience/<ts>_<sid>.txt`，配 `index.json` 索引。三个出口：
  - `recall()` 按 (用户 goal, 当前 App) 打分召回 top-N，注入下一次 `nextAction` prompt 的 `📚 经验` 段
  - `queryAll()` / `loadEntry()` 给 UI 浏览 / 详情用
  - `convertToDraft()` 给草稿入口
- **新增 经验→草稿翻译模块**（`ExperienceToTaskConverter`）：纯工具类，**完全由用户在「人工智能」页 → 经验本对话框主动点击触发**，把成功经验里 `convertible` 的 step 序列翻译成 `RootFlow + preload + ifFlow + doFlow` 弹 `FlowEditorDialog` 让用户审核。复用 `NodeToActionAssembler.wrapAsContainsUiObject`。
- 三模块通过经验本作为单向数据中介解耦，**不是"自动 vs 手动落库"开关**。详见 `aidoc/20-experience-book-design.md` §0。
- **隐私**：`set_text` 实际内容**永不写盘**（仅 `hasTextInput=true` 占位）；`target.text*` / `contentDesc*` / `result.message` / `observation` / `reflection` / `lastActionReview` 全部跑 `ExperienceRedactor` 正则脱敏（手机/邮箱/身份证/卡号）。仅本地 `filesDir`，不上传。
- **会话结果通知**：新增独立 channel `ai_agent_outcome`（`IMPORTANCE_DEFAULT`），不论成功 / 失败 / 中止 / 越权 / 服务未连接 / AI 错误都发独立通知；与前台常驻通知（`voice_command`）完全分离。
- **「语音」Tab → 「人工智能」Tab**：`task_bottom_bar.xml` `item_voice_command` title 改为 `page_title_ai`、icon 改为 `ic_baseline_auto_awesome_24`；语音页顶部加 AI 经验本入口卡片，点击弹 `AiExperienceBookDialog`（BottomSheet 列表 + 一键转草稿 + 长按删除 + 顶部清空）；详情走 `AiExperienceDetailDialog`（全屏 monospace 可滚动可复制原 txt）。
- **Preferences 新增**：`aiAgentExperienceBookEnabled` (默认 true) / `aiAgentExperienceMaxBytes` (默认 1 MB) / `aiAgentExperienceRecallTopN` (默认 3)。
- 清理：删除 `AiAgentPlanner.encodeHistoryForDebug` 调试残骸 + `VoiceCommandService.appendStepRecord` 占位空 lambda 等无效预留代码（详见 `commit 11c2176`）。

### 2026-05-14 · `inlineAdapter` 引用 capture 陷阱修复 + 设计文档统一

- **`inlineAdapter + by lazy + var data` 陷阱**修复：`AiExperienceBookDialog` 第一次打开就显空，根因是 `inlineAdapter` 内部 closure 把传入瞬间的 list 引用永久 capture，外部 var 重新赋值看不到。统一改成 `buildAdapter()` 函数 + 在 list 引用变化时重建 adapter；同套陷阱在 `TaskSnapshotSelectorDialog` / `TaskListDialog` 也修了。`InlineAdapter.kt` 顶部加 KDoc 防再犯，`aidoc/09 §10` 加专节说明。
- 文档同步对齐：`aidoc/01` / `02` / `05` / `06` / `07` / `09` / `11` / `12` / `13` / `14` / `15` / `16` / `19` / `20` / `README.md` 全部更新到与代码一致；删 `09` 重复 `## 9` + `09` "没有 CI" 过时句；修 `16 §13.4` vs `§13.7` 矛盾；修 `16 §13.8` 引用 AIDL 兼容策略错引为 `05 §7`（实际应是 `05 §4` + `09 §7`）；统一"agent 独立运行 / 跑完即丢 / 不为草稿服务"的二元化措辞为"三模块解耦架构"。

### 验证

- 每次大改后 `make debug` + `make install` 推到测试设备实跑过；agent 跑过抖音 / 小红书 / 京东等真实 App 任务。
- 经验本写入、召回、UI 浏览、转草稿四条链路实测通过。
- 通知功能在 vivo Android 16 实测可达。

## AI 接入前基线 / 2026-05-08

### 阶段定位

本阶段不引入新的用户功能，重点是为后续 AI 驱动开发收尾：项目文档、构建体验、CI、lint error、版权归属和语音入口已经整理到可继续演进的状态。

### 主要收尾

- 建立 `aidoc/13-todo.md` 记录未完成工程优化项，已完成项不再混入待办。
- 修正 README、LICENSE 和 App 关于页的派生项目归属说明，区分原始作品与 IanVzs 后续修改。
- 修正 IanVzs 新增语音功能文件的版权头，避免错误归属给原作者。
- 将签名配置命名从 `xjunz` 收敛为中性的 `custom`。
- 新增 `aidoc/14-ai-integration.md`，作为 AI 接入设计草案和后续讨论入口。
- 新增 `aidoc/15-ai-working-notes.md`，记录 AI 接入沟通纪要、方案转向理由和下一步工作锚点。

### 验证

- 最近一次代码收尾已执行 `make lint` 和 `make debug`，均通过。
- 当前仓库状态可作为 AI 接入新阶段的起点。

## 2.0.0 / versionCode 200

发布日期：2026-04-29

### 版本定位

`2.0.0` 是一次围绕语音控制能力、任务执行稳定性和开发体验的大版本升级。语音功能从主界面的一个入口按钮升级为独立底部导航分页，开始具备"输入 -> 理解 -> 匹配 -> 执行 -> 结果"的可观察状态流，为后续接入 AI 自动理解指令预留页面和数据结构。

### 主要更新

- 新增独立"语音"底部导航分页，主界面从 4 个一级 Tab 扩展为 5 个：已启用、常驻任务、单次任务、语音、更多。
- 新增语音控制中心页面，支持启动 / 停止语音监听、展示当前监听状态、最近识别文本、解析命令、匹配任务和执行记录。
- 扩展 `VoiceCommandService` 的 UI 状态输出，新增 `VoiceCommandUiState` 和倒序 `VoiceCommandRecord`，让识别、解析、匹配、执行成功 / 失败都能被页面观察。
- 语音页采用整体可滚动布局，执行记录会自然向下展开，避免被固定在一个屏幕高度内。
- 移除语音 mini FAB 和顶栏语音入口，避免遮挡底部导航或压缩标题区域。
- 恢复右上角服务按钮完整文案：`启动服务` / `停止服务`，并移除导致文字截断的宽度上限。

### 稳定性修复

- 修复部分不需要外部引用的条件在执行一次性任务时误报 `Argument 0 is not referring to anything!` 的问题。
- 优化 Shizuku debug 安装后的远程服务版本识别：debug 构建使用 APK 安装时间参与 UserService version，避免覆盖安装后仍跑旧服务进程。
- 新增 `make stop-service`，便于手动结束 Shizuku 远程服务进程排查旧代码问题。

### 构建与文档

- 将 App 版本集中到根目录 `gradle.properties`：
  - `APP_VERSION_CODE=200`
  - `APP_VERSION_NAME=2.0.0`
- `app/build.gradle.kts` 读取上述 Gradle property，debug 构建仍自动追加 `-debug`。
- 更新 UI 架构文档，明确主页一级导航采用 `activity_main.xml` 壳 + `BottomNavigationView` + `ViewPager2` + Fragment 的管理方式，禁止把具体 Tab 业务 UI 塞回主布局。
- 更新排障文档，补充 Shizuku 覆盖安装后旧服务进程的原因和处理方式。

### 验证

- 已执行 `./gradlew :app:assembleDebug`，构建通过。
- 已处理本轮新增代码和资源的 IDE/lint 诊断。

