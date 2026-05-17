package com.borizon.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Memory-safe bitmap loading utilities.
 * Calculates inSampleSize to avoid OOM when decoding large images.
 */
object BitmapUtils {

    /**
     * Calculate an inSampleSize so the decoded bitmap is close to [reqWidth] x [reqHeight].
     * Follows the Google-recommended power-of-two algorithm.
     */
    fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Decode a file into a Bitmap sized to fit within [reqWidth] x [reqHeight].
     * Two-pass decode: first reads dimensions only, then decodes at reduced size.
     */
    fun decodeSampledBitmap(
        file: File,
        reqWidth: Int,
        reqHeight: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        bounds.inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        bounds.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.absolutePath, bounds)
    }

    /**
     * Decode an [InputStream] into a Bitmap sized to fit within [reqWidth] x [reqHeight].
     * The stream is read twice (bounds + decode), so make sure it supports mark/reset
     * or pass a fresh stream.
     */
    fun decodeSampledBitmap(
        inputStream: InputStream,
        reqWidth: Int,
        reqHeight: Int,
    ): Bitmap? {
        val bis = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
        bis.mark(Integer.MAX_VALUE)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(bis, null, bounds)
        bis.reset()

        bounds.inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        bounds.inJustDecodeBounds = false
        return BitmapFactory.decodeStream(bis, null, bounds)
    }

    /**
     * Create a scaled bitmap preserving aspect ratio, fitting within maxDim x maxDim.
     */
    fun scaleToFit(bitmap: Bitmap, maxDim: Int): Bitmap {
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val matrix = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Read EXIF orientation and rotate the bitmap accordingly.
     * Ensures images from camera/gallery appear correctly oriented.
     */
    fun rotateForExif(bitmap: Bitmap, file: File): Bitmap {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                val matrix = Matrix().apply { preScale(-1f, 1f) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) bitmap.recycle()
                return rotated
            }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                val matrix = Matrix().apply { preScale(1f, -1f) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) bitmap.recycle()
                return rotated
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                val matrix = Matrix().apply { postRotate(90f); preScale(-1f, 1f) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) bitmap.recycle()
                return rotated
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                val matrix = Matrix().apply { postRotate(270f); preScale(-1f, 1f) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) bitmap.recycle()
                return rotated
            }
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }
}
