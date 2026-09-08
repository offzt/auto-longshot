package com.longshot.autoshot.service

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.longshot.autoshot.R
import com.longshot.autoshot.capture.AccessibilityScreenshotEngine
import com.longshot.autoshot.capture.SaveManager
import com.longshot.autoshot.capture.SegmentMerger
import com.longshot.autoshot.capture.ScreenshotEngine
import com.longshot.autoshot.capture.Stitcher
import com.longshot.autoshot.ui.FloatingButton
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 前台服务：截屏流程编排核心。
 *
 * 主循环（worker 线程）：滑动(无障碍拖动) → 等待稳定 → 截图 → 拼接 → 继续循环。
 * ⚠️ 循环【绝不】自行判断是否到底部/是否停止 —— 停止完全由用户触发：
 *    - 悬浮窗"停止截屏"按钮
 *    - 通知栏"停止截屏" Action
 *    - 主界面"停止并保存"按钮
 * 三者均发送 ACTION_STOP 到本服务。
 *
 * Android 15+ 兼容：MediaProjection 获取后立即 registerCallback()，再 createVirtualDisplay()。
 */
class CaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val engine = ScreenshotEngine()
    private var accEngine: AccessibilityScreenshotEngine? = null
    private var captureMode = MODE_NONE
    private var stitcher: Stitcher? = null

    private var frames = 0
    private var slideRatio = 60
    private var intervalMs = 700L
    private var outputScale = 1.0f
    private var topCrop = 0      // 状态栏高度（其内容动态变化，混入拼接会产生痕迹）
    private var bottomCrop = 0   // 底部导航栏/手势条高度
    private var floatingShown = false

    // ---- 分段续拼（长聊天记录）----
    // 内存/高度保护触顶时【不结束会话】：当前段落盘为临时 PNG，重置拼接器继续滚动，
    // 只有用户点"停止并保存"才真正结束；结束时把所有段流式合并成一张长图。
    private var segmentCount = 0
    private var currentOutW = 0
    private var segDir: File? = null
    private var autoSegmentHinted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                loadSettings()
                startCapture(resultCode, data)
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------
    // 启动
    // ------------------------------------------------------------------

    private fun startCapture(resultCode: Int, data: Intent?) {
        if (capturing) return
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Toast.makeText(this, R.string.toast_landscape, Toast.LENGTH_LONG).show()
        }

        // 模式选择：
        //  - 投影模式：有录屏授权（RESULT_OK == -1 且 data 非空）
        //  - 无障碍模式：无授权数据但 API 30+ 且无障碍已开启 → takeScreenshot 免录屏授权
        // ⚠️ 注意：Activity.RESULT_OK == -1！不能用 resultCode == -1 判断"未授权"，
        //    必须 resultCode != RESULT_OK 才算未授权（这是此前录屏授权一直失败的根因）。
        val accAvail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            CaptureAccessibilityService.instance != null
        if (resultCode != Activity.RESULT_OK || data == null) {
            if (!accAvail) {
                Toast.makeText(
                    this,
                    "未获得截屏授权，或请先开启无障碍服务（Android 11+ 可免录屏授权）",
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return
            }
            captureMode = MODE_ACCESSIBILITY
            accEngine = AccessibilityScreenshotEngine(CaptureAccessibilityService.instance!!)
            // ⚠️ 不要在此处调用 instance?.display —— 部分小米 ROM 上对无障碍服务
            // Context 取 Display 抛 UnsupportedOperationException，会崩掉整个进程
            // 连带无障碍服务一起死亡（表现为"点开始没反应"）
            Log.i(
                TAG,
                "无障碍截图模式启动，sdk=${Build.VERSION.SDK_INT}, " +
                    "capabilities=${CaptureAccessibilityService.instance?.serviceInfo?.capabilities}"
            )
        } else {
            captureMode = MODE_PROJECTION
        }

        startAsForeground()
        FloatingButton.show(this)
        floatingShown = true
        postToast("截屏已启动，请切到目标应用开始滚动（若画面无滚动请检查无障碍服务）")

        if (captureMode == MODE_PROJECTION) {
            // 投影模式必然来自"已授权"分支（resultCode!=-1 且 data!=null），显式断言以防残留
            if (data == null) {
                FloatingButton.hide(this)
                stopForegroundCompat()
                stopSelf()
                return
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = try {
                mpm.getMediaProjection(resultCode, data)
            } catch (e: Exception) {
                Log.e(TAG, "getMediaProjection 失败: ${e.message}", e)
                Toast.makeText(this, "无法获取截屏会话，请重新授权", Toast.LENGTH_LONG).show()
                // 清理已启动的前台服务与悬浮窗，避免残留
                FloatingButton.hide(this)
                stopForegroundCompat()
                stopSelf()
                return
            } ?: run {
                Toast.makeText(this, "无法获取截屏会话，请重新授权", Toast.LENGTH_LONG).show()
                FloatingButton.hide(this)
                stopForegroundCompat()
                stopSelf()
                return
            }

            // Android 15+：先注册回调，再创建 VirtualDisplay
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection 被系统停止")
                    stopCapture()
                }
            }
            projection.registerCallback(cb, mainHandler)
            projectionCallback = cb
            mediaProjection = projection
        }

        val metrics = resources.displayMetrics
        capturing = true
        frames = 0

        worker = Thread({ runCaptureLoop(metrics) }, "CaptureLoop").apply { start() }
    }

    private fun runCaptureLoop(metrics: DisplayMetrics) {
        try {
            loadSystemBarHeights()
            if (captureMode == MODE_PROJECTION) {
                val projection = mediaProjection ?: return
                engine.setup(projection, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            }

            val outW = (metrics.widthPixels * outputScale).toInt().coerceIn(480, metrics.widthPixels)
            val slidePx = (metrics.heightPixels * slideRatio / 100f).toInt()
            // 动态内存预算（按字节，非像素）：随设备当前可用内存伸缩，上下限 96~320MB
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val budget = (memInfo.availMem * 0.35f)
                .toLong()
                .coerceIn(96L * 1024 * 1024, 320L * 1024 * 1024)
            Log.i(TAG, "内存预算=${budget / 1048576}MB（可用内存 ${memInfo.availMem / 1048576}MB）")
            stitcher = Stitcher(slidePx, outW, memoryBudgetBytes = budget)
            currentOutW = outW
            // 分段状态重置（同实例内可能再次开始截屏）
            segmentCount = 0
            autoSegmentHinted = false

            // 第一帧：不滑动直接截图（截图时隐藏悬浮窗防残影；偶发失败自动重试一次）
            var first = captureFrameHidingFloating()
            if (first == null && capturing) {
                Log.w(TAG, "第一帧失败，重试一次")
                Thread.sleep(1200)
                first = captureFrameHidingFloating()
            }
            if (first == null) {
                postToast(firstFrameErrorHint())
                stopCapture()
                return
            }
            val cropped1 = cropContent(first)
            val r1 = stitcher?.addFrame(cropped1, 0) ?: 0
            if (cropped1 !== first) cropped1.recycle()
            first.recycle()
            if (r1 > 0) frames = r1
            updateUi()
            if (!capturing) return

            // 主循环：滑动 → 稳定 → 截图 → 拼接。不检查底部、不自动停止。
            var lastScrollPx = 0  // 上一次实际滑动距离（手势取消时复用，供拼接器推算重叠）
            while (capturing) {
                // 1. 滑动（等待无障碍手势完成，最多 3s；回传实际滑动像素）
                val latch = CountDownLatch(1)
                val acc = CaptureAccessibilityService.instance
                if (acc == null) {
                    postToast(getString(R.string.toast_no_accessibility))
                    notifyAccessibilityGone()
                    break
                }
                var scrolledPx = 0
                acc.scrollOnce(slideRatio) { _, px ->
                    scrolledPx = px
                    latch.countDown()
                }
                latch.await(3, TimeUnit.SECONDS)
                if (scrolledPx > 0) lastScrollPx = scrolledPx
                if (!capturing) break

                // 2. 等待滚动动画稳定
                Thread.sleep(intervalMs)

                // 3. 截图（截图时机不靠固定等待：先截两帧比对，画面完全静止才进入拼接；
                //    滑动惯性未停/动画未结束时自动延长等待，避免截到运动模糊帧污染匹配）
                val frame = captureStableFrame()
                if (frame == null) {
                    Log.w(TAG, "截图失败，稍后重试")
                    Thread.sleep(500)
                    continue
                }

                // 4. 裁剪状态栏/导航栏后拼接（携带实际滑动距离）；-1 = 达到内存/高度保护上限
                val cropped = cropContent(frame)
                var r = stitcher?.addFrame(cropped, lastScrollPx) ?: 0
                if (r == -1) {
                    // 保护触顶 → 【不结束会话】：封存当前段，重置拼接器，把触发帧放入新段继续滚
                    if (!autoSegmentHinted) {
                        autoSegmentHinted = true
                        postToast("内容较长，已自动分段拼接：无需干预，点「停止并保存」才会结束")
                    }
                    Log.i(TAG, "分段触发（${stitcher?.lastStopReason}），第 ${segmentCount + 1} 段封存")
                    sealSegment()
                    stitcher = Stitcher(slidePx, currentOutW, memoryBudgetBytes = budget)
                    r = stitcher?.addFrame(cropped, lastScrollPx) ?: 0
                }
                if (cropped !== frame) cropped.recycle()
                frame.recycle()
                if (r > 0) frames = r
                updateUi()

                // 3.5 系统内存压力看门狗（最大屏数设置已移除：高度+内存保护已足够）
                am.getMemoryInfo(memInfo)
                if (memInfo.availMem < 400L * 1024 * 1024) {
                    Log.w(TAG, "系统可用内存不足(${memInfo.availMem / 1048576}MB)，主动停止")
                    postToast("系统内存吃紧，自动停止并保存")
                    stopCapture()
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "截屏循环异常: ${e.message}", e)
            // 之前这里静默吞掉异常 → 表现为"点了开始没反应"，必须给用户可见反馈
            postToast("截屏循环异常已停止: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            finishAndSave()
        }
    }

    /**
     * 统一取帧：无障碍模式用 AccessibilityService.takeScreenshot（免录屏授权）；
     * 投影模式用 MediaProjection + ImageReader。
     * 无障碍模式遇到系统限流（INTERVAL_TIME_SHORT）会自动拉长间隔重试。
     */
    private fun captureFrame(): Bitmap? {
        if (captureMode == MODE_ACCESSIBILITY) {
            var bmp = accEngine?.capture()
            var retries = 0
            while (
                bmp == null &&
                accEngine?.lastErrorCode == AccessibilityScreenshotEngine.ERROR_INTERVAL_TIME_SHORT &&
                retries < 3
            ) {
                Log.w(TAG, "无障碍截图限流，等待 ${intervalMs * (retries + 2)}ms 重试")
                Thread.sleep(intervalMs * (retries + 2))
                bmp = accEngine?.capture()
                retries++
            }
            if (bmp == null) {
                Log.w(TAG, "无障碍截图失败 lastError=${accEngine?.lastErrorCode}")
            }
            return bmp
        }
        return engine.capture()
    }

    /**
     * 截取"已静止"的帧（社区滚动截屏通用做法）：
     * 初始等待后先截一帧，隔 250ms 再截一帧比对——两帧完全一致才认为画面静止。
     * 未静止（惯性滚动中/动画中）则丢弃重截，最多重试 3 次。
     * 好处：不再依赖固定的截图间隔去"赌"滑动已停——慢设备自动多等，快设备不多等。
     */
    private fun captureStableFrame(): Bitmap? {
        Thread.sleep(intervalMs)
        var frame = captureFrameHidingFloating() ?: return null
        var tries = 0
        while (tries < 3) {
            Thread.sleep(250)
            val next = captureFrameHidingFloating() ?: return frame
            val stable = quickHash(frame) == quickHash(next)
            next.recycle()
            if (stable) return frame
            Log.d(TAG, "画面未静止，重截（第 ${tries + 1} 次）")
            frame.recycle()
            frame = captureFrameHidingFloating() ?: return null
            tries++
        }
        return frame
    }

    /** 快速帧指纹：缩到 48x48 后 FNV 哈希，用于两帧一致性比对 */
    private fun quickHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 48, 48, true)
        val px = IntArray(48 * 48)
        s.getPixels(px, 0, 48, 0, 0, 48, 48)
        if (s !== b) s.recycle()
        var h = 1469598103934665603L
        for (p in px) h = (h xor p.toLong()) * 1099511628211L
        return h
    }

    /**
     * 截图前临时隐藏悬浮窗、截图后恢复。
     * ⚠️ 只切换 View 可见性（INVISIBLE），【不】移除/重建窗口：
     *    每帧 removeView/addView 一旦某次恢复失败，"停止截屏"按钮就永久消失（已踩坑）。
     *    窗口常驻 + INVISIBLE 同样不进截屏画面（不绘制即透明）。
     */
    private fun captureFrameHidingFloating(): Bitmap? {
        val hadFloating = floatingShown && FloatingButton.isShowing()
        if (hadFloating) {
            FloatingButton.setHiddenForCapture(true)
            Thread.sleep(160) // 等待一帧系统重绘（悬浮窗从画面消失）
        }
        val frame = captureFrame()
        if (hadFloating && capturing) {
            FloatingButton.setHiddenForCapture(false) // 恢复；窗口常驻不会失败
        }
        return frame
    }

    /** 裁剪状态栏与底部栏：其内容（时间/电量/导航条）每帧动态变化，混入拼接会产生明显接缝 */
    private fun cropContent(src: Bitmap): Bitmap {
        val h = src.height - topCrop - bottomCrop
        if (h <= 0 || (topCrop == 0 && bottomCrop == 0)) return src
        return Bitmap.createBitmap(src, 0, topCrop, src.width, h)
    }

    /** 读取系统状态栏/导航栏高度（用于裁剪） */
    private fun loadSystemBarHeights() {
        val res = resources
        val idStatus = res.getIdentifier("status_bar_height", "dimen", "android")
        topCrop = if (idStatus > 0) res.getDimensionPixelSize(idStatus) else 0
        val idNav = res.getIdentifier("navigation_bar_height", "dimen", "android")
        bottomCrop = if (idNav > 0) res.getDimensionPixelSize(idNav) else 0
        Log.i(TAG, "系统栏裁剪 top=$topCrop bottom=$bottomCrop")
    }

    /** 第一帧截屏失败时的诊断文案（按错误码给出可操作指引） */
    private fun firstFrameErrorHint(): String {
        val ec = accEngine?.lastErrorCode ?: 0
        val viaProj = "可点主界面「改用系统录屏授权方式」按钮重试"
        return when (ec) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                "截图间隔过短触发限流，请调大「截图间隔」后重试"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
                "无障碍服务未就绪，请到系统设置中关闭并重新开启本应用的无障碍服务后重试；$viaProj"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                "当前界面禁止截屏（可能开启了防截屏），请关闭后重试"
            AccessibilityScreenshotEngine.ERROR_TIMEOUT ->
                "截图回调超时，请到系统设置关闭并重新开启无障碍服务后重试；$viaProj"
            AccessibilityScreenshotEngine.ERROR_HB_NULL,
            AccessibilityScreenshotEngine.ERROR_WRAP_NULL,
            AccessibilityScreenshotEngine.ERROR_CONVERT ->
                "截图数据转换失败（错误码 $ec）；$viaProj"
            AccessibilityScreenshotEngine.ERROR_CALL_EXCEPTION ->
                "截图调用异常，可能被系统拦截；$viaProj"
            else -> "截屏初始化失败（错误码 $ec），请到设置重新开启无障碍服务，或 $viaProj"
        }
    }

    // ------------------------------------------------------------------
    // 停止与保存
    // ------------------------------------------------------------------

    private fun stopCapture() {
        if (capturing) {
            capturing = false
            mainHandler.post {
                Toast.makeText(this, R.string.toast_capture_stopped, Toast.LENGTH_SHORT).show()
            }
        }
        // worker 循环退出后由 finally 统一执行 finishAndSave()
    }

    private fun finishAndSave() {
        Log.i(TAG, "开始收尾，共 $frames 帧（mode=$captureMode 段=$segmentCount）")
        // 一帧都没截到：属初始化/截图失败（已由 firstFrameErrorHint 提示），不再弹"保存失败"误导
        if (frames == 0) {
            Log.w(TAG, "未截取到任何画面，跳过保存")
            postToast("未截取到任何画面，截屏已终止（多为无障碍服务被重置，请重新开启后重试）")
            cleanupAndStop()
            return
        }

        // 封存最后一段（若有）
        val last = stitcher?.finish()
        stitcher?.release()
        stitcher = null

        var uri: Uri? = null
        if (segmentCount == 0 && last != null) {
            // 单段快路径：与旧行为一致，直接保存位图
            uri = SaveManager.save(this, last)
            last.recycle()
        } else {
            if (last != null) {
                segmentCount++
                writeSegmentBitmap(last)
                last.recycle()
            }
            val segs = segDir?.listFiles { _, name -> name.startsWith("seg_") }
                ?.sortedBy { it.name }
                ?: emptyList()
            if (segs.isNotEmpty()) {
                // 多段：流式合并（成品图不整体驻留内存，峰值 = 一段 + 输出缓冲）
                Log.i(TAG, "开始流式合并 ${segs.size} 段 → 单张长图")
                uri = SaveManager.saveStream(this) { out ->
                    SegmentMerger.mergeSegments(segs, currentOutW, out)
                }
                cleanupSegments()
            }
        }

        if (uri != null) {
            notifyResult(uri.toString())
            postToast(getString(R.string.toast_saved, "相册"))
        } else {
            notifyResult(null)
            postToast("保存失败，请检查存储空间")
        }
        cleanupAndStop()
    }

    /** 把当前拼接画布封存为临时段 PNG（内存保护触发时调用），不结束会话 */
    private fun sealSegment() {
        val bmp = stitcher?.finish() ?: return
        stitcher?.release()
        segmentCount++
        writeSegmentBitmap(bmp)
        bmp.recycle()
    }

    private fun writeSegmentBitmap(bmp: Bitmap) {
        try {
            val dir = segDir ?: File(cacheDir, "segments").apply { mkdirs() }.also { segDir = it }
            val f = File(dir, "seg_%03d.png".format(segmentCount))
            FileOutputStream(f).use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    Log.e(TAG, "分段写盘失败: ${f.name}")
                } else {
                    Log.i(TAG, "分段已落盘: ${f.name} (${bmp.width}x${bmp.height})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "写分段文件异常: ${e.message}", e)
        }
    }

    private fun cleanupSegments() {
        try {
            segDir?.deleteRecursively()
        } catch (_: Exception) {
        }
        segDir = null
    }

    private fun cleanupAndStop() {
        // 清理
        engine.release()
        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        projectionCallback = null
        mediaProjection?.stop()
        mediaProjection = null
        FloatingButton.hide(this)
        floatingShown = false
        capturing = false
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun startAsForeground() {
        createChannel(NOTIF_CHANNEL_CAPTURE, getString(R.string.notif_channel_capture))
        val notification = buildCaptureNotification(0).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 投影模式必须用 mediaProjection 类型；无障碍模式用 specialUse 类型
            val fgsType = if (captureMode == MODE_PROJECTION) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIF_ID_CAPTURE, notification, fgsType)
        } else {
            startForeground(NOTIF_ID_CAPTURE, notification)
        }
    }

    private fun buildCaptureNotification(framesNow: Int): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_CAPTURE)
            .setContentTitle(getString(R.string.notif_title_capturing))
            .setContentText(getString(R.string.notif_text_frames, framesNow))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(
                0,
                getString(R.string.notif_action_stop),
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
    }

    private fun updateUi() {
        currentFrames = frames
        mainHandler.post {
            FloatingButton.updateFrames(frames)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_CAPTURE, buildCaptureNotification(frames).build())
        }
    }

    private fun notifyResult(uriString: String?) {
        createChannel(NOTIF_CHANNEL_RESULT, getString(R.string.notif_channel_result))
        val contentIntent = if (uriString != null) {
            PendingIntent.getActivity(
                this,
                2,
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uriString), "image/png")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_RESULT)
            .setContentTitle(getString(R.string.notif_title_done))
            .setContentText(
                if (uriString != null) getString(R.string.notif_text_done, "已保存到相册")
                else "保存失败，请重试"
            )
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setAutoCancel(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_RESULT, builder.build())
    }

    private fun createChannel(id: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** 无障碍服务被系统关闭时的醒目提醒（国产 ROM 后台清理会导致进程被杀、无障碍断开） */
    private fun notifyAccessibilityGone() {
        createChannel(NOTIF_CHANNEL_RESULT, getString(R.string.notif_channel_result))
        val settingsPi = PendingIntent.getActivity(
            this,
            3,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(
                NOTIF_ID_RESULT,
                NotificationCompat.Builder(this, NOTIF_CHANNEL_RESULT)
                    .setContentTitle("无障碍服务已被系统关闭")
                    .setContentText("点击前往设置重新开启；建议按主界面「一键保活设置」防再次被关")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(settingsPi)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {
        }
    }

    private fun postToast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    private fun loadSettings() {
        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
        slideRatio = sp.getInt("slideRatio", 60).coerceIn(10, 85)
        // 截图间隔下限 800ms：滑动停止（含抬起惯性）需要时间，截早了画面还在动会污染匹配
        intervalMs = sp.getInt("interval", 900).coerceIn(800, 5000).toLong()
        outputScale = sp.getFloat("scale", 1.0f).coerceIn(0.5f, 1.0f)
    }

    override fun onDestroy() {
        capturing = false
        FloatingButton.hide(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"

        const val ACTION_START = "com.longshot.autoshot.action.START"
        const val ACTION_STOP = "com.longshot.autoshot.action.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"

        private const val NOTIF_CHANNEL_CAPTURE = "capture"
        private const val NOTIF_CHANNEL_RESULT = "result"
        private const val NOTIF_ID_CAPTURE = 1001
        private const val NOTIF_ID_RESULT = 1002

        /** 截屏模式：未初始化 */
        private const val MODE_NONE = 0
        /** 截屏模式：无障碍 takeScreenshot（免录屏授权，API 30+） */
        private const val MODE_ACCESSIBILITY = 1
        /** 截屏模式：MediaProjection 录屏授权 */
        private const val MODE_PROJECTION = 2

        /** 是否正在截屏（跨组件读取） */
        @Volatile
        var capturing = false
            private set

        /** 当前已截帧数（主界面状态显示用） */
        @Volatile
        var currentFrames = 0

        val isCapturing: Boolean
            get() = capturing
    }
}
