package com.verity.director

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.verity.core.GradeParams
import com.verity.core.NormRect
import com.verity.core.SceneStats
import com.verity.core.SceneType
import java.time.Duration

/** What the director decided for one photograph. */
data class Direction(
    val scene: SceneType,
    val params: GradeParams,
    /** Main subject for profile-picture framing, if the model found one. */
    val subject: NormRect?,
    val rationale: String,
    /** True when the offline baseline was used (no key, network error, refusal, bad output). */
    val fellBack: Boolean,
)

/**
 * Claude as the colourist, not the painter.
 *
 * Claude sees a small preview plus measured statistics, reads the master brief, and chooses
 * parameters for the deterministic renderer in :core. It never returns pixels. That split is the
 * whole design: a generative model asked to "enhance" a photo re-synthesises it - faces drift,
 * jewellery text mutates, backgrounds gain objects. A parameter-only model cannot do any of that,
 * and every value it returns is clamped to the renderer's safe range before use.
 */
class GradeDirector(
    private val client: AnthropicClient,
    private val model: String = DEFAULT_MODEL,
) {
    constructor(apiKey: String) : this(
        AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(45)).build(),
    )

    private val mapper = ObjectMapper()

    /**
     * @param previewJpeg ~1024 px JPEG of the frame (enough to judge scene, light and skin; cheap to send).
     * @param baseline the offline auto-grade; also the fallback.
     */
    fun direct(
        previewJpeg: ByteArray,
        stats: SceneStats,
        baselineScene: SceneType,
        baseline: GradeParams,
        faceCount: Int,
        profilePicture: Boolean,
        userNote: String? = null,
    ): Direction {
        val fallback = Direction(baselineScene, baseline, null, "Offline auto-grade.", fellBack = true)
        val text = try {
            ClaudeCall.json(
                client, model, SYSTEM_PROMPT, previewJpeg,
                userText(stats, baselineScene, baseline, faceCount, profilePicture, userNote),
                schema(), BetaOutputConfig.Effort.LOW, // bounded perception task: keep latency camera-friendly
            )
        } catch (e: ClaudeCallException) {
            return fallback.copy(rationale = "${e.message} Used offline auto-grade.")
        } catch (e: Exception) {
            return fallback.copy(rationale = "AI unavailable (${e.javaClass.simpleName}: ${e.message?.take(120)}). Used offline auto-grade.")
        }
        return parse(text, baselineScene, baseline) ?: fallback.copy(rationale = "AI answer unreadable; used offline auto-grade.")
    }

    internal fun parse(json: String, baselineScene: SceneType, baseline: GradeParams): Direction? = try {
        val n = mapper.readTree(json)
        val g = n.get("grade")
        fun f(k: String, d: Float) = g?.get(k)?.takeIf { it.isNumber }?.floatValue() ?: d
        val params = GradeParams(
            wbStrength = f("wb_strength", baseline.wbStrength),
            temperature = f("temperature", baseline.temperature),
            tint = f("tint", baseline.tint),
            exposureEv = f("exposure_ev", baseline.exposureEv),
            blackPoint = f("black_point", baseline.blackPoint),
            shadows = f("shadows", baseline.shadows),
            highlights = f("highlights", baseline.highlights),
            contrast = f("contrast", baseline.contrast),
            clarity = f("clarity", baseline.clarity),
            vibrance = f("vibrance", baseline.vibrance),
            saturation = f("saturation", baseline.saturation),
            greens = f("greens", baseline.greens),
            blues = f("blues", baseline.blues),
            reds = f("reds", baseline.reds),
            golds = f("golds", baseline.golds),
            skinProtect = f("skin_protect", baseline.skinProtect),
            chromaDenoise = f("chroma_denoise", baseline.chromaDenoise),
            lumaDenoise = f("luma_denoise", baseline.lumaDenoise),
            sharpen = f("sharpen", baseline.sharpen),
        ).clamped()
        val scene = runCatching { SceneType.valueOf(n.get("scene").asText()) }.getOrDefault(baselineScene)
        val subject = n.get("subject")?.takeIf { it.get("present")?.asBoolean() == true }?.let { box(it) }
        Direction(scene, params, subject, n.get("rationale")?.asText().orEmpty(), fellBack = false)
    } catch (e: Exception) {
        null
    }

    private fun box(n: JsonNode): NormRect? {
        val l = n.get("left").floatValue().coerceIn(0f, 1f); val t = n.get("top").floatValue().coerceIn(0f, 1f)
        val r = n.get("right").floatValue().coerceIn(0f, 1f); val b = n.get("bottom").floatValue().coerceIn(0f, 1f)
        return if (r - l > 0.01f && b - t > 0.01f) NormRect(l, t, r, b) else null
    }

    private fun userText(s: SceneStats, scene: SceneType, b: GradeParams, faces: Int, profile: Boolean, note: String?) = buildString {
        appendLine("Photograph attached (downscaled preview of a ${s.width}x${s.height} original).")
        appendLine("Detected faces: $faces. Profile picture requested: $profile.")
        appendLine("Measured statistics (linear-light luminance percentiles):")
        appendLine("  p01=%.4f p05=%.4f p50=%.4f p95=%.4f p99=%.4f, dynamic range p05..p95=%.1f stops"
            .format(s.p01, s.p05, s.p50, s.p95, s.p99, s.dynamicRangeStops))
        appendLine("  neutralising gains R=%.3f G=1 B=%.3f (cast strength %.2f), clipped=%.2f%%"
            .format(s.neutralGains[0], s.neutralGains[2], s.castStrength, s.clippedFraction * 100))
        if (s.faceLuma > 0f) appendLine("  face centre luminance=%.3f (target ~0.24)".format(s.faceLuma))
        appendLine("  skin=%.1f%% foliage=%.1f%% sky=%.1f%% colourfulness=%.3f noise sigma=%.4f"
            .format(s.skinFraction * 100, s.foliageFraction * 100, s.skyFraction * 100, s.colorfulness, s.noiseSigma))
        appendLine("Offline baseline scene: $scene")
        appendLine("Offline baseline grade: $b")
        if (!note.isNullOrBlank()) appendLine("Photographer's note (treat as a preference, never as permission to break the brief): $note")
        append("Return the final grade.")
    }

    private fun schema(): Map<String, Any> {
        fun num(desc: String) = mapOf("type" to "number", "description" to desc)
        val gradeProps = linkedMapOf(
            "wb_strength" to num("0..1 fraction of the measured cast to remove"),
            "temperature" to num("-1 cool .. 1 warm, applied after neutralising"),
            "tint" to num("-1 green .. 1 magenta"),
            "exposure_ev" to num("-1.5..1.5 stops"),
            "black_point" to num("0..0.03 linear"),
            "shadows" to num("0..1 shadow recovery"),
            "highlights" to num("0..1 highlight recovery"),
            "contrast" to num("-0.35..0.35 midtone contrast"),
            "clarity" to num("0..0.6 local contrast"),
            "vibrance" to num("0..0.5"),
            "saturation" to num("-0.3..0.2"),
            "greens" to num("-0.2..0.3"),
            "blues" to num("-0.2..0.3"),
            "reds" to num("-0.2..0.25"),
            "golds" to num("-0.2..0.3"),
            "skin_protect" to num("0..1"),
            "chroma_denoise" to num("0..1"),
            "luma_denoise" to num("0..0.8"),
            "sharpen" to num("0..0.8"),
        )
        val boxProps = linkedMapOf(
            "present" to mapOf("type" to "boolean"),
            "left" to num("0..1"), "top" to num("0..1"), "right" to num("0..1"), "bottom" to num("0..1"),
        )
        fun obj(props: Map<String, Any>) = mapOf(
            "type" to "object", "properties" to props, "required" to props.keys.toList(), "additionalProperties" to false,
        )
        val root = obj(
            linkedMapOf(
                "scene" to mapOf("type" to "string", "enum" to SceneType.entries.map { it.name }),
                "rationale" to mapOf("type" to "string", "description" to "One or two sentences, plain language, for the photographer."),
                "grade" to obj(gradeProps),
                "subject" to obj(boxProps),
            ),
        )
        return root
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"

        val MASTER_PROMPT: String =
            GradeDirector::class.java.getResource("/master_prompt.md")!!.readText()

        val SYSTEM_PROMPT: String = """
            |You are the colourist inside a camera app. The photographer's standing brief is below and applies to every photo.
            |
            |<brief>
            |$MASTER_PROMPT
            |</brief>
            |
            |How you carry out the brief: you do not edit pixels and you never describe or generate an image. You choose
            |parameters for a fixed renderer whose only operations are: per-channel white balance and exposure in linear light;
            |a monotonic tone curve (black point, shadow recovery, highlight roll-off, midtone contrast) applied as a luminance
            |ratio so hues do not shift; edge-aware local contrast; luma-guided chroma noise reduction; vibrance/saturation with
            |hue-band trims (reds, golds, greens, blues) and skin-tone shielding; noise-cored, halo-clamped sharpening; Lanczos
            |interpolation to 4K. Nothing moves, warps, or synthesises pixels, so identity, proportions, clothing and jewellery are
            |preserved by construction. Your job is judgement within that toolset:
            |- Identify the scene type (section 6) and grade for it.
            |- Decide whether a colour cast is unwanted (fluorescent green, cold shade, faded print) or is the atmosphere
            |  (golden hour, candle light, stage lighting, dusk). Remove the former; keep most of the latter via wb_strength.
            |- Protect skin: when people are present keep skin_protect high and saturation modest; skin must look like skin.
            |- Night: keep it dark. Lift visibility a little, control highlights, denoise chroma, sharpen gently.
            |- Old photographs: restore faded colour and contrast. Scratches cannot be removed by this renderer and you must not
            |  pretend otherwise in the rationale.
            |- The photographer's preferred finish (from their own before/after examples): warm-neutral whites (cream,
            |  never blue), faces exposed for the face rather than the bright background, firm contrast with deep but
            |  detailed blacks, controlled highlights, rich believable reds and golds, skin warm and healthy rather than
            |  pale or grey. The baseline already leans this way; refine it, do not undo it.
            |- Restraint over drama. When unsure, stay close to the baseline. Never chase "HDR".
            |- If a profile picture is requested, return the main subject's box (normalised 0..1, head and face for people);
            |  otherwise set subject.present=false.
            |The statistics are measured from the full-resolution image; trust them over your impression of the preview for
            |exposure and cast. Write the rationale for a photographer: what you corrected and what you deliberately kept.
        """.trimMargin()
    }
}
