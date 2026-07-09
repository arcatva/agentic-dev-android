package dev.agentic.ui.session

import dev.agentic.domain.AnswerNode
import dev.agentic.domain.PromptNode
import dev.agentic.domain.SkillNode
import dev.agentic.domain.TextNode
import dev.agentic.domain.ToolNode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The fork "previous conversation" preview keeps user prompts and assistant text (prompts +
 *  TextNode + AnswerNode); tool calls and other non-message markers are dropped for a clean card. */
class ForkPreviewTest {

    @Test fun `keeps user prompts and assistant text, drops tool markers and blanks`() {
        val nodes = listOf(
            PromptNode("build a parser"),
            TextNode("let me think…"),                     // kept: assistant text
            ToolNode("Bash", "ls", "…"),                  // dropped: tool call
            AnswerNode("here is the parser"),
            SkillNode("deep-research"),                    // dropped: non-message marker
            TextNode("all done"),                          // kept: assistant text
            PromptNode("   "),                             // dropped: blank
        )

        val msgs = nodes.toForkMessages()

        assertEquals(
            listOf(
                ForkMsg(fromUser = true, "build a parser"),
                ForkMsg(fromUser = false, "let me think…"),
                ForkMsg(fromUser = false, "here is the parser"),
                ForkMsg(fromUser = false, "all done"),
            ),
            msgs,
        )
    }

    @Test fun `empty transcript yields no messages`() {
        assertEquals(emptyList<ForkMsg>(), emptyList<dev.agentic.domain.Node>().toForkMessages())
    }
}
