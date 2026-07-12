package dev.agentic.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPreviewTest {
    // ESC (0x1B) built from its code point so the source stays plain ASCII. Real logs carry it before
    // the CSI codes; it is non-printing, so the app rendered "[1m"/"[22m" literally.
    private val esc = 27.toChar().toString()

    @Test fun strips_ansi_and_local_command_tags() {
        val raw = "<local-command-stdout>Set model to ${esc}[1mFable 5${esc}[22m and saved</local-command-stdout>"
        assertEquals("Set model to Fable 5 and saved", cleanSessionPreview(raw))
    }

    @Test fun collapses_whitespace_and_trims() {
        assertEquals("hi there", cleanSessionPreview("  hi\n\n there  "))
    }

    @Test fun leaves_a_normal_prompt_untouched() {
        assertEquals("挂载我的sda2", cleanSessionPreview("挂载我的sda2"))
    }
}
