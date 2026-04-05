package one.wabbit.web.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BodySamplingSpec {
    @Test
    fun `consumeRawBodyPrefixUtf8 samples the requested prefix`() {
        runBlocking {
            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            respond(
                                content = "abcdef",
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                            )
                        }
                    }
                }

            try {
                val response = client.get("https://example.test")
                assertEquals("abc", response.consumeRawBodyPrefixUtf8(3))
                Unit
            } finally {
                client.close()
            }
        }
    }
}
