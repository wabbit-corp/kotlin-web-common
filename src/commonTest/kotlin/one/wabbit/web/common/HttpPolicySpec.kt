// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HttpPolicySpec {
    @Test
    fun `retrying idempotent helpers support default and explicit random overloads`() = runBlocking {
        val policy =
            RetryPolicy<Throwable>(
                schedule = Schedule.fixed(kotlin.time.Duration.ZERO, 1),
            ) { _, _ ->
                RetryAction.Retry()
            }

        var legacyAttempts = 0
        val legacyResult =
            retryingIdempotentHttpCall(policy) {
                legacyAttempts++
                if (legacyAttempts == 1) throw IOException("transient")
                "ok"
            }

        assertEquals("ok", legacyResult)

        var explicitRandomAttempts = 0
        val explicitRandomResult =
            retryingIdempotentHttpCall(policy, kotlin.random.Random(1)) {
                explicitRandomAttempts++
                if (explicitRandomAttempts == 1) throw IOException("transient")
                "random-ok"
            }

        assertEquals("random-ok", explicitRandomResult)
    }

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
    fun `strict transient policy is narrower than broad default`() {
        assertNull(httpStrictTransientPolicy().newRun().nextDelay(IOException("connection reset")))
        assertNotNull(httpBroadIdempotentPolicy().newRun().nextDelay(IOException("connection reset")))
    }

    @Test
    fun `strict transient response policy does not retry generic 500`() = runBlocking {
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = "boom",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                        )
                    }
                }
            }

        try {
            val response = client.get("https://example.test")

            assertNull(httpStrictTransientResponsePolicy().newRun().nextDelay(response))
            assertNotNull(httpBroadIdempotentResponsePolicy().newRun().nextDelay(response))
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun `custom response retry options can target specific statuses`() = runBlocking {
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = "conflict",
                            status = HttpStatusCode.Conflict,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Text.Plain.toString()),
                                HttpHeaders.RetryAfter to listOf("0"),
                            ),
                        )
                    }
                }
            }

        try {
            val response = client.get("https://example.test")
            val policy =
                httpResponseRetryPolicy(
                    HttpRetryOptions(
                        schedule = Schedule.fixed(kotlin.time.Duration.ZERO, 1),
                        retryOnGenericIoException = false,
                        retryableStatuses = setOf(HttpStatusCode.Conflict.value),
                        respectRetryAfter = true,
                    ),
                )

            assertNotNull(policy.newRun().nextDelay(response))
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun `custom throwable retry options can target redirect statuses`() = runBlocking {
        var attempts = 0
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        attempts++
                        if (attempts == 1) {
                            respond(
                                content = "come back later",
                                status = HttpStatusCode.TemporaryRedirect,
                                headers = headersOf(
                                    HttpHeaders.ContentType to listOf(ContentType.Text.Plain.toString()),
                                    HttpHeaders.Location to listOf("https://example.test/next"),
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
            val policy =
                httpThrowableRetryPolicy(
                    HttpRetryOptions(
                        schedule = Schedule.fixed(kotlin.time.Duration.ZERO, 1),
                        retryOnGenericIoException = false,
                        retryableStatuses = setOf(HttpStatusCode.TemporaryRedirect.value),
                        respectRetryAfter = true,
                    ),
                )

            val result =
                retryingIdempotentHttpCall(policy) {
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
    fun `retry after can be clamped through retry options`() = runBlocking {
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = "slow down",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Text.Plain.toString()),
                                HttpHeaders.RetryAfter to listOf("3600"),
                            ),
                        )
                    }
                }
            }

        try {
            val response = client.get("https://example.test")

            val unclamped =
                httpResponseRetryPolicy(
                    HttpRetryOptions(
                        schedule = Schedule.fixed(1.seconds, 1),
                        retryOnGenericIoException = false,
                        retryableStatuses = setOf(HttpStatusCode.TooManyRequests.value),
                        respectRetryAfter = true,
                    ),
                )

            val clamped =
                httpResponseRetryPolicy(
                    HttpRetryOptions(
                        schedule = Schedule.fixed(1.seconds, 1),
                        retryOnGenericIoException = false,
                        retryableStatuses = setOf(HttpStatusCode.TooManyRequests.value),
                        respectRetryAfter = true,
                        maxRetryAfterDelay = 5.seconds,
                    ),
                )

            assertEquals(3600.seconds, unclamped.newRun().nextDelay(response))
            assertEquals(5.seconds, clamped.newRun().nextDelay(response))
        } finally {
            client.close()
        }
    }

    @Test
    fun `retry after is ignored when respectRetryAfter is false even with a clamp`() = runBlocking {
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = "slow down",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Text.Plain.toString()),
                                HttpHeaders.RetryAfter to listOf("3600"),
                            ),
                        )
                    }
                }
            }

        try {
            val response = client.get("https://example.test")
            val policy =
                httpResponseRetryPolicy(
                    HttpRetryOptions(
                        schedule = Schedule.fixed(1.seconds, 1),
                        retryOnGenericIoException = false,
                        retryableStatuses = setOf(HttpStatusCode.TooManyRequests.value),
                        respectRetryAfter = false,
                        maxRetryAfterDelay = 5.seconds,
                    ),
                )

            assertEquals(1.seconds, policy.newRun().nextDelay(response))
        } finally {
            client.close()
        }
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
            assertFailsWith<ClientRequestException> {
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

    @Test
    fun `retryingIdempotentHttpCall does not retry status responses that do not throw`() = runBlocking {
        var attempts = 0
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        attempts++
                        respond(
                            content = "slow down",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf(ContentType.Text.Plain.toString()),
                                HttpHeaders.RetryAfter to listOf("0"),
                            ),
                        )
                    }
                }
            }

        try {
            val result =
                retryingIdempotentHttpCall {
                    client.get("https://example.test").bodyAsText()
                }

            assertEquals("slow down", result)
            assertEquals(1, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpResponseCall retries status responses without expectSuccess`() = runBlocking {
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
            val response =
                retryingIdempotentHttpResponseCall {
                    client.get("https://example.test")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
            assertEquals(2, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpResponseCall returns non retryable status immediately`() = runBlocking {
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
            val response =
                retryingIdempotentHttpResponseCall {
                    client.get("https://example.test")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("bad request", response.bodyAsText())
            assertEquals(1, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpResponseBodyCall retries before transforming final response`() = runBlocking {
        var attempts = 0
        var transforms = 0
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        attempts++
                        if (attempts == 1) {
                            respond(
                                content = "retry later",
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
                retryingIdempotentHttpResponseBodyCall(
                    request = { client.get("https://example.test") },
                    transform = { response ->
                        transforms++
                        response.bodyAsText()
                    },
                )

            assertEquals("ok", result)
            assertEquals(2, attempts)
            assertEquals(1, transforms)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpResponseBodyCall returns non retryable body immediately`() = runBlocking {
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
            val result =
                retryingIdempotentHttpResponseBodyCall(
                    request = { client.get("https://example.test") },
                    transform = { response -> response.bodyAsText() },
                )

            assertEquals("bad request", result)
            assertEquals(1, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpResponseBodyCall supports explicit random overload`() = runBlocking {
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
                retryingIdempotentHttpResponseBodyCall(
                    random = kotlin.random.Random(1),
                    request = { client.get("https://example.test") },
                    transform = { response -> response.bodyAsText() },
                )

            assertEquals("ok", result)
            assertEquals(2, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpResponseCall advances virtual time by retry after delay`() = runTest {
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
                                    HttpHeaders.RetryAfter to listOf("2"),
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
            val response =
                retryingIdempotentHttpResponseCall {
                    client.get("https://example.test")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, attempts)
            assertEquals(2.seconds.inWholeMilliseconds, testScheduler.currentTime)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpResponseCall advances virtual time by scheduled delay when retry after is absent`() = runTest {
        var attempts = 0
        val client =
            HttpClient(MockEngine) {
                engine {
                    addHandler {
                        attempts++
                        if (attempts == 1) {
                            respond(
                                content = "backend unavailable",
                                status = HttpStatusCode.ServiceUnavailable,
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

        val policy =
            httpResponseRetryPolicy(
                HttpRetryOptions(
                    schedule = Schedule.fixed(3.seconds, 1),
                    retryOnGenericIoException = false,
                    retryableStatuses = setOf(HttpStatusCode.ServiceUnavailable.value),
                    respectRetryAfter = true,
                ),
            )

        try {
            val response =
                retryingIdempotentHttpResponseCall(policy) {
                    client.get("https://example.test")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, attempts)
            assertEquals(3.seconds.inWholeMilliseconds, testScheduler.currentTime)
        } finally {
            client.close()
        }
    }

    @Test
    fun `retryingIdempotentHttpResponseCall advances virtual time by clamped retry after delay`() = runTest {
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
                                    HttpHeaders.RetryAfter to listOf("3600"),
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

        val policy =
            httpResponseRetryPolicy(
                HttpRetryOptions(
                    schedule = Schedule.fixed(1.seconds, 1),
                    retryOnGenericIoException = false,
                    retryableStatuses = setOf(HttpStatusCode.TooManyRequests.value),
                    respectRetryAfter = true,
                    maxRetryAfterDelay = 5.seconds,
                ),
            )

        try {
            val response =
                retryingIdempotentHttpResponseCall(policy) {
                    client.get("https://example.test")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(2, attempts)
            assertEquals(5.seconds.inWholeMilliseconds, testScheduler.currentTime)
        } finally {
            client.close()
        }
    }
}
