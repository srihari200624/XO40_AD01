package dev.onlookermonitor.app.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import dev.onlookermonitor.app.core.FaceBounds

object OnlookerPhotoCapturer {

    fun extractOnlookerCrop(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        candidateBounds: FaceBounds?,
    ): Bitmap? {
        val rawBitmap = runCatching { imageProxy.toBitmap() }.getOrNull() ?: return null
        return processBitmap(rawBitmap, rotationDegrees, candidateBounds)
    }

    fun processBitmap(
        rawBitmap: Bitmap,
        rotationDegrees: Int,
        candidateBounds: FaceBounds?,
    ): Bitmap {
        val rotatedBitmap = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(
                rawBitmap,
                0,
                0,
                rawBitmap.width,
                rawBitmap.height,
                matrix,
                true,
            )
        } else {
            rawBitmap
        }

        val finalBitmap = if (candidateBounds != null) {
            cropFace(rotatedBitmap, candidateBounds) ?: rotatedBitmap
        } else {
            rotatedBitmap
        }

        if (rotatedBitmap != rawBitmap && rotatedBitmap != finalBitmap) {
            rotatedBitmap.recycle()
        }
        if (rawBitmap != rotatedBitmap && rawBitmap != finalBitmap) {
            rawBitmap.recycle()
        }

        return finalBitmap
    }

    fun cropFace(bitmap: Bitmap, bounds: FaceBounds): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        val leftPx = (bounds.left * width).toInt()
        val topPx = (bounds.top * height).toInt()
        val rightPx = (bounds.right * width).toInt()
        val bottomPx = (bounds.bottom * height).toInt()

        val faceW = rightPx - leftPx
        val faceH = bottomPx - topPx
        if (faceW <= 0 || faceH <= 0) return null

        val padX = (faceW * 0.35f).toInt()
        val padY = (faceH * 0.35f).toInt()

        val cropLeft = (leftPx - padX).coerceIn(0, width - 1)
        val cropTop = (topPx - padY).coerceIn(0, height - 1)
        val cropRight = (rightPx + padX).coerceIn(cropLeft + 1, width)
        val cropBottom = (bottomPx + padY).coerceIn(cropTop + 1, height)

        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop

        if (cropW <= 0 || cropH <= 0) return null
        return Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropW, cropH)
    }
}
