/*
 * Copyright (c) 2022 xjunz. All rights reserved.
 */

package top.xjunz.tasker.service.controller

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import top.xjunz.tasker.premium.PremiumMixin
import top.xjunz.tasker.service.isPremium
import top.xjunz.tasker.util.ShizukuUtil
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/**
 * @author xjunz 2022/10/10
 */
abstract class ShizukuServiceController<S : Any> : ServiceController<S>() {

    companion object {
        private const val BINDING_SERVICE_TIMEOUT_MILLS = 3000L

        // 覆盖安装/版本升级后，Shizuku 会重启 :service 远程进程；
        // 在新旧进程交接的瞬间，回调过来的 binder 可能已死。
        // 这种情况我们做有限次自动重试，让用户无感。
        private const val MAX_INVALID_BINDER_RETRY = 2
        private const val INVALID_BINDER_RETRY_DELAY_MILLS = 800L
    }

    protected abstract val tag: String

    protected abstract val userServiceStandaloneProcessArgs: Shizuku.UserServiceArgs

    protected abstract fun asInterface(binder: IBinder): IInterface

    protected abstract fun onServiceConnected(remote: IInterface)

    private var bindingJob: Job? = null

    private var invalidBinderRetryJob: Job? = null

    private var invalidBinderRetryCount = 0

    protected var serviceInterface: IInterface? = null

    private val deathRecipient: IBinder.DeathRecipient by lazy {
        IBinder.DeathRecipient {
            Log.w(tag, "The remote service is dead!")
            serviceInterface?.asBinder()?.unlinkToDeath(deathRecipient, 0)
            listener?.onServiceDisconnected()
        }
    }

    private val userServiceConnection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, ibinder: IBinder?) {
            try {
                bindingJob?.cancel()
                if (ibinder == null || !ibinder.pingBinder()) {
                    handleInvalidBinder()
                    return
                }
                invalidBinderRetryCount = 0
                invalidBinderRetryJob?.cancel()
                ibinder.linkToDeath(deathRecipient, 0)
                asInterface(ibinder).also {
                    serviceInterface = it
                    onServiceConnected(it)
                }
            } catch (t: Throwable) {
                listener?.onError(t)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {}
    }

    private fun handleInvalidBinder() {
        if (invalidBinderRetryCount < MAX_INVALID_BINDER_RETRY) {
            invalidBinderRetryCount++
            Log.w(
                tag,
                "Got an invalid binder, auto retry #$invalidBinderRetryCount in" +
                    " ${INVALID_BINDER_RETRY_DELAY_MILLS}ms"
            )
            invalidBinderRetryJob?.cancel()
            invalidBinderRetryJob = launch {
                delay(INVALID_BINDER_RETRY_DELAY_MILLS)
                bindService()
            }
            return
        }
        val finalCount = invalidBinderRetryCount
        invalidBinderRetryCount = 0
        Log.w(tag, "Got an invalid binder after $finalCount retries")
        listener?.onError(
            RuntimeException("Got an invalid binder after $finalCount retries")
        )
    }

    private fun cancelInvalidBinderRetry() {
        invalidBinderRetryJob?.cancel()
        invalidBinderRetryJob = null
        invalidBinderRetryCount = 0
    }

    protected open fun onServiceConnectionError() {

    }

    fun bindServiceOnBoot() {
        PremiumMixin.loadPremiumFromFileSafely()
        if (isPremium) {
            bindService()
        }
    }

    override fun bindService() {
        if (bindingJob?.isActive == true) return
        ShizukuUtil.ensureShizukuEnv {
            listener?.onStartBinding()
            bindingJob = async {
                Shizuku.bindUserService(userServiceStandaloneProcessArgs, userServiceConnection)
                delay(BINDING_SERVICE_TIMEOUT_MILLS)
                throw TimeoutException()
            }
            bindingJob?.invokeOnCompletion {
                bindingJob = null
                if (it != null && it !is CancellationException) {
                    listener?.onError(it)
                }
            }
        }
    }

    override fun stopService() {
        cancelInvalidBinderRetry()
        safeUnbindUserService(remove = true)
        serviceInterface = null
    }

    override fun bindExistingServiceIfExists() {
        if (ShizukuUtil.isShizukuAvailable &&
            Shizuku.peekUserService(userServiceStandaloneProcessArgs, userServiceConnection) != -1
        ) {
            bindService()
        }
    }

    override fun unbindService() {
        cancelInvalidBinderRetry()
        removeStateListener()
        // unlinkToDeath 在 deathRecipient 没注册过时会抛 NoSuchElementException，
        // Activity 重建（横屏 / 主题切换）时很容易碰到 → 用 runCatching 吞掉。
        runCatching {
            serviceInterface?.asBinder()?.unlinkToDeath(deathRecipient, 0)
        }
        safeUnbindUserService(remove = false)
    }

    /**
     * 包一层 [Shizuku.unbindUserService]，避免 Shizuku binder 还没建立 / 已断开 / 远程进程已死时
     * 抛 `IllegalStateException("binder haven't been received")` 一路冒泡到 Activity 销毁路径，
     * 让 App 在横屏 / 系统主题切换 / 任何 Activity 重建时直接 crash。
     *
     * 这里**不**用 [ShizukuUtil.isShizukuAvailable]：那个 getter 会顺带检查权限，太严；本场景只需要
     * binder 还活着即可——不活也不能强行 unbind，跳过即可。
     */
    private fun safeUnbindUserService(remove: Boolean) {
        if (!Shizuku.pingBinder()) {
            // Shizuku 远程进程不在 / 从未连接，没有可解绑的 user service。
            return
        }
        runCatching {
            Shizuku.unbindUserService(userServiceStandaloneProcessArgs, userServiceConnection, remove)
        }.onFailure { error ->
            // 中途 binder 死掉、UserService 已经被外部杀掉、版本不匹配等场景，吞掉避免影响 onDestroy。
            Log.w(tag, "Shizuku.unbindUserService failed but ignored", error)
        }
    }

    override val isServiceRunning: Boolean
        get() = serviceInterface != null && serviceInterface?.asBinder()?.pingBinder() == true
}