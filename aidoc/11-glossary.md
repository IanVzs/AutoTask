# 11 · 术语表

| 术语 | 含义 |
|------|------|
| **AutoTask / 自动任务** | 本项目名称；Android 任务自动化 App |
| **Applet** | 项目里"可执行最小单元"的统一抽象；既可以是条件（Criterion）、也可以是动作（Action）、还可以是容器（Flow） |
| **Flow** | **复合 Applet**：按顺序执行子节点；`If` / `When` / `Do` / `ElseIf` / `Else` / `WaitFor` / `WaitUntil` / `Repeat` / `ContainerFlow` 等 |
| **RootFlow** | 任务顶层 Flow，执行引用静态检查 |
| **ControlFlow** | Flow 的子类，relation 强制为 `REL_ANYWAY`（条件分支类） |
| **ContainerFlow** | 通用分组 Flow；在 `Do` 父下才允许子使用 `REL_ANYWAY` |
| **ScopeFlow\<T\>** | 为后代 Applet 设置 `runtime.target` 的 Flow（例 `TimeFlow` 设置 `Calendar`） |
| **PhantomFlow** | 仅编辑器使用的占位 Flow；其 `appletId = 目标 Registry id`，让 UI 进入对应 Registry 的选项菜单 |
| **Criterion** | 判定类 Applet（返回 true / false） |
| **Action** | 副作用类 Applet（无布尔语义） |
| **ArgumentAction** | 带槽位参数的 Action；按 `ArgumentDescriptor` 解包 |
| **Processor\<R\>** | 带返回值的 Action（可作为上游给后续 Applet 引用） |
| **AppletOption** | UI / 运行时对 Applet 的**元数据**：标题、描述、参数描述符、创建函数；在 Registry 中以字段声明 |
| **AppletOptionRegistry** | 某个业务域（事件 / App / UI 对象 / Shell...）的 Option 集合，id 占 Applet.id 高 16 位 |
| **AppletOptionFactory** | 聚合所有 Registry；作为 `AppletFactory` 实现 |
| **AppletFactory** | 引擎里按 id 反序列化成 `Applet` 的契约 |
| **AppletOrdinal** | 标注 Option 字段的分类（高字节）+ 组内顺序（低字节） |
| **ArgumentDescriptor** | 一个参数槽位：类型、名称、引用性质、`VariantArgType` |
| **ValueDescriptor** | Applet 输出槽位（referent）的元数据 |
| **VariantArgType** | 编辑器侧的"变体参数类型"：坐标、复合距离、时长、色彩、... |
| **referents** | Applet 对外暴露的**输出命名**（索引→名字）供后续 Applet 引用 |
| **references** | Applet 对外声明的**输入引用**（索引→引用名） |
| **Referent** | 含多个字段的复合返回值的可查询接口（例 `NotificationReferent`） |
| **Relation** | Applet 与前一兄弟的关系：`AND` / `OR` / `ANYWAY`（直接跑） |
| **XTask** | 一个完整的任务对象：包含 metadata + RootFlow + 快照 + 状态监听 |
| **XTaskDTO** | XTask 的序列化 DTO；版本码用于迁移 |
| **TaskRuntime** | 任务执行期间的上下文：当前 Applet / Flow / 成功态 / 引用表 / 作用域缓存 / 协程 scope |
| **TaskSnapshot** | 一次执行的记录：成功 / 失败 / 当前位置 / 可选日志 |
| **AppletIndexer** | 用 `Long` 打包 Applet 在树中的路径（7 bit/层） |
| **ValueRegistry** | 基于字符串 key 的值注册表；`XTask` 实现 |
| **Resident Task** | 常驻任务（事件驱动，后台跑） |
| **Oneshot Task** | 一次性任务（用户触发） |
| **EventDispatcher** | 把系统事件转成引擎 Event[] 的组件 |
| **MetaEventDispatcher** | 多 `EventDispatcher` 的聚合器 |
| **Event** | 引擎的事件对象（对象池）；含 `type` + 负载数据 |
| **UiAutomatorBridge** | 对 `UiAutomation` / `AccessibilityService` 的统一抽象 |
| **Shizuku 模式 / Privileged Mode** | 通过 Shizuku 跑特权进程；使用 `UiAutomation.injectInputEvent` 等隐藏 API |
| **A11y 模式 / Accessibility Mode** | 通过系统无障碍服务，用 `dispatchGesture` 等执行动作 |
| **`@Local` / `@Privileged` / `@Anywhere`** | 进程作用域注解（见 `annotation/`）；修改服务 / Bridge 代码时必须遵循 |
| **Inspector / 悬浮检查器** | 悬浮窗工具，查看 Accessibility 节点树 / 标注节点 / 录制手势 |
| **Showcase** | 任务列表 UI（启用 / 常驻 / 一次性 三个 tab） |
| **DialogStackManager** | 全屏对话框之间的动画协调 |
| **Premium / upForGrabs** | 付费版 / 全局白嫖开关；`PremiumMixin.isPremium` 控制门槛 |
| **`.xtsk` / `.xtsks`** | 单任务 / 任务包文件格式（JSON / ZIP-of-JSON） |
| **`xtsk://`** | 任务深链 scheme |
| **ssl 模块** | **命名容易误导**：它是 AES-CBC/PKCS7 JNI 加解密，用于 API / Premium 数据保护，**不是 TLS** |

## AI 相关术语（2026-05-09 起）

| 术语 | 含义 |
|------|------|
| **AI agent loop** | `AiAgentSession.runLoop`：抓快照 → AI 决策 → 执行单步 → 写 history → 下一轮，最多步数 / 时长 / scope 边界（详见 `14` / `16` / `18`） |
| **`AiAgentAction`** | sealed class：LaunchApp / Click / LongClick / SetText / Wait / Scroll / GlobalBack / GlobalHome / Done / GiveUp / Unknown |
| **`AiUiTarget`** | AI 描述"要操作哪个节点"用的多字段 AND 定位条件（viewId / textEquals / textContains / contentDesc / className + matchIndex），由本地代码在执行瞬间用最新真实节点树二次定位 |
| **`AiUiSnapshot`** | 给 AI 看的当前屏幕子集（压缩后的扁平节点列表 + 元数据） |
| **`AiAgentTaskAssembler`** | 把单步动作 + 真节点组装成一棵跟 inspector「挑节点 → 自动点击」等价的最小临时 XTask（在执行端 :service 进程跑） |
| **`AiActionToTask`** | 把 `AiAgentAction` 翻译成最小临时单步 XTask（用于 LaunchApp / GlobalBack / GlobalHome 等不依赖节点匹配的动作） |
| **ReAct 三段** | observation / last_action_review / reflection 三段强制思考，再写 action（详见 `18`） |
| **stuck detection** | 连续 N 步无进展时强制 AI 在 reflection 里宣告"困住了"+ 给完全不同方向，超硬限直接 GiveUp |
| **silent-fail** | 步骤报 ok 但屏幕签名前后一致（节点选错 / 不响应 / 占位文本）；二次拉黑机制写入 `deadTargetsByPkg` |
| **outcome** | 一次会话的最终结果 sealed class：Completed / GivenUp / LimitExceeded / OutOfScope / PermissionDenied / AiError / Cancelled / ServiceNotConnected |
| **AI 经验本 / Experience Book** | 跨 session 长期记忆，自然语言 markdown + 结构化 JSON 嵌块写到 `${filesDir}/ai_agent_experience/` + `index.json`（2026-05-13） |
| **`AiAgentExperienceBook`** | 经验本单例门面：`recordSession` / `recall` / `queryAll` / `loadEntry` / `convertToDraft` / `delete` / `clearAll` / `usageBytes` |
| **`ExperienceFile` / `ExperienceStep` / `ExperienceIndex`** | 经验本结构化 DTO，schemaVersion=1，内嵌 ` ```json ``` ` 块到 txt 文件尾 |
| **`ExperienceRecaller`** | 召回打分公式 `(pkg_match*3 + keyword_overlap + outcome_failure_bonus) * age_decay`，半衰期 21 天 |
| **`ExperienceRedactor`** | 隐私脱敏：手机/邮箱/身份证/卡号正则；set_text 实际内容**永不写盘** |
| **`ExperienceToTaskConverter`** | 经验本→草稿生成的纯工具类，**完全由用户在 UI 主动点击触发**，跟 agent 自身解耦 |
| **三模块解耦架构** | agent 执行 / 经验本 / 草稿生成 三模块通过经验本作为单向数据中介解耦；详见 `20-experience-book-design.md` §0 |
| **`ai_agent_outcome` channel** | session 结束后发独立通知的 NotificationChannel（IMPORTANCE_DEFAULT），跟前台常驻通知 `voice_command` 完全分离 |
| **「人工智能」Tab** | 底部导航第 4 个 Tab（`page_title_ai`），原"语音"页 2026-05-13 重塑 |

## 常用路径速记

| 路径 | 含义 |
|------|------|
| `app/src/main/java/top/xjunz/tasker/App.kt` | Application |
| `app/src/main/java/top/xjunz/tasker/Preferences.kt` | 偏好 |
| `app/src/main/java/top/xjunz/tasker/service/` | 服务层（A11y / Shizuku） |
| `app/src/main/java/top/xjunz/tasker/bridge/` | Bridge |
| `app/src/main/java/top/xjunz/tasker/task/event/` | 事件分发器 |
| `app/src/main/java/top/xjunz/tasker/task/runtime/` | 调度器 / TaskManager |
| `app/src/main/java/top/xjunz/tasker/task/storage/` | 存储 |
| `app/src/main/java/top/xjunz/tasker/task/applet/option/registry/` | **功能目录**（所有 Registry） |
| `app/src/main/java/top/xjunz/tasker/task/applet/flow/` | App 侧特化 Flow |
| `app/src/main/java/top/xjunz/tasker/task/applet/value/` | 复合值类型 |
| `app/src/main/java/top/xjunz/tasker/task/inspector/overlay/` | 悬浮检查器 overlay |
| `app/src/main/java/top/xjunz/tasker/ui/` | UI 层 |
| `app/src/main/aidl/` | AIDL |
| `tasker-engine/src/main/java/top/xjunz/tasker/engine/` | 引擎 |
| `coroutine-ui-automator/src/main/java/top/xjunz/tasker/uiautomator/` | 协程 UiAutomator |
| `ui-automator/src/main/java/androidx/test/uiautomator/` | 魔改分支 |
| `shared-library/src/main/java/top/xjunz/shared/` | 公共工具 |
| `hidden-apis/src/main/java/android/` | 隐藏 API stub |
| `ssl/src/main/` | AES JNI |
