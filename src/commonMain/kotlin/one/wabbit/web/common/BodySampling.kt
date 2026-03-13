package one.wabbit.web.common

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable

suspend fun HttpResponse.safeBodyPrefix(maxLen: Int): String {
    require(maxLen >= 0) { "maxLen must be non-negative, was $maxLen" }
    if (maxLen == 0) return ""

    val channel = bodyAsChannel()
    return try {
        val buffer = ByteArray(maxLen)
        var offset = 0

        while (!channel.isClosedForRead && offset < maxLen) {
            val read = channel.readAvailable(buffer, offset, maxLen - offset)
            if (read < 0) break
            if (read == 0) break // avoid potential busy loop
            offset += read
        }

        buffer.decodeToString(0, offset)
    } finally {
        channel.cancel(null)
    }
}
