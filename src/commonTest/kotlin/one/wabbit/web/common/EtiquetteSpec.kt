package one.wabbit.web.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EtiquetteSpec {
    @Test
    fun `reserved headers are rejected case insensitively`() {
        val userAgentError =
            assertFailsWith<IllegalArgumentException> {
                Etiquette(
                    userAgent = "agent",
                    extraHeaders = mapOf("user-agent" to "other-agent"),
                )
            }
        assertEquals("extraHeaders must not contain User-Agent", userAgentError.message)

        val refererError =
            assertFailsWith<IllegalArgumentException> {
                Etiquette(
                    userAgent = "agent",
                    extraHeaders = mapOf("REFERER" to "https://example.test"),
                )
            }
        assertEquals("extraHeaders must not contain Referer", refererError.message)
    }
}
