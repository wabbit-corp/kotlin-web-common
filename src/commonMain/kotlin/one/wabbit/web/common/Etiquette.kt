// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.common

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.headers

/**
 * Request etiquette headers shared by Wabbit HTTP clients.
 *
 * The [userAgent] is required and must be a valid HTTP header value. [referer] is optional. Extra
 * headers are defensively copied and validated, but must not override `User-Agent` or `Referer`.
 *
 * @property userAgent value for the `User-Agent` header.
 * @property referer optional value for the `Referer` header.
 * @property extraHeaders additional validated headers to set on each request.
 */
@ConsistentCopyVisibility
data class Etiquette
private constructor(
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

/** Applies [etiquette] headers to this Ktor request builder. */
fun HttpRequestBuilder.applyEtiquette(etiquette: Etiquette) {
    headers {
        set(HttpHeaders.UserAgent, etiquette.userAgent)
        etiquette.referer?.let { set(HttpHeaders.Referrer, it) }
        for ((key, value) in etiquette.extraHeaders) {
            set(key, value)
        }
    }
}
