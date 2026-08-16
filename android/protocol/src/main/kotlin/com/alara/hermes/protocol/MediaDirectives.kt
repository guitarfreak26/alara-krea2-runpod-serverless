package com.alara.hermes.protocol

/**
 * Hermes managed-media directives: assistant text can carry lines like
 * `MEDIA:/opt/data/media-jobs/.../final-reel.mp4`. The client maps each path
 * onto the authenticated `GET {api_prefix}/v1/media?path=<encoded>` route
 * instead of showing the raw filesystem path. The bearer key rides the
 * Authorization header only — never the URL.
 */
object MediaDirectives {

    // Absolute paths only; a path ends at whitespace or quote-ish punctuation.
    private val DIRECTIVE = Regex("""MEDIA:\s*(/[^\s"'<>|]+)""")

    /** All directive paths in order of appearance, duplicates removed. */
    fun extractPaths(text: String): List<String> {
        if (!text.contains("MEDIA:")) return emptyList()
        return DIRECTIVE.findAll(text).map { it.groupValues[1] }.toList().distinct()
    }

    /**
     * Remove directive occurrences from display text (the player renders in
     * their place). Lines left empty by the removal collapse away.
     */
    fun strip(text: String): String {
        if (!text.contains("MEDIA:")) return text
        return text.replace(DIRECTIVE, "")
            .replace(Regex("(?m)^[ \t]+$"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
