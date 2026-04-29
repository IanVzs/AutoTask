# 01 · 项目概述

## 项目身份

- **名字**：AutoTask / 自动任务（Gradle rootProject: `自动任务`）
- **包名 / applicationId**：`top.xjunz.tasker`
- **类型**：开源 **Android 任务自动化 / 无代码自动点击** 工具
- **协议**：Apache-2.0
- **上游仓库**：[xjunz/AutoTask](https://github.com/xjunz/AutoTask)（当前 git remote `origin = git@github.com:IanVzs/AutoTask.git` 为派生仓库）
- **当前版本**：`2.0` / versionCode `18`（见根目录 `gradle.properties`）
- **最低 / 目标 SDK**：`minSdk 24` / `targetSdk 34` / `compileSdk 34`
- **签名配置**：需要在根目录提供 `local.properties`（`storeFile`/`storePassword`/`keyAlias`/`keyPassword`）

## 一句话定位

通过 **事件驱动 + 规则树 (Flow/Applet)** 让用户在不写代码的情况下，在两种运行模式下（**Shizuku 特权模式** 或 **辅助功能模式**）自动化执行 Android 界面操作、系统动作、Shell、文件、网络、时间等任务。

## 产品核心特性（用户视角）

| 特性 | 说明 |
|------|------|
| **双运行模式** | Shizuku（privileged 进程，通过 `UiAutomation` + hidden API，功能最全）与 Accessibility（无 root，通过无障碍服务 + `dispatchGesture`） |
| **常驻任务 + 一次性任务** | `TYPE_RESIDENT`（常驻事件驱动）/ `TYPE_ONESHOT`（手动触发） |
| **规则树式可视化编辑器** | 由 `Flow` 容器（`When` / `If` / `Do` / `ElseIf` / `Else` / `WaitFor` / `WaitUntil` / `Repeat` 等）组合 `Applet` |
| **组件检查器（悬浮检查器）** | 查看当前界面的 Accessibility 节点树、复制 id/text/bounds，并录制手势 |
| **手势录制 & 回放** | 记录多指路径 → 作为 `GestureAction` 的数据源 |
| **事件触发种类丰富** | App 前/后台、窗口变化、内容变化、通知、Toast、时间 tick、文件创建/删除、Wi-Fi、网络可达 |
| **动作库丰富** | 全局按键、锁屏、截图、旋转、UI 点击/长按/输入/拖动/滑动/滚动、打开 App / Activity、Intent、Shell、文件操作、剪贴板、Toast、振动 |
| **多值参数 + 引用机制** | Applet 支持多 value 与 referent，可在后续 Applet 中引用前面 Applet 的输出 |
| **任务导入导出** | `.xtsk` 单任务文件、`.xtsks` 打包文件（ZIP of JSON），支持 `xtsk://` 深链 |
| **任务预设 & 示例** | `app/src/main/assets/presets.xtsks`、`examples.xtsks` |
| **快照 / 调试跟踪** | 每次执行生成 `TaskSnapshot`，编辑器"轨迹模式"可回放成功/失败分支 |
| **付费 / 白嫖版切换** | `PremiumMixin`，当前源码中 `upForGrabs = true` 时可免费使用 premium 功能 |
| **主题 & 国际化** | Material 3；**目前 `res/values*` 仅 `values` + `values-night`**（单语中文，无 i18n 资源目录） |
| **开机自启** | `AutoStarter` 广播 + Shizuku Manager 自启链路，付费项 |

## 运行模式对比

| 维度 | Shizuku 模式 | 辅助功能模式 |
|------|--------------|--------------|
| 需要 root | 否（Shizuku 自身可能需要） | 否 |
| 进程 | **独立特权进程**（`:service`） | **App 进程内** AccessibilityService |
| 输入注入 | `UiAutomation.injectInputEvent`（原始 MotionEvent） | `AccessibilityService.dispatchGesture` |
| 截图 | 支持（API P+） | 支持（P+ 能力依赖系统） |
| 特权动作（Force stop、启用/禁用包、shell、文件操作、SSID 读取、setRotation） | ✅ | ❌（部分降级 / 不可用） |
| 稳定性 | 进程崩溃不拖累 App | 服务崩溃会影响 App |
| 初始化 | `Shizuku.bindUserService(...)` → AIDL binder | 用户在系统设置启用 |

> 大量 Applet Option 被标记 `shizukuOnly = true`，切到 A11y 模式会自动灰显或拒绝。

## 技术栈与关键依赖

| 组件 | 版本 / 备注 |
|------|-------------|
| Kotlin | 1.8.21 |
| Android Gradle Plugin | 8.1.2 |
| Coroutines | kotlinx 1.7.3 |
| Serialization | kotlinx 1.6.21（JSON，`XTaskDTO` / `AppletDTO` 序列化） |
| Ktor | 2.3.5（`api.Client` HTTP） |
| Shizuku API | 13.1.5（`dev.rikka.shizuku:api` / `:provider`） |
| dev.rikka.tools.refine | 4.4.0（隐藏 API 编译期替换） |
| LSPosed HiddenApiBypass | 4.3（运行期绕过非 SDK 接口限制，Android P+） |
| androidx.appcompat | 1.6.1 |
| Material | 1.10.0 |
| Lifecycle | 2.6.2 |
| AppCenter（崩溃 + 分析） | 5.0.2（release 构建才启用） |
| AppIconLoader | 1.5.0（`me.zhanghai.android.appiconloader`） |
| DataBinding / ViewBinding / AIDL | 全部开启 |
| Java / Kotlin 目标 | Java 18 |

**代码规模（当前快照）**：

- 总 `.kt` 文件：**约 330** 个
- 总 Kotlin 代码行：**约 31,900** 行
- `app` 模块 `.kt`：**244** 个 / 约 38,000 行
- AIDL 文件：**4** 个（见 `05-services-and-ipc.md`）
- 布局 XML：**74** 个

## 模块一览（详见 `02-architecture.md`）

```
AutoTask/
├── app/                      // 主工程：UI / 服务 / 桥 / 具体 Applet / 存储
├── tasker-engine/            // 纯领域引擎：Applet、Flow、XTask、Runtime、DTO
├── ui-automator/             // 基于 androidx.test.uiautomator 2.2.0 的魔改分支（Java）
├── coroutine-ui-automator/   // 为 ui-automator 提供 Kotlin 协程包装，双后端
├── shared-library/           // 公用 Kotlin 工具（ktx、日志、MD5、Parcel、OsUtil）
├── hidden-apis/              // compileOnly 隐藏 API + 相关 AIDL
├── ssl/                      // 原生库 libssl：AES-CBC/PKCS7 加解密（非 TLS）
└── gradle/ .idea/ ssl 构建产物…
```

## 支持范围（对用户暴露的能力边界）

参看 `04-feature-catalog.md` 的 **功能矩阵**。概要：

- **触发器 / 事件**：9 种窗口/应用/通知/时间事件 + 文件 + Wi-Fi + 网络；
- **条件 / Criterion**：14 个 Registry，涵盖 App、UI 节点（文本/属性/布局/几何）、时间、设备、文本、网络；
- **动作 / Action**：9 个 Registry，涵盖全局系统按键、UI 节点交互、坐标手势、Shell、文件、App 启动与管理、文本处理、控制流、振动；
- **不支持 / 非内置**：HTTP 请求动作（仅 App 更新检查内部使用）、OCR、数据库、第三方云同步。

## 项目已知边界与注意事项

- `values` 资源当前是**中文 + 默认**，未做英文 / 多语言。i18n 改造需引入 `values-en/` 等并迁移所有 `strings.xml` 键。
- `BatteryManagerHidden` 的 `@RefineAs` 看起来是 **bug**（自指），见 `10-troubleshooting.md`。
- `tasker-engine/build.gradle` 声明了 `implementation ':ui-automator'` 但 main 源码未引用，疑似历史遗留。
- `ssl` 模块的 "ssl" 命名 **有误导**：实际上是 AES 加解密 JNI，用于混淆保护 Premium/API。
- `ClipboardEventDispatcher` 代码存在但在 `AutomatorService.initEventDispatcher` 中被注释，事件 `EVENT_ON_PRIMARY_CLIP_CHANGED` 可能不会被触发。
- 并发执行常驻任务数量非付费用户被限制在 3 个（`ResidentTaskScheduler`）。
