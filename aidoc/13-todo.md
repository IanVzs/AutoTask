# 13 · 待办

本文件只记录尚未完成的后续优化项；已完成的构建体验、CI、lint error 修复和低风险 warning 清理不再列入。

## 高优先级

1. **AI 接入第一阶段 MVP**
   - 范围：`app/src/main/java/top/xjunz/tasker/ai/`（待新增）、`voice/`、`ui/voice/`、`Preferences.kt`、`res/values/strings.xml`。
   - 目标：建立 AI Provider 抽象和 OpenAI-compatible 配置，让语音识别文本进入 AI 意图理解，输出“执行现有任务 / 创建任务草稿 / 需要授权的行动计划 / 需要澄清 / 无法理解”等受控结果。

2. **AI 行动计划与分级授权**
   - 范围：未来 `AiIntent`、`AiActionPlan`、`AiRiskAssessment`、`AiPermissionPolicy`、`AiDecisionRecord`。
   - 目标：把 AI 能力设计成分级自治：建议、草稿、确认执行、授权代理、托管任务。不同风险等级对应不同确认、授权和审计策略。

3. **Applet 能力元数据适配**
   - 范围：`AppletOption` / Registry、未来独立 AI capability 映射表。
   - 目标：为 AI 可生成/可自动执行能力补充风险等级、所需权限、敏感数据类型、是否需要每次确认等元数据，让 AI 深度复用现有 Applet 体系。

4. **AI 输出安全边界**
   - 范围：`tasker-engine` 的 `XTask` / `Applet` 模型、`app/task/applet/option/registry/`、未来 `AiTaskDraft` DTO。
   - 目标：AI 不能绕过现有执行管道；生成任务必须经 schema 校验、风险分级和用户确认。高风险动作必须每次确认，长期授权必须可查看、暂停和撤销。

5. **AI 隐私、成本与审计控制**
   - 范围：未来 AI 设置页、请求上下文裁剪、语音页请求节流。
   - 目标：默认只上传当前用户明确提交的文本；截图、节点树、任务列表、日志等敏感上下文必须单独授权，并加入请求频率限制、成本上限、失败回退和 AI 决策记录。

6. **密钥与服务配置外置**
   - 范围：`app/src/main/java/top/xjunz/tasker/api/Client.kt`、`app/src/main/java/top/xjunz/tasker/App.kt`。
   - 目标：更新接口 token、AppCenter secret 等不再硬编码在源码中，改为 `BuildConfig`、未提交的本机配置或 CI 注入。

7. **收敛明文网络访问**
   - 范围：`app/src/main/AndroidManifest.xml`、`api/Client.kt`。
   - 目标：默认关闭全局 `usesCleartextTraffic`，确有 HTTP 需求时用 `networkSecurityConfig` 仅放行指定域名。

## 中优先级

8. **处理 native 16KB page size 兼容**
   - 范围：`ssl` 模块和 APK 中的 native library。
   - 目标：确认 NDK/CMake 构建产物满足 Android 未来 16KB page size 设备要求。

9. **建立 lint warning baseline 或分批清理**
   - 范围：`make lint` 生成的 lint 报告。
   - 目标：先建立现有 warning 的 baseline，再让 CI 阻止新增 warning；`UnusedResources` 需要逐项确认，不直接批量删除。

10. **依赖升级评估**
   - 范围：`build.gradle.kts`、各模块 `build.gradle.kts`。
   - 目标：对 AndroidX、Material、Lifecycle、Coroutines、HiddenApiBypass 等依赖做兼容性测试后再升级。

## 低优先级

11. **统一 JVM 目标版本**
   - 范围：`app/build.gradle.kts` 与各 library 模块。
   - 目标：明确项目统一使用的 Java/Kotlin target，减少模块间工具链差异。

12. **重新设计崩溃处理委托**
   - 范围：`app/src/main/java/top/xjunz/tasker/ui/outer/GlobalCrashHandler.kt`。
   - 目标：评估是否保留自定义崩溃页，同时正确委托或替代系统默认 uncaught exception handler。
