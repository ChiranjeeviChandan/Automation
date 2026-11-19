package com.verity.camera

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EditorStatus(
    val pending: Int = 0,
    val working: String? = null,
    val lastThumb: Bitmap? = null,
    val lastUri: Uri? = null,
    val lastNote: String? = null,
    val error: String? = null,
)

/**
 * The editor agent. Every photo taken is queued here and edited in the background, like handing the
 * card to a retoucher: scene analysis, AI direction (Claude, when a key is set), full-resolution grade
 * with subject-aware dodge & burn, then save. Only the edited photo is saved unless the user turns on
 * "Keep original" in Settings. The camera stays usable while this runs.
 */
class EditorAgent(private val context: Context, private val engine: EnhanceEngine, private val settings: Settings, scope: CoroutineScope) {
    private val queue = Channel<Bitmap>(capacity = 3)
    private val _status = MutableStateFlow(EditorStatus())
    val status: StateFlow<EditorStatus> = _status

    init {
        scope.launch(Dispatchers.Default) {
            for (shot in queue) {
                _status.update { it.copy(working = "Editing…", error = null) }
                runCatching { process(shot) }
                    .onFailure { e -> _status.update { it.copy(error = "Edit failed: ${e.message}") } }
                _status.update { it.copy(pending = (it.pending - 1).coerceAtLeast(0), working = null) }
            }
        }
    }

    /** Returns false when the queue is full (the user is shooting faster than the phone can edit). */
    fun submit(shot: Bitmap): Boolean {
        val ok = queue.trySend(shot).isSuccess
        if (ok) _status.update { it.copy(pending = it.pending + 1) }
        return ok
    }

    private fun process(shot: Bitmap) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        if (settings.keepOriginal) Bitmaps.saveToGallery(context, shot, "Verity_${stamp}_original", "Original capture (unedited)")
        _status.update { it.copy(working = if (engine.aiAvailable) "AI reading the scene…" else "Analysing…") }
        val plan = engine.plan(shot)
        _status.update { it.copy(working = "Grading full resolution…") }
        val edited = engine.renderFull(shot, plan, plan.direction.params, profile = false)
        val uri = Bitmaps.saveToGallery(context, edited, "Verity_$stamp", "Edited with Verity: ${plan.direction.scene.name.lowercase()} grade")
        _status.update { it.copy(lastThumb = Bitmaps.scaled(edited, 256), lastUri = uri, lastNote = plan.direction.rationale) }
    }
}
