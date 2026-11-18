package com.verity.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

private val SelectionRed = Color(0x99FF3B30)

/** Where an image drawn with ContentScale.Fit lands inside a container. */
private data class Fit(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun toNorm(o: Offset) = ((o.x - left) / width).coerceIn(0f, 1f) to ((o.y - top) / height).coerceIn(0f, 1f)
    companion object {
        fun of(container: IntSize, iw: Int, ih: Int): Fit {
            if (container.width == 0 || container.height == 0) return Fit(0f, 0f, 1f, 1f)
            val s = min(container.width.toFloat() / iw, container.height.toFloat() / ih)
            val w = iw * s; val h = ih * s
            return Fit((container.width - w) / 2f, (container.height - h) / 2f, w, h)
        }
    }
}

/** Paint over what should go. Red = selected. Dashed boxes = AI suggestions not yet accepted. */
@Composable
fun EraseCanvas(image: Bitmap, erase: EraseState, onStroke: (List<Pair<Float, Float>>) -> Unit) {
    val img = remember(image) { image.asImageBitmap() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val live = remember { mutableStateListOf<Offset>() }
    // Selection overlay rasterised at <= 720 px: cheap to rebuild after every stroke.
    val overlay = remember(image, erase.strokes, erase.boxes) {
        val s = min(1f, 720f / max(image.width, image.height))
        val w = max(1, (image.width * s).toInt()); val h = max(1, (image.height * s).toInt())
        val m = selectionMask(w, h, erase.boxes, erase.strokes)
        val px = IntArray(w * h) { if (m.bits[it]) 0x99FF3B30.toInt() else 0 }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val fit = Fit.of(size, image.width, image.height)

    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(fit) {
                detectDragGestures(
                    onDragStart = { live.clear(); live.add(it) },
                    onDrag = { change, _ -> live.add(change.position) },
                    onDragEnd = { onStroke(live.map(fit::toNorm)); live.clear() },
                    onDragCancel = { live.clear() },
                )
            }
            .pointerInput(fit) { detectTapGestures { onStroke(listOf(fit.toNorm(it))) } },
    ) {
        Image(img, "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Image(overlay, "Selection", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize()) {
            val r = erase.brush * max(fit.width, fit.height)
            val col = if (erase.erasing) Color(0x88FFFFFF) else SelectionRed
            live.forEach { drawCircle(col, r, it) }
            erase.suggestions.filter { it.box !in erase.boxes }.forEach { sg ->
                drawRect(
                    Color(0xFFE8B26A),
                    topLeft = Offset(fit.left + sg.box.left * fit.width, fit.top + sg.box.top * fit.height),
                    size = Size((sg.box.right - sg.box.left) * fit.width, (sg.box.bottom - sg.box.top) * fit.height),
                    style = DrawStroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
        }
    }
}

@Composable
fun EraseControls(state: UiState, vm: VerityViewModel) {
    val e = state.erase
    var request by remember { mutableStateOf("") }
    val idle = state.busy == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (e.suggestions.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(e.suggestions) { sg ->
                    FilterChip(selected = sg.box in e.boxes, onClick = { vm.toggleSuggestion(sg) }, label = { Text(sg.label, fontSize = 12.sp) })
                }
            }
        }
        e.note?.let { Text(it, color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = request, onValueChange = { request = it }, singleLine = true,
                placeholder = { Text("Describe what to remove", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (request.isNotBlank()) vm.aiFind(request) }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.aiFind(request) }, enabled = idle && request.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, "Find it") }
            IconButton(onClick = { vm.aiFind(null) }, enabled = idle) { Icon(Icons.Outlined.AutoAwesome, "AI suggestions", tint = Color(0xFFE8B26A)) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !e.erasing, onClick = { vm.setErasing(false) }, label = { Text("Brush") }, leadingIcon = { Icon(Icons.Outlined.Brush, null) })
            FilterChip(selected = e.erasing, onClick = { vm.setErasing(true) }, label = { Text("Unselect") }, modifier = Modifier.padding(start = 8.dp))
            Slider(value = e.brush, onValueChange = vm::setBrush, valueRange = 0.006f..0.08f, modifier = Modifier.padding(start = 12.dp).weight(1f))
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::undoRemoval, enabled = idle && e.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo removal") }
            TextButton(onClick = vm::clearSelection, enabled = idle && (e.strokes.isNotEmpty() || e.boxes.isNotEmpty())) { Text("Clear") }
            Box(Modifier.weight(1f))
            if (!state.modelInstalled) OutlinedButton(onClick = { vm.toggleSettings(true) }) { Text("AI model", fontSize = 12.sp) }
            Button(onClick = vm::applyRemoval, enabled = idle && e.hasSelection) { Text("Remove") }
        }
    }
}
