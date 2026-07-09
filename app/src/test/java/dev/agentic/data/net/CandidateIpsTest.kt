package dev.agentic.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateIpsTest {

    @Test fun `enumerates the full 24 host range`() {
        val ips = candidateIps(LocalNet("192.168.1.50", 24))
        // 1..254 minus the device's own host octet (.50) = 253 candidates.
        assertEquals(253, ips.size)
        assertTrue(ips.contains("192.168.1.1"))
        assertTrue(ips.contains("192.168.1.254"))
    }

    @Test fun `excludes the device's own address`() {
        val ips = candidateIps(LocalNet("192.168.1.50", 24))
        assertFalse(ips.contains("192.168.1.50"))
    }

    @Test fun `excludes network and broadcast addresses`() {
        val ips = candidateIps(LocalNet("192.168.1.50", 24))
        assertFalse("network addr .0 must be excluded", ips.contains("192.168.1.0"))
        assertFalse("broadcast addr .255 must be excluded", ips.contains("192.168.1.255"))
    }

    @Test fun `caps a wide prefix at the local 24`() {
        // A /16 device IP still scans only the device's own /24, never 65k hosts.
        val ips = candidateIps(LocalNet("10.0.5.7", 16))
        assertTrue(ips.all { it.startsWith("10.0.5.") })
        assertEquals(253, ips.size)
    }

    @Test fun `malformed ip yields empty list`() {
        assertEquals(emptyList<String>(), candidateIps(LocalNet("not-an-ip", 24)))
    }

    @Test fun `out-of-range octet yields empty list`() {
        assertEquals(emptyList<String>(), candidateIps(LocalNet("256.1.1.50", 24)))
        assertEquals(emptyList<String>(), candidateIps(LocalNet("-1.2.3.4", 24)))
    }
}
