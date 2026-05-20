package com.thesis.middleware.adaptation

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry of [OffloadableTask]s the application has declared
 * as offload candidates. Acts as the catalog the rest of the middleware
 * (MapeLoop, ExecutionProxy, telemetry) reads from when it needs to look
 * a task up by id or inspect what's offloadable.
 *
 * Today the split between local and offloadable work is explicit: callers
 * construct an [OffloadableTask] and call [register]. A future enhancement
 * could derive the split automatically via bytecode instrumentation or an
 * @Offloadable annotation processor, but that is out of scope here.
 */
class TaskPartitioner {

    private val registry = ConcurrentHashMap<String, OffloadableTask>()

    fun register(task: OffloadableTask) {
        registry[task.id] = task
    }

    fun unregister(taskId: String): OffloadableTask? = registry.remove(taskId)

    fun getOffloadableTask(taskId: String): OffloadableTask? = registry[taskId]

    fun isRegistered(taskId: String): Boolean = registry.containsKey(taskId)

    fun listOffloadable(): List<OffloadableTask> = registry.values.toList()

    fun listByComplexity(complexity: TaskComplexity): List<OffloadableTask> =
        registry.values.filter { it.complexity == complexity }

    fun size(): Int = registry.size

    fun clear() {
        registry.clear()
    }
}
