package com.typefree.ime.service

import java.net.URI

internal fun openAiEndpointUrl(
    baseUrl: String,
    endpointPath: String,
    defaultBaseUrl: String = "https://api.openai.com/v1"
): String {
    val endpoint = endpointPath.trim('/')
    val base = baseUrl.trim().trimEnd('/').ifBlank { defaultBaseUrl.trimEnd('/') }
    if (base.endsWith("/$endpoint")) return base
    val path = runCatching { URI(base).path.orEmpty().trim('/') }.getOrDefault("")
    return if (path.isEmpty()) {
        "$base/v1/$endpoint"
    } else {
        "$base/$endpoint"
    }
}
