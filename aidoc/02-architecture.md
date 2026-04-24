# 02 · 顶层架构

## 1. Gradle 模块依赖图

```
                                        ┌────────────────────────┐
                                        │ hidden-apis (Java)      │
                                        │ (compileOnly, @RefineAs)│
                                        └──────────▲─────────────┘
                                                   │ compileOnly
┌──────────────────────────┐    implementation    ┌─┴───────────────────────┐
│  app (主工程 / Android)    │────────────────────▶│ tasker-engine           │
│  top.xjunz.tasker         │                     │ (纯 JVM Kotlin / Android)│
└─┬─┬─┬─┬──────────────────┘                     └─────▲──────▲─────────────┘
  │ │ │ │                                               │      │
  │ │ │ │ implementation                                │ impl │ impl
  │ │ │ └──────────────────────────▶  coroutine-ui-automator   │
  │ │ │                                 │ api ':ui-automator'  │
  │ │ │                                 ▼                      │
  │ │ │                              ui-automator (Java)       │
  │ │ │                                                         │
  │ │ └───── implementation ─────▶   shared-library ◀───────────┘
  │ │
  │ └──────── implementation ─────▶   ssl（libssl.so 原生库）
  │
  └────── compileOnly ────────────▶   hidden-apis
```

- `app` 是唯一 `com.android.application`；其余全部 `com.android.library` / `java-library`。
- `hidden-apis` **只在编译期**注入，避免打入 APK（通过 `dev.rikka.tools.refine`）。
- `tasker-engine` **不依赖 Android 高层 SDK**，保持可测试、可反射、可在特权进程中复用的特性。
- `coroutine-ui-automator` 是 `ui-automator` 的 Kotlin coroutine 包装并同时承载 **Shizuku + A11y 双后端**。
- `ssl` 虽然名字像 TLS，其实是 **AES-CBC/PKCS7 JNI** 加解密封装，用于 API / 授权保护。

**关键事实**：`tasker-engine/build.gradle` 声明 `implementation ':ui-automator'`，但该模块 `main` 源码中未发现对 `androidx.test.uiautomator.*` 的引用——当前是冗余依赖。

## 2. 进程模型

App 运行时会涉及**最多两个进程**：

| 进程 | 进程名 | 启动方 | 代码入口 |
|------|--------|--------|----------|
| **App 主进程** | `top.xjunz.tasker` | 用户启动 MainActivity；或 Boot 广播（需 Shizuku 就绪） | `App.kt` (`App : Application`) |
| **Shizuku 特权进程** | `:service`（见 `ShizukuServiceController.UserServiceArgs`） | `Shizuku.bindUserService(...)` | `ShizukuAutomatorService` 作为 `UserService` 启动 |
| （替代）辅助功能服务 | 跑在主进程 | 系统在用户启用无障碍后 bind | `A11yAutomatorService : AccessibilityService` |

注意：**同一时刻只会激活一个自动化执行端**（Shizuku 或 A11y），由 `Preferences.operatingMode` 决定，`Environment.currentService` / `Environment.serviceController` 做分发。

`App.isAppProcess` / `App.isPrivilegedProcess` 在进程启动时根据是否有 `App.instance` 判定（特权进程没有 Android Application 启动过程）。

### 进程注解

- `@Local`：仅主进程可用
- `@Privileged`：仅特权进程可用
- `@Anywhere`：两处都必须能用 —— **必须兼容 `Context` 缺失、hidden API 调用路径分叉**

源码位于 `app/src/main/java/top/xjunz/tasker/annotation/`。改 `service/` / `bridge/` / `task/runtime/` 代码前务必检查注解。

## 3. 运行时核心主干（app 模块）

```
┌────────────────────────────────────────────────────────────────────────┐
│                          App.kt  /  Preferences.kt                     │
└──────────────────────────────▲─────────────────────────────────────────┘
                               │
┌──────────────────────────────┴─────────────────────────────────────────┐
│                             Environment.kt                             │
│   currentService / uiAutomatorBridge / serviceController / isPremium   │
└───┬──────────────────────────▲───────────────────────────▲─────────────┘
    │                          │                           │
    ▼                          ▼                           ▼
┌────────────┐  ┌────────────────────────────┐  ┌────────────────────────┐
│ UI 层 /    │  │ service/controller/*       │  │ task/runtime/*         │
│ ui/**       │  │ (ServiceController<S>)     │  │ LocalTaskManager       │
│ Fragments  │  │  ├─ ShizukuServiceController│  │ ResidentTaskScheduler  │
│ Dialogs    │  │  └─ A11yAutomatorServiceCtl │  │ OneshotTaskScheduler   │
└────────────┘  └────────────┬───────────────┘  └────────┬───────────────┘
                             │ bind                       │ schedules
                             ▼                            ▼
                   ┌──────────────────────┐   ┌──────────────────────────┐
                   │ AutomatorService     │   │ tasker-engine.XTask      │
                   │ (抽象: 两模式共用)     │   │ RootFlow / Applet 树      │
                   └───────▲──────────────┘   └──────────┬───────────────┘
                           │                             │ apply()
      ┌────────────────────┴───────────┐                 ▼
      │                                │       TaskRuntime (coroutines)
┌─────┴─────────────────┐   ┌──────────┴──────────────┐
│ A11yAutomatorService  │   │ ShizukuAutomatorService │
│ (app 进程 / A11y)      │   │ (独立特权进程 / AIDL)   │
│ - A11yEventDispatcher │   │ - IRemoteAutomatorService│
│ - A11yUiAutomatorBridge│  │ - IRemoteTaskManager    │
└─────────▲─────────────┘   └───────────┬─────────────┘
          │ 无障碍事件流                │ AIDL binder
          │                             ▼
          │                   ┌──────────────────────┐
          │                   │ PrivilegedTaskManager │
          │                   │ PrivilegedUiAutomatorBridge│
          │                   └──────────────────────┘
          ▼
   bridge/** + coroutine-ui-automator
```

主干要点：

1. UI 层通过 `Environment.currentService` 获取当前激活的 `AutomatorService` 实现，调用 `scheduleOneshotTask` 等 API。
2. `AutomatorService` 聚合 **事件分发器集合**、**两个调度器**（常驻 / 一次性）、**UiAutomatorBridge**。
3. 事件流进入 `EventDispatcher.Callback`（常驻调度器 + 一次性调度器），调度器拉取命中任务的 `XTask`，调用 `obtainTaskRuntime` 后启动协程跑 `RootFlow.apply`。
4. 执行端对 UI / 系统的操作全部经 `bridge/**`（包装 hidden API、PackageManager、UiAutomator 等）。

详见 `05-services-and-ipc.md`、`06-runtime-and-events.md`、`03-engine-applet-model.md`。

## 4. 主要包（app 模块）

| 包（`top.xjunz.tasker.*`） | 作用 |
|-----------|------|
| `App.kt` / `Preferences.kt` | Application、全局偏好 |
| `annotation` | `@Local` / `@Privileged` / `@Anywhere` / `@FieldOrder` |
| `api` | Ktor HTTP 客户端、后端 DTO、加密头 |
| `autostart` | `AutoStarter` 开机广播 + Shizuku 管理器控制 |
| `bridge` | 对 Android 系统 & hidden API 的统一封装（见 `05`） |
| `ktx` | Kotlin 扩展（`IBinder.kt` 等） |
| `premium` | `PremiumMixin`（加密文件加载、`ensurePremium`） |
| `service` | `AutomatorService` 抽象 + Shizuku / A11y 具体实现 |
| `service.controller` | `ServiceController` 抽象 + 两个模式的控制器 |
| `task.applet` | 具体 Applet 实现（action / criterion / flow / value / option） |
| `task.editor` | 非 UI 编辑辅助（`AppletReferenceEditor`） |
| `task.event` | 事件分发器（`A11yEventDispatcher`、`PollEventDispatcher`、`NetworkEventDispatcher`、`ClipboardEventDispatcher`） |
| `task.gesture` | 手势录制 / 回放数据结构 |
| `task.inspector` | 悬浮检查器状态 + 悬浮窗 overlay |
| `task.runtime` | `LocalTaskManager` / `PrivilegedTaskManager` / 调度器 |
| `task.storage` | `TaskStorage`（`.xtsk` 读写、迁移、验校验和） |
| `ui.*` | 全 UI 层（见 `07-ui-architecture.md`） |
| `util` | 杂项工具 |

## 5. 选择切入点的速查表

| 任务 | 入口 | 参考章节 |
|------|------|----------|
| "加一个新动作" | `task/applet/option/registry/*ActionRegistry.kt` + （可选）`task/applet/action/` 新类 | `04` / `09` |
| "加一个新条件" | `task/applet/option/registry/*CriterionRegistry.kt` + 可选 `task/applet/criterion/` | `04` / `09` |
| "加一个新事件源" | `task/event/*EventDispatcher.kt` + `AutomatorService.initEventDispatcher` + `EventCriterionRegistry` | `06` / `09` |
| "加一个新 Flow（控制结构）" | `tasker-engine/.../applet/base/` 新 Flow 子类 + `BootstrapOptionRegistry` 注册 | `03` / `09` |
| "改 IPC 契约" | `app/src/main/aidl/**` + `ShizukuAutomatorService` + `PrivilegedTaskManager` | `05` |
| "改存储格式" | `XTaskDTO.kt` (引擎) + `TaskStorage.kt` + `AppletFactoryUpdateHelper.kt`（迁移） | `06` |
| "排查任务不跑" | `ResidentTaskScheduler.onEvents` / `XTask.launch` / `Flow.applyFlow` 断点 | `06` / `10` |
| "UI 上加一个参数编辑器" | `ui/task/selector/argument/` 新 Dialog + `AppletOption` 的 `VariantArgType` | `04` / `07` |
| "加付费检查" | `PremiumMixin.ensurePremium()` / `AppletOption.premiumOnly` | `08` |
