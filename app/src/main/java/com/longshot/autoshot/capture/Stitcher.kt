package com.longshot.autoshot.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import kotlin.math.abs

/**
 * 长图拼接器：把连续截图去掉重叠区后拼成一张长图。
 *
 * 重叠检测 = 行哈希精确匹配（社区验证方案，参考 zengfw/LongScreenShot 的 sew.c + scrollshot）：
 * 截图是无损像素，重叠区的行在两帧间【逐字节相等】。每行算 64 位 FNV 哈希，
 * 只统计"内容行"（非空白）的精确命中数——真峰几百行命中，假峰≈0，分离度数量级。
 *
 * 性能：画布行画像走【增量缓存】——画布每帧重建，但像素内容只是"旧画布 + 新帧"
 * 原样拼接，旧行哈希依然有效，每帧只对新拼入的部分补算（成本 O(新增高) 而非 O(总高)）。
 *
 * 内存保护：累计高度超过 maxTotalHeight 时 addFrame 返回 -1，调用方应停止并保存（防 OOM）。
 */
class Stitcher(
    private val slidePx: Int,            // 期望滑动距离（原始屏 px，仅无提示时用于估算）
    private val outputWidth: Int,
    private val maxTotalHeight: Int = 45_000,
    private val memoryBudgetBytes: Long = 300L * 1024 * 1024   // 画布内存预算（字节），动态按字节算而非按像素
) {

    /** 最近一次返回 -1 的原因（"屏数上限"/"内存预算"），供调用方给用户明确提示 */
    var lastStopReason: String = ""
        private set

    private var canvas: Bitmap? = null   // ARGB_8888 累计长图
    private var frameCount = 0

    /** 上一次的重叠结果（缩放后坐标），手势失败（scroll=0）时复用 */
    private var lastOverlap = -1

    /** App 固定 UI 区高度（缩放后坐标）：聊天页标题栏/输入栏每帧相同，必须裁除否则污染匹配 */
    private var fixedTop = 0
    private var fixedBottom = 0
    private var fixedDetected = false

    // 画布行画像缓存：cacheLen 之前的行哈希与 canvas 前 cacheLen 行一一对应。
    // 画布重建/裁剪时同步维护；一旦状态对不上（cacheLen != 画布高）就整体重算兜底。
    private val cacheHash = LongArray(maxTotalHeight + 1)
    private val cacheContent = BooleanArray(maxTotalHeight + 1)
    private var cacheLen = 0

    /**
     * 加入一帧截图。
     * @param actualScrollPx 本次截图前实际滚动的像素距离（原始屏坐标，手势被取消时传 0）
     * @return 累计帧数；返回 -1 表示累计高度已达内存保护上限（调用方应暂停并提示用户）
     */
    fun addFrame(frame: Bitmap, actualScrollPx: Int): Int {
        if (frame.width <= 0 || frame.height <= 0) return frameCount

        // 缩放到输出宽度（与画布统一，同时大幅降低内存）
        val scale = outputWidth.toFloat() / frame.width
        val scaled0 = if (frame.width != outputWidth) {
            val h = (frame.height.toFloat() * outputWidth / frame.width).toInt()
            Bitmap.createScaledBitmap(frame, outputWidth, h, true)
        } else {
            frame
        }

        var cur = canvas
        if (cur == null) {
            canvas = scaled0.copy(Bitmap.Config.ARGB_8888, false)
            storeProfile(scaled0, 0)   // 建立画布画像缓存（内容与 canvas 逐字节相同）
            if (scaled0 !== frame) scaled0.recycle()
            frameCount = 1
            return 1
        }

        // 第二帧起：检测 App 固定 UI（聊天页的标题栏 + 输入栏，每帧像素级相同）。
        // 不裁除的后果：①它们原样重复出现在长图里；②探针条匹配到固定栏造成假峰。
        if (!fixedDetected) {
            fixedDetected = true
            detectFixedRegions(cur, scaled0)
            if (fixedTop > 0 || fixedBottom > 0) {
                val oldH = cur.height
                val trimmed = Bitmap.createBitmap(
                    cur, 0, fixedTop, cur.width, cur.height - fixedTop - fixedBottom
                )
                cur.recycle()
                canvas = trimmed
                cur = trimmed
                // 画像缓存同步：裁掉顶部 fixedTop 行、底部 fixedBottom 行 → 整体左移
                if (cacheLen == oldH) {
                    System.arraycopy(cacheHash, fixedTop, cacheHash, 0, trimmed.height)
                    System.arraycopy(cacheContent, fixedTop, cacheContent, 0, trimmed.height)
                    cacheLen = trimmed.height
                } else {
                    cacheLen = 0
                }
                Log.i(TAG, "已裁除固定UI区 top=$fixedTop bottom=$fixedBottom")
            }
        }
        val scaled = if (fixedTop > 0 || fixedBottom > 0) {
            val t = Bitmap.createBitmap(
                scaled0, 0, fixedTop, scaled0.width, scaled0.height - fixedTop - fixedBottom
            )
            if (scaled0 !== frame) scaled0.recycle()
            t
        } else {
            scaled0
        }

        // 期望重叠 = 帧高 − 实际滑动（换算到缩放后坐标）
        val hint = if (actualScrollPx > 0) {
            (scaled.height - actualScrollPx * scale).toInt()
        } else {
            lastOverlap
        }

        // 1. 重叠搜索：prev 画像走缓存（校验失效则整体重算），curr 画像现算
        //    （curr 马上会被拼进画布，其画像随后追加进缓存）
        if (cacheLen != cur.height) {
            Log.w(TAG, "画像缓存失效(cacheLen=$cacheLen 画布=${cur.height})，整体重算")
            storeProfile(cur, 0)
        }
        val currProf = rowProfile(scaled)
        val overlap = findBestOverlap(cur.height, currProf, hint)
        lastOverlap = overlap

        // 2. 拼接（双重内存保护：像素高度硬上限 + 字节预算动态上限。
        //    预算按字节算——"Memory is the quiet killer"，像素数不等于内存成本；
        //    瞬态峰值 = merged + 旧画布 + 新帧 三者同时驻留）
        val totalH = cur.height + scaled.height - overlap
        val mergedBytes = totalH.toLong() * outputWidth * 4
        val peakBytes = mergedBytes * 2 + scaled.height.toLong() * outputWidth * 4
        if (totalH > maxTotalHeight || peakBytes > memoryBudgetBytes) {
            lastStopReason = if (peakBytes > memoryBudgetBytes) "内存预算" else "高度上限"
            Log.w(TAG, "内存保护触发（$lastStopReason）：peak=${peakBytes / 1048576}MB " +
                "budget=${memoryBudgetBytes / 1048576}MB totalH=$totalH")
            if (scaled !== frame) scaled.recycle()
            return -1
        }

        val merged = Bitmap.createBitmap(outputWidth, totalH, Bitmap.Config.ARGB_8888)
        val c = Canvas(merged)
        c.drawBitmap(cur, 0f, 0f, null)
        c.drawBitmap(scaled, 0f, (cur.height - overlap).toFloat(), null)

        cur.recycle()
        if (scaled !== frame) scaled.recycle()
        canvas = merged
        // 画像缓存增量追加：新画布的底部 = 刚拼入的 scaled 帧（像素原样，哈希可直接复用）
        if (cacheLen == cur.height) {
            System.arraycopy(currProf.first, 0, cacheHash, cur.height, scaled.height)
            System.arraycopy(currProf.second, 0, cacheContent, cur.height, scaled.height)
            cacheLen = cur.height + scaled.height
        } else {
            cacheLen = 0   // 状态对不上 → 下帧整体重算兜底
        }
        frameCount++
        Log.d(TAG, "拼接完成 frame=$frameCount overlap=$overlap hint=$hint totalH=$totalH")
        return frameCount
    }

    /** 完成拼接，返回最终长图（调用方负责回收与保存）；失败返回 null */
    fun finish(): Bitmap? {
        return canvas?.also { canvas = null }
    }

    /** 释放内部资源（未取走画布时调用） */
    fun release() {
        canvas?.recycle()
        canvas = null
        cacheLen = 0
    }

    // ------------------------------------------------------------------
    // 重叠检测：行哈希精确匹配（prev 画像来自缓存，curr 画像由调用方传入）
    // ------------------------------------------------------------------

    /**
     * 在画布 [prevH] 行与当前帧画像 [currProf] 之间寻找最优重叠行数。
     * 全范围扫描候选重叠 d，统计"内容行哈希精确相等"的行数作为得分。
     * ≥15 个内容行精确命中才可信（哈希碰撞概率可忽略，假峰到不了这个量级）。
     */
    private fun findBestOverlap(prevH: Int, currProf: Pair<LongArray, BooleanArray>, hintOverlap: Int): Int {
        val currH = currProf.first.size
        val maxOv = (minOf(prevH, currH) * 0.9f).toInt()
        val minOv = 200   // 允许惯性大滑导致的小重叠
        if (maxOv <= minOv + 8) return clampFallback(prevH, currH, hintOverlap)

        val cHh = currProf.first
        val cCt = currProf.second
        val cap = minOf(maxOv, 700)   // 每个候选最多比对 700 行，足够区分

        var bestD = -1
        var bestCnt = 0
        var d = maxOv
        while (d >= minOv) {
            var cnt = 0
            val lim = minOf(d, cap)
            var i = 0
            while (i < lim) {
                val pr = prevH - d + i
                // 内容行 + 哈希精确相等才计分；空白行不参与（任何偏移都"相等"）
                if ((cCt[i] || cacheContent[pr]) && cacheHash[pr] == cHh[i]) cnt++
                i++
            }
            if (cnt > bestCnt) {
                bestCnt = cnt
                bestD = d
            }
            d--
        }

        Log.d(TAG, "重叠搜索 best=$bestD matchedRows=$bestCnt hint=$hintOverlap cache=$cacheLen")
        return if (bestCnt >= 15 && bestD > 0) {
            bestD
        } else {
            Log.w(TAG, "精确匹配行数不足(=$bestCnt)，退化为期望重叠")
            clampFallback(prevH, currH, hintOverlap)
        }
    }

    /** 计算位图行画像并写入缓存数组 [offset] 处（cacheLen = offset + 高度） */
    private fun storeProfile(bmp: Bitmap, offset: Int) {
        val p = rowProfile(bmp)
        System.arraycopy(p.first, 0, cacheHash, offset, bmp.height)
        System.arraycopy(p.second, 0, cacheContent, offset, bmp.height)
        cacheLen = offset + bmp.height
    }

    /**
     * 行画像：宽度压到 120 列（纵向 1:1 保持行对齐），每行算 64 位 FNV 哈希；
     * 行内灰度平均绝对偏差 > 8 视为"内容行"（有文字/图形），否则为空白行。
     * 两帧对同一内容缩放结果一致 → 重叠行哈希精确相等。
     */
    private fun rowProfile(bmp: Bitmap): Pair<LongArray, BooleanArray> {
        val w = 120
        val h = bmp.height
        val s = if (bmp.width != w) {
            Bitmap.createScaledBitmap(bmp, w, h, true)
        } else {
            bmp
        }
        val px = IntArray(w)
        val hashes = LongArray(h)
        val isContent = BooleanArray(h)
        // 屏蔽最右侧 4 列（≈屏幕右边 3%）：列表滚动条所在区域，
        // 其位置随滚动变化，会让重叠区的同一行内容哈希不同 → 真峰被污染
        val validW = w - 4
        var r = 0
        while (r < h) {
            s.getPixels(px, 0, w, 0, r, w, 1)
            var hash = -3750763034362895579L   // FNV offset basis
            var sum = 0
            for (x in 0 until validW) {
                val g = (px[x] shr 16 and 0xFF) + (px[x] shr 8 and 0xFF) + (px[x] and 0xFF)
                sum += g
                hash = (hash xor g.toLong()) * 1099511628211L
            }
            hashes[r] = hash
            val mean = sum / validW
            var dev = 0
            for (x in 0 until validW) {
                val g = (px[x] shr 16 and 0xFF) + (px[x] shr 8 and 0xFF) + (px[x] and 0xFF)
                dev += abs(g / 3 - mean / validW)
            }
            isContent[r] = dev / validW > 8
            r++
        }
        if (s !== bmp) s.recycle()
        return hashes to isContent
    }


    /**
     * 检测两帧之间的"固定 UI 区"（逐行像素比对）：
     * 顶部从第 0 行起、底部从最后一行起的连续相同行块 = 每帧不变的 App 界面元素
     * （聊天页的标题栏/输入栏）。固定区必须从拼接中裁除——否则既会在长图里
     * 原样重复出现，又会污染探针匹配（输入栏是探针条的组成部分，永远"匹配成功"）。
     */
    private fun detectFixedRegions(a: Bitmap, b: Bitmap) {
        val w = 120
        val h = 400
        val sa = Bitmap.createScaledBitmap(a, w, h, true)
        val sb = Bitmap.createScaledBitmap(b, w, h, true)
        val pa = IntArray(w)
        val pb = IntArray(w)
        val ratio = a.height.toFloat() / h

        fun rowDiff(r: Int): Int {
            sa.getPixels(pa, 0, w, 0, r, w, 1)
            sb.getPixels(pb, 0, w, 0, r, w, 1)
            var sum = 0
            for (x in 0 until w) {
                val ca = pa[x]; val cb = pb[x]
                sum += abs((ca shr 16 and 0xFF) - (cb shr 16 and 0xFF)) +
                    abs((ca shr 8 and 0xFF) - (cb shr 8 and 0xFF)) +
                    abs((ca and 0xFF) - (cb and 0xFF))
            }
            return sum / (w * 3)
        }

        var topRows = 0
        while (topRows < h && rowDiff(topRows) <= STATIC_DIFF_T) topRows++
        var botRows = 0
        while (botRows < h && rowDiff(h - 1 - botRows) <= STATIC_DIFF_T) botRows++

        sa.recycle(); sb.recycle()

        val cap = (a.height * 0.3f).toInt()
        fixedTop = if (topRows * ratio >= 60f) minOf((topRows * ratio).toInt(), cap) else 0
        fixedBottom = if (botRows * ratio >= 60f) minOf((botRows * ratio).toInt(), cap) else 0
        // 中间内容所剩无几说明页面几乎没滚动，检测结果不可信 → 放弃裁除
        if (fixedTop + fixedBottom > a.height / 2) {
            fixedTop = 0; fixedBottom = 0
        }
    }

    /** 兜底：优先用"帧高 − 实际滑动"的期望值，其次用"帧高 − 期望滑动"估算，钳制到合法范围 */
    private fun clampFallback(prevH: Int, currH: Int, hintOverlap: Int): Int {
        val maxOv = (minOf(prevH, currH) * 0.9f).toInt()
        val probe = minOf(160, maxOv / 2).coerceAtLeast(40)
        val est = if (hintOverlap in probe..maxOv) {
            hintOverlap
        } else {
            prevH - slidePx
        }
        return est.coerceIn(probe, maxOv)
    }

    companion object {
        private const val TAG = "Stitcher"

        /** 固定区行判定阈值：缩小图逐行平均 RGB 差 ≤ 该值视为"同一行内容没变" */
        private const val STATIC_DIFF_T = 8
    }
}
