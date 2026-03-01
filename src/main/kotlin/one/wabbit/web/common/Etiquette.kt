package one.wabbit.web.common

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.headers

data class Etiquette(
    val userAgent: String,
    val referer: String? = null,
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    init {
        require(userAgent.isNotBlank()) { "User-Agent must not be blank" }
        if (referer != null) {
            require(referer.isNotBlank()) { "Referer must not be blank if provided" }
        }
        require("User-Agent" !in extraHeaders.keys) { "extraHeaders must not contain User-Agent" }
        require("Referer" !in extraHeaders.keys) { "extraHeaders must not contain Referer" }
    }
}

fun HttpRequestBuilder.applyEtiquette(etiquette: Etiquette) {
    headers {
        set("User-Agent", etiquette.userAgent)
        etiquette.referer?.let { set("Referer", it) }
        for ((key, value) in etiquette.extraHeaders) {
            set(key, value)
        }
    }
}
