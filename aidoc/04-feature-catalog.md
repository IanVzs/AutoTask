# 04 · 功能矩阵（Feature Catalog）

本章是**对用户暴露的功能清单**——所有事件 / 条件 / 动作 / 控制流 / 值类型的穷举，对应源码 Registry 文件。修改这些清单时，请同步更新本文件。

所有 Registry 实现文件位于：`app/src/main/java/top/xjunz/tasker/task/applet/option/registry/`

## 1. Registry 架构速览

- 每个 `AppletOptionRegistry` 子类有一个 `id`（高 16 位命名空间），作为 `Applet.id` 的高 16 位。
- Registry 内的 `AppletOption` 作为类的**可声明字段**，用 `@AppletOrdinal(hex)` 标注：
  - `@AppletOrdinal(0x00_02)`：**高字节 = 分类索引**（UI 上的分组 / category header），**低字节 = 组内顺序**。
- 反射扫描字段生成 `allOptions`，`appletId = field.hash & 0xFFFF`（除非显式指定）。
- **`AppletOptionFactory`** 聚合所有 Registry，按 id 分发。

### Registry 列表与 id 分配

| Registry | id | 中文（UI 分类名） | 主要职责 |
|----------|----|-----------------|----------|
| `BootstrapOptionRegistry` | `0x00` | 元 / Flow | Flow 类型（RootFlow / If / When / Do / ...）+ 指向其它 Registry 的"占位符" |
| `EventCriterionRegistry` | `0x0F` | 事件 | 事件触发器（前后台、窗口、通知、时间、文件、网络） |
| `ApplicationCriterionRegistry` | `0x10` | 应用 | 当前/指定 App、活动、窗口标题、系统/启动器判定 |
| `TimeCriterionRegistry` | `0x12` | 时间 | 时间范围 / 年月日 / 时分秒 / 一周几 |
| `GlobalCriterionRegistry` | `0x13` | 全局 | 横竖屏、充电、电量 |
| *(ID_NOTIFICATION_CRITERION_REGISTRY)* | `0x14` | 通知 | **常量存在但无对应类**（预留） |
| `TextCriterionRegistry` | `0x15` | 文本 | 两字符串比较 |
| `UiObjectCriterionRegistry` | `0x16` | UI 对象 | Accessibility 节点的类型 / id / 文本 / 属性 / 布局 |
| `NetworkCriterionRegistry` | `0x17` | 网络 | 网络可达 / Wi-Fi / 蜂窝 / SSID |
| `GlobalActionRegistry` | `0x50` | 全局动作 | 返回 / Home / 锁屏 / 截图 / 旋转 / 唤醒 |
| `UiObjectActionRegistry` | `0x51` | UI 对象动作 | 节点点击 / 长按 / 输入 / 滑动 / 拖拽 / 滚动 |
| `ControlActionRegistry` | `0x52` | 控制 / 循环 | If / Wait / Repeat / Break / Suspend / PauseFor |
| `ApplicationActionRegistry` | `0x53` | 应用动作 | 启动 / 强停 / 启用禁用 / View URI |
| `TextActionRegistry` | `0x54` | 文本 | 日志 / 正则提取 / 剪贴板 / Toast |
| `GestureActionRegistry` | `0x55` | 手势 | 屏幕点击 / 自定义手势 |
| `ShellCmdActionRegistry` | `0x56` | Shell | 执行 shell / 运行 .sh |
| `FileActionRegistry` | `0x57` | 文件 | 删除 / 复制 / 移动 |
| `VibrationActionRegistry` | `0x58` | 振动 | 振动波形 |
| `UiObjectFlowRegistry` | `0x60` | UI 对象流 | `ContainsUiObject` / `UiObjectMatches` |

> 改动 id 等于破坏旧任务文件兼容性（见 `03` / `06`）。

## 2. 事件（Triggers） — `EventCriterionRegistry`

对应底层事件常量在 `tasker-engine/.../engine/runtime/Event.kt`。当前被触发的事件：

| UI 字段 | Event 常量 | 来源 | 说明 / 主要 referent |
|---------|------------|------|----------------------|
| `pkgEntered` | `EVENT_ON_PACKAGE_ENTERED` | `A11yEventDispatcher` | 前台应用切换到某 app；referent: `ComponentInfoWrapper` |
| `pkgExited` | `EVENT_ON_PACKAGE_EXITED` | 同上 | 离开某 app |
| `contentChanged` | `EVENT_ON_CONTENT_CHANGED` | 同上 | 当前窗口内容变化 |
| `notificationReceived` | `EVENT_ON_NOTIFICATION_RECEIVED` | 同上（`TYPE_NOTIFICATION_STATE_CHANGED`） | referent: `NotificationReferent`（title/text/owner/owner app name） |
| `toastReceived` | `EVENT_ON_TOAST_RECEIVED` | 同上 | 弹 Toast |
| `newWindow` | `EVENT_ON_NEW_WINDOW` | 同上 | 新窗口 |
| `timeChanged` | `EVENT_ON_TICK` | `PollEventDispatcher` | 每 1000 ms 一次；uptime 整秒边界还会额外发 |
| `fileCreated` | `EVENT_ON_FILE_CREATED` | *（需 Shizuku，代码中 `FileEventCriterion` 已就绪）* | ★ 注意 `AutomatorService.initEventDispatcher` 未显式注册 file watcher，参见 `10` |
| `fileDeleted` | `EVENT_ON_FILE_DELETED` | 同上 | |
| `wifiConnected` | `EVENT_ON_WIFI_CONNECTED` | `NetworkEventDispatcher` | referent: SSID |
| `wifiDisconnected` | `EVENT_ON_WIFI_DISCONNECTED` | 同上 | |
| `networkAvailable` | `EVENT_ON_NETWORK_AVAILABLE` | 同上 | |
| `networkUnavailable` | `EVENT_ON_NETWORK_UNAVAILABLE` | 同上 | |
| *(Clipboard)* | `EVENT_ON_PRIMARY_CLIP_CHANGED` | `ClipboardEventDispatcher` | **当前在 `initEventDispatcher` 中被注释掉**，且 Registry 中项也被屏蔽 |

## 3. 条件（Criteria）

### 3.1 `ApplicationCriterionRegistry`（id=0x10）

- `isCertainApp`：当前 App 包名 == 目标
- `appCollection`：当前 App 包名 ∈ 集合
- `activityCollection`：当前 Activity ∈ 集合
- `paneTitle`：窗口 / pane title 匹配
- `isSystem` / `isLauncher`：系统 App / 桌面

### 3.2 `UiObjectCriterionRegistry`（id=0x16）

**类型 / id / 文本**：

- `isType`（class 预设列表）
- `withId`（view-id-resource-name）
- `textEquals` / `textStartsWith` / `textEndsWith` / `textContains` / `textPattern`（正则） / `textLengthRange`
- `contentDesc`（contentDescription 匹配）

**A11y 属性**：

- `isClickable` / `isLongClickable` / `isEditable` / `isEnabled` / `isCheckable` / `isChecked` / `isSelected` / `isScrollable`
- `childCount`（范围）

**布局 / 几何**（`BITS_BOUNDS` + `Distance` 复合类型，支持 dp/px/屏占比/父占比/横纵比）：

- `left` / `right` / `top` / `bottom`
- `width` / `height`

### 3.3 `TimeCriterionRegistry`（id=0x12） 目标是 `TimeFlow` 设置的 `Calendar`

- `timeRange`（毫秒绝对时间范围）
- `month` / `dayOfMonth`（0-based） / `dayOfWeek`
- `hourMinSec`（一天内时段范围）
- `isSpecifiedTime`（精确到秒）/ `isSpecifiedTimeInDay`（时分秒）
- `inSpecifiedHours` / `inSpecifiedMinutes` / `inSpecifiedSeconds`（白名单集合）

### 3.4 `GlobalCriterionRegistry`（id=0x13）

- `isScreenPortrait`
- `isBatteryCharging`
- `batteryCapacityRange`（0..100）

### 3.5 `TextCriterionRegistry`（id=0x15）（两字符串比较，**引用 + 字面量** 二元）

- `equalsTo` / `startsWith` / `endsWith` / `contains` / `matches`（正则）

### 3.6 `NetworkCriterionRegistry`（id=0x17）

- `isNetworkActive`
- `isWifiNetwork` / `isCellularNetwork`
- `currentWifiSsidIs`（**Shizuku**）

## 4. 动作（Actions）

### 4.1 `GlobalActionRegistry`（id=0x50）

| 字段 | 功能 | 限制 |
|------|------|------|
| `waitForIdle` | 等待 UI idle（两个区间参数） | — |
| `pressBack` / `pressRecents` / `pressHome` | 系统键 | — |
| `openNotificationShade` | 拉通知栏 | — |
| `lockScreen` | 锁屏 | API P+ |
| `takeScreenshot` | 截图 | API P+，**premium** |
| `setRotation` | 旋转屏幕 | **Shizuku** |
| `wakeUpScreen` | 点亮屏幕 | — |

### 4.2 `UiObjectActionRegistry`（id=0x51）

**快捷流 / 封装动作（带内置筛选）**：

- `clickIfExits` (`ClickUiObjectIfExists`) — 找到就点
- `clickButtonWithText` — 特定文本的 Button
- `clickUiObjectWithText` — 任意控件 + 文本
- `setTextToFirstTextField` — 在第一个可编辑节点输入
- `scrollIntoUiObject` — 滚动到可见
- `forEachUiScrollable` — 列表遍历（暴露 `currentChild` / `current_child_count`）

**原子动作**（需配合 UI 节点引用）：

- `click` / `longClick` / `drag` (到坐标) / `swipe`(`BITS_SWIPE`) / `setText`
- `scrollForward` / `scrollToEnd`（`BITS_SCROLL`）
- `getChildAt` — 按索引取子
- `drawNodeBounds` / `clearNodeBounds` — 调试用：在屏幕上画/清除选中节点外框
- 结果：`matched_ui_object` / 文本 / 中心坐标

### 4.3 `GestureActionRegistry`（id=0x55）

- `click` (`INT_COORDINATE`) / `longClick` (`INT_COORDINATE`)
- `performCustomGestures`（`TEXT_GESTURES` + `GestureAction`，结果为 gesture 对象）

### 4.4 `TextActionRegistry`（id=0x54）

- `logcatText`（格式 + 引用 → 悬浮日志）
- `extractText`（正则首组）
- `copyText`（剪贴板）
- `makeToast`（悬浮 Toast 实现，非系统 Toast）

### 4.5 `ApplicationActionRegistry`（id=0x53）

- `forceStopApp`（**Shizuku**）
- `launchApp` / `launchActivity`（premium）
- `viewUri`（`ACTION_VIEW`）/ `viewUriViaPackage`
- `disablePackage` / `enablePackage`（**Shizuku**）

### 4.6 `ControlActionRegistry`（id=0x52）

- 嵌套 `If` / `WaitUntil` / `WaitFor`
- `Suspension`（单次延时 `INT_INTERVAL`）
- `Repeat`（次数 + 当前迭代输出）
- `Break` / `Continue`
- 停止当前任务（写 `runtime.shouldStop`）
- 禁用当前任务 / 删除当前任务（落盘侧操作）
- `PauseUntilTomorrow`
- `PauseFor`（`BITS_LONG_DURATION`）

> ⚠️ 已知瑕疵：`pauseUntilTomorrow` 与 `pauseFor` 在源码里共享 `@AppletOrdinal(0x0020)`，会影响排序，见 `10-troubleshooting.md`。

### 4.7 `ShellCmdActionRegistry`（id=0x56） —— **Shizuku + Premium**

- `executeShellCmd`
- `executeShFile`

### 4.8 `FileActionRegistry`（id=0x57） —— **Shizuku**

- `deleteFile` / `copyFile` / `moveFile`

### 4.9 `VibrationActionRegistry`（id=0x58）

- `vibrate`（`TEXT_VIBRATION_PATTERN` 字符串 → `Vibrate.VibrationWaveForm`）；API 26+

## 5. 流控结构 — `BootstrapOptionRegistry`

| 字段 | 对应 Flow | 作用 |
|------|-----------|------|
| `rootFlow` | `RootFlow` | 顶层容器 |
| `preloadFlow` | `PreloadFlow` | 预加载 referents：currentComponent / packageName / label / rootInActiveWindow / focused input / currentTime |
| `whenFlow` | `When` | 事件触发 |
| `ifFlow` / `elseIfFlow` / `elseFlow` / `thenFlow` | `If` / `ElseIf` / `Else` / `Then` | 条件分支 |
| `doFlow` | `Do` | 动作容器 |
| `containerFlow` | `ContainerFlow` | 通用分组 |
| `waitUntilFlow` / `waitForFlow` | `WaitUntil` / `WaitFor` | 等待；含超时 |
| `anywayFlow` | `Anyway` | 不论前面如何都跑 |
| `elseStopshipFlow` | `ElseStopship` | 否则中止整棵任务（`runtime.shouldStop=true`） |

**PhantomFlow**（`app/.../task/applet/flow/PhantomFlow.kt`）：`editor-only` 占位，`appletId = 其它 Registry 的 id`，让编辑器从"添加事件/条件/动作"能进入对应 Registry 的选单。示例字段：`eventCriteria` / `appCriteria` / `uiObjectCriteria` / `timeCriteria` / `globalCriteria` / `textCriteria` / `networkCriteria` / `globalActions` / `uiObjectActions` / `gestureActions` / `textActions` / `appActions` / `controlActions` / `shellCmdActions` / `fileActions` / `vibrationActions` / `uiObjectFlows`。

## 6. `UiObjectFlowRegistry`（id=0x60）

- `containsUiObject` — 在给定子树（可选引用）里 DFS 首个满足子条件的节点；可倒置（"不存在"）
- `uiObjectMatches` — 对一个给定节点跑子条件

## 7. 值 / 参数类型

### `VariantArgType` 编码（`app/.../task/applet/value/VariantArgType.kt`）

| 常量 | 携带 |
|------|------|
| `BITS_SWIPE` | `SwipeMetrics`（Direction / 百分比 / 速度） |
| `BITS_SCROLL` | `ScrollMetrics`（Direction / 速度 → 步数） |
| `BITS_LONG_DURATION` | `LongDuration`（日/时/分/秒） |
| `BITS_BOUNDS` | `Distance`（scope / unit / nullable range） |
| `LONG_TIME` | 绝对时间（ms） |
| `INT_COORDINATE` | 打包的 `x|y` |
| `INT_INTERVAL` | 单个持续毫秒 |
| `INT_INTERVAL_XY` | 两段持续（`waitForIdle`） |
| `INT_QUANTITY` | 数量 / 范围 |
| `INT_ROTATION` | 旋转索引 |
| `INT_TIME_OF_DAY` / `INT_MONTH` / `INT_DAY_OF_MONTH` / `INT_DAY_OF_WEEK` / `INT_HOUR_OF_DAY` / `INT_MIN_OR_SEC` | 日历分量 |
| `INT_PERCENT` | 0..100 |
| `TEXT_PACKAGE_NAME` / `TEXT_ACTIVITY` / `TEXT_PANE_TITLE` / `TEXT_FILE_PATH` / `TEXT_GESTURES` / `TEXT_VIBRATION_PATTERN` | 文本专用编辑器 |
| `TEXT_FORMAT` | 格式字符串；**引用匹配时忽略变体** |

### 复合值类

| 类 | 作用 |
|----|------|
| `Distance` | scope(none/screen/parent) + unit(px/dp/比例) + nullable range |
| `SwipeMetrics` / `ScrollMetrics` | 方向 + 百分比 / 速度 |
| `LongDuration` | 四个小整数 |
| `BitwiseValueComposer` / `ValueComposer` | 把多个小字段打包进一个 Long |
| `IntValueUtil` | XY / 时分秒打包 |
| `NumberRangeUtil` | 闭区间、两端可空 |

## 8. 功能范围（快查总表）

| 类别 | 是否支持 |
|------|----------|
| 基于无障碍的点击 / 长按 / 滑动 / 输入 | ✅ |
| Shizuku 注入 MotionEvent（更精准） | ✅ |
| 录制手势 + 回放 | ✅（`GestureAction` / `GestureRecorderView`） |
| 事件驱动（前后台 / 通知 / 时间 / 文件 / 网络） | ✅ |
| 正则匹配 UI 文本 | ✅ |
| 复杂几何约束（屏占比 / dp / 父相对） | ✅ |
| Shell 命令 / 执行脚本文件 | ✅（Shizuku + Premium） |
| 文件操作（CRUD） | ✅（Shizuku；需权限） |
| 网络 SSID 读取 | ✅（Shizuku） |
| HTTP 请求作为自动化 Action | ❌（`api/Client.kt` 仅内部用） |
| OCR / 图像识别 | ❌ |
| 通知直接触发条件（按包名 / 文本过滤） | ★ 部分（事件端暴露 `NotificationReferent`，可用 TextCriterion 二次过滤；独立通知 Registry 预留未实现） |
| 定时触发（精确时间 / 每周 / 每日） | ✅ |
| 多 Applet 引用（上下文传递） | ✅（`referents` / `references`） |
| 云同步 / 任务市场 | ❌ |
| 任务导入 / 导出 / 分享 | ✅（`.xtsk` / `.xtsks`） |
| 多语言 | ★ 仅 `values` + `values-night`（中文） |

## 9. 关键源码引用

- `app/src/main/java/top/xjunz/tasker/task/applet/option/AppletOption.kt`
- `app/src/main/java/top/xjunz/tasker/task/applet/option/registry/*.kt`（所有 Registry）
- `app/src/main/java/top/xjunz/tasker/task/applet/anno/AppletOrdinal.kt`
- `app/src/main/java/top/xjunz/tasker/task/applet/value/VariantArgType.kt`
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/runtime/Event.kt`

> 若要扩展能力，先在本章追加对应条目（或留 TODO），再去改代码；这样后续 AI 能从文档一眼看到"实现进度/能力缺口"。
