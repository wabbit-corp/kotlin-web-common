package web.common

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration

interface RestClient {
    suspend fun get(url: String): String

    companion object {
        fun ktor(
            connTimeoutMs: Int = 10000,
            readTimeoutMs: Int = 10000,
        ): RestClient = object : RestClient {
            private val client = HttpClient(CIO) {
                install(ContentNegotiation) {
                    json()
                }
                install(HttpTimeout) { }
            }

            override suspend fun get(url: String): String {
                val response = client.get(url) {
                    timeout {
                        requestTimeoutMillis = connTimeoutMs.toLong()
                        socketTimeoutMillis = readTimeoutMs.toLong()
                    }
                }
                return response.bodyAsText()
            }
        }

        fun java(
            connTimeoutMs: Int = 10000,
            readTimeoutMs: Int = 10000,
        ): RestClient = object : RestClient {
            override suspend fun get(url: String): String {
                return withContext(Dispatchers.IO) {
                    val conn = java.net.URL(url).openConnection()
                    try {
                        conn.connectTimeout = connTimeoutMs
                        conn.readTimeout = readTimeoutMs
                        conn.getInputStream().bufferedReader().use { it.readText() }
                    } finally {
                        if (conn is java.net.HttpURLConnection) {
                            conn.disconnect()
                        }
                    }
                }
            }
        }
    }
}
