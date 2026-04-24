# 05 · 服务层 / IPC / Bridges

本章覆盖 **AutomatorService 抽象**、**两种运行模式的实现类**、**控制器**、**AIDL 契约**、**Bridge 封装层**。

## 1. 抽象：`AutomatorService`

`app/src/main/java/top/xjunz/tasker/service/AutomatorService.kt`

```kotlin
interface AutomatorService {
    val uiAutomatorBridge: UiAutomatorBridge
    val eventDispatcher: MetaEventDispatcher
    val a11yEventDispatcher: A11yEventDispatcher
    val residentTaskScheduler: ResidentTaskScheduler
    val oneshotTaskScheduler: OneshotTaskScheduler
    val looper: Looper
    fun scheduleOneshotTask(task: XTask, callback: ITaskCompletionCallback)
    fun stopOneshotTask(taskId: Long)
    fun prepareWorkerMode()
    fun initEventDispatcher()
    fun acquireWakeLock() / fun releaseWakeLock()
    ...
}
```

`initEventDispatcher()` 注册默认事件源：

```kotlin
eventDispatcher.registerEventDispatcher(a11yEventDispatcher)
eventDispatcher.registerEventDispatcher(PollEventDispatcher(looper))
eventDispatcher.registerEventDispatcher(NetworkEventDispatcher())
// ClipboardEventDispatcher 当前被注释
eventDispatcher.addCallback(residentTaskScheduler)
eventDispatcher.addCallback(oneshotTaskScheduler)
```

## 2. 两种实现

### 2.1 `A11yAutomatorService`

`app/src/main/java/top/xjunz/tasker/service/A11yAutomatorService.kt`

- 继承 `AccessibilityService`，同时实现 Android hidden 的 `IUiAutomationConnection`
- 运行在 **App 主进程**
- 核心依赖：
  - `A11yUiAutomatorBridge`（A11y 输入路径）
  - `LocalTaskManager`（本地存储任务）
  - `A11yEventDispatcher`（消费 accessibility event）
- 两种模式：
  - **Worker 模式**：跑任务（`prepareWorkerMode` + `FLAG_REQUEST_INSPECTOR_MODE = false`）
  - **Inspector 模式**：给悬浮检查器用（读层级结构）
- 状态：`RUNNING_STATE` / `LAUNCH_ERROR` 均为 `LiveData`

### 2.2 `ShizukuAutomatorService`

`app/src/main/java/top/xjunz/tasker/service/ShizukuAutomatorService.kt`

- 继承 `IRemoteAutomatorService.Stub`，作为 Shizuku 的 `UserService` 运行在**独立特权进程**
- App 侧持有一个 **proxy 构造器**：`ShizukuAutomatorService(remote)` 封装远端 binder
- 连接建立后：
  - `connect(acquireWakeLock, resultReceiver)`
  - `getTaskManager()` 返回 `IRemoteTaskManager`，`LocalTaskManager.setRemotePeer(rtm)` 把本地变更同步到特权端
  - `PrivilegedUiAutomatorBridge` + `PrivilegedTaskManager` 处理真正的自动化 + 任务执行
- 死亡时 `exitProcess(0)`，Shizuku 会在下次 bind 时重启

## 3. 控制器：`ServiceController`

`app/src/main/java/top/xjunz/tasker/service/controller/`

```
ServiceController<S>          （抽象：bind / stop / status / ServiceStateListener）
 ├── A11yAutomatorServiceController       （Accessibility 模式）
 └── ShizukuServiceController<S>           （Shizuku 公共基类：UserServiceArgs、死亡代理、超时）
      └── ShizukuAutomatorServiceController （具体；bindServiceOnBoot()、getTaskManager 同步）
```

- `Environment.serviceController` = 当前模式对应的 controller
- UI 层通过 `MainViewModel` 订阅 `ServiceStateListener` 更新按钮态 / 错误提示

## 4. AIDL 契约

AIDL 文件位于 `app/src/main/aidl/top/xjunz/tasker/`：

| 文件 | 角色 |
|------|------|
| `service/IRemoteAutomatorService.aidl` | 主服务 binder：连接、任务管理器获取、时间戳、oneshot 调度、wake lock、premium 路径同步、字体共享内存、销毁 |
| `task/runtime/IRemoteTaskManager.aidl` | 远程任务注册表：initialize / updateTask / removeTask / enableResidentTask / addOneshotTaskIfAbsent / snapshots / logs / pause 监听 |
| `task/runtime/ITaskCompletionCallback.aidl` | oneshot 任务结束回调 `onTaskCompleted(boolean)` |
| `task/runtime/IOnTaskPauseStateListener.aidl` | 暂停状态变化 `onTaskPauseStateChanged(long checksum)` |

另外 `hidden-apis/src/main/aidl/` 包含 Android 系统侧 AIDL 副本（`IUiAutomationConnection` 等）。

**修改 AIDL 时务必**：

1. 两端（App + 特权进程）代码同步编译
2. 考虑旧版本 App 与新版本 Shizuku 服务共存时的兼容性（通常通过新增方法而非修改签名）
3. 更新 `03-engine-applet-model.md` / 本文件的 IPC 表

## 5. Bridges（`app/src/main/java/top/xjunz/tasker/bridge/`）

所有对 **Android 系统** / **hidden API** / **UiAutomator** 的调用都要经过 bridge，保证 **@Local / @Privileged / @Anywhere 分叉**。

| 类 | 作用（含进程标注） |
|----|---------------------|
| `ContextBridge` | 在 App 与特权进程里都能拿到可用 `Context`（`ActivityThread` 路径 / hidden API） |
| `ContextUiAutomatorBridge` | `CoroutineUiAutomatorBridge` 基类：从 Context 取 display / 电源 / fling 速度 / launcher 包名 |
| `A11yUiAutomatorBridge` | 搭配 `A11yInteractionController` / `A11yGestureController` |
| `PrivilegedUiAutomatorBridge` | 搭配 `PrivilegedInteractionController` / `PrivilegedGestureController` |
| `PackageManagerBridge` | 包查询 / launcher 解析；特权下用 `SystemServiceHelper` |
| `ActivityManagerBridge` | 组件启动 / force-stop（系统 service binder 模拟 shell） |
| `DisplayManagerBridge` | Display size / rotation |
| `PowerManagerBridge` | `isInteractive` / Partial wake lock（worker 模式下防锁屏） |
| `ConnectivityManagerBridge` | NetworkCapabilities / SSID |
| `ClipboardManagerBridge` | 读写剪贴板（@Privileged 下有特殊实现） |
| `VibratorBridge` | VibrationEffect 播放 |
| `BatteryManagerBridge` | 充电 / 电量百分比 |
| `OverlayToastBridge` | `TYPE_APPLICATION_OVERLAY` 悬浮 Toast（进程感知） |
| `ThemedWindowContext` | 特权进程下 `ContextThemeWrapper` 给 overlay 注入默认 token |

### Bridge 的 Shizuku vs A11y 分叉模式

```
UiAutomatorBridge（抽象，来自 ui-automator 模块）
    └── CoroutineUiAutomatorBridge（coroutine-ui-automator）
          └── ContextUiAutomatorBridge（app/bridge）
                ├── A11yUiAutomatorBridge → A11yInteractionController / A11yGestureController
                │                             └── 通过 AccessibilityService.dispatchGesture
                └── PrivilegedUiAutomatorBridge → PrivilegedInteractionController / PrivilegedGestureController
                                                     └── 通过 UiAutomation.injectInputEvent
```

`Environment.uiAutomatorBridge` 会根据当前模式动态返回其中之一。

## 6. 事件分发器（`app/src/main/java/top/xjunz/tasker/task/event/`）

| 分发器 | 事件类型 | 触发源 |
|--------|----------|--------|
| `MetaEventDispatcher` | 聚合器，管理所有子分发器 | 供 service 侧注册 |
| `A11yEventDispatcher` | `EVENT_ON_PACKAGE_ENTERED/EXITED`、`CONTENT_CHANGED`、`NEW_WINDOW`、`NOTIFICATION_RECEIVED`、`TOAST_RECEIVED` | 无障碍事件流；会忽略自家包、可选 6h 自动停机（非 premium） |
| `PollEventDispatcher` | `EVENT_ON_TICK` | 每 1000 ms，uptime 整秒边界附加 |
| `NetworkEventDispatcher` | 网络 / Wi-Fi 事件 | `ConnectivityManager` 回调 |
| `ClipboardEventDispatcher` | `EVENT_ON_PRIMARY_CLIP_CHANGED` | **当前已注释** |

## 7. Shizuku / Refine / 隐藏 API 引用

### `hidden-apis`

| 包 | 典型类 |
|----|--------|
| `android.app` | `UiAutomationHidden`, `IActivityManager`, `ActivityThread`, `ContextImpl`, `UiAutomationConnection` |
| `android.content` | `ContextHidden`, `IClipboard` |
| `android.content.pm` | `IPackageManager` |
| `android.hardware.input` | `IInputManager` |
| `android.accessibilityservice` | `AccessibilityServiceHidden`（+ AIDL 副本） |
| `android.os` | `UserHandleHidden`, `IBinderHidden`, `BatteryManagerHidden` |
| `android.view` | `WindowManagerImpl` |

`@RefineAs` 把 stub 绑到平台真实类。`BatteryManagerHidden` 目前 `@RefineAs(BatteryManagerHidden.class)` 为 **疑似 bug**（应为 `BatteryManager.class`）。详见 `10-troubleshooting.md`。

### `LSPosed HiddenApiBypass`

`App.onCreate()` 中在 P+ 调用 `HiddenApiBypass.addHiddenApiExemptions("")` 开启运行期豁免。

### Shizuku SDK

- `Sui.init(applicationId)` 用于融合 Shizuku Manager
- `Shizuku.bindUserService(...)` 启动 `:service` 进程
- `ShizukuProvider` 放在 manifest 中处理授权

## 8. 开机自启（`autostart/`）

- `AutoStarter`（`Receiver`，`enabled=false` 默认关）：
  - 订阅 `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED`
  - 等 Shizuku binder 可用 → `ShizukuAutomatorServiceController.bindServiceOnBoot()`
- `AutoStartUtil`：通过特权 `IPackageManager` 开关 **Shizuku Manager** 与 **AutoTask** 自启
- Premium 相关：开启常驻自启需 premium

## 9. 崩溃与上报

- `GlobalCrashHandler`（`ui/outer/GlobalCrashHandler.kt`）：
  - 替换 `Thread.setDefaultUncaughtExceptionHandler`
  - 收集 stack + 日志 → 跳转 `CrashReportActivity`
  - 通过 `ShizukuAutomatorServiceController.stopService()` 停止特权端
- `AppCenter` 在 **release** 构建才启用（Analytics + Crashes），详见 `App.onCreate()`

## 10. 典型连接流程（Shizuku）

```
用户点击 MainActivity 的"启动服务"
  → ServiceStarterDialog 选 Shizuku
  → MainViewModel.requestBindService()
  → ShizukuAutomatorServiceController.bindService()
  → Shizuku.bindUserService(UserServiceArgs(ShizukuAutomatorService).processNameSuffix("service"))
  → 系统启动 :service 进程 → 实例化 ShizukuAutomatorService()
    - 构建 UiAutomationHidden / PrivilegedUiAutomatorBridge / PrivilegedTaskManager
  → IBinder 回到 App 进程
  → 创建 ShizukuAutomatorService(remote) 代理
  → controller.onServiceConnected(remote)
      → remote.connect(true, resultReceiver)
      → remote.getTaskManager() → IRemoteTaskManager
      → LocalTaskManager.setRemotePeer(rtm)（同步所有已启用 resident task）
  → Environment.currentService 可用 → UI 转 Running
```

## 11. 典型连接流程（Accessibility）

```
用户点击"启动服务"（A11y 模式）
  → ServiceStarterDialog
  → 打开 Android 系统"无障碍"设置
  → 用户启用 "AutoTask"
  → 系统绑定 A11yAutomatorService（app 进程内）
      - prepareWorkerMode()
      - initEventDispatcher()
      - RUNNING_STATE.postValue(true)
  → UI 观察到 LiveData → 转 Running
```

## 12. 关键源码引用

- `app/src/main/java/top/xjunz/tasker/service/**`
- `app/src/main/java/top/xjunz/tasker/bridge/**`
- `app/src/main/java/top/xjunz/tasker/task/event/**`
- `app/src/main/aidl/**`
- `hidden-apis/src/main/java/**`
- `coroutine-ui-automator/src/main/java/top/xjunz/tasker/uiautomator/**`
- `App.kt:58-74`（初始化顺序）
- `AndroidManifest.xml`（组件声明 + 权限）
