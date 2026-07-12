package dev.agentic.domain

private val ANSI = Regex("\\u001B\\[[0-9;?]*[a-zA-Z]")
private val CMD_TAGS =
    Regex("</?(?:local-command-stdout|local-command-stderr|command-name|command-message|command-args)>")
private val WS = Regex("\\s+")

/** Clean a raw CLI prompt for one-line display: strip ANSI escape codes and Claude Code local-command
 *  wrapper tags (`<local-command-stdout>`, `<command-name>`, …), then collapse whitespace. Adopt rows
 *  render the session's first prompt verbatim, so without this the control junk shows literally. */
fun cleanSessionPreview(s: String): String =
    s.replace(ANSI, "").replace(CMD_TAGS, "").replace(WS, " ").trim()
