package cc.ptoe.easytier.compose.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset

/** A configuration document read from the network or a content provider. */
internal data class ConfigText(val text: String, val tooLarge: Boolean)

internal const val MAX_CONFIG_BYTES = 1_048_576

/**
 * Fetches a configuration over HTTP(S) on the calling thread (callers run this
 * on Dispatchers.IO). Only the URL the user typed is requested: no automatic
 * retries, redirects are followed by [HttpURLConnection] itself, and the body
 * is capped at [MAX_CONFIG_BYTES].
 */
internal fun fetchConfigText(uri: URI): ConfigText {
    val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
    }
    return try {
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        readLimitedConfigText(connection.inputStream)
    } finally {
        connection.disconnect()
    }
}

/**
 * Reads at most [MAX_CONFIG_BYTES] bytes, decoding the content by its byte
 * order mark. Config files saved as UTF-16 (e.g. from Windows Notepad) would
 * otherwise decode as UTF-8 with a NUL interleaved per character, which TOML
 * rejects ("Unexpected \u0000").
 */
internal fun readLimitedConfigText(stream: InputStream): ConfigText {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (bytes.size() < MAX_CONFIG_BYTES) {
        val read = stream.read(buffer)
        if (read <= 0) break
        val room = MAX_CONFIG_BYTES - bytes.size()
        bytes.write(buffer, 0, minOf(read, room))
    }
    val tooLarge = stream.read() != -1
    return ConfigText(decodeConfigBytes(bytes.toByteArray()), tooLarge)
}

private fun decodeConfigBytes(content: ByteArray): String {
    val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    val body: ByteArray
    val charset: Charset
    when {
        content.startsWith(utf16LeBom) -> {
            body = content.copyOfRange(2, content.size)
            charset = Charsets.UTF_16LE
        }

        content.startsWith(utf16BeBom) -> {
            body = content.copyOfRange(2, content.size)
            charset = Charsets.UTF_16BE
        }

        content.startsWith(utf8Bom) -> {
            body = content.copyOfRange(3, content.size)
            charset = Charsets.UTF_8
        }

        else -> {
            body = content
            charset = Charsets.UTF_8
        }
    }
    // NUL defense: stray padding, or UTF-16 misread as UTF-8 by a BOM-less path.
    return String(body, charset).replace("\u0000", "")
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) {
        if (this[i] != prefix[i]) return false
    }
    return true
}
