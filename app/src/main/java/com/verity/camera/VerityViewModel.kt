package com.verity.camera

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verity.core.GradeParams
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
) {
    val reviewing get() = original != null
}

class VerityViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val engine = EnhanceEngine(settings)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var previewJob: Job? = null

    fun onCaptured(bitmap: Bitmap) { start(bitmap) }

    fun onPicked(uri: Uri) { viewModelScope.launch {
        _state.update { it.copy(busy = "Opening photo…") }
        val bmp = runCatching { withContext(Dispatchers.IO) { Bitmaps.decode(getApplication(), uri) } }
            .getOrElse { e -> _state.update { it.copy(busy = null, message = "Could not open photo: ${e.message}") }; return@launch }
        start(bmp)
    } }

    private fun start(bitmap: Bitmap) = viewModelScope.launch {
        _state.update { UiState(original = bitmap, busy = if (settings.aiEnabled && settings.apiKey.isNotBlank()) "Reading the scene…" else "Grading…") }
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
                withContext(Dispatchers.IO) { Bitmaps.saveToGallery(getApplication(), out, name) }
                "Saved ${out.width}×${out.height} to Pictures/Verity"
            }
            _state.update { it.copy(busy = null, message = result.getOrElse { e -> "Save failed: ${e.message}" }) }
        }
    }

    fun discard() = _state.update { UiState() }
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
