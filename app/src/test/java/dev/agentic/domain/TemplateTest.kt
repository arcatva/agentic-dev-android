package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateTest {
    @Test fun substitutes_known_var() {
        assertEquals("hi bob", applyTemplate("hi {{name}}", mapOf("name" to "bob")))
    }

    @Test fun unknown_var_left_as_is() {
        assertEquals("hello {{unknown}}", applyTemplate("hello {{unknown}}", emptyMap()))
    }

    @Test fun multiple_vars_all_substituted() {
        assertEquals("a b", applyTemplate("{{x}} {{y}}", mapOf("x" to "a", "y" to "b")))
    }
}
