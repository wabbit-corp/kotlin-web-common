package one.wabbit.web.common

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Consumes up to [maxLen] bytes from the response body channel for diagnostics.
 *
 * This is byte-count based, not character-count based. The sampled bytes are decoded as UTF-8 for
 * human-readable diagnostics, and malformed byte sequences may decode with replacement
 * characters. This helper also cancels the body channel it reads from, so callers should treat
 * it as destructive and not rely on later body reads.
 */
suspend fun HttpResponse.consumeBodyPrefix(maxLen: Int): String {
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
    message = "This helper consumes the response body for diagnostics. Use consumeBodyPrefix instead.",
    replaceWith = ReplaceWith("consumeBodyPrefix(maxLen)"),
)
suspend fun HttpResponse.safeBodyPrefix(maxLen: Int): String =
    consumeBodyPrefix(maxLen)

private const val BODY_PREFIX_CHUNK_SIZE = 8192
