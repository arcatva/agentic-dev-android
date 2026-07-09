package dev.agentic.domain

/** One question in an AskUserQuestion event, owned by the domain layer. [multiSelect] = the user may
 *  pick more than one option (answers joined with ", "). */
data class AskQuestion(
    val text: String = "",
    val options: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)
