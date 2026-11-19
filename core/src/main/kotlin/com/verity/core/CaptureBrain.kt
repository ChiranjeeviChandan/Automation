package com.verity.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What the camera reports about the frame it just produced. */
data class CameraState(
    val iso: Int,
    val exposureNs: Long,
    val evIndex: Int,
    val evStep: Float,          // stops per index (usually 1/3 or 1/2)
    val evMin: Int, val evMax: Int,
    val isoMin: Int, val isoMax: Int,
    val expMinNs: Long, val expMaxNs: Long,
    val manualSupported: Boolean,
)

/** What the agent measured on the preview frame (Y plane, gamma-encoded 0..1). */
data class FrameMeasure(
    val meanLuma: Float,
    val p99Luma: Float,
    /** Fraction of pixels at 250+/255. */
    val clipped: Float,
    /** Fraction of pixels at 5-/255. */
    val crushed: Float,
    /** Median luma inside the largest face, or null when no face. */
    val faceLuma: Float?,
    /** 0 = still, ~1 = strong motion (mean abs frame difference, normalised). */
    val motion: Float,
    /** 0..1 relative sharpness of the focus region (for monitoring only). */
    val focusScore: Float,
)

enum class AgentMode { ASSIST, PRO }

data class CaptureDecision(
    /** Exposure-compensation index to apply (ASSIST mode, or PRO fallback). */
    val evIndex: Int,
    /** Manual sensor settings (PRO mode only). */
    val iso: Int? = null,
    val exposureNs: Long? = null,
    /** Short human-readable reasons for the HUD. */
    val notes: List<String> = emptyList(),
)

/**
 * The capture agent's brain: a small, stable feedback controller.
 *
 * ASSIST: the camera's own auto-exposure keeps running (metered on the face by the platform layer);
 *         the brain trims exposure compensation so faces land at a portrait-friendly brightness and
 *         large highlights do not blow out.
 * PRO:    the brain picks ISO and shutter itself: the slowest shutter that freezes the motion it sees,
 *         snapped to mains-flicker multiples indoors so there is no banding, and the lowest ISO that
 *         reaches the target exposure.
 *
 * Changes are rate-limited and have dead-bands, so the preview never "breathes".
 */
class CaptureBrain(
    private val mode: AgentMode,
    /** Mains frequency for anti-banding: 50 Hz (India, Europe, most of Asia/Africa) or 60 Hz (Americas, ...). */
    private val mainsHz: Int = 50,
    /** Highest ISO the agent will choose in PRO mode before trading it for a slower shutter. */
    private val isoCeiling: Int = 1600,
) {
    private var lastChangeMs = -10_000L
    private var smoothedErr = 0f

    fun decide(cam: CameraState, f: FrameMeasure, nowMs: Long): CaptureDecision {
        val notes = ArrayList<String>()
        // Exposure error in stops (positive = frame should be brighter).
        var err = when {
            f.faceLuma != null -> stops(FACE_TARGET, f.faceLuma).also { notes += "Metering on face" }
            else -> stops(SCENE_TARGET, f.meanLuma)
        }
        // Highlight protection: never let big areas blow out, even if the face wants more light.
        if (f.clipped > 0.06f) { err = min(err, -0.3f * (f.clipped / 0.06f).coerceAtMost(3f)); notes += "Protecting highlights" }
        else if (f.clipped > 0.02f && err > 0f) err = 0f
        // Shadow protection when there is no face: avoid crushed blacks on dark scenes.
        if (f.faceLuma == null && f.crushed > 0.25f && err < 0.3f) { err = max(err, 0.3f); notes += "Lifting shadows" }

        smoothedErr = 0.6f * smoothedErr + 0.4f * err
        val canChange = nowMs - lastChangeMs >= 350

        if (mode == AgentMode.PRO && cam.manualSupported) {
            return pro(cam, f, notes, canChange, nowMs)
        }
        var ev = cam.evIndex
        if (canChange && abs(smoothedErr) > DEAD_BAND) {
            val steps = (smoothedErr / cam.evStep).roundToInt().coerceIn(-2, 2) // at most 2 steps per tick
            if (steps != 0) { ev = (cam.evIndex + steps).coerceIn(cam.evMin, cam.evMax); lastChangeMs = nowMs }
        }
        if (ev != cam.evIndex) notes += "EV %+.1f".format(ev * cam.evStep)
        return CaptureDecision(ev, notes = notes)
    }

    private fun pro(cam: CameraState, f: FrameMeasure, notes: MutableList<String>, canChange: Boolean, nowMs: Long): CaptureDecision {
        // Exposure the scene needs, relative to what produced this frame.
        val current = cam.exposureNs.toDouble() * cam.iso
        val wanted = if (canChange && abs(smoothedErr) > DEAD_BAND) current * Math.pow(2.0, smoothedErr.toDouble()) else current

        // Shutter: freeze what is moving. People move even when they think they do not.
        var maxT = when {
            f.motion > 0.35f -> 1_000_000_000L / 500
            f.motion > 0.15f -> 1_000_000_000L / 250
            f.faceLuma != null -> 1_000_000_000L / 125
            else -> 1_000_000_000L / 60 // handheld limit for a phone's wide lens
        }
        maxT = maxT.coerceIn(cam.expMinNs, cam.expMaxNs)
        var t = maxT
        var iso = (wanted / t).roundToInt()
        if (iso < cam.isoMin) { // bright: shorten the shutter instead of under-shooting ISO
            iso = cam.isoMin
            t = (wanted / iso).roundToLong().coerceIn(cam.expMinNs, maxT)
        }
        if (iso > isoCeiling && f.motion < 0.15f) { // dark and still: trade noise for a slower shutter, up to 1/30
            val slow = min(cam.expMaxNs, 1_000_000_000L / 30)
            t = (wanted / isoCeiling).roundToLong().coerceIn(t, slow)
            iso = (wanted / t).roundToInt()
            notes += "Low light: slower shutter"
        }
        // Anti-banding: under mains lighting, exposures >= one flicker period must be whole multiples of it.
        val period = 1_000_000_000L / (2 * mainsHz)
        if (t >= period) {
            val snapped = max(1L, (t.toDouble() / period).roundToLong()) * period
            if (snapped <= cam.expMaxNs) { iso = (iso * t.toDouble() / snapped).roundToInt(); t = snapped }
            notes += "Anti-flicker ${mainsHz}Hz"
        }
        iso = iso.coerceIn(cam.isoMin, cam.isoMax)
        if (iso == cam.isoMax && wanted / t > cam.isoMax) notes += "Underexposed: editor will lift"
        if (canChange && (iso != cam.iso || t != cam.exposureNs)) lastChangeMs = nowMs
        notes += "ISO $iso  ${shutterLabel(t)}"
        return CaptureDecision(cam.evIndex, iso, t, notes)
    }

    companion object {
        /** Preview-frame luma (gamma) targets. */
        const val FACE_TARGET = 0.52f
        const val SCENE_TARGET = 0.42f
        const val DEAD_BAND = 0.2f

        /** Gamma-encoded luma ratio to stops (luma ~ linear^(1/2.2)). */
        fun stops(target: Float, actual: Float) = 2.2f * ln(max(target, 1e-3f) / max(actual, 1e-3f)) / ln(2f)

        fun shutterLabel(ns: Long): String =
            if (ns >= 500_000_000L) "%.1fs".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()}"

        /** Countries on 60 Hz mains (the rest of the world is 50 Hz). */
        private val SIXTY_HZ = setOf("US", "CA", "MX", "BR", "CO", "VE", "PE", "EC", "TW", "KR", "PH", "SA", "CR", "PA",
            "GT", "HN", "SV", "NI", "DO", "PR", "CU", "JM", "BS", "BZ", "LR", "SR", "GY", "BO")

        fun mainsHzFor(countryCode: String?): Int = if (countryCode?.uppercase() in SIXTY_HZ) 60 else 50
    }
}
