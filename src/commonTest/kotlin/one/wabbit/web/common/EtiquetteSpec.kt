package one.wabbit.web.common

import io.ktor.http.IllegalHeaderNameException
import io.ktor.http.IllegalHeaderValueException
import kotlin.collections.mutableMapOf
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

    @Test
    fun `extraHeaders are defensively copied`() {
        val extraHeaders = mutableMapOf("X-Test" to "before")
        val etiquette = Etiquette(userAgent = "agent", extraHeaders = extraHeaders)

        extraHeaders["X-Test"] = "after"

        assertEquals("before", etiquette.extraHeaders["X-Test"])
    }

    @Test
    fun `invalid header names and values are rejected`() {
        assertFailsWith<IllegalHeaderNameException> {
            Etiquette(userAgent = "agent", extraHeaders = mapOf("Bad Header" to "value"))
        }

        assertFailsWith<IllegalHeaderValueException> {
            Etiquette(userAgent = "agent", extraHeaders = mapOf("X-Test" to "bad\u0001value"))
        }
    }
}
