package com.alara.hermes.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatFormattingTest {

    @Test
    fun `lone newlines become hard breaks`() {
        assertEquals("line one  \nline two", ChatFormatting.chatLineBreaks("line one\nline two"))
    }

    @Test
    fun `paragraph breaks and lists are preserved`() {
        val text = "para one\n\npara two\n- a\n- b"
        assertEquals("para one\n\npara two  \n- a  \n- b", ChatFormatting.chatLineBreaks(text))
    }

    @Test
    fun `code fences stay untouched`() {
        val text = "before\n```\nval x = 1\nval y = 2\n```\nafter"
        val result = ChatFormatting.chatLineBreaks(text)
        assert(result.contains("val x = 1\nval y = 2"))
        assert(result.startsWith("before  \n```"))
    }
}
