# 07 · UI 架构

## 1. 基础层（`ui/base/`）

**关键点**：本项目 **没有自定义 `BaseActivity`**。活动类直接继承 `AppCompatActivity`，用 `ActivityXxxBinding.inflate(layoutInflater)`。

| 类 | 作用 |
|----|------|
| `BaseFragment<T : ViewBinding>` | 通过反射拿到泛型 `T`，自动 inflate；`HasDefaultViewModelProviderFactory` + `InnerViewModelFactory` |
| `BaseDialogFragment<T : ViewBinding>` | 全屏 / 紧凑样式对话框；`activityViewModels<MainViewModel>()`；`DialogStackMixin`；支持 `onBackPressed()` |
| `BaseBottomSheetDialog<T : ViewBinding>` | Material 底部表单；默认展开；同样走 `DialogStackMixin` |
| `DialogStackMixin` | 和 `DialogStackManager` + `MainViewModel` 协作：新全屏 dialog 出现时，底下的 dialog 做"退场动画"，回来时做"入场动画" |
| `InnerViewModelFactory` | `AbstractSavedStateViewModelFactory` 的 **内部类 / 嵌套 ViewModel** 支持（无参构造 + SavedStateHandle 构造） |
| `SavedStateViewModel` | `SavedStateHandle` 的 `LiveData` / `savedLiveData` 属性委托 |
| `InlineAdapter` | `GenericViewHolder` + `inlineAdapter(...)` DSL：用 `ViewDataBinding` item 组装小型 RecyclerView |

**DataBinding vs ViewBinding**：基础 Fragment / Dialog 用 **ViewBinding**；`inlineAdapter` 和很多 `item_*.xml` 用 **DataBinding**（`<layout>` + 表达式）。

## 2. 主 Activity 与导航

| 组件 | 路径 |
|------|------|
| Launcher | `ui/main/MainActivity.kt` |
| Root layout | `res/layout/activity_main.xml` |
| 底部导航 | `BottomNavigationView` + `ViewPager2`（`res/menu/task_bottom_bar.xml` 菜单） |
| 5 个一级 Tab | `EnabledTaskFragment` / `ResidentTaskFragment` / `OneshotTaskFragment` / `VoiceCommandFragment` / `AboutFragment` |
| FAB | 打开 `TaskCreatorDialog` |
| Toolbar 按钮 | 仅保留启动 / 停止服务（打开 `ServiceStarterDialog` 或确认 `MainViewModel.requestStopService`） |
| 副活动 | `ui/outer/CrashReportActivity.kt`（崩溃时跳转） |

**跨 Fragment 共享**：`MainViewModel` 承担事件总线（服务状态、对话框栈、导入分享）。

### 2.1 主页一级导航约束

主页采用 **一个 Activity 根布局 + 五个 Fragment 页面** 的方式管理，不把一级页面内容直接塞进 `activity_main.xml`：

| 位置 | 职责 |
|------|------|
| `res/layout/activity_main.xml` | 只放主框架：`ViewPager2`、顶栏、添加任务 FAB、`BottomNavigationView`。不要在这里新增某个 Tab 的业务 UI。 |
| `res/menu/task_bottom_bar.xml` | 声明底部导航项，菜单顺序必须和 `MainActivity.viewPagerAdapter.createFragment(position)` 的 position 顺序一致。 |
| `ui/main/MainActivity.kt` | 只负责一级页面映射、底部导航与 `ViewPager2` 双向同步、AppBar lift/scroll target、任务页 badge。 |
| 各 Fragment 自己的 layout | 每个一级页面的真实 UI 放在自己的 `fragment_*.xml` 中，例如语音页是 `fragment_voice_command.xml`。 |

当前固定映射如下：

| position | bottom item id | Fragment | 布局 |
|----------|----------------|----------|------|
| 0 | `item_running_tasks` | `EnabledTaskFragment` | `fragment_task_showcase.xml` |
| 1 | `item_resident_tasks` | `ResidentTaskFragment` | `fragment_task_showcase.xml` |
| 2 | `item_oneshot_tasks` | `OneshotTaskFragment` | `fragment_task_showcase.xml` |
| 3 | `item_voice_command` | `VoiceCommandFragment` | `fragment_voice_command.xml` |
| 4 | `item_more` | `AboutFragment` | `fragment_about.xml` |

修改一级导航时必须同步检查：

- `MainActivity.viewPagerAdapter.getItemCount()`。
- `MainActivity.viewPagerAdapter.createFragment(position)`。
- `MainActivity.scrollTargets` 数组长度。
- `res/menu/task_bottom_bar.xml` 的 item 顺序。
- `taskBadgeItemIds` 只应包含三个任务页：已启用、常驻任务、单次任务；不要给语音页或更多页挂任务数量 badge。
- 新页面如需参与 AppBar lift 行为，应实现 `ScrollTarget` 并返回自己的滚动视图。
- 不要用左下角 mini FAB、顶栏额外按钮等方式承载一级功能入口，避免遮挡底部导航或压缩标题/服务按钮。

### 2.2 关于页与反馈交流

`AboutFragment` 渲染底部导航的"关于"页，选项列表来自 `MainOption.ALL_OPTIONS`。其中"反馈 & 交流"对应 `MainOption.Feedback`，点击后弹出 `res/menu/feedbacks.xml` 菜单：

- `item_feedback_group`：调用 `Feedbacks.addGroup()`，通过 `mqqapi://card/show_pslcard?...uin=258644994...` 打开 QQ 群名片。
- `item_feedback_email`：调用 `Feedbacks.feedbackByEmail(null)`，拉起邮件客户端，收件人为 `webackup.feedback@gmail.com`。

反馈邮件正文由 `util/Feedbacks.kt` 的 `dumpEnvInfo()` 生成环境信息，包含版本号、Android 版本、品牌型号、ABI、Shizuku binder / 版本 / uid。邮件标题和正文模板在 `res/values/strings.xml` 的 `mail_subject`、`mail_body`。

崩溃反馈复用同一套能力：

- `GlobalCrashHandler` 捕获异常后写入 crash log，并进入 `CrashReportActivity`。
- `CrashReportActivity` 的"反馈"按钮可发送带附件的邮件；"加群"按钮仍走 `Feedbacks.addGroup()`。
- 通用错误弹窗 `showErrorDialog()` 会调用 `Feedbacks.feedbackErrorByEmail(stackTrace)`。

修改"反馈 & 交流"信息时同步检查：

- QQ 群入口是**代码写死**的，不在配置文件里：改 `util/Feedbacks.kt` 的 `addGroup()`，替换 `mqqapi://card/show_pslcard?...uin=258644994...` 中的 QQ 群号或整段 URL。改完需要重新打包安装。
- 反馈邮箱是**代码写死**的，不在配置文件里：改 `util/Feedbacks.kt` 的 `feedbackByEmail()` 与 `feedbackErrorByEmail()`，替换 `Intent.EXTRA_EMAIL` 里的 `webackup.feedback@gmail.com`。两处都要改，否则普通反馈和错误反馈会发到不同邮箱。改完需要重新打包安装。
- 邮件标题 / 正文模板是**Android 资源配置**：改 `res/values/strings.xml` 的 `mail_subject`、`mail_body`。这里只控制邮件标题文字和正文模板，不控制收件邮箱。改完需要重新打包安装。
- "反馈 & 交流"入口名称、"加群"、"邮件"这些菜单文案是**Android 资源配置**：改 `res/values/strings.xml` 的 `feedback_and_communicate`、`feedback_group`、`feedback_email`。改完需要重新打包安装。
- 菜单里有哪些项是**XML 菜单配置**：改 `res/menu/feedbacks.xml`。如果新增菜单项，还要同步改 `AboutFragment.onOptionClicked()` 中 `MainOption.Feedback` 分支的点击处理。改完需要重新打包安装。

### 2.3 语音指令入口

语音指令是一个独立底部导航分页，不再放在标题栏按钮或悬浮按钮里。用户进入"语音"页后，点击页面内主按钮启动 `voice/VoiceCommandService` 前台服务；服务会在通知栏常驻，用户可在语音页或通知里的"停止语音监听"关闭。没有做后台热词唤醒，也不会在 App 启动时自动监听。

入口与权限：

- UI 入口：`res/menu/task_bottom_bar.xml` 的 `item_voice_command` → `MainActivity` position 3 → `ui/voice/VoiceCommandFragment.kt`。
- 页面布局：`res/layout/fragment_voice_command.xml`；执行记录 item：`res/layout/item_voice_command_record.xml`。
- 阿里云配置入口：关于/设置页的"语音识别 AppKey"、"阿里云 AccessKey ID"、"阿里云 AccessKey Secret"和"语音识别 Token"，由 `ui/main/MainOption.kt` 注册，点击处理在 `ui/main/AboutFragment.kt`。配置值保存在 `Preferences.speechRecognitionAppKey`、`Preferences.speechRecognitionAccessKeyId`、`Preferences.speechRecognitionAccessKeySecret` 和 `Preferences.speechRecognitionToken`，底层是本机 `SharedPreferences`，不是写死在代码或打包资源里；用户可在 App 内修改或清空。
- 麦克风权限：`AndroidManifest.xml` 的 `RECORD_AUDIO`，运行时由 `VoiceCommandFragment` 请求。
- 通知权限：`AndroidManifest.xml` 的 `POST_NOTIFICATIONS`，Android 13+ 运行时由 `VoiceCommandFragment` 请求；没有通知权限时不启动语音监听，因为用户无法从通知栏关闭。
- 前台服务权限：`AndroidManifest.xml` 的 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MICROPHONE`；`VoiceCommandService` 声明 `foregroundServiceType="microphone"`。

识别与执行：

- 语音识别服务：`voice/VoiceCommandService.kt` 目前使用 Android 系统 `SpeechRecognizer`。实际识别能力由手机系统中安装的语音识别服务提供，不是项目内自带模型；系统识别不读取 AppKey / AccessKey / Token。后续接入阿里云等云识别服务时，应从 `Preferences` 读取用户在 App 内配置的信息，不要硬编码。
- 命令解析：`voice/VoiceCommandParser.kt` 支持 `执行`、`运行`、`启动`、`打开`、`开始` 前缀；没有前缀时把整句话当任务名。
- 任务匹配：`VoiceCommandService.findTask()` 先按任务标题精确匹配，再做去空白/标点后的包含匹配；匹配到多个任务时不会执行，会提示用户说完整任务名。
- 任务执行：当前只支持一次性任务（`XTask.TYPE_ONESHOT`）。匹配到一次性任务后复用现有入口：`LocalTaskManager.addOneshotTaskIfAbsent(task)` + `currentService.scheduleOneshotTask(...)`。匹配到常驻任务时只提示，不直接启用或运行。
- 结果展示：`VoiceCommandService.uiState` 输出 `VoiceCommandUiState` 和倒序 `VoiceCommandRecord`；`VoiceCommandFragment` 展示当前状态、最近识别文本、解析命令、匹配任务和执行记录。toast 和前台通知仍保留为即时反馈。

阿里云 ASR 的 `AppKey` 与 `Token` 获取：

- 本 App 是免费分发的工具，不提供作者账号下的付费云识别服务，也不会内置共享 `AppKey`、`AccessKey`、`Token` 或云服务额度。使用阿里云 ASR 时，使用者需要自行开通 / 购买阿里云智能语音交互服务，并在 App 设置页填写自己的服务信息。
- 先在阿里云开通"智能语音交互"服务，进入 [智能语音交互控制台](https://nls-portal.console.aliyun.com/applist) 创建项目 / 应用。项目列表或项目详情里显示的 `AppKey` 就是 Android SDK 初始化和识别参数里的 `app_key`。
- `AccessKey ID` / `AccessKey Secret` 用于向阿里云换取短期 `Token`。这里允许使用者在自己手机上的 App 设置页填写自己的 AccessKey，类似用户在客户端填写自己的账号密码；它不是作者内置到 APK 的密钥，也不应该由作者提供给所有用户共享。
- 如果使用者不想在 App 内保存 `AccessKey Secret`，也可以只手动填写 `AppKey` 和 `Token`。测试时可在阿里云控制台临时获取 Token；官方说明控制台获取的 Token 有效期为 24 小时，过期后需要重新获取并在 App 内更新。
- 如果后续实现自动刷新 Token，可由 App 使用本机保存的 `AccessKey ID` / `AccessKey Secret` 调阿里云 SDK 或 OpenAPI 获取 Token，并保存到 `Preferences.speechRecognitionToken`。如果使用者有自己的服务端，也可以改为让 App 请求使用者自己的服务端，由服务端换 Token 后下发。
- 通过 SDK 或 OpenAPI 获取的 Token 会返回 `ExpireTime`，这是秒级时间戳；App 或服务端应在过期前刷新。重新获取 Token 不会让旧 Token 立即失效，旧 Token 是否可用只取决于它自己的有效期。
- `AppKey`、生成 Token 使用的 `AccessKey`、以及阿里云账号 / RAM 子账号权限必须属于同一账号体系；如果混用不同账号的 `AppKey` 和 Token，阿里云会返回 `APPKEY_UID_MISMATCH`、`Appkey not exist`、`403 Forbidden` 或 Token 无效/过期等错误。
- 使用 RAM 子账号时，需要给子账号授予智能语音交互相关权限，例如 `AliyunNLSFullAccess`。如果获取 Token 时报无权限，先检查 RAM 授权和 AccessKey 是否正确。
- 当前 App 里已经有"语音识别 AppKey"、"阿里云 AccessKey ID"、"阿里云 AccessKey Secret"和"语音识别 Token"本机配置项。后续真正接入阿里云 ASR 时，让识别代码读取 `Preferences` 中的这些配置；不要内置作者账号，也不要把任意用户的密钥同步到作者服务器。

修改语音指令行为时同步检查：

- 改识别前缀 / 指令语法：`voice/VoiceCommandParser.kt`。
- 改匹配策略 / 支持常驻任务：`voice/VoiceCommandService.kt` 的 `findTask()`、`launchTask()`。
- 改状态或记录展示：`VoiceCommandUiState`、`VoiceCommandRecord`、`ui/voice/VoiceCommandFragment.kt`、`res/layout/fragment_voice_command.xml`、`res/layout/item_voice_command_record.xml`。
- 改 AppKey / AccessKey / Token 保存位置或配置项说明：`Preferences.kt`、`MainOption.kt`、`AboutFragment.kt`、`res/values/strings.xml`。
- 改语音 Tab 入口或图标：`res/menu/task_bottom_bar.xml`、`ui/main/MainActivity.kt` 的 position 映射、`res/drawable/ic_mic_24px.xml`。不要把语音入口重新放回 `activity_main.xml` 的顶栏或 FAB。
- 改通知标题、权限提示、执行提示：`res/values/strings.xml` 中 `voice_command*` 和 `format_voice_command_*` 字符串。

## 3. 任务编辑器（`ui/task/editor/`）

这是整个应用最复杂的 UI 子系统。

| 类 | 作用 |
|----|------|
| `FlowEditorDialog` | **主编辑器**（`dialog_flow_editor.xml`）：Applet 列表 RecyclerView + 工具栏（拆分、静态检查、元数据、快照、轨迹模式...） |
| `FlowEditorViewModel` | 选中 / 多选 / 引用选取 / 分支导航 / 静态错误 / 合并拆分 / 退出确认 |
| `GlobalFlowEditorViewModel` | **快照数据源**：`allSnapshots` / `currentSnapshot` / `currentSnapshotIndex`；连接 `LocalTaskManager` |
| `TaskFlowAdapter` | RecyclerView adapter |
| `FlowItemViewBinder` | 绑定 `ItemFlowItemBinding`：标题、缩进、控制流样式、静态错误、选中、快照成功/失败/当前着色 |
| `FlowItemTouchHelperCallback` | 拖拽重排 + 扩展钩子 |
| `AppletOperationMenuHelper` | 每个 Applet 的上下文 / 溢出菜单（移动、删除、进入容器、编辑、添加子...）；与 `PreferenceHelpDialog` + `DragToMoveDemo` 联动 |

### 编辑器常用工作流

| 操作 | 路径 |
|------|------|
| 加 Applet | FAB → `AppletSelectorDialog`（`ui/task/selector/`） → `vm.addInside` |
| 删除 / 移动 / 取反 | `AppletOperationMenuHelper` |
| 重排 | `ItemTouchHelper` + `FlowItemTouchHelperCallback` |
| 编辑参数 | `AppletOptionClickHandler` → `ArgumentsEditorDialog` |
| 静态检查 | 工具栏按钮 → `flow.performStaticCheck()` → 显示错误 |
| 试跑 / 轨迹 | 切到"轨迹模式"；最近 snapshot 绑到 `GlobalFlowEditorViewModel` |

### 相关编辑辅助

- `VarargTextEditorDialog`：多值字符串参数（例如 `TEXT_FORMAT`）
- `VibrationPatternEditorDialog`：振动波形（含测试按钮）
- `TaskMetadataEditor`：编辑任务元数据
- `SnapshotLogDialog`：查看一次运行的日志
- `TaskSnapshotSelectorDialog`：选快照

**非 UI**：`task/editor/AppletReferenceEditor.kt` 处理引用/值的增删改（支持撤销）。

## 4. 悬浮检查器（Inspector）

### 4.1 入口

| 类 | 作用 |
|----|------|
| `ui/task/inspector/FloatingInspectorDialog.kt` | 底部表单：选 `InspectorMode`（组件 vs 任务助手），选 Shizuku 或 A11y，请求 overlay / 无障碍权限，最后 `a11yAutomatorService.showFloatingInspector(mode)` |
| `task/inspector/InspectorViewModel.kt` | 总状态：`currentComponent` / `currentNodeTree` / `highlightNode` / `showNodeTree` / `showNodeInfo` / `showGrids` / `pinScreenShot` / 手势录制/回放 / `toastText` / 折叠态 / 游戏手柄 |

### 4.2 悬浮窗（`task/inspector/overlay/`）

每个 overlay 继承 `FloatingInspectorOverlay<ViewDataBinding>`，用 `WindowManager` 创建 `TYPE_APPLICATION_OVERLAY` 窗口。

| Overlay | 作用 |
|---------|------|
| `InspectorViewOverlay` | 主触控 / 截图 / 手势管道 |
| `NodeInfoOverlay` | 按 **Applet Option 注册** 展示当前选中节点对应的 NodeDetails（复选 / 文本 / id 等） |
| `NodeTreeOverlay` | **层级树**：面包屑 + 子列表，点击选中更新 `highlightNode` |
| `ComponentOverlay` | 当前组件（package + activity） |
| `BoundsDetectorOverlay` | 画矩形 / 测量 |
| `CollapsedBubbleOverlay` / `ExpandedBubbleOverlay` | 可折叠的悬浮球（检查器入口） |
| `TaskAssistantOverlay` | 任务助手（辅助选择 / 触发动作） |
| `ToastOverlay` | 自己实现的悬浮 Toast（`OverlayToastBridge` 用） |
| `TrashBinOverlay` | 拖拽删除目标 |
| `GestureShowcaseOverlay` | 手势回放展示 |

### 4.3 高亮 / 绘制

- `ui/widget/LayoutInspectorView.kt` 从 `StableNodeInfo` 画节点 bounds + label
- `InspectorViewModel.highlightNode` 驱动突出

## 5. 选择器（`ui/task/selector/`）

| 类 | 作用 |
|----|------|
| `AppletSelectorDialog` | 层次化 Applet / 选项拾取；支持"购物车"多选 (`ShoppingCartIntegration`)；联动 `FloatingInspectorDialog` |
| `AppletSelectorViewModel` / `AppletCandidatesAdapter` | Registry 导航 + 候选列表 |
| `AppletOptionClickHandler` | 命中 Option → 进入 `ArgumentsEditorDialog` 或直接 toggle |
| `ArgumentsEditorDialog` | **参数矩阵**：每个 `ArgumentDescriptor` 一行；按 `VariantArgType` 分发到专用编辑器 |
| `ComponentSelectorDialog` + `ComponentSelectorViewModel` | 选 App / Activity；内部 `PackageSelectorFragment` / `ActivitySelectorFragment` |
| `BaseComponentFragment` | 包 / 活动 Tab 公共基类 |
| `EnumSelectorDialog` | 枚举参数 |
| `RangeEditorDialog` | 数值范围 |
| `XYEditorDialog` | 坐标 |
| `DistanceEditorDialog` | `Distance` 复合（几何约束） |
| `DateTimeRangeEditorDialog` / `TimeRangeEditorDialog` / `TimeIntervalEditorDialog` | 时间相关 |
| `BitsValueEditorDialog` | 通用 bit-packed 编辑 |

### 手势录制

- `ui/widget/GestureRecorderView.kt` — 触控录制
- `ui/widget/GesturePlaybackView.kt` — 回放
- 用于 `GestureAction`（`TEXT_GESTURES` 参数）

## 6. 任务陈列 Showcase（`ui/task/showcase/`）

| 类 | 作用 |
|----|------|
| `BaseTaskShowcaseFragment` | 通用任务列表（启用开关 / 运行 / 快照 / 编辑 / 分享 / 删除），用 `activityViewModels<TaskShowcaseViewModel>()` |
| `EnabledTaskFragment` / `ResidentTaskFragment` / `OneshotTaskFragment` | 3 个 Tab |
| `TaskShowcaseViewModel` | 事件总线：`requestEditTask` / `requestTrackTask` / `requestToggleTask` / `requestDeleteTask` / `requestAddNewTasks` 等 |
| `TaskCreatorDialog` | 新任务创建 |
| `TaskListDialog` | 导入预览 |
| `TaskCollectionSelectorDialog` | 任务集合 / 导出 |

## 7. 常用对话框 / 控件

### `ui/common/`

| 文件 | 说明 |
|------|------|
| `TextEditorDialog.kt` | 通用文本输入 |
| `PreferenceHelpDialog.kt` | 带"不再提示" |
| `DropdownArrayAdapter.kt` | Spinner 适配器 |

### `ui/widget/`

| 文件 | 说明 |
|------|------|
| `LayoutInspectorView.kt` | 节点边框 & 标签绘制 |
| `GestureRecorderView.kt` / `GesturePlaybackView.kt` | 手势录制 / 回放 |
| `FloatingDraggableLayout.kt` | 可拖拽容器（悬浮用） |
| `DrawBoundsFrameLayout.kt` | 画矩形 |
| `GamePadLayout.kt` | 屏上 D-pad（检查器） |
| `MaterialButtonSpreadContainer.kt` | 按钮散开动画 |
| `WaveDivider.kt` | 分隔线 |
| `LeftCorneredTextView.kt` | 左侧对齐文本 |
| `PopupListMenu.kt` | 弹出列表菜单 |

## 8. 付费 UI（`ui/purchase/`）

| 文件 | 说明 |
|------|------|
| `PurchaseDialog.kt` | `BaseBottomSheetDialog` + WebView / API 订单流程；发起订单 → 唤醒支付宝 → 轮询订单状态；支持兑换码；`upForGrabs = true` 时自动 dismiss |
| `PurchaseViewModel.kt` | 订单生命周期、与 `PremiumMixin` 对接 |

## 9. 服务启动 UI（`ui/service/`）

| 文件 | 说明 |
|------|------|
| `ServiceStarterDialog.kt` | `BaseBottomSheetDialog`：Shizuku / 辅助功能 RadioGroup（`OperatingMode`），请求悬浮窗权限，绑定服务按钮，订阅 `MainViewModel` 状态；服务运行时自动 dismiss |

## 10. Outer / Model / Demo

| 目录 | 用途 |
|------|------|
| `ui/outer/GlobalCrashHandler.kt` | 全局未捕获异常处理，转 CrashReportActivity |
| `ui/outer/CrashReportActivity.kt` | 查看 / 分享崩溃日志 |
| `ui/model/PackageInfoWrapper.kt` | 包装 `PackageInfo`（label / 排序） |
| `ui/model/ActivityInfoWrapper.kt` | 包装 `ActivityInfo` |
| `ui/demo/*.kt` | 编辑器的教学动画：`DragToMoveDemo` / `LongClickToSelectDemo` / `SwipeToRemoveDemo` 等 |

## 11. 布局资源概览

`app/src/main/res/layout/` 共 **76** 个 XML，分组如下：

- **Activity / 主页**：`activity_main.xml`、`activity_crash_report.xml`、`fragment_about.xml`、`fragment_voice_command.xml`、`item_voice_command_record.xml`
- **任务陈列**：`fragment_task_showcase.xml`、`item_task_showcase.xml`、`dialog_task_showcase.xml`、`item_task_list.xml`、`item_task_assistant.xml`、`layout_menu_list.xml`、`item_menu.xml`
- **流编辑**：`dialog_flow_editor.xml`、`item_flow_item.xml`、`item_flow_cascade.xml`、`item_flow_name.xml`、`item_flow_opt_demo.xml`、`layout_flow_operation_demo.xml`、`layout_progress.xml`
- **Applet 选择 / 参数**：`dialog_applet_selector.xml`、`item_applet_candidate.xml`、`item_applet_cateory.xml`（拼写保留）、`item_applet_option.xml`、`item_applet_factory.xml`、`dialog_component_selector.xml`、`fragment_component_selector.xml`、`dialog_enum_selector.xml`、`item_enum_selector.xml`、`item_argument_editor.xml`、`dialog_arguments_editor.xml`、`dialog_bits_value_editor.xml`、`dialog_range_editor.xml`、`dialog_coordinate_editor.xml`、`dialog_distance_editor.xml`、`dialog_time_interval_editor.xml`、`layout_shopping_cart.xml`、`item_bread_crumbs.xml`、`item_input_layout.xml`、`item_check_case.xml`、`item_main_option.xml`、`item_application_info.xml`、`item_activity_info.xml`、`dialog_activity_selector.xml`
- **元数据 / 日志 / 文本**：`dialog_task_metadata_editor.xml`、`dialog_task_log.xml`、`dialog_task_creator.xml`、`dialog_task_list.xml`、`dialog_task_collection_selector.xml`、`dialog_text_editor.xml`、`dialog_vararg_text_editor.xml`、`item_vararg_text.xml`、`dialog_snapshot_selector.xml`、`item_task_snapshot.xml`、`dialog_gestures_editor.xml`、`item_gesture.xml`、`dialog_vibration_pattern_editor.xml`、`item_vibration_pattern.xml`、`dialog_preference_help.xml`、`dialog_privacy_policy.xml`、`dialog_version_info.xml`、`dialog_purchase.xml`、`dialog_service_starter.xml`、`dialog_floating_inspector.xml`
- **Overlay**：`overlay_inspector.xml`、`overlay_node_info.xml`、`item_node_info.xml`、`overlay_node_tree.xml`、`item_node_tree.xml`、`overlay_component.xml`、`overlay_toast.xml`、`overlay_trash_bin.xml`、`overlay_bubble_collapsed.xml`、`overlay_bubble_expanded.xml`、`overlay_gesture_showcase.xml`、`overlay_task_assistant.xml`、`overlay_window_bounds_detector.xml`

## 12. UI 层原则（给开发者的约束）

1. **全部复杂交互走 DialogFragment + ViewBinding**，不新建 Activity。
2. **对话框栈**：全屏 Dialog 必须继承 `BaseDialogFragment` 以参与 `DialogStackManager` 动画。
3. **事件总线** `MainViewModel` / `TaskShowcaseViewModel` 里统一跑跨组件事件。
4. **主页一级页面只通过 `BottomNavigationView` + `ViewPager2` + Fragment 管理**。`activity_main.xml` 只做壳，不承载具体 Tab 的业务 UI；新增/移动 Tab 必须同步菜单顺序、adapter position、scroll target 和 badge 逻辑。
5. **参数编辑器**必须实现：`ArgumentDescriptor.valueType → 对应 Dialog → onResult` 写回 `ArgumentsEditorDialog.vm`。
6. 增加参数类型时需要同时修改：
   - `VariantArgType.kt`
   - `AppletOption.withXxxArgument(...)`
   - `ArgumentsEditorDialog.kt` 派发逻辑
   - 新的专用 Dialog

## 13. 关键源码引用

- `app/src/main/java/top/xjunz/tasker/ui/base/**`
- `app/src/main/java/top/xjunz/tasker/ui/main/MainActivity.kt`
- `app/src/main/java/top/xjunz/tasker/ui/task/editor/**`
- `app/src/main/java/top/xjunz/tasker/ui/task/selector/**`
- `app/src/main/java/top/xjunz/tasker/ui/task/inspector/**`
- `app/src/main/java/top/xjunz/tasker/task/inspector/overlay/**`
- `app/src/main/java/top/xjunz/tasker/ui/task/showcase/**`
- `app/src/main/java/top/xjunz/tasker/ui/voice/**`
- `app/src/main/java/top/xjunz/tasker/ui/widget/**`
- `app/src/main/res/layout/`（76 个 xml）
