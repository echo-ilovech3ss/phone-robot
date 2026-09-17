package dev.phonerobot.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Single-worker ownership; RGB preprocessing follows the pinned upstream MobileFaceNet wrapper. */
internal class FaceEmbedder(context: Context) : AutoCloseable {
    private val model = context.assets.open("mobile_face_net.tflite").use { input ->
        val bytes = input.readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    private val input = ByteBuffer.allocateDirect(SIDE * SIDE * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIDE * SIDE)
    private val output = arrayOf(FloatArray(192))
    private val scaled = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(scaled)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF(0f, 0f, SIDE.toFloat(), SIDE.toFloat())

    init {
        try {
            require(interpreter.getInputTensor(0).shape().contentEquals(intArrayOf(1, SIDE, SIDE, 3)))
            require(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32)
            require(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(1, 192)))
            require(interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32)
        } catch (error: Exception) {
            interpreter.close()
            scaled.recycle()
            throw IllegalArgumentException("MobileFaceNet tensor format is incompatible", error)
        }
    }

    fun embedding(bitmap: Bitmap, box: Rect): FloatArray? {
        val crop = Rect(box.left.coerceIn(0, bitmap.width), box.top.coerceIn(0, bitmap.height),
            box.right.coerceIn(0, bitmap.width), box.bottom.coerceIn(0, bitmap.height))
        if (crop.width() < 100 || crop.height() < 100) return null
        canvas.drawBitmap(bitmap, crop, destination, paint)
        scaled.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE)
        input.rewind()
        for (pixel in pixels) {
            input.putFloat(((pixel shr 16 and 255) - 128f) / 128f)
            input.putFloat(((pixel shr 8 and 255) - 128f) / 128f)
            input.putFloat(((pixel and 255) - 128f) / 128f)
        }
        input.rewind()
        interpreter.run(input, output)
        return output[0].copyOf()
    }

    override fun close() {
        interpreter.close()
        scaled.recycle()
    }

    private companion object { const val SIDE = 112 }
}
