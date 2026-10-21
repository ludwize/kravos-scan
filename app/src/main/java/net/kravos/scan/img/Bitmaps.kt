package net.kravos.scan.img

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Glue between Android bitmaps/files and the platform-free [Img]. */
object Bitmaps {
    /** Upper bound for stored scans: 4800 px ≈ A4 at 400 dpi ("desktop scanner" quality) while staying within memory. */
    const val MAX_SIDE = 4800

    fun toImg(bm: Bitmap): Img {
        val px = IntArray(bm.width * bm.height)
        bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        return Img(bm.width, bm.height, px)
    }

    fun toBitmap(img: Img, alpha: Boolean = false): Bitmap {
        val bm = Bitmap.createBitmap(img.w, img.h, Bitmap.Config.ARGB_8888)
        bm.setPixels(img.px, 0, img.w, 0, 0, img.w, img.h)
        bm.setHasAlpha(alpha)
        return bm
    }

    /** Decodes a file honoring EXIF orientation, optionally subsampling to roughly [maxSide]. */
    fun decode(file: File, maxSide: Int = MAX_SIDE): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bm = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Ne mogu da pročitam sliku")
        val rot = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
        return if (rot == 0) bm else Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
    }

    fun decodeImg(file: File, maxSide: Int = MAX_SIDE): Img {
        val bm = decode(file, maxSide)
        val img = toImg(bm); bm.recycle()
        return if (maxOf(img.w, img.h) > maxSide) Ops.fitMax(img, maxSide) else img
    }

    fun boundsOf(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        val rot = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
        return if (rot == 90 || rot == 270) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    fun saveJpeg(img: Img, file: File, quality: Int) {
        val bm = toBitmap(img)
        FileOutputStream(file).use { bm.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bm.recycle()
    }

    fun savePng(bm: Bitmap, file: File) { FileOutputStream(file).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    fun copyUri(ctx: Context, uri: Uri, dest: File) {
        val ins: InputStream = ctx.contentResolver.openInputStream(uri) ?: error("Ne mogu da otvorim $uri")
        ins.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
    }
}
