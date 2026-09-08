package com.longshot.autoshot.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保存管理器：把拼接好的长图写入系统相册 Pictures/LongScreenshots。
 * API 29+ 走 MediaStore 无需存储权限；API 26-28 依赖 WRITE_EXTERNAL_STORAGE 权限（Manifest 已声明）。
 */
object SaveManager {

    private const val TAG = "SaveManager"

    /** 保存长图，返回相册 Uri；失败返回 null */
    fun save(context: Context, bitmap: Bitmap): Uri? {
        return saveWith(context) { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    /**
     * 流式保存：内容由 [writer] 直接写入相册输出流（用于分段合并，
     * 避免把最终长图整体加载进内存）。writer 返回 false 视为失败。
     */
    fun saveStream(context: Context, writer: (OutputStream) -> Boolean): Uri? {
        return saveWith(context) { stream -> writer(stream) }
    }

    /** MediaStore 流程统一封装：插入 → 写入 → 结束挂起状态 */
    private fun saveWith(context: Context, writeTo: (OutputStream) -> Boolean): Uri? {
        return try {
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "自动长截图_$time.png"
            val mimeType = "image/png"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/LongScreenshots"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = context.contentResolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore 插入失败")

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                if (!writeTo(stream)) {
                    throw IllegalStateException("写入失败")
                }
            } ?: throw IllegalStateException("无法打开输出流")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }

            Log.d(TAG, "已保存: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "保存失败: ${e.message}", e)
            null
        }
    }
}
