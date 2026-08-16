package com.alara.hermes.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDirectivesTest {

    @Test
    fun `extracts video paths from directives`() {
        val text = """
            Here's your reel!
            MEDIA:/opt/data/media-jobs/job-42/final-reel.mp4
            And the raw cut:
            MEDIA: /opt/data/media-jobs/job-42/raw.mov
        """.trimIndent()
        assertEquals(
            listOf(
                "/opt/data/media-jobs/job-42/final-reel.mp4",
                "/opt/data/media-jobs/job-42/raw.mov",
            ),
            MediaDirectives.extractPaths(text),
        )
    }

    @Test
    fun `handles inline directives and deduplicates`() {
        val text = "Done: MEDIA:/a/b.webm and again MEDIA:/a/b.webm plus MEDIA:/c/d.m4v"
        assertEquals(listOf("/a/b.webm", "/c/d.m4v"), MediaDirectives.extractPaths(text))
    }

    @Test
    fun `ignores text without directives and relative paths`() {
        assertTrue(MediaDirectives.extractPaths("no media here, MEDIA:relative.mp4").isEmpty())
        assertTrue(MediaDirectives.extractPaths("plain text").isEmpty())
    }

    @Test
    fun `strip removes directives but keeps prose`() {
        val text = "Your clip is ready.\nMEDIA:/opt/x/final.mp4\nEnjoy!"
        assertEquals("Your clip is ready.\n\nEnjoy!", MediaDirectives.strip(text))
    }
}
