package dev.agentic.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFocusPolicyTest {
    @Test fun `empty search field rejects programmatic focus until user taps it`() {
        assertFalse(searchTextFieldCanFocus(query = "", userActivated = false))
    }

    @Test fun `empty search field accepts focus after user taps it`() {
        assertTrue(searchTextFieldCanFocus(query = "", userActivated = true))
    }

    @Test fun `non-empty search field remains focusable across recomposition`() {
        assertTrue(searchTextFieldCanFocus(query = "fix", userActivated = false))
    }
}
