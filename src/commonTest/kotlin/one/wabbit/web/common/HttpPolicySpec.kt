package one.wabbit.web.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HttpPolicySpec {
    @Test
    fun `idempotent default policy retries transient transport failures`() {
        val connectRun = httpIdempotentDefaultPolicy().newRun()
        assertNotNull(connectRun.nextDelay(ConnectTimeoutException("connect timed out")))

        val socketRun = httpIdempotentDefaultPolicy().newRun()
        assertNotNull(socketRun.nextDelay(SocketTimeoutException("socket timed out")))

        val ioRun = httpIdempotentDefaultPolicy().newRun()
        assertNotNull(ioRun.nextDelay(IOException("connection reset")))

        val runtimeRun = httpIdempotentDefaultPolicy().newRun()
        assertNull(runtimeRun.nextDelay(IllegalStateException("not retryable")))
    }

    @Test
    fun `retryingIdempotentHttpCall retries 408 responses`() = runBlocking {
        var attempts = 0
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        attempts++
                        if (attempts == 1) {
                            respond(
                                content = "try again",
                                status = HttpStatusCode.RequestTimeout,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                            )
                        } else {
                            respond(
                                content = "ok",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                            )
                        }
                    }
                }
            }

        try {
            val result =
                retryingIdempotentHttpCall {
                    client.get("https://example.test") {
                        expectSuccess = true
                    }.bodyAsText()
                }

            assertEquals("ok", result)
            assertEquals(2, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpCall retries 429 responses with retry after`() = runBlocking {
        var attempts = 0
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        attempts++
                        if (attempts == 1) {
                            respond(
                                content = "slow down",
                                status = HttpStatusCode.TooManyRequests,
                                headers = headersOf(
                                    HttpHeaders.ContentType to listOf(ContentType.Text.Plain.toString()),
                                    HttpHeaders.RetryAfter to listOf("0"),
                                ),
                            )
                        } else {
                            respond(
                                content = "ok",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                            )
                        }
                    }
                }
            }

        try {
            val result =
                retryingIdempotentHttpCall {
                    client.get("https://example.test") {
                        expectSuccess = true
                    }.bodyAsText()
                }

            assertEquals("ok", result)
            assertEquals(2, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpCall does not retry non retryable client errors`() = runBlocking {
        var attempts = 0
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        attempts++
                        respond(
                            content = "bad request",
                            status = HttpStatusCode.BadRequest,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                        )
                    }
                }
            }

        try {
            assertFailsWith<Throwable> {
                retryingIdempotentHttpCall {
                    client.get("https://example.test") {
                        expectSuccess = true
                    }.bodyAsText()
                }
            }
            assertEquals(1, attempts)
        } finally {
            client.close()
        }
    }
}
