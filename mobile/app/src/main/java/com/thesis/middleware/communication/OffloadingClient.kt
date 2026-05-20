package com.thesis.middleware.communication

import com.thesis.middleware.adaptation.OffloadableTask

/**
 * HTTP client that serializes tasks and submits them to edge or cloud servers.
 * Uses Retrofit under the hood; responses are deserialized and returned as ByteArray.
 * TODO: Add retry logic with exponential backoff via Retrofit + OkHttp interceptors.
 */
class OffloadingClient(private val connectionManager: ConnectionManager) {

    suspend fun submitToEdge(task: OffloadableTask): ByteArray {
        val endpoint = connectionManager.edgeEndpoint
        // TODO: serialize task, POST to $endpoint/offload, return result bytes
        return ByteArray(0)
    }

    suspend fun submitToCloud(task: OffloadableTask): ByteArray {
        val endpoint = connectionManager.cloudEndpoint
        // TODO: serialize task, POST to $endpoint/offload, return result bytes
        return ByteArray(0)
    }
}
