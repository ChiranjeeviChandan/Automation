package com.verity.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.FaceDetector
import android.net.Uri
import android.provider.MediaStore
import com.verity.core.ArgbImage
import com.verity.core.NormRect
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object Bitmaps {

    /** Decode with EXIF orientation applied, into a mutable software bitmap (pixels readable). */
    fun decode(context: Context, uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, false) else it }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun toArgb(b: Bitmap): ArgbImage {
        val img = ArgbImage(b.width, b.height)
        b.getPixels(img.pixels, 0, b.width, 0, 0, b.width, b.height)
        return img
    }

    fun fromArgb(img: ArgbImage): Bitmap =
        Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888)

    fun scaled(b: Bitmap, longEdge: Int): Bitmap {
        val s = longEdge.toFloat() / max(b.width, b.height)
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true)
    }

    fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /**
     * On-device face finding with the platform detector (no extra dependency, nothing uploaded).
     * Used for scene classification and profile-picture framing; never to modify faces.
     */
    fun detectFaces(b: Bitmap, maxFaces: Int = 12): List<NormRect> {
        val small = scaled(b, 800)
        val w = small.width and 1.inv() // detector requires an even width
        val rgb565 = Bitmap.createBitmap(w, small.height, Bitmap.Config.RGB_565)
        android.graphics.Canvas(rgb565).drawBitmap(small, 0f, 0f, null)
        val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
        val n = FaceDetector(w, small.height, maxFaces).findFaces(rgb565, faces)
        val mid = android.graphics.PointF()
        return (0 until n).mapNotNull { i ->
            val f = faces[i] ?: return@mapNotNull null
            if (f.confidence() < 0.4f) return@mapNotNull null
            f.getMidPoint(mid)
            val e = f.eyesDistance()
            NormRect(
                ((mid.x - e * 1.1f) / w).coerceIn(0f, 1f), ((mid.y - e * 1.2f) / small.height).coerceIn(0f, 1f),
                ((mid.x + e * 1.1f) / w).coerceIn(0f, 1f), ((mid.y + e * 1.6f) / small.height).coerceIn(0f, 1f),
            )
        }
    }

    fun union(rects: List<NormRect>): NormRect? = rects.takeIf { it.isNotEmpty() }?.let { r ->
        NormRect(r.minOf { it.left }, r.minOf { it.top }, r.maxOf { it.right }, r.maxOf { it.bottom })
    }

    /** Save as a high-quality JPEG into Pictures/Verity. Returns the new item's Uri. */
    fun saveToGallery(context: Context, b: Bitmap, name: String, description: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Verity")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            // Authenticity: edited photos say so. The original file is never overwritten.
            put(MediaStore.Images.Media.DESCRIPTION, description)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
