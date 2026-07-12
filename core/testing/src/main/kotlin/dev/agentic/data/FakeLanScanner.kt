package dev.agentic.data

import dev.agentic.data.net.LanScanner
import dev.agentic.data.net.ScanUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

// In-memory [LanScanner] that replays a scripted update list per scan() call.
class FakeLanScanner(var updates: List<ScanUpdate> = listOf(ScanUpdate.Done)) : LanScanner {
    var scanCount = 0
    override fun scan(): Flow<ScanUpdate> {
        scanCount++
        return updates.asFlow()
    }
}
