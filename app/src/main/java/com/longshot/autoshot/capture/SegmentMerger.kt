package com.longshot.autoshot.capture

import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 分段长图合并器：把内存保护触发时分段落盘的 PNG 段，流式合并成一张长图。
 *
 * 思路（社区"成品图从不整体驻留内存"的落地版）：
 * 1. 各段 PNG 只按需逐个解码（峰值内存 = 一段 + 输出缓冲，与总长度无关）；
 * 2. 每段按行取像素，经 deflate 流式写入最终 PNG——成品图从未完整存在于内存。
 *
 * PNG 手写实现：signature + IHDR(RGBA8) + 多个 IDAT(zlib 流分块) + IEND。
 * 高度在 IHDR 中直接写入真实值（先通过 inJustDecodeBounds 拿到各段高度求和）。
 */
object SegmentMerger {

    private const val TAG = "SegmentMerger"

    /**
     * 把 [segments]（等宽 PNG）合并写入 [out]。
     * @return 成功与否
     */
    fun mergeSegments(segments: List<File>, width: Int, out: OutputStream): Boolean {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val heights = IntArray(segments.size)
            var totalH = 0
            segments.forEachIndexed { i, f ->
                BitmapFactory.decodeFile(f.absolutePath, opts)
                val h = opts.outHeight
                if (h <= 0) return false
                heights[i] = h
                totalH += h
            }
            if (totalH <= 0) return false

            val writer = PngWriter(out, width, totalH)
            segments.forEachIndexed { i, f ->
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return false
                if (bmp.width != width) return false
                val row = IntArray(width)
                for (r in 0 until bmp.height) {
                    bmp.getPixels(row, 0, width, 0, r, width, 1)
                    writer.writeRow(row)
                }
                bmp.recycle()
            }
            writer.finish()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "合并失败: ${e.message}", e)
            return false
        }
    }

    /** 流式 PNG 写入器：逐行喂入 RGBA 像素，内部按 1MB 缓冲分块写 IDAT */
    private class PngWriter(out: OutputStream, width: Int, height: Int) {

        private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // raw deflate（无 zlib 头）
        private val adler = Adler32()
        private val crc = CRC32()
        private val out = out
        private val deflateBuf = ByteArray(1 shl 16)
        private val rowBuf = ByteArray(width * 4 + 1)
        private val chunkBuf = java.io.ByteArrayOutputStream(1 shl 20)
        private val intBuf = ByteArray(4)

        init {
            writeSignature()
            writeIHDR(width, height)
            // zlib 流头（CMF/FLG：32K 窗口 + 默认压缩）
            chunkBuf.write(0x78)
            chunkBuf.write(0x01)
        }

        fun writeRow(px: IntArray) {
            rowBuf[0] = 0 // 滤波类型 None
            var j = 1
            for (p in px) {
                rowBuf[j++] = (p shr 16 and 0xFF).toByte()
                rowBuf[j++] = (p shr 8 and 0xFF).toByte()
                rowBuf[j++] = (p and 0xFF).toByte()
                rowBuf[j++] = (p shr 24 and 0xFF).toByte()
            }
            adler.update(rowBuf, 0, rowBuf.size)
            deflater.setInput(rowBuf)
            pump()
        }

        fun finish() {
            deflater.finish()
            pump()
            flushChunk()
            // zlib 校验尾（adler32）作为最后一个 IDAT 分块
            val ad = adler.value
            intBuf[0] = (ad ushr 24).toByte()
            intBuf[1] = (ad ushr 16).toByte()
            intBuf[2] = (ad ushr 8).toByte()
            intBuf[3] = ad.toByte()
            writeChunk("IDAT", intBuf, 4)
            writeChunk("IEND", ByteArray(0), 0)
            out.flush()
            deflater.end()
        }

        private fun pump() {
            while (true) {
                val n = deflater.deflate(deflateBuf)
                if (n <= 0) break
                chunkBuf.write(deflateBuf, 0, n)
                if (chunkBuf.size() >= (1 shl 20)) flushChunk()
            }
        }

        private fun flushChunk() {
            if (chunkBuf.size() == 0) return
            val data = chunkBuf.toByteArray()
            chunkBuf.reset()
            writeChunk("IDAT", data, data.size)
        }

        private fun writeChunk(type: String, data: ByteArray, len: Int) {
            putInt(len)
            out.write(type.toByteArray(Charsets.US_ASCII))
            crc.reset()
            crc.update(type.toByteArray(Charsets.US_ASCII))
            crc.update(data, 0, len)
            out.write(data, 0, len)
            putInt(crc.value.toInt())
        }

        private fun putInt(v: Int) {
            intBuf[0] = (v ushr 24).toByte()
            intBuf[1] = (v ushr 16).toByte()
            intBuf[2] = (v ushr 8).toByte()
            intBuf[3] = v.toByte()
            out.write(intBuf, 0, 4)
        }

        private fun writeSignature() {
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        }

        private fun writeIHDR(w: Int, h: Int) {
            val d = ByteArray(13)
            d[0] = (w ushr 24).toByte(); d[1] = (w ushr 16).toByte()
            d[2] = (w ushr 8).toByte(); d[3] = w.toByte()
            d[4] = (h ushr 24).toByte(); d[5] = (h ushr 16).toByte()
            d[6] = (h ushr 8).toByte(); d[7] = h.toByte()
            d[8] = 8   // bit depth
            d[9] = 6   // color type RGBA
            d[10] = 0  // compression
            d[11] = 0  // filter
            d[12] = 0  // interlace
            writeChunk("IHDR", d, 13)
        }
    }
}
