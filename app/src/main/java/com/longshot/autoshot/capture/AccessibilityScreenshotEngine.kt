package com.longshot.autoshot.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍截图引擎：基于 AccessibilityService.takeScreenshot()（API 30+）。
 *
 * 这是 Android 11+ 的官方截屏能力：只要用户开启了无障碍服务即可取帧，
 * **无需 MediaProjection 录制授权、无需每次会话确认** —— 彻底绕开
 * "录屏授权弹窗被 ROM 拦截/用户拒绝"导致的截图权限问题。
 *
 * 已知限制（API 30+ 系统限流）：相邻两次截图间隔过短会回调
 * ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT（errorCode=3），
 * 调用方应捕获该错误并把截图间隔翻倍重试。
 *
 * 兼容性要点：
 * 1. takeScreenshot 必须在主线程调用（部分 ROM / 系统版本在子线程调用会直接抛异常或被忽略）；
 * 2. ScreenshotResult 的硬件缓冲是共享的，需 wrapHardwareBuffer + copy 成独立位图后 close；
 * 3. 若窗口含防截屏内容（FLAG_SECURE），系统回调 SECURE_WINDOW 错误。
 */
class AccessibilityScreenshotEngine(private val service: AccessibilityService) {

    /** 最后一次失败错误码（用于诊断）：0=成功但 Bitmap 为 null；负数为本地诊断码 */
    @Volatile
    var lastErrorCode: Int = 0
        private set

    /**
     * 同步抓取一帧（内部在主线程发起调用、阻塞等待系统回调，最多 [timeoutMs] 毫秒）。
     * 成功返回 Bitmap（ARGB_8888 拷贝，调用方负责回收）；失败返回 null。
     * ⚠️ 必须在非主线程调用（内部会切到主线程发起 takeScreenshot；本方法会阻塞等待回调）。
     */
    fun capture(timeoutMs: Long = 5000): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            lastErrorCode = ERROR_OLD_API
            return null
        }
        // 防死锁：本方法会阻塞等待回调，而回调走主线程 executor，因此不能在主线程调用
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lastErrorCode = ERROR_MAIN_THREAD
            return null
        }

        lastErrorCode = 0
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var errorCode = 0
        // ⚠️ service.display 在部分小米 ROM 上会抛 UnsupportedOperationException
        // （Context 未关联 Display），必须兜底默认主屏，绝不让它崩掉进程
        val displayId = try {
            service.display?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (_: Exception) {
            Display.DEFAULT_DISPLAY
        }

        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                try {
                    val hb = screenshot.hardwareBuffer
                    if (hb != null) {
                        // wrapHardwareBuffer 是共享只读视图，必须 copy 成独立 ARGB_8888
                        val wrapped = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                        if (wrapped != null) {
                            bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped.recycle()
                        } else {
                            Log.w(TAG, "wrapHardwareBuffer 返回 null（缓冲不可读）")
                            errorCode = ERROR_WRAP_NULL
                        }
                        hb.close()
                    } else {
                        Log.w(TAG, "ScreenshotResult.hardwareBuffer 为 null")
                        errorCode = ERROR_HB_NULL
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "转换截图 Bitmap 失败: ${e.message}", e)
                    errorCode = ERROR_CONVERT
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode1: Int) {
                errorCode = errorCode1
                Log.w(TAG, "无障碍截图失败 errorCode=$errorCode1")
                latch.countDown()
            }
        }

        // takeScreenshot 必须在主线程发起
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            try {
                service.takeScreenshot(displayId, service.mainExecutor, callback)
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot 调用异常: ${e.message}", e)
                errorCode = ERROR_CALL_EXCEPTION
                latch.countDown()
            }
        }

        val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            Log.w(TAG, "截图回调超时（${timeoutMs}ms）")
            errorCode = ERROR_TIMEOUT
        }
        lastErrorCode = errorCode
        return bitmap
    }

    companion object {
        private const val TAG = "AccScreenEngine"

        /** 系统限流：相邻截图间隔太短（对应 ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT） */
        const val ERROR_INTERVAL_TIME_SHORT = 3

        // —— 本地诊断码（非系统错误码）——
        /** 截图回调超时 */
        const val ERROR_TIMEOUT = -10
        /** takeScreenshot 调用抛出异常 */
        const val ERROR_CALL_EXCEPTION = -11
        /** 硬件缓冲为 null */
        const val ERROR_HB_NULL = -12
        /** wrapHardwareBuffer 返回 null */
        const val ERROR_WRAP_NULL = -13
        /** 位图转换异常 */
        const val ERROR_CONVERT = -14
        /** API 版本低于 30（R） */
        const val ERROR_OLD_API = -15
        /** 在主线了调用（会死锁，拒绝） */
        const val ERROR_MAIN_THREAD = -16
    }
}