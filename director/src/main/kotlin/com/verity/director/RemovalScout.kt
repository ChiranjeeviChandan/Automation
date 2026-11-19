package com.verity.director

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.fasterxml.jackson.databind.ObjectMapper
import com.verity.core.NormRect
import java.time.Duration

enum class RemovalKind { PERSON, VEHICLE, LITTER, SIGN, WIRE, ANIMAL, OBJECT }

/** One thing Claude thinks could be removed. Always shown to the user; never removed automatically. */
data class RemovalSuggestion(val label: String, val kind: RemovalKind, val box: NormRect, val reason: String)

data class ScoutResult(val suggestions: List<RemovalSuggestion>, val note: String, val ok: Boolean)

/**
 * Claude as a second pair of eyes for clean-up.
 *
 * Two modes:
 *  - no request: propose distractions a photographer would plausibly remove (photobombers far behind
 *    the subject, litter, stray wires, a bin), never the subject, never cultural or religious elements.
 *  - request ("remove the man in the red shirt"): locate exactly what the user described.
 *
 * Output is boxes only. The phone turns a box into a selection, the user checks it, and the
 * on-device remover does the work.
 */
class RemovalScout(private val client: AnthropicClient, private val model: String = GradeDirector.DEFAULT_MODEL) {

    constructor(apiKey: String) : this(AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(45)).build())

    private val mapper = ObjectMapper()

    fun scout(previewJpeg: ByteArray, request: String? = null): ScoutResult {
        val ask = if (request.isNullOrBlank())
            "Suggest distractions worth removing from this photograph, if any."
        else
            "The photographer asks: \"${request.trim()}\". Locate exactly what they mean. If nothing matches, return no suggestions and say so in the note."
        val text = try {
            // localisation benefits from a little more care than grading
            ClaudeCall.json(client, model, SYSTEM, previewJpeg, ask, schema(), BetaOutputConfig.Effort.MEDIUM)
        } catch (e: ClaudeCallException) {
            return ScoutResult(emptyList(), "${e.message} Paint over what you want removed.", ok = false)
        } catch (e: Exception) {
            return ScoutResult(emptyList(), "AI unavailable (${e.javaClass.simpleName}). Paint over what you want removed.", ok = false)
        }
        return parse(text) ?: ScoutResult(emptyList(), "AI answer unreadable. Paint over what you want removed.", ok = false)
    }

    internal fun parse(json: String): ScoutResult? = try {
        val n = mapper.readTree(json)
        val list = n.get("suggestions")?.mapNotNull { s ->
            val b = s.get("box") ?: return@mapNotNull null
            // Model boxes are approximate: pad 6% so edges and shadows are included; the user refines.
            val l = b.get("left").floatValue(); val t = b.get("top").floatValue()
            val r = b.get("right").floatValue(); val btm = b.get("bottom").floatValue()
            if (r - l < 0.005f || btm - t < 0.005f) return@mapNotNull null
            val px = (r - l) * 0.06f; val py = (btm - t) * 0.06f
            RemovalSuggestion(
                label = s.get("label")?.asText().orEmpty().ifBlank { "Object" },
                kind = runCatching { RemovalKind.valueOf(s.get("kind").asText()) }.getOrDefault(RemovalKind.OBJECT),
                box = NormRect((l - px).coerceIn(0f, 1f), (t - py).coerceIn(0f, 1f), (r + px).coerceIn(0f, 1f), (btm + py).coerceIn(0f, 1f)),
                reason = s.get("reason")?.asText().orEmpty(),
            )
        }.orEmpty().take(6)
        ScoutResult(list, n.get("note")?.asText().orEmpty(), ok = true)
    } catch (e: Exception) {
        null
    }

    private fun schema(): Map<String, Any> {
        fun num(d: String) = mapOf("type" to "number", "description" to d)
        fun obj(props: Map<String, Any>) = mapOf("type" to "object", "properties" to props, "required" to props.keys.toList(), "additionalProperties" to false)
        val box = obj(linkedMapOf("left" to num("0..1 of image width"), "top" to num("0..1 of image height"), "right" to num("0..1"), "bottom" to num("0..1")))
        val item = obj(
            linkedMapOf(
                "label" to mapOf("type" to "string", "description" to "Short name the photographer will recognise, e.g. 'man in red shirt, left background'"),
                "kind" to mapOf("type" to "string", "enum" to RemovalKind.entries.map { it.name }),
                "box" to box,
                "reason" to mapOf("type" to "string", "description" to "Why it distracts, one short sentence"),
            ),
        )
        val root = obj(
            linkedMapOf(
                "suggestions" to mapOf("type" to "array", "items" to item),
                "note" to mapOf("type" to "string", "description" to "One sentence for the photographer"),
            ),
        )
        return root
    }

    companion object {
        val SYSTEM = """
            |You help a photographer clean up a photograph. You only point at things; an on-device tool removes
            |what the photographer confirms, so precise, tight bounding boxes matter most (normalised 0..1, covering the
            |whole object including limbs, its shadow if attached, and anything it holds).
            |
            |When asked for suggestions, propose at most 6 genuine distractions, most distracting first: strangers or
            |photobombers clearly behind or beside the intended subject, litter, stray cables or wires, bins, traffic cones,
            |intrusive signs, a passing vehicle, a fingertip at the frame edge. Return an empty list if the photo is clean.
            |
            |Never suggest: the main subject(s); anyone posing, interacting with, or part of the group being photographed;
            |religious or cultural symbols, ritual objects, clothing, jewellery or anything a person wears; text or
            |structures that give the place its identity. When in doubt, leave it out.
            |
            |When the photographer names something specific, locate exactly that, even if it is a person, because they
            |asked for it explicitly. If several things match, return each. If nothing matches, return none and say so.
        """.trimMargin()
    }
}
