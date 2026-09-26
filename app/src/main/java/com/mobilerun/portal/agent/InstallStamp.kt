package com.mobilerun.portal.agent

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The dashboard's invite download stores {"token": ...} in the APK Signing Block (outside what the
 * signature covers, the Walle method; see the dashboard's apk_stamp.py). On first launch the app
 * reads it from its own APK and connects without the user tapping anything.
 */
object InstallStamp {
    const val ID = 0x46414155 // "FAAU"
    private const val EOCD_MAX = 65_557
    private val MAGIC = "APK Sig Block 42".toByteArray()

    fun token(apk: File): String? = runCatching {
        RandomAccessFile(apk, "r").use { f -> read(f) }?.let { JSONObject(String(it)).optString("token").ifBlank { null } }
    }.getOrNull()

    private fun read(f: RandomAccessFile): ByteArray? {
        val tailSize = minOf(f.length(), EOCD_MAX.toLong()).toInt()
        val tail = bytes(f, f.length() - tailSize, tailSize)
        val eocd = (tail.size - 22 downTo 0).firstOrNull { i ->
            tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()
        } ?: return null
        val cd = le(tail, eocd + 16, 4).int.toLong() and 0xffffffffL
        if (cd < 24) return null
        val footer = bytes(f, cd - 24, 24)
        if (!footer.copyOfRange(8, 24).contentEquals(MAGIC)) return null
        val size = le(footer, 0, 8).long
        val start = cd - size - 8
        if (start < 0 || size > f.length()) return null
        val block = bytes(f, start + 8, (size - 24).toInt()) // the pairs
        var pos = 0
        while (pos + 12 <= block.size) {
            val length = le(block, pos, 8).long.toInt()
            val id = le(block, pos + 8, 4).int
            if (id == ID) return block.copyOfRange(pos + 12, pos + 8 + length)
            pos += 8 + length
        }
        return null
    }

    private fun bytes(f: RandomAccessFile, at: Long, n: Int) = ByteArray(n).also { f.seek(at); f.readFully(it) }

    private fun le(b: ByteArray, at: Int, n: Int) = ByteBuffer.wrap(b, at, n).order(ByteOrder.LITTLE_ENDIAN)
}
