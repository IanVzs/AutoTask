# 09 · 开发扩展指南

本章把"**常见增改需求 → 具体落地步骤**"做成 checklist，后续 AI 只需按步骤照做。

## 1. 新增一个"动作"（Action）

### 1.1 最小步骤

1. **定位 Registry**：例如要增加"关闭蓝牙"，放在 `GlobalActionRegistry.kt`。
2. **选择实现方式**：
   - **轻量**：在 Registry 类里用 `emptyArgAction { ... }` / `simpleSingleArgAction<Int> { ... }` 内联定义。
   - **重量 / 复用**：在 `app/src/main/java/top/xjunz/tasker/task/applet/action/` 新建 `Action` / `ArgumentAction` / `Processor` 子类（参考 `ShellCmdAction.kt`、`GestureAction.kt`）。
3. **声明 `AppletOption` 字段**：

```kotlin
@AppletOrdinal(0x00_xx)   // 分类 00、顺序 xx
val disableBluetooth = appletOption(R.string.format_disable_bluetooth) {
    // DSL 代码，构建 Applet
}.withSingleValueArgument<Boolean>(R.string.enable)
  .shizukuOnly()            // 如有特权要求
  .premiumOnly()            // 如属付费
  .describer { applet -> ... }  // 可选：决定摘要行文本
```

4. **串文案**：`res/values/strings.xml` 追加 `format_disable_bluetooth` 等 key（必要的话加 `not_xxx` 对应取反态）。
5. **如需 UI 编辑器**：见 `07-ui-architecture.md` 第 5 / 12 节，`ArgumentsEditorDialog` 派发分支。
6. **运行验证**：编辑器 → 选中 → 填参数 → 任务试跑 → 快照确认。

### 1.2 注意

- `@AppletOrdinal` 的高字节是分类，切分了 UI 上的分组（`categoryNames` 数组）。确保分类索引在 Registry 的 `categoryNames` 中有对应文案。
- **不要改已发布的 `appletId`**：默认 `appletId = field.hash & 0xFFFF`，字段重命名会导致 id 变化，旧任务反序列化失败。如果重命名必须兼容，在 Registry 声明时**显式固定** id（参考 `AppletOption` 的重载）。

## 2. 新增一个"条件"（Criterion）

流程几乎与 Action 相同，只是：

1. 在 `*CriterionRegistry.kt` 里加字段
2. 常见封装：`equalCriterion`、`referenceValueCriterion`、`propertyCriterion`、`collectionCriterion`、`unaryEqualCriterion`
3. 使用 `invertibleAppletOption(...)` 自动生成 `not_xxx` 标题
4. 需要自定义匹配逻辑时：新建 `Criterion<T, V>` 子类，实现 `matchTarget`

参考：`UiObjectCriterionRegistry.kt`（丰富的文本 / 属性判定）。

## 3. 新增一个"事件源"（Event）

### 3.1 引擎层

1. 在 `tasker-engine/.../runtime/Event.kt` 添加常量（**不要复用已有数字**）：
   ```kotlin
   const val EVENT_ON_XX = 15
   ```
2. 如果需要携带 referent，扩展 `Event` 或新建 `Referent` 实现。

### 3.2 App 层

1. 在 `app/src/main/java/top/xjunz/tasker/task/event/` 新建 `XxEventDispatcher` 继承 `EventDispatcher`：
   - 注册系统回调 / 广播 / Callback
   - 拿到触发 → `Event.obtain(EVENT_ON_XX, ...)` → `dispatchEvents(event)` → `event.recycle()` 外层做
2. 在 `AutomatorService.initEventDispatcher()` 里 `eventDispatcher.registerEventDispatcher(XxEventDispatcher())`
3. 在 `EventCriterionRegistry.kt` 新增 option 字段，参考已有（例如 `wifiConnected`）
4. 记得 `destroy()` 释放资源（分发器销毁时调用）

### 3.3 文档同步

- 更新 `04-feature-catalog.md` 的 "事件（Triggers）" 表
- 更新 `06-runtime-and-events.md` 的分发器列表

## 4. 新增一个 Flow（控制结构）

1. 在 `tasker-engine/.../applet/base/` 或 `app/.../task/applet/flow/` 新建 Flow 子类：
   - 决定 relation 语义：`ControlFlow`（强制 `REL_ANYWAY`）或 `ContainerFlow`
   - 如果要作用域化引用，考虑 `ScopeFlow<T>`
2. 如果需要替代 `applyFlow` 行为（例如条件轮询），重写它
3. 在 `BootstrapOptionRegistry.kt` 中增加字段，类似 `ifFlow` / `waitForFlow`
4. 在 `getPeerOptions` / `getRegistryOptions` 里更新父子联动关系（哪里能添加本 Flow、本 Flow 里能添加什么）

## 5. 新增一个 Bridge

1. 在 `app/src/main/java/top/xjunz/tasker/bridge/` 新建 `object XxBridge`
2. 确认进程约束，加注解：
   - 仅 App 进程：`@Local`
   - 仅特权：`@Privileged`
   - 两处共用：`@Anywhere`，且代码要判断 `App.isPrivilegedProcess`
3. Hidden API 调用：
   - 先加到 `hidden-apis/` 的 stub（配 `@RefineAs`）
   - 如果要绕过运行期限制：`HiddenApiBypass` 已在 App.onCreate 全局豁免
4. 在 Registry / Action 里通过 `XxBridge.foo()` 调用

## 6. 新增一个参数类型（VariantArgType）

1. 在 `VariantArgType.kt` 加常量（注意不要和 `ARG_TYPE_*` / collection mask 冲突）
2. 如果是 **复合值**（多字段打包成 `Long` / `String`），在 `task/applet/value/` 新建类（参考 `Distance`、`SwipeMetrics`）
3. 在 `BitwiseValueComposer` 或 `ValueComposer` 里定义编解码
4. 为 Applet 实现 `serializeArgumentToString` / `deserializeArgumentFromString`
5. UI：
   - `ArgumentsEditorDialog` 派发新 dialog
   - `ui/task/selector/argument/` 新 dialog 类（`BaseBottomSheetDialog` + 参数类型专属交互）
6. 在 `AppletOption` DSL 里增加 `withXxxArgument(...)` 方法

## 7. 修改 AIDL 契约

1. 调整 `app/src/main/aidl/**`
2. **兼容策略**：
   - 新增方法：直接加到末尾，老客户端 / 老服务都能兼容
   - 修改签名：**必须同时升级两端**，否则 Shizuku 服务启动会 `onTransact` 抛 SecurityException
3. 同步更新：
   - `ShizukuAutomatorService.kt` 的 `Stub` 实现
   - `PrivilegedTaskManager.Delegate`
   - 调用方（`ShizukuAutomatorServiceController` / `LocalTaskManager` / `MainViewModel`）
4. 更新 `05-services-and-ipc.md` 的 IPC 表

## 8. 修改序列化格式 / 升级版本

1. 在 `XTaskDTO.kt` 里加字段（默认值必备，避免旧文件反序列化失败）
2. 修改 `XTask.Metadata.version` 并在 `AppletFactoryUpdateHelper.checkUpdate` 中实现 `oldId → newId` 迁移
3. 如果 `Applet.id` 变了：
   - 记录旧 id → 新 id 映射
   - 在工厂里根据 `compatMode` 透明替换
4. 确保 `TaskStorage.loadAllTasks()` 命中版本分支后重新 `persistTask`（落新版本到磁盘）

## 9. 新增一个 UI 对话框（通用流程）

1. 新建 `ui/.../XxDialog.kt`：
   ```kotlin
   class XxDialog : BaseDialogFragment<DialogXxBinding>() {
       private val vm by viewModels<XxViewModel> { InnerViewModelFactory(...) }
       override fun onViewCreated(...)
   }
   ```
2. 布局：`res/layout/dialog_xx.xml`（用 `<layout>` 包装以启用 DataBinding）
3. 如果需要和主流程通信：放到 `activityViewModels<MainViewModel>()` 或 `TaskShowcaseViewModel`
4. 在调用处用 `show(childFragmentManager, tag)` 弹出
5. 如果是**全屏**对话框，走 `DialogStackManager` 的退入场动画

## 10. 添加一个新的测试 / 集成测试

- 单元测试：`tasker-engine/src/test/java/**`（纯 JVM）
- 仪器测试：`app/src/androidTest/java/**`（需要真机 / 模拟器）
- 目前没有 CI 配置（仓库里没有 `.github/workflows`），请手动跑 `./gradlew :tasker-engine:test`

## 11. 常见"一步到位"check list

| 场景 | 必改文件 |
|------|----------|
| 新 Action | `*ActionRegistry.kt` + 可选 `action/*.kt` + `strings.xml` |
| 新 Criterion | `*CriterionRegistry.kt` + 可选 `criterion/*.kt` + `strings.xml` |
| 新事件 | `engine.runtime.Event.kt` + `event/*EventDispatcher.kt` + `AutomatorService.initEventDispatcher` + `EventCriterionRegistry.kt` + `strings.xml` |
| 新 Flow | `engine.applet.base/*.kt` 或 `flow/*.kt` + `BootstrapOptionRegistry.kt` |
| 新 Bridge | `bridge/*.kt`（注明进程） + 可选 `hidden-apis/` 扩展 |
| 新 VariantArgType | `VariantArgType.kt` + `value/*.kt` + `ArgumentsEditorDialog.kt` + 新 Dialog |
| 新 AIDL | `aidl/**` + 两端实现 + ProGuard 保留 |
| 新 DTO 字段 | `XTaskDTO.kt` / `AppletDTO.kt`（需默认值） + `AppletFactoryUpdateHelper.kt` |

## 12. 通用原则

- **永远写最小改动**：不要顺手重构，老任务文件会被破坏。
- **任何 id / 序列化名 / string key 变化都要做向后兼容**。
- 修改 `Environment` / `App` 静态入口会牵动所有模块，**慎改**。
- **Shizuku 进程没有 UI**：特权进程里**不要**引用 `Activity` / `Fragment` / `Toast.makeText`（除非走 `OverlayToastBridge`）。
- **注解是硬约束**：`@Local` 方法在特权进程调用会 NPE 或 crash。
- **编辑 Applet Option 后**务必同步更新 `aidoc/04-feature-catalog.md`。
