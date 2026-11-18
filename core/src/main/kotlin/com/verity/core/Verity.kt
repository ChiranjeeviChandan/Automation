package com.verity.core

/** One-call facade: analyse -> classify -> propose -> render. Platform code needs only this. */
object Verity {
    fun autoEnhance(img: ArgbImage, faceCount: Int = 0, options: RenderOptions = RenderOptions()): ArgbImage {
        val stats = SceneAnalyzer.analyze(img)
        val params = AutoGrader.propose(stats, AutoGrader.classify(stats, faceCount))
        return Renderer.render(img, stats, params, options)
    }
}
