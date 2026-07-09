package dev.agentic.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CertPinningTest {
    @Test fun `certHostKey normalises scheme host and port`() {
        assertEquals("192.0.2.1:7420", certHostKey("https://192.0.2.1:7420"))
        assertEquals("192.0.2.1:7420", certHostKey("http://192.0.2.1:7420"))
        assertEquals("host:7420", certHostKey("https://host:7420/api/login"))
        // Default ports when omitted.
        assertEquals("example.com:443", certHostKey("https://example.com"))
        assertEquals("example.com:80", certHostKey("http://example.com"))
        // Host is lowercased; surrounding whitespace trimmed.
        assertEquals("host.local:7420", certHostKey("  https://Host.Local:7420  "))
        // Scheme-less manual entry keeps its explicit port (assumed https), not collapsed to :80.
        assertEquals("10.0.0.5:7420", certHostKey("10.0.0.5:7420"))
    }

    @Test fun `sha256Hex matches known empty-input vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    @Test fun `formatFingerprint groups uppercase hex with colons`() {
        assertEquals("AA:BB:CC:DD", formatFingerprint("aabbccdd"))
        assertEquals("E3:B0:C4", formatFingerprint("e3b0c4"))
    }

    @Test fun `findCause walks the cause chain and is cycle-safe`() {
        val target = NeedsTrustException("host:7420", "deadbeef")
        val wrapped: Throwable = RuntimeException("outer", java.io.IOException("io", target))
        assertSame(target, wrapped.findCause<NeedsTrustException>())
        // Absent type → null.
        assertNull(RuntimeException("no cause").findCause<NeedsTrustException>())
        // A cause cycle must terminate (not loop forever).
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertNull(a.findCause<NeedsTrustException>())
    }
}
