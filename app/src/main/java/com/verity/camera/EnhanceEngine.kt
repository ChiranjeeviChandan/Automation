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
import com.verity.director.RemovalScout
import com.verity.director.ScoutResult
import com.verity.core.Mask
import com.verity.core.ObjectRemover
import kotlin.math.max

/** Everything decided about one photo; rendering it at any size is then deterministic. */
data class Plan(
    val stats: SceneStats,
    val direction: Direction,
    val faces: List<NormRect>,
)

/** A brush stroke in normalised image coordinates. [radius] is a fraction of the long edge. */
data class Stroke(val points: List<Pair<Float, Float>>, val radius: Float, val erase: Boolean)

/** Rasterise a selection at any resolution: boxes first, then strokes in order (so the eraser can trim boxes). */
fun selectionMask(w: Int, h: Int, boxes: List<NormRect>, strokes: List<Stroke>): Mask {
    val m = Mask(w, h)
    boxes.forEach { m.rect(it.toPixels(w, h)) }
    val long = max(w, h).toFloat()
    strokes.forEach { s -> m.stroke(s.points.map { (x, y) -> x * w to y * h }, s.radius * long, value = !s.erase) }
    return m
}

/** Blocking calls: run on Dispatchers.Default. */
class EnhanceEngine(private val settings: Settings, val models: ModelStore) {

    private var director: GradeDirector? = null
    private var scout: RemovalScout? = null
    private var directorKey: String? = null

    val aiAvailable get() = settings.aiEnabled && settings.apiKey.isNotBlank()

    /** Claude suggests distractions, or locates what [request] describes. */
    fun scout(image: Bitmap, request: String?): ScoutResult {
        if (!aiAvailable) return ScoutResult(emptyList(), "Add an Anthropic API key in Settings to use AI find. You can still paint over objects.", ok = false)
        directorFor(settings.apiKey)
        return scout!!.scout(Bitmaps.jpeg(Bitmaps.scaled(image, 1280), 85), request)
    }

    /** Full-resolution removal. Returns the new image and whether the neural model contributed. */
    fun remove(image: Bitmap, boxes: List<NormRect>, strokes: List<Stroke>): Pair<Bitmap, Boolean> {
        val src = Bitmaps.toArgb(image)
        val sel = selectionMask(src.width, src.height, boxes, strokes)
        if (sel.isEmpty()) return image to false
        val neural = models.inpainter()
        val res = ObjectRemover.remove(src, sel, neural, neural?.inputSize ?: 512)
        return Bitmaps.fromArgb(res.image) to res.usedNeural
    }

    /** After an edit, re-measure the image but keep the chosen grade (no second AI call). */
    fun restat(plan: Plan, image: Bitmap): Plan {
        val faces = Bitmaps.detectFaces(image)
        return plan.copy(stats = SceneAnalyzer.analyze(Bitmaps.toArgb(image), hasFaces = faces.isNotEmpty(), faces = faces), faces = faces)
    }

    fun plan(original: Bitmap): Plan {
        val faces = Bitmaps.detectFaces(original)
        val stats = SceneAnalyzer.analyze(Bitmaps.toArgb(original), hasFaces = faces.isNotEmpty(), faces = faces)
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
        return Bitmaps.fromArgb(Renderer.render(Bitmaps.toArgb(small), plan.stats, params, RenderOptions(targetLongEdge = 0, subjects = plan.faces)))
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
        val out = Renderer.render(Bitmaps.toArgb(original), plan.stats, params, RenderOptions(target, subject, subjects = plan.faces))
        return Bitmaps.fromArgb(out)
    }

    private fun directorFor(key: String): GradeDirector {
        if (director == null || directorKey != key) {
            director = GradeDirector(key); scout = RemovalScout(key); directorKey = key
        }
        return director!!
    }
}
