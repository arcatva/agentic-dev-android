package dev.agentic.domain

/** Replace every {{varName}} in [body] with [vars]; unknowns left unchanged. */
fun applyTemplate(body: String, vars: Map<String, String>): String =
    vars.entries.fold(body) { acc, (k, v) -> acc.replace("{{$k}}", v) }
