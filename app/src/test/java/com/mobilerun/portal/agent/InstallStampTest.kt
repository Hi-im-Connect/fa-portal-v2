package com.mobilerun.portal.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstallStampTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A zip with an APK Signing Block holding the given pairs, like the dashboard's stamped download. */
    private fun apk(vararg pairs: Pair<Int, ByteArray>): java.io.File {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("AndroidManifest.xml")); it.write("hi".toByteArray()); it.closeEntry() }
        }.toByteArray()
        val eocd = (zip.size - 22 downTo 0).first { i -> zip[i] == 0x50.toByte() && zip[i + 1] == 0x4b.toByte() && zip[i + 2] == 5.toByte() && zip[i + 3] == 6.toByte() }
        val le = { n: Int -> ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN) }
        val cd = le(4).put(zip, eocd + 16, 4).flip().let { (it as ByteBuffer).int }
        val body = ByteArrayOutputStream()
        for ((id, value) in pairs) body.write(le(12).putLong(value.size + 4L).putInt(id).array()).also { body.write(value) }
        val size = body.size() + 8L + 16
        val block = le(8).putLong(size).array() + body.toByteArray() + le(8).putLong(size).array() + "APK Sig Block 42".toByteArray()
        val out = zip.copyOfRange(0, cd) + block + zip.copyOfRange(cd, zip.size)
        val newCd = le(4).putInt(cd + block.size).array()
        System.arraycopy(newCd, 0, out, eocd + block.size + 16, 4)
        return tmp.newFile("app.apk").apply { writeBytes(out) }
    }

    @Test
    fun `reads the invite the dashboard put in the download`() {
        val file = apk(0x7109871A to "sig".toByteArray(), InstallStamp.ID to """{"token":"abc"}""".toByteArray())
        assertEquals("abc", InstallStamp.token(file))
    }

    @Test
    fun `a plain download or a broken file has no invite`() {
        assertNull(InstallStamp.token(apk(0x7109871A to "sig".toByteArray())))
        assertNull(InstallStamp.token(tmp.newFile("junk.apk").apply { writeText("not a zip") }))
    }
}
