package dev.agentic.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Parse an AskUserQuestion `questions` array into [AskQuestion]s. Tolerant of the current Claude
 *  schema ({question, header, options:[{label, description}], multiSelect}) and the older
 *  {text, options:[string]} one: the question text is `question` (fallback `text`), each option is a
 *  bare string or an object with a `label`, and `multiSelect` is captured. A question with neither
 *  text nor options is dropped (nothing to render/answer). */
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
