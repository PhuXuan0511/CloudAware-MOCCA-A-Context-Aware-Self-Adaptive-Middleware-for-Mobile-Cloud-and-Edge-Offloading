package com.thesis.middleware.communication

/**
 * Manages active connections to edge and cloud endpoints.
 * Selects the best available interface (Wi-Fi vs. 5G) and handles reconnection.
 * TODO: Inject Android ConnectivityManager to monitor network state changes.
 */
class ConnectionManager {

    var edgeEndpoint: String = "http://edge-server:8001"
    var cloudEndpoint: String = "http://cloud-server:8002"

    fun isEdgeReachable(): Boolean {
        // TODO: ping edge endpoint or check cached RTT
        return true
    }

    fun isCloudReachable(): Boolean {
        // TODO: check network availability + cloud health endpoint
        return true
    }

    fun getBestEndpoint(): String {
        // TODO: compare edge vs cloud latency and return the better option
        return edgeEndpoint
    }
}
