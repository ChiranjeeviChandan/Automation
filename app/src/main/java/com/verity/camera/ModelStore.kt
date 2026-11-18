package com.verity.camera

import android.content.Context
import com.verity.inpaint.OnnxInpainter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The optional on-device neural eraser model (LaMa, ONNX, ~200 MB). Downloaded once on request;
 * without it the eraser still works using PatchMatch alone.
 */
class ModelStore(private val context: Context, private val settings: Settings) {
    private val file get() = File(context.filesDir, "inpaint.onnx")
    private var cached: OnnxInpainter? = null

    val installed: Boolean get() = file.exists() && file.length() > 1_000_000

    /** Blocking. [progress] receives 0..1 (or -1 when the server does not send a length). */
    fun download(progress: (Float) -> Unit) {
        val tmp = File(context.filesDir, "inpaint.onnx.part")
        val conn = (URL(settings.modelUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000
        }
        try {
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        progress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            // Validate before installing: a broken model must not break the eraser.
            OnnxInpainter(tmp.absolutePath, threads = 1).close()
            synchronized(this) { cached?.close(); cached = null; tmp.renameTo(file) }
        } finally {
            conn.disconnect(); tmp.delete()
        }
    }

    fun remove() = synchronized(this) { cached?.close(); cached = null; file.delete() }

    /** Null when not installed or unloadable. */
    fun inpainter(): OnnxInpainter? = synchronized(this) {
        if (!installed) return null
        cached ?: runCatching { OnnxInpainter(file.absolutePath, threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)) }
            .getOrNull().also { cached = it }
    }
}
