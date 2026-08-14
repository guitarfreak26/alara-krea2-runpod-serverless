package com.alara.hermes.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLinksTest {

    @Test
    fun `detects video urls with query strings and trailing prose punctuation`() {
        val text = "Here you go: https://host/files/h3_final.mp4?dl=1. Enjoy!"
        val links = extractMediaLinks(text)
        assertEquals(1, links.size)
        assertEquals(MediaKind.VIDEO, links[0].kind)
        assertEquals("https://host/files/h3_final.mp4?dl=1", links[0].url)
    }

    @Test
    fun `detects markdown images and bare image urls without duplicates`() {
        val text = """
            ![frame](https://host/a.png)
            Also raw: https://host/a.png and https://host/b.jpg
        """.trimIndent()
        val links = extractMediaLinks(text)
        assertEquals(listOf("https://host/a.png", "https://host/b.jpg"), links.map { it.url })
        assertTrue(links.all { it.kind == MediaKind.IMAGE })
    }

    @Test
    fun `ignores non media urls and plain text`() {
        assertEquals(emptyList<MediaLink>(), extractMediaLinks("see https://example.com/docs page"))
        assertEquals(emptyList<MediaLink>(), extractMediaLinks("no links at all"))
    }

    @Test
    fun `classifies audio`() {
        val links = extractMediaLinks("voice note https://host/note.m4a done")
        assertEquals(MediaKind.AUDIO, links.single().kind)
    }
}
