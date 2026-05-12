// IRemoteAutomatorService.aidl
package top.xjunz.tasker.service;

import top.xjunz.tasker.task.runtime.IRemoteTaskManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import java.util.List;
import top.xjunz.tasker.engine.dto.XTaskDTO;
import top.xjunz.tasker.task.runtime.ITaskCompletionCallback;
import android.os.ResultReceiver;

interface IRemoteAutomatorService {

    void connect(boolean acquireWakeLock, in ResultReceiver callback) = 1;

    long getStartTimestamp() = 2;

    boolean isConnected() = 4;

    IRemoteTaskManager getTaskManager() = 8;

    void setSystemTypefaceSharedMemory(in SharedMemory mem) = 9;

    void suppressResidentTaskScheduler(boolean suppress) = 10;

    void scheduleOneshotTask(long id, in ITaskCompletionCallback callback) = 11;

    void stopOneshotTask(long id) = 12;

    void setPremiumContextStoragePath(in String path) = 13;

    void loadPremiumContext() = 14;

    void acquireWakeLock() = 15;

    void releaseWakeLock() = 16;

    /**
     * 让主进程拿到当前焦点窗口节点树的压缩 JSON。
     * 在特权进程里调本地 uiAutomation.rootInActiveWindow + StableNodeInfo.freeze + AiNodeTreeCompactor。
     * 主进程拿到 JSON 后用 AiJson 反序列化为 AiUiSnapshot 即用。
     *
     * 这一条是 AI agent 在 Shizuku 模式下能"看见屏幕"的唯一新增 IPC 契约：
     * agent 的"执行动作"复用 scheduleOneshotTask，不再扩 AIDL。
     */
    String captureUiSnapshotJson(int maxNodes, int maxTextLen) = 17;

    /**
     * AI agent 「按 target 描述执行单步动作」的就地组装入口（见 aidoc/17）。
     *
     * 跟 [scheduleOneshotTask] 的差别：[scheduleOneshotTask] 要求调用方把整棵 task 树先在
     * 主进程组好，但 AI agent 只持有 [AiUiTarget] 弱字段定位，主进程拿不到真节点，**没法**
     * 抽出跟 inspector 自动点击同款的完整 criteria（11+ 字段），命中范围太宽屡屡误命中。
     *
     * 此方法把"target → 真节点 → NodeCriteriaExtractor 抽完整 criteria → 包成 task → 跑"
     * 整段挪到执行端进程内部完成，复用 inspector 已 work 的链路，AI agent 跟 inspector 真正等价。
     *
     * 参数：
     * - actionType：1=click / 2=longClick / 3=setText
     * - targetJson：序列化的 AiUiTarget（含 viewId/textEquals/contentDescEquals/className 等弱字段）
     * - extraText：仅 setText 用，其他类型传 null
     * - callback：执行完成回调，isSuccessful 真实反映 task 树是否跑通（命中节点 + perform 成功）
     */
    /**
     * extraText：仅 setText 用；click / longClick 等无文本动作传**空字符串**而非 null
     * （AIDL `@nullable` 在 Shizuku binder 路径 + 旧 Kotlin override 上兼容性差，传空字符串最稳）。
     * service 端用 `extraText.takeIf { it.isNotEmpty() }` 当 null 处理。
     */
    void executeAgentActionByTarget(int actionType, String targetJson, String extraText, in ITaskCompletionCallback callback) = 18;

    /**
     * 拉一段执行端进程**最近一次** [executeAgentActionByTarget] 的诊断信息（aidoc/18 §3.E.2 + §3.D）。
     *
     * - main 端在 callback isSuccessful=false 后调本方法，拿到精细原因（哪些字段不符 / 节点不可编辑等），
     *   写进 step.result.message 喂给 AI 反思。
     * - 失败 / 服务未启动 / 没有诊断时返回 ""，调用方按 isNullOrBlank 兼容。
     * - 单实例 agent session 串行跑，不存在并发覆盖问题；多 agent 场景需要扩成 keyed map（暂不需要）。
     */
    String getLastAgentDiagnostic() = 19;

    oneway void destroy() = 16777114; // Destroy method defined by Shizuku server

}