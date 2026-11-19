package com.verity.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.verity.core.AgentMode
import kotlin.math.max

private val Amber = Color(0xFFE8B26A)

@Composable
fun CameraScreen(vm: VerityViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::onPicked) }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    val agent = vm.captureAgent
    val hud by agent.hud.collectAsState()
    val editor by vm.editor.status.collectAsState()
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    if (granted) DisposableEffect(front) {
        agent.bind(owner, previewView, front)
        onDispose { agent.unbind() }
    }
    LaunchedEffect(flash) { if (flash) { kotlinx.coroutines.delay(120); flash = false } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { agent.focusAt(previewView, it.x, it.y) } },
            )
            MonitorOverlay(hud, mirrored = front)
        } else Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera access is needed to take photos. You can still enhance photos from your gallery.", color = Color.White)
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 16.dp)) { Text("Allow camera") }
        }
        if (flash) Box(Modifier.fillMaxSize().background(Color(0x66FFFFFF)))

        // ---- top: agent controls + readout
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = hud.mode == AgentMode.PRO, onClick = vm::toggleProAgent, enabled = hud.manualSupported || hud.mode == AgentMode.PRO,
                    label = { Text(if (hud.mode == AgentMode.PRO) "PRO agent" else "AUTO agent", fontSize = 12.sp) },
                )
                FilterChip(selected = agent.peaking, onClick = vm::togglePeaking, label = { Text("Peaking", fontSize = 12.sp) })
                FilterChip(selected = agent.zebras, onClick = vm::toggleZebras, label = { Text("Zebras", fontSize = 12.sp) })
                Box(Modifier.weight(1f))
                IconButton(onClick = { vm.toggleSettings(true) }) { Icon(Icons.Outlined.Settings, "Settings", tint = Color.White) }
            }
            Text(
                "ISO ${hud.iso}   ${hud.shutter}   EV %+.1f   ${hud.focus}   ${hud.wb}".format(hud.ev),
                color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (hud.notes.isNotEmpty()) Text(
                hud.notes.joinToString("  ·  "), color = Amber, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp).background(Color(0x88000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // ---- bottom: histogram, editor status, shutter row
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                Histogram(hud.histogram, Modifier.size(128.dp, 52.dp))
                Box(Modifier.weight(1f))
                FocusMeter(hud.focusScore, hud.motion)
            }
            val status = editor.error ?: editor.working?.let { w -> if (editor.pending > 1) "$w (${editor.pending} in queue)" else w }
            if (status != null) Row(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).background(Color(0xAA000000), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editor.error == null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Amber)
                Text(status, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                // Last edited photo: opens in the system viewer. Long-press the gallery icon flow lives in Review.
                val thumb = editor.lastThumb
                if (thumb != null) {
                    val img = remember(thumb) { thumb.asImageBitmap() }
                    Image(img, "Last edited photo", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, Color.White, RoundedCornerShape(10.dp))
                            .clickable { editor.lastUri?.let { u -> context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } })
                } else IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.PhotoLibrary, "Edit a photo from the gallery", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Box(
                    Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(8.dp).background(Color.White, CircleShape)
                        .clickable(enabled = granted) {
                            flash = true
                            agent.capture(onBitmap = vm::onCaptured, onError = { vm.showMessage(it) })
                        },
                )
                Row {
                    if (thumb != null) IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Outlined.PhotoLibrary, "Edit a photo from the gallery", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    IconButton(onClick = { front = !front }) {
                        Icon(Icons.Outlined.Cameraswitch, "Switch camera", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}

/** Peaking / zebra overlay and face box, drawn with the same centre-crop as the preview. */
@Composable
private fun MonitorOverlay(hud: HudState, mirrored: Boolean) {
    val bmp = hud.overlay
    val img = remember(hud.frame) { bmp?.asImageBitmap() }
    Canvas(Modifier.fillMaxSize().graphicsLayer(scaleX = if (mirrored) -1f else 1f)) {
        val bw = bmp?.width ?: 3; val bh = bmp?.height ?: 4
        val s = max(size.width / bw, size.height / bh)
        val dw = bw * s; val dh = bh * s
        val ox = (size.width - dw) / 2f; val oy = (size.height - dh) / 2f
        if (img != null) drawImage(img, dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize(dw.toInt(), dh.toInt()))
        hud.face?.let { f ->
            drawRect(
                Amber, topLeft = Offset(ox + f.left * dw, oy + f.top * dh), size = Size((f.right - f.left) * dw, (f.bottom - f.top) * dh),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun Histogram(h: FloatArray, modifier: Modifier) {
    Canvas(modifier.background(Color(0x88000000), RoundedCornerShape(6.dp)).padding(4.dp)) {
        val bw = size.width / h.size
        h.forEachIndexed { i, v ->
            val c = if (i >= 62) Color(0xFFFF5040) else if (i <= 1) Color(0xFF5080FF) else Color(0xDDFFFFFF)
            drawRect(c, topLeft = Offset(i * bw, size.height * (1 - v)), size = Size(bw, size.height * v))
        }
    }
}

/** Sharpness of the focus area and subject/hand motion, as two small bars. */
@Composable
private fun FocusMeter(focus: Float, motion: Float) {
    Column(Modifier.background(Color(0x88000000), RoundedCornerShape(6.dp)).padding(6.dp)) {
        Bar("Sharp", focus, Color(0xFF8BC34A))
        Bar("Shake", motion.coerceAtMost(1f), if (motion > 0.25f) Color(0xFFFF7043) else Color(0xFFBDBDBD))
    }
}

@Composable
private fun Bar(label: String, v: Float, c: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 10.sp, modifier = Modifier.size(38.dp, 14.dp))
        Canvas(Modifier.size(60.dp, 6.dp)) {
            drawRect(Color(0x55FFFFFF), size = size)
            drawRect(c, size = Size(size.width * v.coerceIn(0f, 1f), size.height))
        }
    }
}
