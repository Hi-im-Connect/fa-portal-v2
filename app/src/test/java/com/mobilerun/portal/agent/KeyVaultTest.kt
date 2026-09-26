package com.mobilerun.portal.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeyVaultTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val xor = object : SecretBox {
        override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    @Test
    fun `the key is stored sealed and survives a restart`() {
        val file = tmp.newFile("key.json")
        KeyVault(file, xor).save("sk-or-v1-secret", "hash1")
        val vault = KeyVault(file, xor)
        assertEquals("sk-or-v1-secret", vault.key())
        assertEquals("hash1", vault.hash())
        assertFalse(file.readText().contains("sk-or-v1-secret"))
        vault.clear()
        assertNull(vault.key())
        assertEquals("", vault.hash())
    }
}
