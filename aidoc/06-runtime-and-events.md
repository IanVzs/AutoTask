# 06 · 运行时 / 事件 / 存储 / 快照

## 1. 运行时全链路

```
Android 系统事件 → Dispatcher → MetaEventDispatcher.dispatchEvents(Event[])
                                    │
             ┌──────────────────────┴───────────────────────┐
             ▼                                              ▼
   ResidentTaskScheduler.onEvents                 OneshotTaskScheduler.onEvents
   └── 从 TaskManager 取 **enabled resident**      └── 仅对 **当前运行中** 的 oneshot task 转发
       遍历匹配 → 为每个命中 task 启动 coroutine
                                    │
                                    ▼
                  XTask.launch(runtime = obtainTaskRuntime(task, events, factory))
                                    │
                                    ▼
                       RootFlow.apply(runtime)
                                    │
                                    ▼
                Flow.applyFlow 递归各子 Applet；更新 runtime.isSuccessful
                                    │
                                    ▼
                      SnapshotObserver 写快照
                                    │
                                    ▼
                   TaskStateListener.onTaskFinished
```

## 2. 事件系统

### 2.1 `Event`（`tasker-engine/.../engine/runtime/Event.kt`）

定义常量：

```
EVENT_ON_PACKAGE_ENTERED     = 1
EVENT_ON_PACKAGE_EXITED      = 2
EVENT_ON_CONTENT_CHANGED     = 3
EVENT_ON_NOTIFICATION_RECEIVED = 4
EVENT_ON_NEW_WINDOW          = 5
EVENT_ON_PRIMARY_CLIP_CHANGED = 6   (目前未激活)
EVENT_ON_TICK                = 7
EVENT_ON_TOAST_RECEIVED      = 8
EVENT_ON_FILE_CREATED        = 9
EVENT_ON_FILE_DELETED        = 10
EVENT_ON_WIFI_CONNECTED      = 11
EVENT_ON_WIFI_DISCONNECTED   = 12
EVENT_ON_NETWORK_AVAILABLE   = 13
EVENT_ON_NETWORK_UNAVAILABLE = 14
```

一个 `Event` 对象使用 **对象池**（`Event.obtain` / `recycle`），避免 GC。

### 2.2 分发器分层

```
MetaEventDispatcher
 ├── A11yEventDispatcher   — AccessibilityEvent → 1/2/3/4/5/8
 ├── PollEventDispatcher   — 系统时钟（1s） → 7
 ├── NetworkEventDispatcher— ConnectivityManager 回调 → 11/12/13/14
 └── (ClipboardEventDispatcher)  目前注释
```

- 每个分发器可以单独 `destroy()` 释放资源。
- `initEventDispatcher()` 里 **顺序很重要**：`a11yEventDispatcher` 一定先（UI 事件占比最高、优先级最高）。

### 2.3 `EventDispatcher` 抽象（引擎）

```kotlin
abstract class EventDispatcher {
    protected fun dispatchEvents(vararg events: Event) {
        callbacks.forEach { it.onEvents(events.casted()) }
    }
    abstract fun destroy()
    interface Callback { fun onEvents(events: Array<Event>) }
}
```

所有调度器（Resident / Oneshot）都是 Callback。

## 3. 任务调度器

### 3.1 `ResidentTaskScheduler`（app）

- 监听 `MetaEventDispatcher` 的 events
- 在 `onEvents` 内：
  1. 从 `TaskManager.enabledResidentTasks` 拿到所有**已启用**的常驻任务
  2. 非 premium 用户 → 最多 **3** 个常驻同时激活，超出 `exitProcess(-1)`
  3. 若 task 已在跑 → `task.onNewEvents(events)`
  4. 否则 `launch { obtainTaskRuntime ... }` 启动新协程
- 支持 `isSuppressed` 开关（UI 上"暂停常驻"）

### 3.2 `OneshotTaskScheduler`

- `scheduleTask(xtask, callback)` 跑单个任务
- `onEvents(events)` 只对**当前活跃**的 oneshot runtime 转发事件
- 任务结束通过 `ITaskCompletionCallback.onTaskCompleted(success)` 反馈

## 4. `TaskManager`

抽象在 `tasker-engine/.../task/TaskManager.kt`。两个实现：

### 4.1 `LocalTaskManager`（app 侧）

- 本地 `ArrayList<XTask>`
- **setRemotePeer(rtm)** 把 remote binder 接上：所有 `addTask / removeTask / enableResidentTask` 同步到 `IRemoteTaskManager`
- 作为 `XTask.Metadata.checksum → XTask` 的查找来源
- UI 通过 `LocalTaskManager.allTaskLiveData` 观察任务列表

### 4.2 `PrivilegedTaskManager`（特权进程）

- 仅持 `Map<Long checksum, XTaskDTO>`；**不在特权进程中构造完整 XTask 直到需要运行**
- `initialize(listOfDTO)` 批量初始化
- 跑任务时 `dto.toXTask(AppletOptionFactory, compatMode)` 反序列化
- 作为 `IRemoteTaskManager.Stub` 委托

## 5. 存储

### 5.1 文件布局

```
Context.getExternalFilesDir("xtsk")/
    ├── <checksum>0.xtsk         # 已禁用
    ├── <checksum>1.xtsk         # 已启用
    ├── <某任务>.pv16            # 版本迁移后的备份
    └── ...

Context.getExternalFilesDir("")/ PremiumMixin.PREMIUM_CONTEXT_FILE_NAME
```

assets 里：

- `app/src/main/assets/presets.xtsks` — 预置任务 zip
- `app/src/main/assets/examples.xtsks` — 示例任务 zip
- `app/src/main/assets/privacy-policy.html` — 隐私政策
- `index-cannon.html` / `index-continuous.html` — 自动点击器页面（WebView 内嵌）
- `confetti.js` — UI 动效

### 5.2 `TaskStorage`（`app/.../task/storage/TaskStorage.kt`）

| 方法 | 作用 |
|------|------|
| `loadAllTasks()` | 加载目录下全部 `.xtsk`，已启用的 → `LocalTaskManager.enableResidentTask` |
| `persistTask(xtask)` | `XTaskJson.encodeToString(dto)` → 写文件；文件名含 enabled flag |
| `unpersistTask(xtask)` | 删文件 |
| `readAssetsBundle(name)` | 从 `.xtsks` ZIP 中流读 JSON |
| `toXTaskPrevVersionCode16(...)` | 版本迁移，旧文件改名 `.pv16` |
| `verifyChecksum()` | 校验 metadata.checksum 与 flow 内容一致 |

**`.xtsk` 文件格式**：序列化的 `XTaskDTO` JSON；版本 >= 16 直接 `decodeFromStream<XTaskDTO>`。

**`.xtsks` 文件格式**：ZIP，内部每个 entry 是一个 `XTaskDTO` JSON。

### 5.3 序列化

- 使用 `kotlinx.serialization` + 自定义的 `XTaskJson`（配置 `ignoreUnknownKeys`、`prettyPrint`）
- `XTaskDTO` 按 `@SerialName` 精简字段名节省空间
- 旧版格式（version < 16）用单字段 `serialized` 的 blob，迁移时用 `toAppletPreVersionCode16`

## 6. 导入 / 导出 / 深链

### 6.1 `MainActivity` 的 `ACTION_VIEW` 处理

Manifest 绑定：

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <data android:scheme="xtsk" />
    <data android:scheme="file" android:host="*" />
    <data android:scheme="content" />
    <data android:pathPattern=".*\\.xtsk" />
    <data android:pathPattern=".*\\.xtsks" />
    ...
</intent-filter>
```

`MainActivity.handleImportTask(intent)`：

1. 分辨 `.xtsk` / `.xtsks`
2. 解析（单任务 / bundle）→ `TaskListDialog` 预览
3. 用户确认 → `TaskStorage.persistTask`

### 6.2 分享

- `BaseTaskShowcaseFragment` 里每个任务可单独分享
- `MainActivity` 处理 `ACTION_SEND`
- 使用 `androidx.core.content.FileProvider`（authority `${applicationId}.provider.file`，路径配置 `res/xml/file_paths.xml`）

## 7. 快照（调试 / 轨迹模式）

### 7.1 生成

- `XTask.launch` 开始时创建 `SnapshotObserver` 并绑到 `runtime.observer`
- `observer.onAppletStarted` / `onAppletTerminated` / `onAppletSkipped` 按 `AppletIndexer` 的 packed long 写 `TaskSnapshot.successes` / `failures` / `current`
- 每次启动一份新 `TaskSnapshot`

### 7.2 消费

- `LocalTaskManager.snapshotsOf(checksum)` → 最近 N 份
- UI：
  - `TaskSnapshotSelectorDialog` 选择
  - `SnapshotLogDialog` 看日志缓冲
  - `FlowEditorDialog` 的"轨迹模式"把当前 snapshot 绑到 `GlobalFlowEditorViewModel`，`FlowItemViewBinder` 渲染成功 / 失败 / 当前标色

### 7.3 容量限制

- 每个任务最多保留若干 snapshot（见 `LocalTaskManager` 的常量），过老会被丢弃
- DEBUG 构建 `SnapshotObserver` 还会写 `log` 缓冲

## 8. 暂停 / 恢复

`PauseFor` / `PauseUntilTomorrow` 改变 `XTask.pauseState`，通过 `IOnTaskPauseStateListener.onTaskPauseStateChanged(checksum)` 通知 UI。

## 9. 打断 / 取消 / 超时

| 场景 | 实现 |
|------|------|
| 用户点停止 | `XTask.halt()` → 取消 runtimeScope |
| `WaitFor` / `WaitUntil` 超时 | Flow 子类内处理（成功/失败区别） |
| App 全局退出 | `ResidentTaskScheduler.isSuppressed = true` + `GlobalCrashHandler` 断开特权服务 |
| 非 premium 6 小时 | `A11yEventDispatcher` 启动 auto-stop 计时 |
| 特权进程崩溃 | Shizuku 会解 binder 死亡 → controller 监听 deathRecipient → UI 转错误状态 |

## 10. 调试 / 观测入口

| 关注点 | 断点位置 |
|--------|----------|
| "任务为什么没触发" | `A11yEventDispatcher.dispatchEvents`、`ResidentTaskScheduler.onEvents`、`TaskManager.enabledResidentTasks` |
| "触发了但动作没执行" | `Flow.applyFlow:31-77` relation gate；检查 `runtime.isSuccessful` 每轮取值 |
| "引用取不到值" | `TaskRuntime.getReferentByName`、`registerResult`、上游 Applet 的 `referents` 配置 |
| "序列化报错" | `AppletDTO.toApplet` 的 `factory.createAppletById` 或 `deserializeArgumentFromString` |
| "任务启动慢 / 阻塞" | 关注是否在 Looper 线程做同步 UI 遍历；`UiAutomatorBridge.waitForIdle` 超时 |

## 11. 关键源码引用

- `app/src/main/java/top/xjunz/tasker/task/runtime/ResidentTaskScheduler.kt`
- `app/src/main/java/top/xjunz/tasker/task/runtime/OneshotTaskScheduler.kt`
- `app/src/main/java/top/xjunz/tasker/task/runtime/LocalTaskManager.kt`
- `app/src/main/java/top/xjunz/tasker/task/runtime/PrivilegedTaskManager.kt`
- `app/src/main/java/top/xjunz/tasker/task/event/*.kt`
- `app/src/main/java/top/xjunz/tasker/task/storage/TaskStorage.kt`
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/task/EventDispatcher.kt`
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/runtime/Event.kt`
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/task/TaskSnapshot.kt`
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/dto/XTaskDTO.kt`
