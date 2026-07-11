package dev.agentic.domain

/** One AskUserQuestion question. [multiSelect] answers are joined with ", ". */
data class AskQuestion(
    val text: String = "",
    val options: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)
