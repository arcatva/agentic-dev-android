package dev.agentic.ui.home

import dev.agentic.data.net.Session
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the small helper that locates the currently-open session inside the list rendered by
 * [SessionListPane]. The Composable uses this index to call `LazyListState.animateScrollToItem`
 * whenever the open session moves (e.g. a new message bumps it to the top, or the user opens a
 * different session). The function is pure so it can be unit-tested without Compose UI.
 */
class SessionListScrollTest {

    private fun session(id: String) = Session(id = id, prompt = "p-$id")

    @Test fun `returns 0 when selected id is the first session`() {
        val list = listOf(session("a"), session("b"), session("c"))
        assertEquals(0, indexOfSelected(list, "a"))
    }

    @Test fun `returns the matching index when selected id is in the middle`() {
        val list = listOf(session("a"), session("b"), session("c"))
        assertEquals(1, indexOfSelected(list, "b"))
    }

    @Test fun `returns the last index when selected id is at the bottom`() {
        val list = listOf(session("a"), session("b"), session("c"))
        assertEquals(2, indexOfSelected(list, "c"))
    }

    @Test fun `returns -1 when selected id is not in the list`() {
        val list = listOf(session("a"), session("b"))
        assertEquals(-1, indexOfSelected(list, "z"))
    }

    @Test fun `returns -1 when selected id is null (no open session)`() {
        val list = listOf(session("a"), session("b"))
        assertEquals(-1, indexOfSelected(list, null))
    }

    @Test fun `returns -1 when the list is empty regardless of selection`() {
        assertEquals(-1, indexOfSelected(emptyList(), "a"))
        assertEquals(-1, indexOfSelected(emptyList(), null))
    }

    @Test fun `tracks position changes after the list reorders (simulates a new message bumping the session to top)`() {
        // Before: "b" was at index 0 (open session at top).
        val before = listOf(session("b"), session("a"), session("c"))
        assertEquals(0, indexOfSelected(before, "b"))
        // After the server reordered (e.g. a new message bumped "a" to the top), the open session
        // "a" is now at index 0.
        val after = listOf(session("a"), session("b"), session("c"))
        assertEquals(0, indexOfSelected(after, "a"))
    }
}
