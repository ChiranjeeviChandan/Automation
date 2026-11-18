package com.verity.camera

import android.graphics.Bitmap
import com.verity.core.AutoGrader
import com.verity.core.GradeParams
import com.verity.core.NormRect
import com.verity.core.Renderer
import com.verity.core.RenderOptions
import com.verity.core.SceneAnalyzer
import com.verity.core.SceneStats
import com.verity.director.Direction
import com.verity.director.GradeDirector

/** Everything decided about one photo; rendering it at any size is then deterministic. */
data class Plan(
    val stats: SceneStats,
    val direction: Direction,
    val faces: List<NormRect>,
)

/** Blocking calls: run on Dispatchers.Default. */
class EnhanceEngine(private val settings: Settings) {

    private var director: GradeDirector? = null
    private var directorKey: String? = null

    fun plan(original: Bitmap): Plan {
        val faces = Bitmaps.detectFaces(original)
        val stats = SceneAnalyzer.analyze(Bitmaps.toArgb(original))
        val scene = AutoGrader.classify(stats, faces.size)
        val baseline = AutoGrader.propose(stats, scene)
        val key = settings.apiKey
        val direction = if (settings.aiEnabled && key.isNotBlank()) {
            val d = directorFor(key)
            d.direct(
                previewJpeg = Bitmaps.jpeg(Bitmaps.scaled(original, 1024), 85),
                stats = stats, baselineScene = scene, baseline = baseline,
                faceCount = faces.size, profilePicture = false,
            )
        } else {
            Direction(scene, baseline, null,
                if (key.isBlank()) "Offline auto-grade (add an API key in settings for AI direction)." else "Offline auto-grade.",
                fellBack = true)
        }
        return Plan(stats, direction, faces)
    }

    /** Fast on-screen preview: same parameters, smaller canvas, no upscale. */
    fun renderPreview(original: Bitmap, plan: Plan, params: GradeParams): Bitmap {
        val small = Bitmaps.scaled(original, 1600)
        return Bitmaps.fromArgb(Renderer.render(Bitmaps.toArgb(small), plan.stats, params, RenderOptions(targetLongEdge = 0)))
    }

    /** Full-resolution export. Profile mode = square crop around faces (or the AI's subject box). */
    fun renderFull(original: Bitmap, plan: Plan, params: GradeParams, profile: Boolean): Bitmap {
        val subject = if (profile) {
            Bitmaps.union(plan.faces) ?: plan.direction.subject ?: NormRect(0.25f, 0.2f, 0.75f, 0.7f)
        } else null
        val target = when {
            profile -> 2048 // avatars are shown small; 2048 keeps them crisp without a huge file
            settings.upscaleTo4k -> 3840
            else -> 0
        }
        val out = Renderer.render(Bitmaps.toArgb(original), plan.stats, params, RenderOptions(target, subject))
        return Bitmaps.fromArgb(out)
    }

    private fun directorFor(key: String): GradeDirector {
        if (director == null || directorKey != key) { director = GradeDirector(key); directorKey = key }
        return director!!
    }
}
