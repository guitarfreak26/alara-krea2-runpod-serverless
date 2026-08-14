package com.alara.hermes.protocol

private val CREDENTIAL_QUERY = Regex("(?i)([?&](?:token|ticket|key|api_key)=)[^&\\s\"]+")
private val BEARER_HEADER = Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+")

/**
 * Remove credential material from a string before it reaches logs, error
 * surfaces, or crash reports. Redacts known secret values verbatim plus
 * anything shaped like a credential query param or Authorization header.
 */
fun redact(text: String, vararg secrets: String): String {
    var result = text
    secrets.filter { it.isNotBlank() }.forEach { secret ->
        result = result.replace(secret, "•••")
    }
    result = CREDENTIAL_QUERY.replace(result) { match -> "${match.groupValues[1]}•••" }
    result = BEARER_HEADER.replace(result) { match -> "${match.groupValues[1]}•••" }
    return result
}
