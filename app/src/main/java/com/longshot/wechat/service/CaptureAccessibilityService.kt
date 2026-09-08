package com.longshot.wechat.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * 无障碍服务：负责在微信聊天界面模拟"拖动"手势向上滚动。
 *
 * 拖动（drag）优于 fling 的原因：微信列表对拖动是"跟手"滚动、无惯性，
 * 内容滚动距离 ≈ 手势位移，便于拼接器用固定偏移 + 行匹配去重。
 *
 * 注意：本服务只负责滑动，不负责判断是否停止 ——
 * 停止完全由用户点击悬浮窗/通知栏按钮触发（见 CaptureService）。
 */
class CaptureAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // 本服务只做手势，不解析窗口内容
    }

    override fun onInterrupt() {
        // ignore
    }

    /**
     * 执行一次向上拖动。
     * @param slideRatio 滑动距离 = 内容高度百分比（如 60 表示滑动 60% 屏幕高度）
     * @param onDone 手势完成后回调（主线程）；参数2 = 实际滑动像素距离（供拼接器推算重叠）。
     *               false 表示手势被系统取消（如屏幕被触碰），实际距离为 0。
     *               距离会被几何钳制（终点不得越过屏幕上沿保护区），因此必须回传真实值。
     */
    fun scrollOnce(slideRatio: Int, onDone: (Boolean, Int) -> Unit) {
        val metrics = resources.displayMetrics
        val height = metrics.heightPixels
        val width = metrics.widthPixels
        if (width <= 0 || height <= 0) {
            onDone(false, 0)
            return
        }

        // 手势起点：屏幕水平中心，纵向约 62% 处（避开顶部状态栏与底部输入栏）
        val startY = (height * 0.62f).toInt()
        // 终点保护线：屏幕上沿 12% 处（状态栏/标题栏之下）
        val topGuard = (height * 0.12f).toInt()
        // 实际滑动距离 = 期望值与几何上限取小（钳制后必须如实回传，拼接器靠它推算重叠）
        val distance = minOf(
            (height * (slideRatio.coerceIn(10, 85)) / 100f).toInt(),
            startY - topGuard
        ).coerceAtLeast(height / 10)
        val endY = startY - distance
        val centerX = width / 2

        // ⚠️ 单段拖动（400ms）。曾试验"两段变速拖动"消除抬起惯性，但在
        // MIUI Android17 预览版上连续两次触发 system_server 重启（手机整机关机重启），
        // 多段手势注入在该 ROM 上不稳定，禁止再用。抬起惯性导致的滑动距离波动
        // 由 Stitcher 的"双探针一致性匹配"兜底（见 Stitcher.findBestOverlap）。
        val path = Path().apply {
            moveTo(centerX.toFloat(), startY.toFloat())
            lineTo(centerX.toFloat(), endY.toFloat())
        }
        // 1000ms 慢拖：抬起速度 = 距离/时长，时长拉长 → 抬起惯性（fling）能量按平方下降，
        // 实际滑动更贴近手指位移，给拼接器稳定的几何期望（单段手势，勿改多段——会触发本机系统重启）
        val stroke = GestureDescription.StrokeDescription(path, 0, 1000L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "滑动完成: $distance px")
                    mainHandler.post { onDone(true, distance) }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "滑动被取消")
                    mainHandler.post { onDone(false, 0) }
                }
            },
            mainHandler
        )

        if (!dispatched) {
            Log.w(TAG, "dispatchGesture 返回 false（服务可能未连接）")
            mainHandler.post { onDone(false, 0) }
        }
    }

    companion object {
        private const val TAG = "CaptureAccessibility"

        @Volatile
        var instance: CaptureAccessibilityService? = null
            private set

        /** 无障碍服务是否已开启 */
        val isConnected: Boolean
            get() = instance != null
    }
}
