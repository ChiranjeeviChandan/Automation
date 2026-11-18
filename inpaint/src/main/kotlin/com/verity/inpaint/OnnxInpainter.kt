package com.verity.inpaint

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.verity.core.ArgbImage
import com.verity.core.NeuralFill
import java.nio.FloatBuffer

/**
 * On-device neural inpainting with a LaMa-style ONNX model (Suvorov et al., "Resolution-robust Large
 * Mask Inpainting with Fourier Convolutions"). Expected signature, which public LaMa exports use:
 *
 *   inputs : image [1,3,S,S] float RGB in 0..1,  mask [1,1,S,S] float (1 = fill)
 *   output : [1,3,S,S] float RGB in 0..1 or 0..255 (detected at runtime)
 *
 * Inputs are told apart by channel count, not by name, and S is read from the model, so other
 * exports with the same layout (e.g. MI-GAN) also work.
 *
 * Runs entirely on the phone; nothing is uploaded.
 */
class OnnxInpainter(modelPath: String, threads: Int = 4) : NeuralFill, AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelPath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) },
    )
    private val imageInput: String
    private val maskInput: String
    /** Square input size the model expects. */
    val inputSize: Int

    init {
        val infos = session.inputInfo.mapValues { (it.value.info as TensorInfo).shape }
        require(infos.size == 2) { "expected 2 inputs (image, mask), model has ${infos.keys}" }
        imageInput = infos.entries.firstOrNull { it.value.size == 4 && it.value[1] == 3L }?.key
            ?: error("no [1,3,S,S] image input in ${infos.mapValues { it.value.toList() }}")
        maskInput = infos.keys.first { it != imageInput }
        val s = infos.getValue(imageInput)[2]
        inputSize = if (s > 0) s.toInt() else 512 // dynamic axes: LaMa is trained at 512
    }

    override fun fill(crop: ArgbImage, hole: BooleanArray): ArgbImage {
        val s = inputSize
        require(crop.width == s && crop.height == s) { "crop must be ${s}x$s" }
        val plane = s * s
        val img = FloatBuffer.allocate(3 * plane)
        val msk = FloatBuffer.allocate(plane)
        for (i in 0 until plane) {
            val m = if (hole[i]) 1f else 0f
            val p = crop.pixels[i]
            val keep = 1f - m // models are trained with the hole zeroed out
            img.put(i, ((p shr 16) and 0xFF) / 255f * keep)
            img.put(plane + i, ((p shr 8) and 0xFF) / 255f * keep)
            img.put(2 * plane + i, (p and 0xFF) / 255f * keep)
            msk.put(i, m)
        }
        val shape3 = longArrayOf(1, 3, s.toLong(), s.toLong())
        val shape1 = longArrayOf(1, 1, s.toLong(), s.toLong())
        OnnxTensor.createTensor(env, img, shape3).use { ti ->
            OnnxTensor.createTensor(env, msk, shape1).use { tm ->
                session.run(mapOf(imageInput to ti, maskInput to tm)).use { result ->
                    val out = (result.get(0) as OnnxTensor)
                    val shape = out.info.shape
                    val fb = out.floatBuffer
                    val nhwc = shape.size == 4 && shape[3] == 3L
                    var mx = 0f
                    for (i in 0 until fb.limit()) if (fb.get(i) > mx) mx = fb.get(i)
                    val scale = if (mx > 1.5f) 1f else 255f
                    val res = ArgbImage(s, s, crop.pixels.copyOf())
                    for (i in 0 until plane) {
                        if (!hole[i]) continue
                        val r = if (nhwc) fb.get(3 * i) else fb.get(i)
                        val g = if (nhwc) fb.get(3 * i + 1) else fb.get(plane + i)
                        val b = if (nhwc) fb.get(3 * i + 2) else fb.get(2 * plane + i)
                        res.pixels[i] = -0x1000000 or (c8(r * scale) shl 16) or (c8(g * scale) shl 8) or c8(b * scale)
                    }
                    return res
                }
            }
        }
    }

    private fun c8(v: Float): Int { val i = (v + 0.5f).toInt(); return if (i < 0) 0 else if (i > 255) 255 else i }

    override fun close() { session.close() }
}
