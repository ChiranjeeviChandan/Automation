package com.verity.director

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.verity.core.GradeParams
import com.verity.core.SceneType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GradeDirectorTest {
    private val director = GradeDirector(AnthropicOkHttpClient.builder().apiKey("test").build())
    private val baseline = GradeParams()

    @Test fun clampsOutOfRangeValuesFromTheModel() {
        val json = """{"scene":"NIGHT","rationale":"r","grade":{"wb_strength":3,"temperature":0,"tint":0,"exposure_ev":9,
            "black_point":0,"shadows":0.2,"highlights":0.5,"contrast":2,"clarity":5,"vibrance":1,"saturation":1,"greens":0,
            "blues":0,"reds":0,"golds":0,"skin_protect":0.9,"chroma_denoise":0.8,"luma_denoise":0.3,"sharpen":4},
            "subject":{"present":false,"left":0,"top":0,"right":0,"bottom":0}}"""
        val d = assertNotNull(director.parse(json, SceneType.GENERAL, baseline))
        assertEquals(SceneType.NIGHT, d.scene)
        assertEquals(1f, d.params.wbStrength); assertEquals(1.5f, d.params.exposureEv)
        assertEquals(0.35f, d.params.contrast); assertEquals(0.6f, d.params.clarity)
        assertEquals(0.2f, d.params.saturation); assertEquals(0.8f, d.params.sharpen)
        assertNull(d.subject)
    }

    @Test fun missingFieldsFallBackToBaselineAndSubjectIsRead() {
        val json = """{"scene":"PORTRAIT","rationale":"ok","grade":{"vibrance":0.1},"subject":{"present":true,"left":0.3,"top":0.2,"right":0.6,"bottom":0.6}}"""
        val d = assertNotNull(director.parse(json, SceneType.GENERAL, baseline))
        assertEquals(baseline.clarity, d.params.clarity)
        assertEquals(0.1f, d.params.vibrance)
        assertNotNull(d.subject)
    }

    @Test fun garbageReturnsNullSoCallerFallsBack() {
        assertNull(director.parse("not json", SceneType.GENERAL, baseline))
    }

    @Test fun systemPromptEmbedsTheFullBrief() {
        assertTrue(GradeDirector.SYSTEM_PROMPT.contains("Authenticity comes before aesthetics"))
        assertTrue(GradeDirector.SYSTEM_PROMPT.contains("you do not edit pixels"))
    }
}
