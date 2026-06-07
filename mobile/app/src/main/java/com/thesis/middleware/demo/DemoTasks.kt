package com.thesis.middleware.demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.adaptation.TaskComplexity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random

/**
 * Sample [OffloadableTask]s used by [com.thesis.middleware.MainActivity]'s
 * demo buttons. Each task has a `task_name` that matches a handler in the
 * backend `shared/tasks/registry.py`, so the same byte payload runs either
 * locally (via [OffloadableTask.execute]) or remotely (via the same handler
 * on edge/cloud) — making the local-vs-remote comparison fair.
 *
 * Five tasks span LIGHT → HEAVY and the full payload-size spectrum so the
 * MAPE policy can be observed making genuinely different decisions:
 *
 *   echo               LIGHT     11 B            network-only baseline
 *   sha256             LIGHT     1 KB            tiny CPU work
 *   image-grayscale    MEDIUM   ~30 KB           image filter
 *   matrix-multiply    HEAVY    ~4 KB            pure compute (32x32 floats)
 *   video-frame-edges  HEAVY    ~500 KB - 1 MB   per-frame Canny edge detection
 */
object DemoTasks {

    private const val MATRIX_N = 32
    private const val SYNTHETIC_IMAGE_SIDE = 512
    private const val VIDEO_ASSET_NAME = "sample_video.mp4"
    private const val SEED = 0xC0FFEEL

    fun echo(): OffloadableTask {
        val payload = "hello mocca".toByteArray()
        return OffloadableTask(
            id = "echo-${System.currentTimeMillis()}",
            name = "echo",
            inputSizeBytes = payload.size.toLong(),
            complexity = TaskComplexity.LIGHT,
            inputPayload = payload,
            execute = { payload },
        )
    }

    fun sha256(): OffloadableTask {
        val payload = ByteArray(1024).also { Random(SEED).nextBytes(it) }
        return OffloadableTask(
            id = "sha256-${System.currentTimeMillis()}",
            name = "sha256",
            inputSizeBytes = payload.size.toLong(),
            complexity = TaskComplexity.LIGHT,
            inputPayload = payload,
            execute = { MessageDigest.getInstance("SHA-256").digest(payload) },
        )
    }

    fun imageGrayscale(): OffloadableTask {
        val payload = generateSyntheticJpeg(SYNTHETIC_IMAGE_SIDE)
        return OffloadableTask(
            id = "img-gray-${System.currentTimeMillis()}",
            name = "image-grayscale",
            inputSizeBytes = payload.size.toLong(),
            complexity = TaskComplexity.MEDIUM,
            inputPayload = payload,
            execute = { localGrayscaleJpeg(payload) },
        )
    }

    fun matrixMultiply(n: Int = MATRIX_N): OffloadableTask {
        val payload = randomMatrixBytes(n)
        return OffloadableTask(
            id = "matrix-${System.currentTimeMillis()}",
            name = "matrix-multiply",
            inputSizeBytes = payload.size.toLong(),
            complexity = TaskComplexity.HEAVY,
            inputPayload = payload,
            execute = { naiveMatrixSquare(payload, n) },
        )
    }

    /**
     * Reads the MP4 bytes bundled at `mobile/app/src/main/assets/sample_video.mp4`.
     * Throws a friendly error if the asset is missing — the user must drop a
     * short clip there for this demo to work end-to-end.
     */
    fun videoFrameEdges(context: Context): OffloadableTask {
        val payload = try {
            context.assets.open(VIDEO_ASSET_NAME).use { it.readBytes() }
        } catch (e: IOException) {
            throw IllegalStateException(
                "Drop a short MP4 clip at app/src/main/assets/$VIDEO_ASSET_NAME to enable this demo.",
                e
            )
        }
        return OffloadableTask(
            id = "video-${System.currentTimeMillis()}",
            name = "video-frame-edges",
            inputSizeBytes = payload.size.toLong(),
            complexity = TaskComplexity.HEAVY,
            inputPayload = payload,
            execute = { extractFirstFrameJpeg(payload) },
        )
    }

    // ── Synthetic-image generation (no asset needed) ─────────────────────

    private fun generateSyntheticJpeg(side: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)

        val rand = Random(SEED)
        repeat(50) {
            paint.color = Color.argb(
                255,
                rand.nextInt(256),
                rand.nextInt(256),
                rand.nextInt(256),
            )
            canvas.drawCircle(
                rand.nextInt(side).toFloat(),
                rand.nextInt(side).toFloat(),
                rand.nextInt(80).toFloat() + 20f,
                paint,
            )
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun localGrayscaleJpeg(input: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(input, 0, input.size)
            ?: throw IllegalArgumentException("invalid JPEG payload")
        val gray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        val out = ByteArrayOutputStream()
        gray.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        gray.recycle()
        return out.toByteArray()
    }

    // ── Video local fallback ─────────────────────────────────────────────
    //
    // We don't ship OpenCV on Android, so a true local Canny would be 200+
    // lines of code. Instead, on offload failure we degrade to "extract the
    // first frame as JPEG" — still gives the user *something* visible.

    private fun extractFirstFrameJpeg(videoBytes: ByteArray): ByteArray {
        val temp = File.createTempFile("mocca-video-", ".mp4")
        try {
            temp.writeBytes(videoBytes)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(temp.absolutePath)
                val frame = retriever.getFrameAtTime(0)
                    ?: throw IllegalStateException("could not decode any frame")
                val out = ByteArrayOutputStream()
                frame.compress(Bitmap.CompressFormat.JPEG, 80, out)
                frame.recycle()
                return out.toByteArray()
            } finally {
                retriever.release()
            }
        } finally {
            temp.delete()
        }
    }

    // ── Matrix helpers ───────────────────────────────────────────────────

    private fun randomMatrixBytes(n: Int): ByteArray {
        val buf = ByteBuffer.allocate(n * n * 4).order(ByteOrder.LITTLE_ENDIAN)
        val rand = Random(SEED)
        repeat(n * n) { buf.putFloat(rand.nextFloat()) }
        return buf.array()
    }

    private fun naiveMatrixSquare(payload: ByteArray, n: Int): ByteArray {
        val src = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val mat = FloatArray(n * n) { src.float }
        val out = FloatArray(n * n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                var sum = 0f
                for (k in 0 until n) sum += mat[i * n + k] * mat[k * n + j]
                out[i * n + j] = sum
            }
        }
        val dst = ByteBuffer.allocate(n * n * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.forEach { dst.putFloat(it) }
        return dst.array()
    }
}
