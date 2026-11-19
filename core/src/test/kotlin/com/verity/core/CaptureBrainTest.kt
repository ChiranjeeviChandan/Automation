package com.verity.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CaptureBrainTest {
    private val cam = CameraState(iso = 400, exposureNs = 10_000_000, evIndex = 0, evStep = 1f / 3, evMin = -6, evMax = 6,
        isoMin = 50, isoMax = 3200, expMinNs = 100_000, expMaxNs = 200_000_000, manualSupported = true)
    private fun frame(mean: Float = 0.42f, clipped: Float = 0f, face: Float? = null, motion: Float = 0f) =
        FrameMeasure(mean, 0.9f, clipped, 0f, face, motion, 0.5f)

    @Test fun overexposedFaceBringsEvDown() {
        val b = CaptureBrain(AgentMode.ASSIST)
        var d = b.decide(cam, frame(face = 0.78f), 0)
        d = b.decide(cam.copy(evIndex = d.evIndex), frame(face = 0.78f), 1000)
        assertTrue(d.evIndex < 0, "ev ${d.evIndex}")
    }

    @Test fun deadBandKeepsStableFramesStable() {
        val b = CaptureBrain(AgentMode.ASSIST)
        repeat(10) { assertEquals(0, b.decide(cam, frame(face = 0.5f), it * 500L).evIndex) }
    }

    @Test fun blownHighlightsOverrideAFaceThatWantsMoreLight() {
        val b = CaptureBrain(AgentMode.ASSIST)
        var ev = 0
        repeat(4) { ev = b.decide(cam.copy(evIndex = ev), frame(face = 0.35f, clipped = 0.15f), it * 500L).evIndex }
        assertTrue(ev < 0, "ev $ev")
    }

    @Test fun rateLimitedSoPreviewDoesNotBreathe() {
        val b = CaptureBrain(AgentMode.ASSIST)
        val d1 = b.decide(cam, frame(face = 0.85f), 0)
        val d2 = b.decide(cam.copy(evIndex = d1.evIndex), frame(face = 0.85f), 100)
        assertEquals(d1.evIndex, d2.evIndex)
    }

    @Test fun proModeFreezesMotionAndSnapsToFlicker() {
        val b = CaptureBrain(AgentMode.PRO, mainsHz = 50)
        val moving = b.decide(cam, frame(motion = 0.5f), 0)
        assertTrue(assertNotNull(moving.exposureNs) <= 2_000_000, "fast shutter for motion")
        val indoor = CaptureBrain(AgentMode.PRO, mainsHz = 50).decide(cam.copy(iso = 1600, exposureNs = 20_000_000), frame(face = 0.5f), 0)
        val t = assertNotNull(indoor.exposureNs)
        assertTrue(t < 10_000_000 || t % 10_000_000 == 0L, "banding-safe exposure $t")
    }

    @Test fun proModePrefersLowIsoInBrightLight() {
        val d = CaptureBrain(AgentMode.PRO).decide(cam.copy(iso = 50, exposureNs = 500_000), frame(), 0)
        assertEquals(50, d.iso)
    }

    @Test fun meterFindsClippingMotionAndFaceLuma() {
        val w = 64; val h = 48
        val y = ByteArray(w * h) { i -> if (i % w > 48) 255.toByte() else 100.toByte() }
        val m = FrameMeter()
        val r = m.measure(y, w, h, w, face = intArrayOf(5, 5, 30, 30))
        assertTrue(r.measure.clipped > 0.2f)
        assertEquals(100f / 255f, r.measure.faceLuma!!, 0.01f)
        val y2 = ByteArray(w * h) { 30 }
        assertTrue(m.measure(y2, w, h, w).measure.motion > 0.3f)
    }
}
