package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSearchTest {
    private fun s(id: String, prompt: String) = TestSession(id = id, prompt = prompt)

    private val sessions = listOf(
        s("1", "Fix login bug"),
        s("2", "Add search box to list"),
        s("3", "Refactor LIST pane"),
        s("4", ""),
    )

    @Test
    fun `blank or whitespace query returns the full list unchanged`() {
        assertEquals(sessions, sessions.filterByTitle(""))
        assertEquals(sessions, sessions.filterByTitle("   "))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf("2", "3"), sessions.filterByTitle("list").map { it.id })
    }

    @Test
    fun `matches a substring anywhere in the title`() {
        assertEquals(listOf("1"), sessions.filterByTitle("ogin").map { it.id })
    }

    @Test
    fun `leading and trailing whitespace in the query is trimmed`() {
        assertEquals(listOf("1"), sessions.filterByTitle("  login  ").map { it.id })
    }

    @Test
    fun `no match returns an empty list`() {
        assertEquals(emptyList<String>(), sessions.filterByTitle("zzz").map { it.id })
    }

    @Test
    fun `a blank-prompt session is excluded by a non-empty query`() {
        assertEquals(emptyList<String>(), listOf(s("4", "")).filterByTitle("x").map { it.id })
    }
}
