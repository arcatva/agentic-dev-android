package dev.agentic.domain

/** Replace every {{varName}} in [body] with the matching value from [vars].
 *  Unknown placeholders are left unchanged. */
fun applyTemplate(body: String, vars: Map<String, String>): String =
    vars.entries.fold(body) { acc, (k, v) -> acc.replace("{{$k}}", v) }
