// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License-1.1

package one.wabbit.web.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BodySamplingSpec {
    @Test
    fun `consumeBodyPrefixUtf8Sample reports prefix metadata`() {
        runTest {
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
                val sample = response.consumeBodyPrefixUtf8Sample(3)
                assertEquals("abc", sample.text)
                assertEquals(3, sample.bytesRead)
                assertTrue(sample.limitReached)
                Unit
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `consumeBodyPrefixUtf8Sample distinguishes complete samples from limit reached`() {
        runTest {
            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            respond(
                                content = "abc",
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                            )
                        }
                    }
                }

            try {
                val response = client.get("https://example.test")
                val sample = response.consumeBodyPrefixUtf8Sample(10)
                assertEquals("abc", sample.text)
                assertEquals(3, sample.bytesRead)
                assertFalse(sample.limitReached)
                Unit
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `consumeRawBodyPrefixUtf8 preserves legacy string helper behavior`() {
        runTest {
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

    @Test
    fun `consumeRawBodyPrefixUtf8OrNull returns sampled text when sampling succeeds`() {
        runTest {
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
                assertEquals("abc", response.consumeRawBodyPrefixUtf8OrNull(3))
                Unit
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `responseBodySampleOrNull returns a best effort sample from response exceptions`() {
        runTest {
            val client =
                HttpClient(MockEngine) {
                    expectSuccess = true
                    engine {
                        addHandler {
                            respond(
                                content = """{"error":"bad request"}""",
                                status = HttpStatusCode.BadRequest,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    }
                }

            try {
                val error =
                    assertIs<ResponseException>(
                        runCatching { client.get("https://example.test") }.exceptionOrNull(),
                    )
                val sample = error.responseBodySampleOrNull()
                assertNotNull(sample)
                assertTrue(sample.contains("bad request"))
                Unit
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `consumeBodyPrefixUtf8Sample ignores declared charset and decodes diagnostically as UTF-8`() {
        runTest {
            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            respond(
                                content = byteArrayOf(0xE9.toByte()),
                                headers =
                                    headersOf(
                                        HttpHeaders.ContentType,
                                        "text/plain; charset=ISO-8859-1",
                                    ),
                            )
                        }
                    }
                }

            try {
                val response = client.get("https://example.test")
                val sample = response.consumeBodyPrefixUtf8Sample(10)
                assertEquals("\uFFFD", sample.text)
                assertEquals(1, sample.bytesRead)
                assertFalse(sample.limitReached)
                Unit
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `consumeBodyPrefixUtf8Sample returns compressed bytes as diagnostic text`() {
        runTest {
            val gzipAbc =
                byteArrayOf(
                    31,
                    139.toByte(),
                    8,
                    0,
                    109,
                    20,
                    211.toByte(),
                    105,
                    0,
                    3,
                    75,
                    76,
                    74,
                    6,
                    0,
                    194.toByte(),
                    65,
                    36,
                    53,
                    3,
                    0,
                    0,
                    0,
                )
            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            respond(
                                content = gzipAbc,
                                headers =
                                    headersOf(
                                        HttpHeaders.ContentType to listOf(ContentType.Text.Plain.toString()),
                                        HttpHeaders.ContentEncoding to listOf("gzip"),
                                    ),
                            )
                        }
                    }
                }

            try {
                val response = client.get("https://example.test")
                val sample = response.consumeBodyPrefixUtf8Sample(100)
                assertNotEquals("abc", sample.text)
                assertEquals(gzipAbc.size, sample.bytesRead)
                assertFalse(sample.limitReached)
                Unit
            } finally {
                client.close()
            }
        }
    }
}
