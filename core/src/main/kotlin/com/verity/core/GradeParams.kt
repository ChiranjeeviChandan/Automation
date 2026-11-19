package com.verity.core

/** Photograph categories from section 6 of the master prompt. Each gets a different editing approach. */
enum class SceneType { PORTRAIT, FAMILY, CULTURAL, NATURE, ARCHITECTURE, NIGHT, OLD_PHOTO, GENERAL }

/**
 * Every knob the renderer exposes. All values are bounded by [clamped]: whatever proposes them
 * (the offline auto-grader, the AI director, or a user slider) cannot push the image into
 * over-saturated, over-sharpened or HDR territory. The bounds ARE the "strict negative instructions".
 *
 * None of these operations moves, warps, synthesises or repaints a pixel. They are tone and colour
 * transforms plus edge-aware local contrast, so faces, bodies, clothing and jewellery keep their
 * geometry by construction - identity preservation is an architectural guarantee, not a hope.
 */
data class GradeParams(
    /** 0 = keep the original colour cast, 1 = fully neutralise it. */
    val wbStrength: Float = 0.7f,
    /** -1 cool .. +1 warm creative offset applied after neutralisation. */
    val temperature: Float = 0f,
    /** -1 green .. +1 magenta. */
    val tint: Float = 0f,
    /** Exposure change in stops. */
    val exposureEv: Float = 0f,
    /** Linear-light black point (fraction of white). */
    val blackPoint: Float = 0f,
    /** Shadow detail recovery. */
    val shadows: Float = 0.25f,
    /** Highlight recovery / roll-off. */
    val highlights: Float = 0.3f,
    /** Midtone contrast. */
    val contrast: Float = 0.1f,
    /** Edge-aware local contrast. */
    val clarity: Float = 0.2f,
    /** Saturation boost weighted toward muted colours. */
    val vibrance: Float = 0.2f,
    /** Global saturation. */
    val saturation: Float = 0.05f,
    val greens: Float = 0f,
    val blues: Float = 0f,
    val reds: Float = 0f,
    val golds: Float = 0f,
    /** How strongly skin tones are shielded from saturation and hue-band changes. */
    val skinProtect: Float = 0.8f,
    val chromaDenoise: Float = 0.4f,
    val lumaDenoise: Float = 0.2f,
    val sharpen: Float = 0.35f,
    /** Local exposure on the subject (faces), in stops: the retoucher's "dodge". */
    val subjectEv: Float = 0f,
    /** Local exposure on everything else, in stops: the retoucher's "burn" for subject separation. */
    val backgroundEv: Float = 0f,
) {
    fun clamped() = GradeParams(
        wbStrength = wbStrength.coerceIn(0f, 1f),
        temperature = temperature.coerceIn(-1f, 1f),
        tint = tint.coerceIn(-1f, 1f),
        exposureEv = exposureEv.coerceIn(-1.5f, 1.5f),
        blackPoint = blackPoint.coerceIn(0f, 0.03f),
        shadows = shadows.coerceIn(0f, 1f),
        highlights = highlights.coerceIn(0f, 1f),
        contrast = contrast.coerceIn(-0.35f, 0.35f),
        clarity = clarity.coerceIn(0f, 0.6f),
        vibrance = vibrance.coerceIn(0f, 0.5f),
        saturation = saturation.coerceIn(-0.3f, 0.2f),
        greens = greens.coerceIn(-0.2f, 0.3f),
        blues = blues.coerceIn(-0.2f, 0.3f),
        reds = reds.coerceIn(-0.2f, 0.25f),
        golds = golds.coerceIn(-0.2f, 0.3f),
        skinProtect = skinProtect.coerceIn(0f, 1f),
        chromaDenoise = chromaDenoise.coerceIn(0f, 1f),
        lumaDenoise = lumaDenoise.coerceIn(0f, 0.8f),
        sharpen = sharpen.coerceIn(0f, 0.8f),
        subjectEv = subjectEv.coerceIn(-0.5f, 0.5f),
        backgroundEv = backgroundEv.coerceIn(-0.8f, 0.3f),
    )
}

/** Output geometry options. Neither option changes the content of the frame. */
data class RenderOptions(
    /** Upscale (never downscale) so the long edge reaches this size; 0 disables. 3840 = 4K UHD. */
    val targetLongEdge: Int = 3840,
    /** Square 1:1 profile crop centred on this subject (e.g. detected faces). Null = keep aspect ratio. */
    val profileSubject: NormRect? = null,
    /** Subjects (normally detected faces) for local dodge & burn. Empty = global grade only. */
    val subjects: List<NormRect> = emptyList(),
)
