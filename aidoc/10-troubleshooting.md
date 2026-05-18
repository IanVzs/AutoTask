# 10 · 已知坑点 / 排障入口

## 1. 已知代码级问题（源码扫描发现）

| 问题 | 文件 / 位置 | 影响 | 建议 |
|------|-------------|------|------|
| `BatteryManagerHidden` 的 `@RefineAs` 自指 | `hidden-apis/src/main/java/android/os/BatteryManagerHidden.java` | 实际运行时 refine 可能不生效（取决于 AGP/Refine 解释） | 改为 `@RefineAs(BatteryManager.class)` |
| `pauseUntilTomorrow` 与 `pauseFor` 共享 `@AppletOrdinal(0x0020)` | `app/.../task/applet/option/registry/ControlActionRegistry.kt` | UI 排序紊乱；`appletId` 由字段 hash 生成理论上不冲突，但分类 / 顺序乱 | 给其中一个换一个唯一 ordinal |
| `ClipboardEventDispatcher` 在 `AutomatorService.initEventDispatcher` 被注释 | `app/.../service/AutomatorService.kt` | `EVENT_ON_PRIMARY_CLIP_CHANGED` 永远不会被分发，UI 上对应选项也屏蔽 | 如需恢复请同时开启 Registry 对应字段 |
| `res/xml/automator_service.xml` 的 `android:settingsActivity` 指向 `top.xjunz.tasker.main.MainActivity`（实际类在 `top.xjunz.tasker.ui.main.MainActivity`） | `automator_service.xml` | 系统的"无障碍服务设置"入口不可用；不会崩溃 | 改为正确 class 名 |
| `tasker-engine/build.gradle` 声明 `implementation ':ui-automator'` 但 main 源码未引用 | `tasker-engine/build.gradle` | 编译时多拉一个模块；无 runtime 影响 | 可移除 |
| `ID_NOTIFICATION_CRITERION_REGISTRY = 0x14` 在 Bootstrap 中声明但无对应 Registry 类 | `BootstrapOptionRegistry.kt` | 预留，不影响功能 | 实现后记得同步 `04-feature-catalog.md` |
| 非 Premium A11y 模式 6 小时自动停机 | `A11yEventDispatcher` | 用户体验不明确 | UI 上应显式提示 |
| 非 Premium 常驻任务 > 3 触发 `exitProcess(-1)` | `ResidentTaskScheduler` | 直接结束进程，像崩溃 | 友好提示后再退或改为 `suppress` |

## 2. 常见运行态问题

### 2.1 启动后点"启动服务"但一直转圈

- **Shizuku 模式**
  - 检查 `Shizuku Manager` 是否已授权 AutoTask（打开 Shizuku Manager 查看"应用列表"）
  - 看 `ShizukuServiceController` 有无 `TimeoutException`
  - 特权进程崩溃会触发 `DeathRecipient` → `LAUNCH_ERROR` LiveData 带堆栈
  - 查看 logcat：`AutoTask` tag + 过滤 `ShizukuAutomatorService`
- **A11y 模式**
  - 检查系统设置 → 无障碍 → AutoTask 是否启用
  - Android 12+ 部分厂商对无障碍有"敏感权限"提示
  - 需悬浮窗权限（`Settings.canDrawOverlays`）用于检查器
  - `RUNNING_STATE` LiveData 的 false → 查 `LAUNCH_ERROR`

### 2.2 任务没按预期触发

排查路径（参考 `06-runtime-and-events.md`）：

1. **事件没产生**
   - 断点 `A11yEventDispatcher` / `PollEventDispatcher` / `NetworkEventDispatcher` 的 `dispatchEvents`
   - 注意 `A11yEventDispatcher` 会**忽略自家包**的事件
2. **事件产生但没分发**
   - `ResidentTaskScheduler.isSuppressed` 是否被置为 true？
   - `TaskManager.enabledResidentTasks` 是否包含目标 task？看任务是否处于启用态（文件名末位是 `1`）
3. **分发但 Flow 没进入**
   - Flow 里 relation gate：`Flow.applyFlow:36-48`，检查 `runtime.isSuccessful` 的取值流转
4. **进入但节点找不到**
   - UI 节点条件：在 **Inspector** 下确认节点有对应 id / text / class
   - `AccessibilityBridge` 压缩层级可能把一些 `View` 合并 / 丢弃

### 2.3 引用（`$xxx`）取不到值

- 确认上游 Applet 的 `referents[i] = "xxx"` 已设置
- 确认上游 Applet **成功执行**（引用注册依赖 `result.isSuccessful`）
- 静态检查（`flow.performStaticCheck()`）会报 `RootFlow.kt:20-44` 的引用未声明错误

### 2.4 任务导入失败

- 文件不是 UTF-8 JSON / ZIP ⇒ `XTaskJson.decodeFromStream` 抛
- checksum 校验失败 ⇒ `TaskStorage.verifyChecksum` 返 false
- 版本 > 当前 → 需要升级 App
- 版本 < 16 → 自动迁移；迁移失败看 `AppletFactoryUpdateHelper.checkUpdate`
- 打印：`logcat | grep AutoTask`

### 2.5 悬浮检查器空白 / 看不到节点

- Android 14+ 对 overlay + AccessibilityService 交互有限制
- 切到 Shizuku 模式通常更稳
- 确认 `automator_service.xml` 的 flag 里包含 `flagRetrieveInteractiveWindows`

### 2.6 动作执行没反应

- A11y `dispatchGesture` 在部分设备被限流；需要改用 Shizuku 注入 MotionEvent
- 某些目标 App 对无障碍事件做拦截（银行类），无法绕过
- `ActivityManagerBridge.forceStop` 需要 Shizuku + 特定 Android 版本

### 2.7 任务一启动就结束

- 检查 `When` 失败 → `runtime.shouldStop = true`
- 检查 `ElseStopship` 是否被执行（设计即中止）
- 检查 `stop current task` 动作

### 2.8 Shizuku 模式下任务编辑后不立即生效

- `LocalTaskManager.updateTask` 会通过 AIDL 同步到 `PrivilegedTaskManager`
- 如果 AIDL 某个方法抛 `RemoteException`，见 `logcat | grep RemoteTaskManager`
- 重启服务通常能恢复（右上角 toolbar）

### 2.9 覆盖安装后仍然跑旧代码 / 行号不对

**现象**

- 刚安装新的 debug APK，但运行记录里的异常堆栈仍指向旧源码行号。
- 修改了执行引擎或 Shizuku 服务相关代码后，主界面已是新包，单次任务仍表现为旧逻辑。
- `adb shell ps -A` 里还能看到 `top.xjunz.tasker:service`，即使已经 `am force-stop top.xjunz.tasker`。

**原因**

Shizuku 的 UserService 是独立的远程进程，进程名为 `top.xjunz.tasker:service`。覆盖安装 APK 不一定会杀掉这个远程进程；如果服务版本没有变化，Shizuku 可能继续复用旧进程，导致 App 进程和特权进程运行的代码版本不一致。

代码侧已在 `ShizukuAutomatorServiceController` 的 `UserServiceArgs.version(...)` 中为 debug 构建使用 APK 安装时间（秒级），避免同一个 `versionCode` 的 debug 包覆盖安装后复用旧服务；release 构建仍使用正常的 `BuildConfig.VERSION_CODE`。

**手动处理**

优先使用 Makefile：

```bash
make stop-service
make run
```

或直接用 adb：

```bash
adb shell pidof top.xjunz.tasker:service
adb shell kill <pid>
adb shell monkey -p top.xjunz.tasker 1
```

如果有多台设备，给 Makefile 指定设备：

```bash
make stop-service DEVICE=<adb设备序列号>
```

处理后重新在 App 内启动自动化服务，再复现任务。

### 2.11 反馈 AI 出错时，怎么把现场信息发给维护者？

两种途径任选：

**途径一：从 App 直接复制（用户自助）**

打开语音页 → 找到红色"AI 返回内容无法解析"等记录卡片 → 点击展开"AI 调用详情"对话框 → 点底部的 **「复制全部」** 按钮，即可把"标题 + 时间 + 简述 + 完整 prompt + 原始返回 + 诊断信息"打包到剪贴板。粘贴到聊天里即可。

如果只想要 AI 的原始返回（例如开发者只关心模型为什么吐了不合 schema 的 JSON），点 **「复制原始返回」** 即可。

**途径二：用 logcat 抓完整链路（推荐给开发者）**

所有 AI agent 链路的日志都走统一 tag `AiAgent`：

```bash
# 项目根目录下
make logs
# 或者直接：
~/Android/Sdk/platform-tools/adb logcat -s AiAgent:*
```

按 section 分类：

| section | 内容 |
|---------|------|
| `[planSession]` / `[planSession.prompt]` / `[planSession.response]` | 开场会话规划，prompt 长文本会自动按 ~3.5KB 分段输出 `[1/N] ...` |
| `[nextAction]` / `[nextAction.prompt]` / `[nextAction.response]` | 每步 next action 决策的 prompt + raw response + 解析后的动作 |
| `[snapshot]` | 每次屏幕抓取的 pkg / activity / 节点统计（不打节点详情，太长） |
| `[execute]` | 每个动作执行结果，含命中节点摘要 |
| `[session.start]` / `[session.end]` / `[session.outOfScope]` / `[session.denyCap]` / `[session.limit]` | session 生命周期与边界事件 |

实现见 `app/src/main/java/top/xjunz/tasker/ai/agent/AiAgentLog.kt`。

### 2.13 vivo / xiaomi / oppo 等 OEM 上 App 主进程 logcat 几乎全丢

很多国产 ROM 默认对第三方 App 的主进程 logcat 做激进过滤，跑 `adb logcat` 只能看到 selinux 警告和 GC JIT 系统消息，业务日志几乎全无（有时连 `:service` 特权进程也丢）。**症状**：调试时 `make logs` / `adb logcat -s AutoTask:*` 几乎看不到任何东西；常规 task 失败也找不到 task pipeline 异常。

替代方案：

1. **AI agent 链路**：日志统一走 `top.xjunz.tasker.ai.agent.AiAgentLog`，**同时**写文件镜像 `/sdcard/Android/data/top.xjunz.tasker/files/agent.log`，OEM 不会过滤这个文件。`adb pull` 取走即可（无需 root）。文件 4MB 上限，超出截头保尾。
2. **常规 task 链路**：每次任务跑一次生成一份 `TaskSnapshot` 挂在 `XTask.snapshots` 内存 deque（最多 10 份），App 进程不杀就还在。从主页 task 列表点对应任务行的"快照"图标按钮 → 弹出 `TaskSnapshotSelectorDialog` 选最近一次失败的快照 → 进 `FlowEditorDialog` 轨迹模式（红/绿标色，红色 = 失败 applet），点失败 applet 弹 `SnapshotLogDialog` 看 actual 值 + 异常堆栈。
3. 实在要看 logcat：换台没做主进程过滤的设备（Pixel / 小米开发者版等），或在系统设置开发者选项里翻"日志缓冲区"相关设置。

### 2.12 横屏 / 主题切换时 App 直接崩，堆栈最末是 `binder haven't been received`

**现象**

```
java.lang.RuntimeException: Unable to destroy activity {top.xjunz.tasker/.../MainActivity}:
    java.lang.IllegalStateException: binder haven't been received
    at rikka.shizuku.Shizuku.requireService(Shizuku.java:430)
    at rikka.shizuku.Shizuku.getVersion(Shizuku.java:526)
    at rikka.shizuku.Shizuku.unbindUserService(Shizuku.java:807)
    at top.xjunz.tasker.service.controller.ShizukuServiceController.unbindService(ShizukuServiceController.kt:164)
    at top.xjunz.tasker.ui.main.MainActivity.onDestroy(MainActivity.kt:445)
```

**触发条件**

- 横屏 / 系统主题切换 / 任何会触发 `MainActivity` 重建的 configChanges（项目 manifest 没禁用这些 configChanges 转发）。
- **同时** Shizuku 没装、未启动、未授权，或 binder 在生命周期里中途断开。

**根因**

`MainActivity.onDestroy()` → `serviceController.unbindService()` → 直接调 `Shizuku.unbindUserService(...)`。Shizuku 内部会先 `getVersion() → requireService()`，binder 不在就抛 `IllegalStateException`，一路冒泡。`stopService()` 同样路径，同样隐患。

**当前实现**

`ShizukuServiceController` 抽出一个 `safeUnbindUserService(remove)` 私有方法：

1. 先用 `Shizuku.pingBinder()` 廉价判断 binder 是否存在；不存在直接 return。
2. 真正的 `Shizuku.unbindUserService(...)` 调用包 `runCatching`，失败仅 `Log.w` 不抛。

`unbindService()` / `stopService()` 都改走这个 helper；附带把 `serviceInterface?.asBinder()?.unlinkToDeath(...)` 也用 runCatching 吞了 `NoSuchElementException`（重建时 deathRecipient 可能没注册过）。

> 不用 `ShizukuUtil.isShizukuAvailable`：那个 getter 还检查 `checkSelfPermission`，太严；卸载场景需要解绑同样要走过去，binder 活着就够了。

如果以后想从根上避免横屏重建，可以在 `MainActivity` 的 manifest 项加 `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden"`，但**会影响**资源切换 / Fragment 状态恢复，不是本次想做的事。

### 2.10 覆盖安装后启动服务报 `Got an invalid binder!`

**现象**

- 覆盖安装新版本（debug 或 release）后，第一次打开 App 启动 Shizuku 服务，崩溃日志或反馈邮件里出现：

  ```
  java.lang.RuntimeException: Got an invalid binder!
      at top.xjunz.tasker.service.controller.ShizukuServiceController$userServiceConnection$1.onServiceConnected(...)
  ```
- App 不会闪退；通常再点一次"启动服务"就能成功。

**原因**

Shizuku 的 `UserService` 通过 `UserServiceArgs.version(...)` 决定是否要重启远程 `:service` 进程。版本号变了（debug 用 `lastUpdateTime`、release 用 `versionCode`），Shizuku 会杀旧进程、起新进程；在新旧进程交接的瞬间，回调到 App 的 binder 可能已经是死的，`pingBinder()` 返回 false。

这是 Shizuku UserService 重启策略带来的固有时序问题，App 自身代码没有错。

**当前实现**

`ShizukuServiceController.userServiceConnection.onServiceConnected` 检测到 invalid binder 时，会：

1. 记录一条 `Got an invalid binder, auto retry #N` 警告日志。
2. 间隔 `INVALID_BINDER_RETRY_DELAY_MILLS`（默认 800ms）后自动重新 `bindService()`，相当于替用户再点一次"启动服务"。
3. 最多重试 `MAX_INVALID_BINDER_RETRY` 次（默认 2 次），仍失败才抛 `RuntimeException("Got an invalid binder after N retries")` 给 `listener.onError`，让 UI 报错给用户。
4. 成功连接后清零计数；`stopService` / `unbindService` 时取消正在等待的 retry。

调整重试参数请改 `ShizukuServiceController.MAX_INVALID_BINDER_RETRY` 和 `INVALID_BINDER_RETRY_DELAY_MILLS`；如果想在覆盖安装时减少触发概率，可以保留对 `stop-service` 的手动处理。

## 3. 构建 / 依赖问题

| 现象 | 原因 | 解决 |
|------|------|------|
| `FileNotFoundException: local.properties` | 没有配置签名 | 见 `08-build-config-premium.md` §1.1 |
| dProtect 依赖 404 | 未配置 GitHub Packages 凭据 / classpath 已注释 | `gpr.user` / `gpr.token`，或保持注释状态 |
| AppCenter release 构建崩 | AppCenter app secret 失效 | 替换 `App.kt:62` 的 UUID 或关闭 AppCenter |
| R8 把反射字段干掉 | `@AppletOrdinal` 字段被优化 | `app/proguard-rules.pro` 里保留 AppletOption / registry 反射路径 |
| Gradle 8.x + Java 18 问题 | JDK 版本 | 需要 JDK 17+ |

## 4. 调试工具箱

### 4.1 Logcat tag

- `AutoTask` — 全局
- 具体分发器 / 服务类名前缀，例如 `A11yEventDispatcher`

### 4.2 `shared-library/Logger.kt`

```kotlin
"some message".logcat()
"debug only".debugLogcat()
throwable.logcatStackTrace()
```

### 4.3 `TaskSnapshot` + DEBUG 构建

- DEBUG 构建下 Flow 节点启动 / 跳过 / 结束会写入 `snapshot.log`
- UI：任务列表 → 某任务 → 快照 → 日志

### 4.4 `Environment` 常用断点

- `Environment.currentService` —— 确认当前运行模式
- `Environment.uiAutomatorBridge` —— 当前 Bridge（Privileged vs A11y）
- `Environment.isPremium` —— 付费态

## 5. 崩溃上报分析（AppCenter）

- AppCenter 仅在 release 构建（见 `App.onCreate`）
- 崩溃 `Thread.defaultUncaughtExceptionHandler` 会被 `GlobalCrashHandler` 替换 → 转 `CrashReportActivity` + 分享

## 6. 贡献改动时的自查

- [ ] 没有改动已发布的 `Applet.id` / `AppletOrdinal`
- [ ] 新 AIDL 方法放在末尾、两端编译通过
- [ ] 新引入的 @Privileged / @Anywhere 注解已标
- [ ] `res/values/strings.xml` 有对应的 key
- [ ] `aidoc/04-feature-catalog.md` 或 `aidoc/05-services-and-ipc.md` 已同步
- [ ] 如改 UI 对话框层叠：`DialogStackManager` 行为测试

## 7. 参考：如何快速复现某个历史 bug

Git log 可见修复历史：

- `v1.1.1r01 / r02`：R8 问题
- `v1.1.2r01 / r02`：A11y 通知事件类型错误
- `v1.0.1r03`：允许更长文本值（`TEXT_FORMAT` 之类的）
- `v1.0.1r02`：文件解析失败时清理任务文件、不缓存 `rootInActiveWindow`

`git log --all --oneline -- app/src/main/java/top/xjunz/tasker/**` 可以追踪具体文件的修改节奏。
