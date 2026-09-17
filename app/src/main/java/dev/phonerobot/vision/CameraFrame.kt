package dev.phonerobot.vision

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/** Converts all three YUV planes independently; neither row nor chroma pixel stride is assumed. */
internal class CameraFrame {
    private var pixels = IntArray(0)

    fun upright(image: ImageProxy): Bitmap {
        require(image.format == ImageFormat.YUV_420_888) { "Expected YUV camera output" }
        val width = image.width
        val height = image.height
        if (pixels.size != width * height) pixels = IntArray(width * height)
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yStart = yBuffer.position()
        val uStart = uBuffer.position()
        val vStart = vBuffer.position()
        for (row in 0 until height) {
            val yRow = yStart + row * yPlane.rowStride
            val uRow = uStart + (row / 2) * uPlane.rowStride
            val vRow = vStart + (row / 2) * vPlane.rowStride
            for (column in 0 until width) {
                val y = (yBuffer.get(yRow + column * yPlane.pixelStride).toInt() and 255) - 16
                val u = (uBuffer.get(uRow + (column / 2) * uPlane.pixelStride).toInt() and 255) - 128
                val v = (vBuffer.get(vRow + (column / 2) * vPlane.pixelStride).toInt() and 255) - 128
                val luminance = 298 * y.coerceAtLeast(0)
                val red = ((luminance + 409 * v + 128) shr 8).coerceIn(0, 255)
                val green = ((luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val blue = ((luminance + 516 * u + 128) shr 8).coerceIn(0, 255)
                pixels[row * width + column] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        val raw = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val crop = image.cropRect
        val rotation = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        val upright = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height(), rotation, true)
        if (upright !== raw) raw.recycle()
        return upright
    }
}
