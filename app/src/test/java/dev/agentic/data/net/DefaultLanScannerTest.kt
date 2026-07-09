package dev.agentic.data.net

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeNetworkInfoProvider(private val net: LocalNet?) : NetworkInfoProvider {
    override fun localNet(): LocalNet? = net
}

/** Records every probed ip; returns a hit (with the given latency) for ips in [hits]. */
private class FakeServerProbe(private val hits: Map<String, Long>) : ServerProbe {
    val calledIps: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    override suspend fun probe(ip: String): DiscoveredServer? {
        calledIps.add(ip)
        return hits[ip]?.let { DiscoveredServer(ip = ip, latencyMs = it) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultLanScannerTest {

    @Test fun `emits NotOnLan and nothing else when there is no local network`() = runTest {
        val scanner = DefaultLanScanner(
            networkInfo = FakeNetworkInfoProvider(null),
            probe = FakeServerProbe(emptyMap()),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val updates = scanner.scan().toList()
        assertEquals(listOf(ScanUpdate.NotOnLan), updates)
    }

    @Test fun `finds the responding hosts, probes the whole 24 except own ip, and ends with Done`() = runTest {
        val probe = FakeServerProbe(mapOf("192.168.1.10" to 12L, "192.168.1.23" to 31L))
        val scanner = DefaultLanScanner(
            networkInfo = FakeNetworkInfoProvider(LocalNet("192.168.1.50", 24)),
            probe = probe,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            concurrency = 16,
        )

        val updates = scanner.scan().toList()

        val found = updates.filterIsInstance<ScanUpdate.Found>().map { it.server }
        assertEquals(setOf("https://192.168.1.10:7420", "https://192.168.1.23:7420"), found.map { it.baseUrl }.toSet())
        assertEquals(12L, found.first { it.ip == "192.168.1.10" }.latencyMs)

        assertTrue("must end with Done", updates.last() == ScanUpdate.Done)
        assertEquals("probes every host in the /24 except its own", 253, probe.calledIps.size)
        assertFalse("never probes its own ip", probe.calledIps.contains("192.168.1.50"))

        val lastProgress = updates.filterIsInstance<ScanUpdate.Progress>().last()
        assertEquals(253, lastProgress.scanned)
        assertEquals(253, lastProgress.total)
    }
}
