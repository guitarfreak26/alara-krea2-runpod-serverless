package com.alara.hermes.util

enum class MediaKind { IMAGE, VIDEO, AUDIO }

data class MediaLink(val url: String, val kind: MediaKind)

private val URL_PATTERN = Regex("""https?://[^\s()<>\[\]"']+""")
private val MARKDOWN_IMAGE = Regex("""!\[[^\]]*]\((https?://[^)\s]+)\)""")

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif")
private val VIDEO_EXT = setOf("mp4", "webm", "mov", "m4v", "mkv")
private val AUDIO_EXT = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus")

private fun classify(url: String): MediaKind? {
    val path = url.substringBefore('?').substringBefore('#')
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXT -> MediaKind.IMAGE
        in VIDEO_EXT -> MediaKind.VIDEO
        in AUDIO_EXT -> MediaKind.AUDIO
        else -> null
    }
}

/**
 * Media referenced by a message: markdown image syntax, bare http(s) URLs
 * whose file extension marks them as image/video/audio, and Hermes
 * `MEDIA:/abs/path` directives resolved through [mediaPathUrl] onto the
 * authenticated gateway media route. Order preserved, duplicates removed.
 */
fun extractMediaLinks(
    text: String,
    mediaPathUrl: ((String) -> String?)? = null,
): List<MediaLink> {
    val links = LinkedHashMap<String, MediaKind>()
    if (text.contains("http")) {
        val urls = LinkedHashSet<String>()
        MARKDOWN_IMAGE.findAll(text).forEach { urls += it.groupValues[1] }
        URL_PATTERN.findAll(text).forEach { match ->
            // Trailing punctuation from prose ("…file.mp4.") is not part of the URL.
            urls += match.value.trimEnd('.', ',', ';', ':', '!', '?')
        }
        urls.forEach { url -> classify(url)?.let { links[url] = it } }
    }
    if (mediaPathUrl != null) {
        com.alara.hermes.protocol.MediaDirectives.extractPaths(text).forEach { path ->
            // Classify by the FILE path — the built gateway URL hides the
            // extension inside the ?path= query parameter.
            val kind = classify(path) ?: return@forEach
            mediaPathUrl(path)?.let { links[it] = kind }
        }
    }
    return links.map { (url, kind) -> MediaLink(url, kind) }
}
