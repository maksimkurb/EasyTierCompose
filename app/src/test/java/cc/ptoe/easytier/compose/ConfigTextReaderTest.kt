package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.core.MAX_CONFIG_BYTES
import cc.ptoe.easytier.compose.core.readLimitedConfigText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ConfigTextReaderTest {

    @Test
    fun `decodes utf 16 little endian with bom`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(Charsets.UTF_16LE)
        val result = readLimitedConfigText(ByteArrayInputStream(bytes))
        assertEquals("hello", result.text)
        assertFalse(result.tooLarge)
    }

    @Test
    fun `decodes utf 8 with bom and strips nul bytes`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "a\u0000b".toByteArray(Charsets.UTF_8)
        val result = readLimitedConfigText(ByteArrayInputStream(bytes))
        assertEquals("ab", result.text)
        assertFalse(result.tooLarge)
    }

    @Test
    fun `reports documents above the size limit`() {
        val result = readLimitedConfigText(ByteArrayInputStream(ByteArray(MAX_CONFIG_BYTES + 1) { 'a'.code.toByte() }))
        assertTrue(result.tooLarge)
        assertEquals(MAX_CONFIG_BYTES, result.text.length)
    }
}
