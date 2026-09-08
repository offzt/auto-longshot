package com.longshot.autoshot.ui

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.longshot.autoshot.R
import com.longshot.autoshot.service.CaptureService

/**
 * 悬浮停止按钮：截屏过程中悬浮在屏幕上的"停止截屏"按钮。
 * 点击后向 CaptureService 发送停止指令 —— 这是用户手动结束截屏的入口之一（另一个是通知栏按钮）。
 * 程序自身不会自动停止。
 */
object FloatingButton {

    private var view: View? = null
    private var wm: WindowManager? = null
    private var tvFrames: TextView? = null

    /** 显示悬浮按钮（需已授予 SYSTEM_ALERT_WINDOW 权限） */
    fun show(context: Context) {
        hide(context)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val inflater = LayoutInflater.from(context)
        val v = inflater.inflate(R.layout.floating_button, null) ?: return

        tvFrames = v.findViewById(R.id.tvFrames)
        v.setOnClickListener {
            // 用户点击 → 停止截屏
            context.startService(
                Intent(context, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_STOP)
            )
            hide(context)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 注：FLAG_NOT_MIRRORED 是隐藏 API 无法使用；防入镜由"截图瞬间切 INVISIBLE"
            // （窗口常驻不销毁，见 setHiddenForCapture）统一解决
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 300
        }

        try {
            wm.addView(v, lp)
            this.wm = wm
            this.view = v
        } catch (e: Exception) {
            // 无悬浮窗权限时静默失败（主界面已做引导）
        }
    }

    /** 更新已截屏数 */
    fun updateFrames(frames: Int) {
        tvFrames?.text = if (frames > 0) "${frames} 屏" else ""
    }

    fun isShowing(): Boolean = view != null

    /**
     * 截图瞬间临时隐藏/恢复（只切 View 可见性，窗口常驻）。
     * 之前每帧 removeView/addView 销毁重建窗口，恢复偶发失败导致按钮永久消失；
     * INVISIBLE 不绘制任何内容 → 截屏画面中同样不可见，但窗口结构不动、绝不丢按钮。
     * ⚠️ 调用方在截屏工作线程：View.setVisibility 必须在创建它的主线程执行，post 切换。
     */
    fun setHiddenForCapture(hide: Boolean) {
        val v = view ?: return
        v.post { v.visibility = if (hide) View.INVISIBLE else View.VISIBLE }
    }

    fun hide(context: Context) {
        try {
            view?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        view = null
        wm = null
        tvFrames = null
    }
}
