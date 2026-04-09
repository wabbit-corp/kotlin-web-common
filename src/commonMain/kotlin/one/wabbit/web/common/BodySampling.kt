// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.common

import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

@Serializable
data class BodyPrefixUtf8Sample(
    val text: String,
    val bytesRead: Int,
    val limitReached: Boolean,
)

/**
 * Consumes up to [maxLen] bytes from the response body channel for diagnostics and decodes the
 * sampled bytes as UTF-8.
 *
 * This is byte-count based, not character-count based. The bytes come from [bodyAsChannel], so
 * they reflect Ktor's response-body pipeline rather than a charset-aware text decode. Malformed
 * byte sequences may decode with replacement characters. This helper is destructive and should be
 * treated as "sample and consume the body channel."
 */
suspend fun HttpResponse.consumeBodyPrefixUtf8Sample(maxLen: Int): BodyPrefixUtf8Sample {
    require(maxLen >= 0) { "maxLen must be non-negative, was $maxLen" }
    if (maxLen == 0) {
        return BodyPrefixUtf8Sample(
            text = "",
            bytesRead = 0,
            limitReached = false,
        )
    }

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

        val bytes = sink.readByteArray()
        BodyPrefixUtf8Sample(
            text = bytes.decodeToString(),
            bytesRead = bytes.size,
            limitReached = bytes.size == maxLen,
        )
    } finally {
        channel.cancel(null)
    }
}

/**
 * Consumes up to [maxLen] bytes from the response body channel for diagnostics and returns the
 * UTF-8-decoded text prefix only.
 */
suspend fun HttpResponse.consumeRawBodyPrefixUtf8(maxLen: Int): String =
    consumeBodyPrefixUtf8Sample(maxLen).text

/**
 * Best-effort variant of [consumeRawBodyPrefixUtf8] that returns `null` if sampling fails.
 */
suspend fun HttpResponse.consumeRawBodyPrefixUtf8OrNull(maxLen: Int): String? =
    runCatching { consumeRawBodyPrefixUtf8(maxLen) }.getOrNull()

/**
 * Best-effort body sample for Ktor [ResponseException] errors.
 */
suspend fun ResponseException.responseBodySampleOrNull(maxLen: Int = 2048): String? =
    response.consumeRawBodyPrefixUtf8OrNull(maxLen)

private const val BODY_PREFIX_CHUNK_SIZE = 8192
