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

    fun propose(s: SceneStats, scene: SceneType): GradeParams {
        // Exposure: move the median toward a scene-appropriate key, but only part of the way -
        // the original lighting atmosphere is kept on purpose.
        val key = when (scene) {
            SceneType.NIGHT -> 0.06f
            SceneType.PORTRAIT, SceneType.FAMILY -> 0.2f
            SceneType.ARCHITECTURE -> 0.17f
            else -> 0.18f
        }
        var ev = (ln(key / max(s.p50, 1e-4f)) / ln(2f)) * if (scene == SceneType.NIGHT) 0.4f else 0.6f
        // Never push a meaningful amount of the frame into clipping.
        val headroomEv = ln(1.6f / max(s.p99, 1e-4f)) / ln(2f)
        ev = ev.coerceAtMost(headroomEv).coerceIn(-1f, 1f)
        val gain = Math.pow(2.0, ev.toDouble()).toFloat()

        // Strong casts are often intentional (sunsets, candle light, stage lighting): correct them less.
        var wb = when (scene) {
            SceneType.OLD_PHOTO -> 0.9f
            SceneType.NIGHT -> 0.35f
            SceneType.NATURE -> 0.55f
            SceneType.PORTRAIT, SceneType.FAMILY -> 0.65f
            SceneType.CULTURAL -> 0.55f
            else -> 0.7f
        }
        if (s.castStrength > 1.8f && scene != SceneType.OLD_PHOTO) wb *= 0.85f

        val flat = s.dynamicRangeStops < 4f
        val portraitLike = scene == SceneType.PORTRAIT || scene == SceneType.FAMILY

        return GradeParams(
            wbStrength = wb,
            temperature = if (portraitLike || scene == SceneType.CULTURAL) 0.08f else 0f,
            exposureEv = ev,
            blackPoint = (s.p01 * gain * 0.6f).coerceAtMost(0.02f),
            shadows = when {
                scene == SceneType.NIGHT -> 0.2f
                scene == SceneType.FAMILY -> 0.5f
                s.p05 * gain < 0.012f -> 0.5f
                else -> 0.25f
            },
            highlights = if (s.clippedFraction > 0.01f || s.p99 * gain > 0.9f) 0.65f else 0.3f,
            contrast = when {
                scene == SceneType.NIGHT -> 0.05f
                scene == SceneType.OLD_PHOTO -> 0.25f
                flat -> 0.22f
                else -> 0.1f
            },
            clarity = when (scene) {
                SceneType.PORTRAIT -> 0.1f
                SceneType.FAMILY -> 0.14f
                SceneType.ARCHITECTURE -> 0.35f
                SceneType.NATURE -> 0.3f
                SceneType.NIGHT -> 0.12f
                SceneType.OLD_PHOTO -> 0.28f
                SceneType.CULTURAL -> 0.22f
                SceneType.GENERAL -> 0.22f
            },
            vibrance = when (scene) {
                SceneType.PORTRAIT, SceneType.FAMILY -> 0.18f
                SceneType.NIGHT -> 0.1f
                SceneType.OLD_PHOTO -> 0.3f
                SceneType.NATURE -> 0.3f
                else -> 0.25f
            },
            saturation = if (s.colorfulness > 0.12f) 0f else 0.05f,
            greens = when (scene) { SceneType.NATURE -> 0.15f; SceneType.GENERAL, SceneType.ARCHITECTURE -> 0.08f; else -> 0.05f },
            blues = when (scene) { SceneType.NATURE, SceneType.ARCHITECTURE -> 0.12f; else -> 0.06f },
            reds = when (scene) { SceneType.CULTURAL -> 0.08f; SceneType.NATURE -> 0.08f; else -> 0.03f },
            golds = when (scene) { SceneType.CULTURAL -> 0.12f; SceneType.FAMILY, SceneType.PORTRAIT -> 0.04f; else -> 0.06f },
            skinProtect = if (portraitLike || s.skinFraction > 0.02f) 0.85f else 0.5f,
            chromaDenoise = ((0.3f + s.noiseSigma * 25f) + if (scene == SceneType.NIGHT) 0.3f else 0f).coerceIn(0.3f, 0.9f),
            lumaDenoise = (s.noiseSigma * 18f + if (scene == SceneType.NIGHT) 0.15f else 0f).coerceIn(0.08f, 0.6f),
            sharpen = when (scene) {
                SceneType.PORTRAIT -> 0.22f
                SceneType.FAMILY -> 0.28f
                SceneType.ARCHITECTURE -> 0.5f
                SceneType.NATURE -> 0.45f
                SceneType.NIGHT -> 0.18f
                SceneType.OLD_PHOTO -> 0.3f
                else -> 0.35f
            },
        ).clamped()
    }
}
