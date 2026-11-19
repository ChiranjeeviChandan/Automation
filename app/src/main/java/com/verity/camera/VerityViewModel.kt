package com.verity.camera

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verity.core.GradeParams
import com.verity.core.NormRect
import com.verity.director.RemovalSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Mode { GRADE, ERASE }

data class EraseState(
    val strokes: List<Stroke> = emptyList(),
    /** Boxes the user accepted from AI suggestions. */
    val boxes: List<NormRect> = emptyList(),
    val suggestions: List<RemovalSuggestion> = emptyList(),
    val note: String? = null,
    /** Brush radius as a fraction of the image's long edge. */
    val brush: Float = 0.025f,
    val erasing: Boolean = false,
    val canUndo: Boolean = false,
    /** True once anything was removed: the saved file is labelled as edited. */
    val edited: Boolean = false,
) {
    val hasSelection get() = boxes.isNotEmpty() || strokes.any { !it.erase }
}

data class UiState(
    val original: Bitmap? = null,
    val originalPreview: Bitmap? = null,
    val gradedPreview: Bitmap? = null,
    val plan: Plan? = null,
    /** 0 = untouched, 1 = full grade. Blends parameters, not pixels, so the result is still one coherent grade. */
    val strength: Float = 1f,
    val busy: String? = null,
    val message: String? = null,
    val showSettings: Boolean = false,
    val mode: Mode = Mode.GRADE,
    val erase: EraseState = EraseState(),
    val modelInstalled: Boolean = false,
    val modelProgress: Float? = null,
) {
    val reviewing get() = original != null
}

class VerityViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    val models = ModelStore(app, settings)
    private val engine = EnhanceEngine(settings, models)
    /** Live camera control (exposure, focus, metering, overlays). */
    val captureAgent = CaptureAgent(app).also {
        it.peaking = settings.peaking; it.zebras = settings.zebras
        it.setMode(if (settings.proAgent) com.verity.core.AgentMode.PRO else com.verity.core.AgentMode.ASSIST)
    }
    /** Background retoucher: edits and saves every shot. */
    val editor = EditorAgent(app, engine, settings, viewModelScope)
    private val _state = MutableStateFlow(UiState(modelInstalled = models.installed))
    private var undoImage: Bitmap? = null
    val state: StateFlow<UiState> = _state
    private var previewJob: Job? = null

    /** A photo from the camera goes straight to the editor agent; only the edited version is saved. */
    fun onCaptured(bitmap: Bitmap) {
        if (!editor.submit(bitmap)) _state.update { it.copy(message = "Still editing the previous shots - give it a second.") }
    }

    fun toggleProAgent() {
        settings.proAgent = !settings.proAgent
        captureAgent.setMode(if (settings.proAgent) com.verity.core.AgentMode.PRO else com.verity.core.AgentMode.ASSIST)
    }
    fun togglePeaking() { settings.peaking = !settings.peaking; captureAgent.peaking = settings.peaking }
    fun toggleZebras() { settings.zebras = !settings.zebras; captureAgent.zebras = settings.zebras }

    fun onPicked(uri: Uri) { viewModelScope.launch {
        _state.update { it.copy(busy = "Opening photo…") }
        val bmp = runCatching { withContext(Dispatchers.IO) { Bitmaps.decode(getApplication(), uri) } }
            .getOrElse { e -> _state.update { it.copy(busy = null, message = "Could not open photo: ${e.message}") }; return@launch }
        start(bmp)
    } }

    private fun start(bitmap: Bitmap) = viewModelScope.launch {
        undoImage = null
        _state.update { UiState(modelInstalled = models.installed, original = bitmap, busy = if (settings.aiEnabled && settings.apiKey.isNotBlank()) "Reading the scene…" else "Grading…") }
        val plan = withContext(Dispatchers.Default) { engine.plan(bitmap) }
        val origPreview = withContext(Dispatchers.Default) { Bitmaps.scaled(bitmap, 1600) }
        _state.update { it.copy(plan = plan, originalPreview = origPreview, busy = "Grading…") }
        rerenderPreview()
    }

    fun setStrength(v: Float) = _state.update { it.copy(strength = v) }

    fun rerenderPreview() {
        val s = _state.value
        val original = s.original ?: return
        val plan = s.plan ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) { engine.renderPreview(original, plan, effectiveParams(plan, s.strength)) }
            _state.update { it.copy(gradedPreview = bmp, busy = null) }
        }
    }

    fun save(profile: Boolean) {
        val s = _state.value
        val original = s.original ?: return
        val plan = s.plan ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = if (profile) "Framing profile picture…" else "Rendering full resolution…") }
            val result = runCatching {
                val out = withContext(Dispatchers.Default) { engine.renderFull(original, plan, effectiveParams(plan, s.strength), profile) }
                val name = "Verity_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + if (profile) "_profile" else ""
                val note = if (s.erase.edited) "Edited with Verity: colour grade and object removal" else "Edited with Verity: colour grade"
                withContext(Dispatchers.IO) { Bitmaps.saveToGallery(getApplication(), out, name, note) }
                "Saved ${out.width}×${out.height} to Pictures/Verity"
            }
            _state.update { it.copy(busy = null, message = result.getOrElse { e -> "Save failed: ${e.message}" }) }
        }
    }

    fun discard() { undoImage = null; _state.update { UiState(modelInstalled = models.installed) } }

    // ---------------- Erase mode ----------------

    fun setMode(m: Mode) = _state.update { it.copy(mode = m) }
    fun setBrush(r: Float) = _state.update { it.copy(erase = it.erase.copy(brush = r)) }
    fun setErasing(e: Boolean) = _state.update { it.copy(erase = it.erase.copy(erasing = e)) }

    fun addStroke(points: List<Pair<Float, Float>>) {
        if (points.isEmpty()) return
        _state.update { s -> s.copy(erase = s.erase.copy(strokes = s.erase.strokes + Stroke(points, s.erase.brush, s.erase.erasing))) }
    }

    fun clearSelection() = _state.update { it.copy(erase = it.erase.copy(strokes = emptyList(), boxes = emptyList())) }

    fun toggleSuggestion(sg: RemovalSuggestion) = _state.update { s ->
        val boxes = if (sg.box in s.erase.boxes) s.erase.boxes - sg.box else s.erase.boxes + sg.box
        s.copy(erase = s.erase.copy(boxes = boxes))
    }

    /** Ask Claude. With [request] it finds that thing and pre-selects it; without, it lists distractions. */
    fun aiFind(request: String?) {
        val image = _state.value.original ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = if (request.isNullOrBlank()) "Looking for distractions…" else "Finding it…") }
            val r = withContext(Dispatchers.Default) { engine.scout(image, request) }
            _state.update { s ->
                val boxes = if (!request.isNullOrBlank()) (s.erase.boxes + r.suggestions.map { it.box }).distinct() else s.erase.boxes
                s.copy(busy = null, erase = s.erase.copy(
                    suggestions = r.suggestions, boxes = boxes,
                    note = r.note.ifBlank { if (r.suggestions.isEmpty()) "Nothing obvious to remove." else null },
                ))
            }
        }
    }

    fun applyRemoval() {
        val s = _state.value
        val image = s.original ?: return
        val plan = s.plan ?: return
        if (!s.erase.hasSelection) return
        viewModelScope.launch {
            _state.update { it.copy(busy = if (models.installed) "Removing (AI + texture)…" else "Removing…") }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val (out, neural) = engine.remove(image, s.erase.boxes, s.erase.strokes)
                    Triple(out, neural, engine.restat(plan, out))
                }
            }
            result.onSuccess { (out, neural, newPlan) ->
                undoImage = image
                val prev = withContext(Dispatchers.Default) { Bitmaps.scaled(out, 1600) }
                _state.update { st -> st.copy(
                    original = out, originalPreview = prev, plan = newPlan,
                    erase = st.erase.copy(strokes = emptyList(), boxes = emptyList(), suggestions = emptyList(),
                        canUndo = true, edited = true, note = if (neural) "Removed with the on-device AI model." else "Removed."),
                ) }
                rerenderPreview()
            }.onFailure { e -> _state.update { it.copy(busy = null, message = "Removal failed: ${e.message}") } }
        }
    }

    fun undoRemoval() {
        val prev = undoImage ?: return
        undoImage = null
        viewModelScope.launch {
            val p = withContext(Dispatchers.Default) { Bitmaps.scaled(prev, 1600) }
            val plan = _state.value.plan?.let { withContext(Dispatchers.Default) { engine.restat(it, prev) } }
            _state.update { it.copy(original = prev, originalPreview = p, plan = plan, erase = it.erase.copy(canUndo = false)) }
            rerenderPreview()
        }
    }

    // ---------------- Model management ----------------

    fun downloadModel() {
        if (_state.value.modelProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(modelProgress = 0f) }
            val r = runCatching {
                withContext(Dispatchers.IO) { models.download { p -> _state.update { it.copy(modelProgress = p) } } }
            }
            _state.update { it.copy(modelProgress = null, modelInstalled = models.installed,
                message = r.fold({ "AI eraser model installed." }, { e -> "Model download failed: ${e.message}" })) }
        }
    }

    fun deleteModel() { models.remove(); _state.update { it.copy(modelInstalled = false) } }
    fun showMessage(m: String) = _state.update { it.copy(message = m) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun toggleSettings(show: Boolean) = _state.update { it.copy(showSettings = show) }

    companion object {
        private val NEUTRAL = GradeParams(
            wbStrength = 0f, temperature = 0f, tint = 0f, exposureEv = 0f, blackPoint = 0f, shadows = 0f,
            highlights = 0f, contrast = 0f, clarity = 0f, vibrance = 0f, saturation = 0f, greens = 0f, blues = 0f,
            reds = 0f, golds = 0f, chromaDenoise = 0f, lumaDenoise = 0f, sharpen = 0f,
        )

        fun effectiveParams(plan: Plan, t: Float): GradeParams {
            val p = plan.direction.params
            if (t >= 0.999f) return p
            fun l(a: Float, b: Float) = a + (b - a) * t
            val n = NEUTRAL
            return GradeParams(
                l(n.wbStrength, p.wbStrength), l(n.temperature, p.temperature), l(n.tint, p.tint), l(n.exposureEv, p.exposureEv),
                l(n.blackPoint, p.blackPoint), l(n.shadows, p.shadows), l(n.highlights, p.highlights), l(n.contrast, p.contrast),
                l(n.clarity, p.clarity), l(n.vibrance, p.vibrance), l(n.saturation, p.saturation), l(n.greens, p.greens),
                l(n.blues, p.blues), l(n.reds, p.reds), l(n.golds, p.golds), p.skinProtect,
                l(n.chromaDenoise, p.chromaDenoise), l(n.lumaDenoise, p.lumaDenoise), l(n.sharpen, p.sharpen),
            )
        }
    }
}
