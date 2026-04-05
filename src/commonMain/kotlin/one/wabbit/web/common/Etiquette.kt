package one.wabbit.web.common

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.headers

@ConsistentCopyVisibility
data class Etiquette private constructor(
    val userAgent: String,
    val referer: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
    private val copied: Boolean = true,
) {
    constructor(
        userAgent: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ) : this(
        userAgent = userAgent,
        referer = referer,
        extraHeaders = extraHeaders.toMap(),
        copied = true,
    )

    init {
        require(userAgent.isNotBlank()) { "User-Agent must not be blank" }
        HttpHeaders.checkHeaderValue(userAgent)
        if (referer != null) {
            require(referer.isNotBlank()) { "Referer must not be blank if provided" }
            HttpHeaders.checkHeaderValue(referer)
        }
        for ((headerName, headerValue) in extraHeaders) {
            HttpHeaders.checkHeaderName(headerName)
            HttpHeaders.checkHeaderValue(headerValue)
            require(!headerName.equals(HttpHeaders.UserAgent, ignoreCase = true)) {
                "extraHeaders must not contain User-Agent"
            }
            require(!headerName.equals(HttpHeaders.Referrer, ignoreCase = true)) {
                "extraHeaders must not contain Referer"
            }
        }
    }
}

fun HttpRequestBuilder.applyEtiquette(etiquette: Etiquette) {
    headers {
        set(HttpHeaders.UserAgent, etiquette.userAgent)
        etiquette.referer?.let { set(HttpHeaders.Referrer, it) }
        for ((key, value) in etiquette.extraHeaders) {
            set(key, value)
        }
    }
}
