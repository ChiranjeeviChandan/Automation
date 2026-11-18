package com.verity.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ReviewScreen(state: UiState, vm: VerityViewModel) {
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::discard) { Icon(Icons.Outlined.Close, "Back to camera", tint = Color.White) }
            val scene = state.plan?.direction?.scene?.name?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
            Text(scene ?: "", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (state.plan?.direction?.fellBack == false) Text("AI graded", color = Color(0xFFE8B26A), fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val before = state.originalPreview
            val after = state.gradedPreview
            if (before != null) CompareView(before, after)
        }

        state.plan?.direction?.rationale?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color(0xFFBDBDBD), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }

        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Strength", color = Color.White, fontSize = 13.sp)
            Slider(
                value = state.strength, onValueChange = vm::setStrength, onValueChangeFinished = vm::rerenderPreview,
                enabled = state.plan != null, modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.save(profile = true) }, enabled = state.busy == null && state.plan != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.AccountCircle, null); Spacer(Modifier.padding(4.dp)); Text("Profile 1:1")
            }
            Button(onClick = { vm.save(profile = false) }, enabled = state.busy == null && state.plan != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Download, null); Spacer(Modifier.padding(4.dp)); Text(if (vm.settings.upscaleTo4k) "Save 4K" else "Save")
            }
        }
    }
}

/** Drag anywhere to move the divider: original on the left, graded on the right. */
@Composable
private fun CompareView(before: android.graphics.Bitmap, after: android.graphics.Bitmap?) {
    var split by remember { mutableFloatStateOf(0.5f) }
    val b = remember(before) { before.asImageBitmap() }
    val a = remember(after) { after?.asImageBitmap() }
    Box(
        Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
            detectDragGestures { change, _ -> split = (change.position.x / size.width).coerceIn(0f, 1f) }
        },
    ) {
        Image(b, "Original", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (a != null) {
            Image(
                a, "Enhanced", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().drawWithContent {
                    clipRect(left = size.width * split) { this@drawWithContent.drawContent() }
                },
            )
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * split
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        Text("Original", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x88000000)).padding(4.dp))
        Text("Verity", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x88000000)).padding(4.dp))
    }
}

