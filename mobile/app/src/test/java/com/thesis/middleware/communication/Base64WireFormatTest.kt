package com.thesis.middleware.communication

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.util.Base64

/**
 * Guards the exact defect found testing against the real edge server: its
 * base64 responses arrive with an embedded newline every 76 characters
 * (RFC 2045 MIME-style line wrapping), not the unbroken RFC 4648 encoding
 * `Base64.getDecoder()` requires. That decoder throws "Illegal base64
 * character a" on the first embedded `\n` (hex `a` = 0x0A) it hits — which
 * silently turned every real remote task response into a decode failure and
 * a local fallback, indistinguishable in the logs from a genuinely
 * unreachable server.
 *
 * [OffloadingClient]'s `BASE64_DESERIALIZER` uses `getMimeDecoder()`
 * specifically so it tolerates either style; this test exercises that
 * decision directly, without needing a live server or Retrofit.
 */
class Base64WireFormatTest {

    @Test
    fun `mime decoder accepts base64 with no line breaks`() {
        val raw = ByteArray(200) { it.toByte() }
        val encoded = Base64.getEncoder().encodeToString(raw)
        assertArrayEquals(raw, Base64.getMimeDecoder().decode(encoded))
    }

    @Test
    fun `mime decoder accepts base64 wrapped every 76 characters`() {
        val raw = ByteArray(200) { it.toByte() }
        val unbroken = Base64.getEncoder().encodeToString(raw)
        val wrapped = unbroken.chunked(76).joinToString("\n")

        assertArrayEquals(raw, Base64.getMimeDecoder().decode(wrapped))
    }

    @Test
    fun `the plain decoder rejects exactly the response shape the server sends`() {
        // Documents the failure this fix replaces: the standard decoder must
        // NOT tolerate line breaks, or this test would stop proving anything.
        val raw = ByteArray(200) { it.toByte() }
        val wrapped = Base64.getEncoder().encodeToString(raw).chunked(76).joinToString("\n")

        var threw = false
        try {
            Base64.getDecoder().decode(wrapped)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assert(threw) { "expected the plain decoder to reject line-wrapped base64" }
    }
}
