# 03 · 引擎 · Applet / Flow / XTask 模型

本章聚焦 `tasker-engine/` 模块 —— AutoTask 的**领域核心**。它独立于 Android UI / 服务实现，只负责描述任务树、执行、持久化、事件通知。

## 1. 核心抽象

```
Applet（节点）
 ├── Flow（复合节点，本身也是 Applet；MutableList<Applet>）
 │    ├── ControlFlow   （relation 固定为 REL_ANYWAY）
 │    │    ├── RootFlow （顶层；做 reference 静态校验）
 │    │    ├── If / ElseIf / Else / Then
 │    │    ├── When     （事件触发；失败让父流 shouldStop = true）
 │    │    └── Do       （动作容器）
 │    ├── ContainerFlow （通用分组；在 Do 下支持 REL_ANYWAY 的子节点）
 │    ├── ScopeFlow<T>  （给后代设置 runtime.target：如 TimeFlow / UiObjectFlow）
 │    ├── WaitFor / WaitUntil （带超时等待）
 │    ├── Loop / Repeat
 │    └── DslFlow       （测试/DSL 专用）
 ├── Criterion<T, V>    （条件判断：matchTarget vs matchValue）
 │    └── DslCriterion  （创建器 DslCriterionCreator）
 └── Action             （副作用；supportsAnywayRelation = true）
      ├── ArgumentAction  （参数化动作；按 ArgumentDescriptor 解包）
      └── Processor<R>    （有返回值的 Action）
```

源码位置：`tasker-engine/src/main/java/top/xjunz/tasker/engine/applet/**`

### 1.1 `Applet`（`applet/base/Applet.kt`）

核心字段：

| 字段 | 说明 |
|------|------|
| `id: Int` | **复合 id**：高 16 位 `registryId`，低 16 位 `appletId`。改 id = 破坏序列化兼容。 |
| `relation: Int` | 与**前一个同级兄弟**的关系：`REL_AND=0` / `REL_OR=1` / `REL_ANYWAY=2` |
| `isEnabled: Boolean` | 是否启用（被禁用的 Applet 在 `applyFlow` 里会 skip） |
| `isInverted: Boolean` | 结果取反（对 Criterion 有意义） |
| `values: Map<Int, Any>` | 按参数槽位保存**字面量**（序列化成 string） |
| `references: Map<Int, String>` | 按参数槽位保存**对前面 Applet 输出的引用名** |
| `referents: Map<Int, String>` | 本 Applet **对外暴露的输出命名**（供后续 Applet 引用） |
| `comment: String?` | 用户写的注释，会被序列化 |
| `name: String?` | 可选的开发者调试名（仅用于 `toString()`） |
| `parent: Flow?` / `index: Int` | 运行时由 `Flow.applyFlow` 或 `buildHierarchy()` 注入 |
| `requiredIndex: Int = -1` | 供 `When` 之类强约束子索引的场景 |
| `argumentTypes: IntArray` | 每个参数槽位的类型码（含 collection 位 + `ARG_TYPE_*`） |

**Relation 语义**（见 `Flow.applyFlow` in `Flow.kt:36-48`）：

- 流中**第一个**子 Applet 一定执行（除非自身被禁用/跳过）。
- 之后每个子 Applet 以 `relation` 对照**上一个兄弟的成功与否**（即 `runtime.isSuccessful`）决定是否执行：
  - `REL_AND`：上一个**成功**才跑
  - `REL_OR`：上一个**失败**才跑
  - `REL_ANYWAY`：**无条件**跑（`isAnyway = true` 走这个分支）
- `ControlFlow` 强制 `relation = REL_ANYWAY`；`ContainerFlow` 只有在父为 `Do` 时才允许对子用 `REL_ANYWAY`。

### 1.2 `Flow`（`applet/base/Flow.kt`）

- 由 `MutableList<Applet>` 委托实现；`applyFlow(runtime)` 按索引遍历子节点。
- 钩子：`onPreApply` → `onPrepareApply` → `apply`（子节点自身执行） → `onPostApply`；skip 走 `onSkipped`。
- **异常处理**：`CancellationException` 继续抛；其它异常日志化后根据 `isRepetitive` 返回 `EMPTY_FAILURE` 或 `AppletResult.error(t)`。
- `buildHierarchy()` 是**静态**初始化：递归为整棵树填 `parent` / `index`，用于编辑器里的点击定位。

### 1.3 `Criterion<T, V>`（`applet/criterion/Criterion.kt`）

```
final fun apply(runtime):
    target = getMatchTarget(runtime)
    value = getMatchValue(runtime)
    matched = matchTarget(target, value)
    if (isInverted) matched = !matched
    return AppletResult.of(matched, ...)
```

- 子类必须实现 `matchTarget(T, V): Boolean`；可选 `getMatchTarget`（默认取 `runtime.target`）与 `getMatchValue`。
- 通常产出 `AppletResult.EMPTY_SUCCESS` / `EMPTY_FAILURE`，也可返回带值的结果（`AppletResult.of(value, matched)`）用于 fingerprint。

### 1.4 `Action`（`applet/action/Action.kt`）

- `supportsAnywayRelation = true`。
- `ArgumentAction`：按 `argumentTypes.size + references.size` 开数组，逐槽位调用 `runtime.getArgument(i)` 解引用。
- `Processor<Result>`：语义上"动作 + 结果"，结果通过 `AppletResult` 带出，可被下一个 Applet 通过 referent 引用。

### 1.5 `AppletResult`

| 常量 / 方法 | 说明 |
|-------------|------|
| `EMPTY_SUCCESS` / `EMPTY_FAILURE` | 最常见返回 |
| `of(isSuccessful, returned)` | 带返回值的结果，`returned` 会被注册到 runtime referent 表 |
| `error(throwable)` | 异常包装 |
| `recycle()` | `Flow.applyFlow` 每轮末尾调用，减少 GC（对象池） |

## 2. 执行引擎

### 2.1 `TaskRuntime`（`runtime/TaskRuntime.kt`）

对象池 + 协程作用域 + 可观察执行上下文。

| 关键字段 / 方法 | 说明 |
|------------------|------|
| `currentApplet` / `currentFlow` | 执行过程中当前节点 |
| `isSuccessful` | 最近一个子 Applet 的布尔结果（relation gate 依据） |
| `target` / `setTarget` / `getTarget` | 由 `ScopeFlow` 设置的"上下文目标"（例如 `Calendar` 或 UI 节点） |
| `events: Array<Event>` | 触发本次执行的事件数组（用于 EventCriterion 判定） |
| `tracker: AppletIndexer` | 当前在树上的位置（用 long 编码） |
| `getScopedValue(scope, key, init)` | 按作用域（task / event / ...）缓存值 |
| `registerResult(applet, result)` | 将 result 映射到 applet 的 referents |
| `getReferenceArgument(applet, i)` | 按 `references[i]` 的命名在 referent 表查值 |
| `getReferentByName(name)` | 底层查值 |
| `updateFingerprint(...)` | 校验任务幂等/缓存键 |
| `halt()` | 取消 runtimeScope |
| `shouldStop` | `When` 失败或动作主动写入；`applyFlow` 每轮检测 |

**获得 runtime**：`CoroutineScope.obtainTaskRuntime(task, events, factory)`；跑完记得让其归还到对象池。

### 2.2 `Observer` 与 `SnapshotObserver`

- `TaskRuntime.Observer`：`onAppletStarted` / `onAppletTerminated` / `onAppletSkipped`，默认 no-op。
- `XTask.SnapshotObserver`：把 Applet 位置（`AppletIndexer` 的 long 编码）写入 `TaskSnapshot.successes` / `failures` / `current`，并在 DEBUG 模式追加日志。
- 编辑器的"轨迹模式"就是基于最后一次的 `TaskSnapshot` 回放。

## 3. XTask 与元数据

`XTask`（`task/XTask.kt`）是"一个可运行的自动化任务"。

```
class XTask : ValueRegistry {
    val metadata: Metadata
    var flow: RootFlow?
    val checksum: Long                 // = metadata.checksum
    var stateListener: TaskStateListener?
    ...
    suspend fun launch(runtime: TaskRuntime)
    fun onNewEvents(events: Array<Event>) // 已经在跑时继续消费事件
    fun requireFlow(): RootFlow
    fun halt()
    fun toDTO(): XTaskDTO
    val isResident: Boolean / isOneshot
}
```

### `XTask.Metadata`

| 字段 | 说明 |
|------|------|
| `title` | 任务标题（UI 展示） |
| `taskType` | `TYPE_RESIDENT` / `TYPE_ONESHOT` |
| `description` | 描述 |
| `checksum: Long` | 内容校验（MD5 前缀之类），用作 taskId / 查重 |
| `creationTimestamp` / `modificationTimestamp` | 时间戳 |
| `author` | 作者名 |
| `preload: Boolean` | 是否预置任务 |
| `version: Int` | 序列化版本码；低于 16 触发迁移 |

### `TaskSnapshot`（`task/TaskSnapshot.kt`）

- 每次执行一条：`id` / `checksum` / 开始结束时间 / 成功集合 / 失败集合 / 当前位置 / 可选日志缓冲。
- `successes` / `failures` 中每一项是 `AppletIndexer` 的 packed `Long`（7 bit/层，最多若干层）。
- `loadApplets(rootFlow)` 反解出对应的 `Applet` 实例。

### `TaskStateListener`

```kotlin
interface TaskStateListener {
    fun onTaskStarted(runtime)
    fun onTaskSuccess(runtime)     // 默认调用 onTaskFinished
    fun onTaskFailure(runtime)
    fun onTaskCancelled(runtime)
    fun onTaskError(runtime, t)
    fun onTaskFinished(runtime)
}
```

## 4. 序列化 / 反序列化

### 4.1 DTO 结构

- `AppletDTO`（`dto/AppletDTO.kt`）：节点树；字段 `id`、`relation`、`isEnabled`、`isInverted`、`values`（Map<Int, String>）、`referents`、`references`、`comment`；若是 Flow 还有 `elements: List<AppletDTO>`。
- `XTaskDTO`（`dto/XTaskDTO.kt`）：包住 `flow: AppletDTO` + `metadata: XTask.Metadata`；提供 `toXTask(factory, compatMode)`。
- **老版本**（`pre version code 16`）：单字段 `serialized` 包所有内容 → `toAppletPreVersionCode16`。

### 4.2 `AppletFactory` / `AppletOptionFactory`（app 侧实现）

引擎里的 `AppletFactory` 只是一个接口：`createAppletById(id, compatMode): Applet`。

App 层 `task/applet/option/AppletOptionFactory.kt` 是 `AppletFactory` 的具体实现：

1. `id` 拆分：`registryId = (id ushr 16) and 0xFF`、`appletId = id and 0xFFFF`
2. `requireRegistryById(registryId)` 找到对应的 `AppletOptionRegistry` 子类
3. 在 registry 的 `allOptions` 中按 `appletId` 定位到 `AppletOption`
4. 调用 `option.rawCreateApplet(...)` 生成 `Applet` 并注入参数描述 `argumentTypes` 等

### 4.3 `AppletArgumentSerializer`（`dto/AppletArgumentSerializer.kt`）

- 标量：按 `ARG_TYPE_*` 类型调用 Applet 的 `serializeArgumentToString` / `deserializeArgumentFromString`。
- 集合：用 `JSON`，类型码带 `collectionTypeOf` 掩码。
- 自定义类型：子类需要重写 `serializeArgumentToString` 做 packed-long / DSL 字符串等编码。

### 4.4 校验和与版本迁移

- `XTaskDTO.verifyChecksum()`：确保 metadata.checksum 与 flow 内容一致，反之丢弃文件。
- 版本码 < 16 的 `.xtsk` 会被 `toXTaskPrevVersionCode16` 迁移；原文件改名为 `.pv16` 保留，并重新写盘。
- 版本迁移相关分片逻辑位于 `AppletFactoryUpdateHelper.checkUpdate`（当前几乎是 identity，后续版本应在此扩展）。

## 5. `AppletIndexer`（`runtime/AppletIndexer.kt`）

- 7 bit/层编码节点路径到一个 `Long`，约束每层最多 128 个 sibling。
- `jumpIn` / `jumpOut` 用来在 `Flow.apply` 入栈出栈。
- `moveTo(index)` 更新当前层位置。
- 用于 `TaskSnapshot` 的 hierarchy key 和编辑器的"轨迹高亮"。

## 6. `ValueRegistry` & `Referent`

- `ValueRegistry`：持有 `ArrayMap<String, Any>` 的命名值容器，`XTask` 实现它，`runtime.getScopedValue(scope, key, init)` 分任务/事件作用域复用。
- `Referent` 接口：复合对象可让外部按"字段索引"取值（例如 `NotificationReferent` 提供 title、text、owner 等）。

## 7. 扩展点速览（详见 `09-development-guide.md`）

| 想做 | 应该改 |
|------|--------|
| 加新的 Applet 类型 | 继承 `Action` / `ArgumentAction` / `Criterion` / `Flow`，在对应 Registry 注册 `AppletOption` |
| 加新的复合值类型 | `VariantArgType` 新增常量 + `BitwiseValueComposer` 编解码 + UI 编辑器 |
| 加新流控节点 | `ControlFlow` 子类（注意 relation 语义），在 `BootstrapOptionRegistry` 配置父子约束 |
| 加新参数槽位的复合引用 | `Referent` 实现 + `referents` 映射 + `withResult(...)` 描述 |

## 8. 关键源码引用

- `tasker-engine/src/main/java/top/xjunz/tasker/engine/applet/base/Applet.kt` — 核心节点
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/applet/base/Flow.kt:31-77` — `applyFlow` relation gate
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/applet/base/RootFlow.kt:20-44` — 引用静态校验
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/runtime/TaskRuntime.kt` — 运行时
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/runtime/AppletIndexer.kt` — 路径编码
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/task/XTask.kt` — 任务 / 快照
- `tasker-engine/src/main/java/top/xjunz/tasker/engine/dto/AppletDTO.kt` / `XTaskDTO.kt` — 序列化
- `app/src/main/java/top/xjunz/tasker/task/applet/option/AppletOptionFactory.kt` — App 侧 factory 实现

> 阅读提示：**不要把 `Flow` 当成普通容器**——它本身也是 `Applet`，能被当作条件参与 relation。`If` / `When` 之所以能被 `Else` 链接，就是因为 `Flow.apply` 成功与否写回了 `runtime.isSuccessful`。
