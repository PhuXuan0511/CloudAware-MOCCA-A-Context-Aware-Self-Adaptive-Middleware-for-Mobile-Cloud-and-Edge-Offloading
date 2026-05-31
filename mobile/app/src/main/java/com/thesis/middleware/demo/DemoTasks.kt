package com.thesis.middleware.demo

import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.adaptation.TaskComplexity
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
 */
object DemoTasks {

    private const val MATRIX_N = 32

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

    private const val SEED = 0xC0FFEEL
}
