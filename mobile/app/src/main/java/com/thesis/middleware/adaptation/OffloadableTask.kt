package com.thesis.middleware.adaptation

/**
 * Represents a unit of computation that can be executed locally or offloaded.
 * The [execute] function contains the actual work; [inputSizeBytes] and
 * [complexity] help the estimators gauge cost.
 *
 * `equals`/`hashCode` are written by hand because the auto-generated versions
 * for a data class with a `ByteArray` field compare arrays by reference, not
 * by content — a foot-gun the rest of the system would not expect.
 */
data class OffloadableTask(
    val id: String,
    val name: String,
    val inputSizeBytes: Long,
    val complexity: TaskComplexity,
    val inputPayload: ByteArray,
    val execute: () -> ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OffloadableTask) return false
        return id == other.id &&
            name == other.name &&
            inputSizeBytes == other.inputSizeBytes &&
            complexity == other.complexity &&
            inputPayload.contentEquals(other.inputPayload)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + inputSizeBytes.hashCode()
        result = 31 * result + complexity.hashCode()
        result = 31 * result + inputPayload.contentHashCode()
        return result
    }
}

enum class TaskComplexity { LIGHT, MEDIUM, HEAVY }
