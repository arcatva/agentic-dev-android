package dev.agentic.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Parse AskUserQuestion `questions` tolerating new (`question`/`header`/object-options/`multiSelect`) and old (`text`/string-options) schemas; options accept bare strings or `{label}`; drop questions with neither text nor options. */
fun parseAskQuestions(arr: JsonArray?): List<AskQuestion> =
    arr.orEmpty().mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val text = ((obj["question"] ?: obj["text"]) as? JsonPrimitive)?.contentOrNull ?: ""
        val options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { opt ->
            (opt as? JsonPrimitive)?.contentOrNull
                ?: ((opt as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull
        }
        val multiSelect = (obj["multiSelect"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (text.isBlank() && options.isEmpty()) return@mapNotNull null
        AskQuestion(text, options, multiSelect)
    }
