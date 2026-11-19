package com.verity.director

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.verity.core.AutoGrader
import com.verity.core.ArgbImage
import com.verity.core.SceneAnalyzer
import kotlin.test.Test

class CaptureRun {
    @Test fun capture() {
        val url = System.getenv("VERITY_CAPTURE_URL") ?: return
        val c = AnthropicOkHttpClient.builder().apiKey("x").baseUrl(url).maxRetries(0).build()
        val img = ArgbImage(64, 64, IntArray(64 * 64) { -0x1000000 or 0x808080 })
        val s = SceneAnalyzer.analyze(img)
        val d = GradeDirector(c).direct(byteArrayOf(1, 2, 3), s, AutoGrader.classify(s, 0), AutoGrader.propose(s, AutoGrader.classify(s, 0)), 0, false)
        println("RESULT: ${d.rationale}")
    }
}
