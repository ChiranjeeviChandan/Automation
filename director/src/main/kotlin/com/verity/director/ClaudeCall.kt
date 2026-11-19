package com.verity.director

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.TextBlockParam
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64

/** Why a call produced no usable JSON, worded for the photographer. */
class ClaudeCallException(message: String) : Exception(message)

/**
 * One image + text -> JSON request, with a compatibility fallback.
 *
 * First attempt: structured outputs (schema-constrained JSON) plus the server-side refusal fallback.
 * If the API rejects that request shape (HTTP 400, e.g. a feature not enabled for this account), the
 * same question is asked again as a plain Messages request with the schema in the prompt, and the
 * JSON is extracted from the text. Callers clamp and validate every value either way.
 */
internal object ClaudeCall {
    private val mapper = ObjectMapper()

    fun json(
        client: AnthropicClient,
        model: String,
        system: String,
        jpeg: ByteArray,
        userText: String,
        schema: Map<String, Any>,
        effort: BetaOutputConfig.Effort,
    ): String {
        val b64 = Base64.getEncoder().encodeToString(jpeg)
        return try {
            structured(client, model, system, b64, userText, schema, effort)
        } catch (e: BadRequestException) {
            try {
                plain(client, model, system, b64, userText, schema)
            } catch (e2: AnthropicServiceException) {
                throw ClaudeCallException("Anthropic rejected the request (${e2.statusCode()}): ${apiMessage(e2)}")
            }
        } catch (e: AnthropicServiceException) {
            throw ClaudeCallException(
                when (e.statusCode()) {
                    401 -> "API key rejected. Check it in Settings."
                    403 -> "This API key has no access to $model."
                    404 -> "Model $model not available for this key."
                    429 -> "Rate limited or out of credits. Try again shortly."
                    else -> "Anthropic error ${e.statusCode()}: ${apiMessage(e)}"
                },
            )
        }
    }

    private fun structured(
        client: AnthropicClient, model: String, system: String, b64: String, userText: String,
        schema: Map<String, Any>, effort: BetaOutputConfig.Effort,
    ): String {
        val sb = BetaJsonOutputFormat.Schema.builder()
        schema.forEach { (k, v) -> sb.putAdditionalProperty(k, JsonValue.from(v)) }
        val params = com.anthropic.models.beta.messages.MessageCreateParams.builder()
            .model(model)
            .maxTokens(4000L)
            .system(system)
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
            .outputConfig(BetaOutputConfig.builder().effort(effort).format(BetaJsonOutputFormat.builder().schema(sb.build()).build()).build())
            .addUserMessageOfContentBlockParams(
                listOf(
                    BetaContentBlockParam.ofImage(
                        BetaBase64ImageSource.builder().mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).data(b64).build(),
                    ),
                    BetaContentBlockParam.ofText(userText),
                ),
            )
            .build()
        val r = client.beta().messages().create(params)
        if (r.stopReason().map { it == BetaStopReason.REFUSAL }.orElse(false)) throw ClaudeCallException("AI declined this photo.")
        if (r.stopReason().map { it == BetaStopReason.MAX_TOKENS }.orElse(false)) throw ClaudeCallException("AI answer was cut off.")
        return r.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    }

    private fun plain(client: AnthropicClient, model: String, system: String, b64: String, userText: String, schema: Map<String, Any>): String {
        val params = com.anthropic.models.messages.MessageCreateParams.builder()
            .model(model)
            .maxTokens(4000L)
            .system(system + "\n\nReply with exactly one JSON object and nothing else, matching this JSON Schema:\n" + mapper.writeValueAsString(schema))
            .addUserMessageOfContentBlockParams(
                listOf(
                    ContentBlockParam.ofImage(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).data(b64).build()),
                    ContentBlockParam.ofText(TextBlockParam.builder().text(userText).build()),
                ),
            )
            .build()
        val r = client.messages().create(params)
        val stop = r.stopReason().map { it.toString().lowercase() }.orElse("")
        if (stop.contains("refusal")) throw ClaudeCallException("AI declined this photo.")
        return extractJson(r.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""))
            ?: throw ClaudeCallException("AI answer had no JSON.")
    }

    /** First balanced {...} block in [text] (tolerates prose or code fences around it). */
    internal fun extractJson(text: String): String? {
        val start = text.indexOf('{'); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) } }
        }
        return null
    }

    /** The human sentence from Anthropic's error JSON, e.g. "Your credit balance is too low ...". */
    internal fun apiMessage(e: Exception): String {
        val raw = e.message ?: return "unknown error"
        return Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw)?.groupValues?.get(1)?.take(180) ?: raw.take(180)
    }
}
