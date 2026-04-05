package one.wabbit.web.common

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Consumes up to [maxLen] raw bytes from the response body channel for diagnostics and decodes
 * the sampled bytes as UTF-8.
 *
 * This is byte-count based, not character-count based. Because it reads [bodyAsChannel], the
 * sampled bytes may still be compressed or otherwise encoded depending on the active client
 * pipeline. Malformed byte sequences may decode with replacement characters. This helper is
 * destructive and should be treated as "sample and consume the raw body channel."
 */
suspend fun HttpResponse.consumeRawBodyPrefixUtf8(maxLen: Int): String {
    require(maxLen >= 0) { "maxLen must be non-negative, was $maxLen" }
    if (maxLen == 0) return ""

    val channel = bodyAsChannel()
    return try {
        val chunk = ByteArray(maxLen.coerceAtMost(BODY_PREFIX_CHUNK_SIZE))
        var remaining = maxLen
        val sink = Buffer()

        while (!channel.isClosedForRead && remaining > 0) {
            val read = channel.readAvailable(chunk, 0, minOf(chunk.size, remaining))
            if (read < 0) break
            if (read == 0) break // avoid potential busy loop
            sink.write(chunk, 0, read)
            remaining -= read
        }

        sink.readByteArray().decodeToString()
    } finally {
        channel.cancel(null)
    }
}

@Deprecated(
    message = "This helper consumes raw response bytes and decodes them as UTF-8. Use consumeRawBodyPrefixUtf8 instead.",
    replaceWith = ReplaceWith("consumeRawBodyPrefixUtf8(maxLen)"),
)
suspend fun HttpResponse.consumeBodyPrefix(maxLen: Int): String =
    consumeRawBodyPrefixUtf8(maxLen)

@Deprecated(
    message = "This helper consumes raw response bytes and decodes them as UTF-8. Use consumeRawBodyPrefixUtf8 instead.",
    replaceWith = ReplaceWith("consumeRawBodyPrefixUtf8(maxLen)"),
)
suspend fun HttpResponse.safeBodyPrefix(maxLen: Int): String =
    consumeRawBodyPrefixUtf8(maxLen)

private const val BODY_PREFIX_CHUNK_SIZE = 8192
