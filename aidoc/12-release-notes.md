# 12 · 发版说明

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

