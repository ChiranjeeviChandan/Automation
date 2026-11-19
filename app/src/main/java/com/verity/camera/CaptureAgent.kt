package com.verity.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect as ARect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.FaceDetector
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.verity.core.AgentMode
import com.verity.core.CameraState
import com.verity.core.CaptureBrain
import com.verity.core.FrameMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/** Everything the viewfinder HUD shows. Face box and overlay are in upright preview orientation. */
data class HudState(
    val iso: Int = 0,
    val shutter: String = "",
    val ev: Float = 0f,
    val focus: String = "",
    val wb: String = "",
    val notes: List<String> = emptyList(),
    val histogram: FloatArray = FloatArray(64),
    val face: com.verity.core.NormRect? = null,
    val overlay: Bitmap? = null,
    val frame: Long = 0,
    val focusScore: Float = 0f,
    val motion: Float = 0f,
    val mode: AgentMode = AgentMode.ASSIST,
    val manualSupported: Boolean = false,
)

/**
 * The capture agent. Watches every preview frame (~10/s) and drives the camera like a careful
 * photographer would: meters and focuses on faces, protects highlights, chooses ISO/shutter in Pro
 * mode, and paints monitoring overlays (focus peaking, zebras, histogram).
 *
 * Decisions come from [CaptureBrain] (pure Kotlin, unit-tested); this class only talks to the camera.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CaptureAgent(private val context: Context) {
    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud

    var peaking = true
    var zebras = true
    private var mode = AgentMode.ASSIST
    private var brain = CaptureBrain(AgentMode.ASSIST, CaptureBrain.mainsHzFor(Locale.getDefault().country))

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val meter = FrameMeter()
    @Volatile private var lastResult: TotalCaptureResult? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var characteristics: CameraCharacteristics? = null
    private var lastFrameMs = 0L
    private var lastMeterMs = 0L
    private var lastFaceCenter: Pair<Float, Float>? = null
    private var manualHoldUntil = 0L
    private var appliedManual: Pair<Int, Long>? = null
    private var yBuf = ByteArray(0)
    private var overlayBmp: Bitmap? = null
    private var frameNo = 0L
    private var swFaceFrame = 0
    private var swFace: com.verity.core.NormRect? = null // buffer-normalised

    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
        .build()

    fun setMode(m: AgentMode) {
        if (m == mode) return
        mode = m
        brain = CaptureBrain(m, CaptureBrain.mainsHzFor(Locale.getDefault().country))
        appliedManual = null
        if (m == AgentMode.ASSIST) camera?.let { Camera2CameraControl.from(it.cameraControl).clearCaptureRequestOptions() }
        _hud.update { it.copy(mode = m) }
    }

    fun bind(owner: LifecycleOwner, previewView: PreviewView, front: Boolean) {
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val faceMode = bestFaceDetectMode(front)
        val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()

        val previewBuilder = Preview.Builder().setResolutionSelector(ratio)
        Camera2Interop.Extender(previewBuilder).apply {
            setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { lastResult = result }
            })
            if (faceMode != null) setCaptureRequestOption(CaptureRequest.STATISTICS_FACE_DETECT_MODE, faceMode)
        }
        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val an = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        an.setAnalyzer(analysisExecutor) { img -> try { onFrame(img) } catch (_: Exception) { } finally { img.close() } }
        analysis = an

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()
            appliedManual = null
            camera = runCatching { provider.bindToLifecycle(owner, selector, preview, imageCapture, an) }.getOrNull()
            camera?.let { cam ->
                val info = Camera2CameraInfo.from(cam.cameraInfo)
                characteristics = runCatching {
                    (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(info.cameraId)
                }.getOrNull()
                val manual = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
                _hud.update { it.copy(manualSupported = manual, mode = mode) }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun unbind() {
        runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        camera = null
    }

    /** Tap-to-focus: the user overrides the agent's face metering for 5 seconds. */
    fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
                .setAutoCancelDuration(5, TimeUnit.SECONDS).build(),
        )
        manualHoldUntil = System.currentTimeMillis() + 5000
    }

    fun capture(onBitmap: (Bitmap) -> Unit, onError: (String) -> Unit) {
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bmp = image.toBitmap()
                image.close()
                onBitmap(Bitmaps.rotate(bmp, rotation))
            }
            override fun onError(exception: ImageCaptureException) { onError(exception.message ?: "Capture failed") }
        })
    }

    // ---------------------------------------------------------------- per frame

    private fun onFrame(img: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastFrameMs < 90) return // ~10 decisions per second is plenty and keeps the phone cool
        lastFrameMs = now
        val cam = camera ?: return
        val plane = img.planes[0]
        val w = img.width; val h = img.height; val stride = plane.rowStride
        val need = stride * (h - 1) + w
        if (yBuf.size < need) yBuf = ByteArray(need)
        val buf = plane.buffer.duplicate().also { it.rewind() }
        buf.get(yBuf, 0, minOf(need, buf.remaining()))
        val rot = img.imageInfo.rotationDegrees
        val result = lastResult

        // Faces: hardware detector when the camera has one, otherwise the platform detector at ~3 Hz.
        val faceBuf: com.verity.core.NormRect? = hardwareFace(result) ?: softwareFace(w, h, stride, rot)
        val facePx = faceBuf?.let { intArrayOf((it.left * w).toInt(), (it.top * h).toInt(), (it.right * w).toInt(), (it.bottom * h).toInt()) }

        val m = meter.measure(yBuf, w, h, stride, face = facePx, peaking = peaking, zebras = zebras)

        val exp = cam.cameraInfo.exposureState
        val ch = characteristics
        val isoRange = ch?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = ch?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val state = CameraState(
            iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100,
            exposureNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 16_666_666L,
            evIndex = exp.exposureCompensationIndex,
            evStep = exp.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: (1f / 3),
            evMin = exp.exposureCompensationRange.lower, evMax = exp.exposureCompensationRange.upper,
            isoMin = isoRange?.lower ?: 50, isoMax = isoRange?.upper ?: 3200,
            expMinNs = expRange?.lower ?: 100_000L, expMaxNs = minOf(expRange?.upper ?: 250_000_000L, 250_000_000L),
            manualSupported = _hud.value.manualSupported,
        )
        val d = brain.decide(state, m.measure, now)

        // Apply.
        val dIso = d.iso; val dExp = d.exposureNs // locals: cross-module properties don't smart-cast
        if (mode == AgentMode.PRO && state.manualSupported && dIso != null && dExp != null) {
            val want: Pair<Int, Long> = dIso to dExp
            val prev = appliedManual
            if (prev == null || abs(prev.first - want.first) > prev.first / 10 || abs(prev.second - want.second) > prev.second / 10) {
                Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, want.first)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, want.second)
                        .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(want.second, 33_333_333L))
                        .build(),
                )
                appliedManual = want
            }
        } else if (d.evIndex != state.evIndex && exp.isExposureCompensationSupported) {
            cam.cameraControl.setExposureCompensationIndex(d.evIndex)
        }
        // Face-priority AF/AE/AWB, re-armed only when the face moved or every few seconds (no AF hunting).
        if (faceBuf != null && now > manualHoldUntil) {
            val cx = (faceBuf.left + faceBuf.right) / 2f; val cy = (faceBuf.top + faceBuf.bottom) / 2f
            val moved = lastFaceCenter?.let { abs(it.first - cx) > 0.08f || abs(it.second - cy) > 0.08f } ?: true
            if (moved || now - lastMeterMs > 4000) {
                analysis?.let { a ->
                    val point = SurfaceOrientedMeteringPointFactory(1f, 1f, a).createPoint(cx, cy, maxOf(0.1f, faceBuf.right - faceBuf.left))
                    cam.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
                            .setAutoCancelDuration(4, TimeUnit.SECONDS).build(),
                    )
                }
                lastFaceCenter = cx to cy; lastMeterMs = now
            }
        }

        // HUD.
        val overlay = m.overlay?.let { uprightOverlay(it, w, h, rot) }
        val dist = result?.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val afState = when (result?.get(CaptureResult.CONTROL_AF_STATE)) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "AF●"
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN, CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "AF…"
            else -> "AF"
        }
        val awb = when (result?.get(CaptureResult.CONTROL_AWB_STATE)) {
            CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "AWB●"; CaptureResult.CONTROL_AWB_STATE_LOCKED -> "AWB🔒"; else -> "AWB…"
        }
        frameNo++
        _hud.update {
            it.copy(
                iso = state.iso, shutter = CaptureBrain.shutterLabel(state.exposureNs),
                ev = d.evIndex * state.evStep,
                focus = afState + (if (dist != null && dist > 0f) " %.1fm".format(1f / dist) else if (dist == 0f) " ∞" else ""),
                wb = awb, notes = d.notes.distinct().take(3), histogram = m.histogram,
                face = faceBuf?.let { f -> toUpright(f, rot) }, overlay = overlay, frame = frameNo,
                focusScore = m.measure.focusScore, motion = m.measure.motion,
            )
        }
    }

    private fun bestFaceDetectMode(front: Boolean): Int? = runCatching {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = mgr.cameraIdList.first { mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
        val modes = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: intArrayOf()
        when {
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE in modes -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL in modes -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
            else -> null
        }
    }.getOrNull()

    /** Largest hardware face, mapped from sensor coordinates into analysis-buffer coordinates (0..1). */
    private fun hardwareFace(r: TotalCaptureResult?): com.verity.core.NormRect? {
        val faces = r?.get(CaptureResult.STATISTICS_FACES) ?: return null
        val f = faces.filter { it.score >= 30 }.maxByOrNull { it.bounds.width() * it.bounds.height() } ?: return null
        val crop: ARect = r.get(CaptureResult.SCALER_CROP_REGION)
            ?: characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val b = f.bounds
        return com.verity.core.NormRect(
            ((b.left - crop.left).toFloat() / crop.width()).coerceIn(0f, 1f), ((b.top - crop.top).toFloat() / crop.height()).coerceIn(0f, 1f),
            ((b.right - crop.left).toFloat() / crop.width()).coerceIn(0f, 1f), ((b.bottom - crop.top).toFloat() / crop.height()).coerceIn(0f, 1f),
        )
    }

    /** Fallback face finder on the luma plane (upright, ~320 px), every third analysed frame. */
    private fun softwareFace(w: Int, h: Int, stride: Int, rot: Int): com.verity.core.NormRect? {
        if (++swFaceFrame % 3 != 0) return swFace
        val step = max(1, w / 320)
        val bw = (w / step) and 1.inv(); val bh = h / step
        val px = IntArray(bw * bh) { i -> val v = yBuf[(i / bw) * step * stride + (i % bw) * step].toInt() and 0xFF; -0x1000000 or (v shl 16) or (v shl 8) or v }
        var bmp = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)
        bmp = Bitmaps.rotate(bmp, rot)
        val evenW = bmp.width and 1.inv()
        val rgb = Bitmap.createBitmap(evenW, bmp.height, Bitmap.Config.RGB_565)
        android.graphics.Canvas(rgb).drawBitmap(bmp, 0f, 0f, null)
        val out = arrayOfNulls<FaceDetector.Face>(3)
        val n = FaceDetector(evenW, bmp.height, 3).findFaces(rgb, out)
        val face = (0 until n).mapNotNull { out[it] }.filter { it.confidence() >= 0.4f }.maxByOrNull { it.eyesDistance() }
        swFace = face?.let {
            val mid = android.graphics.PointF(); it.getMidPoint(mid); val e = it.eyesDistance()
            val up = com.verity.core.NormRect(
                ((mid.x - e * 1.1f) / evenW).coerceIn(0f, 1f), ((mid.y - e * 1.2f) / bmp.height).coerceIn(0f, 1f),
                ((mid.x + e * 1.1f) / evenW).coerceIn(0f, 1f), ((mid.y + e * 1.6f) / bmp.height).coerceIn(0f, 1f),
            )
            fromUpright(up, rot)
        }
        return swFace
    }

    // Orientation helpers. rotationDegrees = clockwise rotation that makes the buffer upright.
    private fun toUpright(r: com.verity.core.NormRect, rot: Int): com.verity.core.NormRect {
        fun p(x: Float, y: Float): Pair<Float, Float> = when (rot) { 90 -> (1 - y) to x; 180 -> (1 - x) to (1 - y); 270 -> y to (1 - x); else -> x to y }
        val a = p(r.left, r.top); val b = p(r.right, r.bottom)
        return com.verity.core.NormRect(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    private fun fromUpright(r: com.verity.core.NormRect, rot: Int): com.verity.core.NormRect {
        fun p(u: Float, v: Float): Pair<Float, Float> = when (rot) { 90 -> v to (1 - u); 180 -> (1 - u) to (1 - v); 270 -> (1 - v) to u; else -> u to v }
        val a = p(r.left, r.top); val b = p(r.right, r.bottom)
        return com.verity.core.NormRect(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    private fun uprightOverlay(src: IntArray, w: Int, h: Int, rot: Int): Bitmap {
        val ow = if (rot == 90 || rot == 270) h else w
        val oh = if (rot == 90 || rot == 270) w else h
        val dst = IntArray(ow * oh)
        for (y in 0 until h) for (x in 0 until w) {
            val v = src[y * w + x]; if (v == 0) continue
            val (u, vv) = when (rot) { 90 -> (h - 1 - y) to x; 180 -> (w - 1 - x) to (h - 1 - y); 270 -> y to (w - 1 - x); else -> x to y }
            dst[vv * ow + u] = v
        }
        val b = overlayBmp?.takeIf { it.width == ow && it.height == oh } ?: Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { overlayBmp = it }
        b.setPixels(dst, 0, ow, 0, 0, ow, oh)
        return b
    }

}
