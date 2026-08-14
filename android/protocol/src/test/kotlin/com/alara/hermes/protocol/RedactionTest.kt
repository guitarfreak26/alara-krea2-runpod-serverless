package com.alara.hermes.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactionTest {

    @Test
    fun `known secret values are removed verbatim`() {
        val out = redact("failed for key sk-abc123 at host", "sk-abc123")
        assertFalse(out.contains("sk-abc123"))
        assertEquals("failed for key ••• at host", out)
    }

    @Test
    fun `credential query params are masked`() {
        val out = redact("ws://host/api/ws?token=supersecret&x=1")
        assertFalse(out.contains("supersecret"))
        val ticket = redact("dial https://host/api/ws?ticket=abc.def-ghi failed")
        assertFalse(ticket.contains("abc.def-ghi"))
    }

    @Test
    fun `bearer headers are masked`() {
        val out = redact("request had Authorization: Bearer sk-live-42 attached")
        assertFalse(out.contains("sk-live-42"))
    }

    @Test
    fun `blank secrets do not blank the message`() {
        assertEquals("plain message", redact("plain message", ""))
    }
}
