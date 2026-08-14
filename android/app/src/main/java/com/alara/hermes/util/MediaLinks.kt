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
 * Media referenced by a message: markdown image syntax plus bare http(s) URLs
 * whose file extension marks them as image/video/audio. Order preserved,
 * duplicates removed.
 */
fun extractMediaLinks(text: String): List<MediaLink> {
    if (!text.contains("http")) return emptyList()
    val urls = LinkedHashSet<String>()
    MARKDOWN_IMAGE.findAll(text).forEach { urls += it.groupValues[1] }
    URL_PATTERN.findAll(text).forEach { match ->
        // Trailing punctuation from prose ("…file.mp4.") is not part of the URL.
        urls += match.value.trimEnd('.', ',', ';', ':', '!', '?')
    }
    return urls.mapNotNull { url -> classify(url)?.let { MediaLink(url, it) } }
}
