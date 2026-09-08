package com.longshot.wechat.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer

/**
 * 截屏引擎：基于 MediaProjection + ImageReader 抓取当前屏幕帧，转为 Bitmap。
 *
 * 兼容 Android 15+ 的 MediaProjection 生命周期要求：
 * 调用方必须先 mediaProjection.registerCallback(...) 再 createVirtualDisplay()，
 * 且注册回调与创建 VirtualDisplay 必须在同一进程中（本应用单进程，天然满足）。
 */
class ScreenshotEngine {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Volatile
    private var released = false

    private var width = 0
    private var height = 0

    /**
     * 建立虚拟显示。调用前必须已完成 mediaProjection.registerCallback()。
     */
    fun setup(projection: MediaProjection, screenWidth: Int, screenHeight: Int, densityDpi: Int) {
        releaseQuietly()
        released = false
        width = screenWidth
        height = screenHeight
        mediaProjection = projection

        captureThread = HandlerThread("ScreenCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        virtualDisplay = projection.createVirtualDisplay(
            "WeChatLongShotCapture",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler
        )
        Log.d(TAG, "VirtualDisplay 已创建: ${screenWidth}x${screenHeight}")
    }

    /**
     * 抓取一帧并转为 Bitmap。
     * 首次调用时 VirtualDisplay 可能还没产生帧，内部最多等待 1.5s。
     * 返回的 Bitmap 为 ARGB_8888（调用方负责回收；拼接时统一转 RGB_565）。
     */
    fun capture(): Bitmap? {
        if (released) return null
        val reader = imageReader ?: return null

        var image: Image? = null
        var bitmap: Bitmap? = null
        try {
            // 循环取最新帧：MediaProjection 只在画面变化时产生新帧，
            // 滚动停止后画面静止，取"最新一帧"即当前画面。
            var attempts = 0
            while (attempts < 30) {
                image = reader.acquireLatestImage()
                if (image != null) break
                Thread.sleep(50)
                attempts++
            }
            if (image == null) {
                Log.w(TAG, "1.5s 内未获取到帧")
                return null
            }

            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // 去掉行填充，裁剪为真实尺寸
            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                bitmap = cropped
            }
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "截屏失败: ${e.message}", e)
            return null
        } finally {
            image?.close()
        }
    }

    /** 停止并释放所有资源 */
    fun release() {
        released = true
        releaseQuietly()
    }

    private fun releaseQuietly() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            captureThread?.quitSafely()
        } catch (_: Exception) {
        }
        captureThread = null
        captureHandler = null
        // mediaProjection 由 CaptureService 统一 stop，这里不重复 stop
        mediaProjection = null
    }

    companion object {
        private const val TAG = "ScreenshotEngine"
    }
}
