package com.verity.core

import kotlin.math.ln
import kotlin.math.max

/**
 * Deterministic, offline grade proposal. Works with no network; also used as the baseline the AI
 * director refines, so a bad or missing AI answer degrades to "good" instead of "broken".
 */
object AutoGrader {

    /** Offline scene guess. [faceCount] comes from the platform face detector. */
    fun classify(s: SceneStats, faceCount: Int): SceneType = when {
        s.p50 < 0.035f && s.p99 < 0.6f -> SceneType.NIGHT
        faceCount >= 3 -> SceneType.FAMILY
        faceCount >= 1 -> SceneType.PORTRAIT
        s.colorfulness < 0.035f && s.castStrength > 1.15f -> SceneType.OLD_PHOTO
        s.foliageFraction > 0.25f -> SceneType.NATURE
        s.skyFraction > 0.2f -> SceneType.ARCHITECTURE
        else -> SceneType.GENERAL
    }

    /**
     * Phone JPEGs are already tone-mapped, denoised and sharpened by the camera. The grade therefore
     * *finishes* the image: richer colour, firmer blacks, gentle contrast. It does not re-process it.
     * Lifting shadows and compressing highlights on an already tone-mapped frame is what makes
     * "enhanced" photos look flat and grey, so those are only used when the measurements call for it.
     */
    fun propose(s: SceneStats, scene: SceneType): GradeParams {
        val key = when (scene) {
            SceneType.NIGHT -> 0.06f
            SceneType.PORTRAIT, SceneType.FAMILY -> 0.18f
            SceneType.ARCHITECTURE -> 0.17f
            else -> 0.18f
        }
        var ev = (ln(key / max(s.p50, 1e-4f)) / ln(2f)) * 0.4f
        if (kotlin.math.abs(ev) < 0.25f) ev = 0f // the camera's auto-exposure is usually right: leave it
        val headroomEv = ln(1.0f / max(s.p99, 1e-4f)) / ln(2f)
        ev = ev.coerceAtMost(max(0f, headroomEv)).coerceIn(-0.7f, 0.7f)
        val gain = Math.pow(2.0, ev.toDouble()).toFloat()

        val portraitLike = scene == SceneType.PORTRAIT || scene == SceneType.FAMILY
        var wb = when (scene) {
            SceneType.OLD_PHOTO -> 0.9f
            SceneType.NIGHT -> 0.3f
            SceneType.NATURE -> 0.5f
            SceneType.PORTRAIT, SceneType.FAMILY -> 0.5f
            SceneType.CULTURAL -> 0.45f
            else -> 0.65f
        }
        if (s.castStrength > 1.8f && scene != SceneType.OLD_PHOTO) wb *= 0.85f
        wb *= s.wbConfidence // little neutral evidence = little correction

        val flat = s.dynamicRangeStops < 4f
        return GradeParams(
            wbStrength = wb,
            temperature = 0f,
            exposureEv = ev,
            blackPoint = (s.p01 * gain * 0.8f).coerceAtMost(0.02f),
            shadows = when {
                scene == SceneType.NIGHT -> 0.1f
                s.p05 * gain < 0.006f -> 0.3f
                scene == SceneType.FAMILY -> 0.2f
                else -> 0.08f
            },
            highlights = when {
                s.clippedFraction > 0.02f -> 0.5f
                s.p99 * gain > 0.95f -> 0.3f
                else -> 0.1f
            },
            contrast = when {
                scene == SceneType.NIGHT -> 0.08f
                scene == SceneType.OLD_PHOTO || flat -> 0.25f
                portraitLike -> 0.12f
                else -> 0.15f
            },
            clarity = when (scene) {
                SceneType.PORTRAIT -> 0.08f
                SceneType.FAMILY -> 0.1f
                SceneType.ARCHITECTURE -> 0.3f
                SceneType.NATURE -> 0.25f
                SceneType.NIGHT -> 0.1f
                SceneType.OLD_PHOTO -> 0.25f
                SceneType.CULTURAL, SceneType.GENERAL -> 0.18f
            },
            vibrance = when (scene) {
                SceneType.PORTRAIT, SceneType.FAMILY -> 0.15f
                SceneType.NIGHT -> 0.1f
                SceneType.OLD_PHOTO, SceneType.NATURE -> 0.3f
                else -> 0.22f
            },
            saturation = if (s.colorfulness < 0.06f) 0.05f else 0f,
            greens = when (scene) { SceneType.NATURE -> 0.15f; SceneType.GENERAL, SceneType.ARCHITECTURE -> 0.08f; else -> 0.04f },
            blues = when (scene) { SceneType.NATURE, SceneType.ARCHITECTURE -> 0.12f; else -> 0.05f },
            reds = when (scene) { SceneType.CULTURAL, SceneType.NATURE -> 0.08f; else -> 0.02f },
            golds = when (scene) { SceneType.CULTURAL -> 0.12f; else -> 0.04f },
            skinProtect = if (portraitLike || s.skinFraction > 0.02f) 0.9f else 0.5f,
            chromaDenoise = ((0.2f + s.noiseSigma * 25f) + if (scene == SceneType.NIGHT) 0.3f else 0f).coerceIn(0.2f, 0.9f),
            lumaDenoise = (s.noiseSigma * 12f + if (scene == SceneType.NIGHT) 0.15f else 0f).coerceIn(0f, 0.4f),
            // Phone JPEGs arrive pre-sharpened; more on top produces crunchy edges and halos.
            sharpen = when (scene) {
                SceneType.PORTRAIT -> 0.1f
                SceneType.FAMILY -> 0.15f
                SceneType.ARCHITECTURE -> 0.3f
                SceneType.NATURE -> 0.25f
                SceneType.NIGHT -> 0.08f
                SceneType.OLD_PHOTO -> 0.25f
                else -> 0.18f
            },
        ).clamped()
    }
}
