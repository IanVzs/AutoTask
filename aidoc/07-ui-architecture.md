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
| 底部导航 | `BottomNavigationView` + `ViewPager2`（`task_bottom_bar.xml` 菜单） |
| 4 个一级 Tab | `EnabledTaskFragment` / `ResidentTaskFragment` / `OneshotTaskFragment` / `AboutFragment` |
| FAB | 打开 `TaskCreatorDialog` |
| Toolbar 按钮 | 启动 / 停止服务（打开 `ServiceStarterDialog` 或确认 `MainViewModel.requestStopService`） |
| 副活动 | `ui/outer/CrashReportActivity.kt`（崩溃时跳转） |

**跨 Fragment 共享**：`MainViewModel` 承担事件总线（服务状态、对话框栈、导入分享）。

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

`app/src/main/res/layout/` 共 **74** 个 XML，分组如下：

- **Activity / 主页**：`activity_main.xml`、`activity_crash_report.xml`、`fragment_about.xml`
- **任务陈列**：`fragment_task_showcase.xml`、`item_task_showcase.xml`、`dialog_task_showcase.xml`、`item_task_list.xml`、`item_task_assistant.xml`、`layout_menu_list.xml`、`item_menu.xml`
- **流编辑**：`dialog_flow_editor.xml`、`item_flow_item.xml`、`item_flow_cascade.xml`、`item_flow_name.xml`、`item_flow_opt_demo.xml`、`layout_flow_operation_demo.xml`、`layout_progress.xml`
- **Applet 选择 / 参数**：`dialog_applet_selector.xml`、`item_applet_candidate.xml`、`item_applet_cateory.xml`（拼写保留）、`item_applet_option.xml`、`item_applet_factory.xml`、`dialog_component_selector.xml`、`fragment_component_selector.xml`、`dialog_enum_selector.xml`、`item_enum_selector.xml`、`item_argument_editor.xml`、`dialog_arguments_editor.xml`、`dialog_bits_value_editor.xml`、`dialog_range_editor.xml`、`dialog_coordinate_editor.xml`、`dialog_distance_editor.xml`、`dialog_time_interval_editor.xml`、`layout_shopping_cart.xml`、`item_bread_crumbs.xml`、`item_input_layout.xml`、`item_check_case.xml`、`item_main_option.xml`、`item_application_info.xml`、`item_activity_info.xml`、`dialog_activity_selector.xml`
- **元数据 / 日志 / 文本**：`dialog_task_metadata_editor.xml`、`dialog_task_log.xml`、`dialog_task_creator.xml`、`dialog_task_list.xml`、`dialog_task_collection_selector.xml`、`dialog_text_editor.xml`、`dialog_vararg_text_editor.xml`、`item_vararg_text.xml`、`dialog_snapshot_selector.xml`、`item_task_snapshot.xml`、`dialog_gestures_editor.xml`、`item_gesture.xml`、`dialog_vibration_pattern_editor.xml`、`item_vibration_pattern.xml`、`dialog_preference_help.xml`、`dialog_privacy_policy.xml`、`dialog_version_info.xml`、`dialog_purchase.xml`、`dialog_service_starter.xml`、`dialog_floating_inspector.xml`
- **Overlay**：`overlay_inspector.xml`、`overlay_node_info.xml`、`item_node_info.xml`、`overlay_node_tree.xml`、`item_node_tree.xml`、`overlay_component.xml`、`overlay_toast.xml`、`overlay_trash_bin.xml`、`overlay_bubble_collapsed.xml`、`overlay_bubble_expanded.xml`、`overlay_gesture_showcase.xml`、`overlay_task_assistant.xml`、`overlay_window_bounds_detector.xml`

## 12. UI 层原则（给开发者的约束）

1. **全部复杂交互走 DialogFragment + ViewBinding**，不新建 Activity。
2. **对话框栈**：全屏 Dialog 必须继承 `BaseDialogFragment` 以参与 `DialogStackManager` 动画。
3. **事件总线** `MainViewModel` / `TaskShowcaseViewModel` 里统一跑跨组件事件。
4. **参数编辑器**必须实现：`ArgumentDescriptor.valueType → 对应 Dialog → onResult` 写回 `ArgumentsEditorDialog.vm`。
5. 增加参数类型时需要同时修改：
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
- `app/src/main/java/top/xjunz/tasker/ui/widget/**`
- `app/src/main/res/layout/`（74 个 xml）
