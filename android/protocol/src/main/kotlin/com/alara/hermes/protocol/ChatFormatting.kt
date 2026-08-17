package com.alara.hermes.protocol

/**
 * Chat-style markdown adjustments. Agents write messenger-style text where a
 * single newline means a line break (as Discord/Matrix render it), but strict
 * markdown treats lone newlines as soft wraps and glues the lines into one
 * paragraph — "solid blocks of text". Converting lone newlines to two-space
 * hard breaks preserves real markdown structure (paragraphs, lists, headings
 * keep their own semantics) while honouring the intended line breaks.
 */
object ChatFormatting {

    private val LONE_NEWLINE = Regex("(?<!\n)\n(?!\n)")

    /** Apply chat line-break semantics, leaving fenced code blocks untouched. */
    fun chatLineBreaks(text: String): String {
        if (!text.contains('\n')) return text
        val parts = text.split("```")
        return parts.mapIndexed { index, part ->
            // Even indexes are outside fences; odd are inside ``` blocks.
            if (index % 2 == 0) part.replace(LONE_NEWLINE, "  \n") else part
        }.joinToString("```")
    }
}
