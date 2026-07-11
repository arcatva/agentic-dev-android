@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.agentic.data

import dev.agentic.data.util.pollFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PollingTest {

    @Test
    fun `pollFlow emits first value immediately`() = runTest {
        var callCount = 0
        val results = mutableListOf<Int>()
        pollFlow(intervalMs = 1000L) { ++callCount }.take(1).toList(results)
        assertEquals(1, results.size)
        assertEquals(1, results[0])
    }

    @Test
    fun `pollFlow emits again after each interval`() = runTest {
        var callCount = 0
        val results = pollFlow(intervalMs = 500L) { ++callCount }.take(3).toList()
        assertEquals(3, results.size)
        assertEquals(listOf(1, 2, 3), results)
    }

    @Test
    fun `pollFlow survives a failing tick and keeps polling after a backoff`() = runTest {
        var calls = 0
        val results = mutableListOf<Int>()
        val job = launch {
            pollFlow(intervalMs = 1000L) {
                calls++
                if (calls == 2) throw RuntimeException("boom")
                calls
            }.collect { results.add(it) }
        }
        advanceTimeBy(1L)
        assertEquals(listOf(1), results)
        advanceTimeBy(1001L)
        assertEquals(listOf(1), results)
        advanceTimeBy(2001L)
        assertEquals(listOf(1, 3), results)
        job.cancel()
    }

    @Test
    fun `pollFlow does not emit extra before interval elapses`() = runTest {
        var callCount = 0
        val results = mutableListOf<Int>()
        val job = launch {
            pollFlow(intervalMs = 1000L) { ++callCount }.collect { results.add(it) }
        }
        advanceTimeBy(1L)
        assertEquals(1, results.size)
        advanceTimeBy(998L)
        assertEquals(1, results.size)
        advanceTimeBy(2L)
        assertEquals(2, results.size)
        job.cancel()
    }
}
