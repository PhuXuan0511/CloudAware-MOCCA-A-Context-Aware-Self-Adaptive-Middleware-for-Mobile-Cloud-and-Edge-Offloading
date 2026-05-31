package com.thesis.middleware.communication

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.annotations.SerializedName
import com.thesis.middleware.adaptation.OffloadableTask
import com.thesis.middleware.context.ContextManager
import com.thesis.middleware.context.ContextSnapshot
import com.thesis.middleware.context.NetworkType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Submits [OffloadableTask] instances to the edge or cloud `/offload` endpoint.
 *
 * Wire format mirrors the backend `OffloadingRequest` / `OffloadingResponse`:
 *  - `bytes` fields (`input_payload`, `result_payload`) are base64 strings,
 *    which is Pydantic v2's default JSON representation for `bytes`. Custom
 *    Gson adapters handle the encode/decode transparently.
 *  - Snake-case JSON keys are mapped via [SerializedName] so the Kotlin
 *    data classes can stay camelCase.
 *  - The current [ContextSnapshot] is attached so the server can log it.
 *
 * The target URL is chosen per call via Retrofit's [@Url] — one shared
 * [api] is built against a placeholder base URL.
 *
 * Failure modes (HTTP error, deserialization error, network exception,
 * `success=false` in the response body) bubble up as exceptions so
 * `ExecutionProxy` can fall back to local execution.
 */
class OffloadingClient(
    private val connectionManager: ConnectionManager,
    private val contextManager: ContextManager,
    securityManager: SecurityManager? = null,
    httpClient: OkHttpClient = if (securityManager != null) defaultClient(securityManager) else defaultClientNoAuth(),
) {

    private val gson = GsonBuilder()
        .registerTypeAdapter(ByteArray::class.java, BASE64_SERIALIZER)
        .registerTypeAdapter(ByteArray::class.java, BASE64_DESERIALIZER)
        .create()

    private val api: OffloadApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(OffloadApi::class.java)

    suspend fun submitToEdge(task: OffloadableTask): ByteArray = submit(task, Tier.EDGE)
    suspend fun submitToCloud(task: OffloadableTask): ByteArray = submit(task, Tier.CLOUD)

    private suspend fun submit(task: OffloadableTask, tier: Tier): ByteArray {
        val (baseUrl, reachable) = when (tier) {
            Tier.EDGE -> connectionManager.edgeEndpoint to connectionManager.isEdgeReachable()
            Tier.CLOUD -> connectionManager.cloudEndpoint to connectionManager.isCloudReachable()
        }
        if (!reachable) {
            // Fast-fail without paying the OkHttp connect timeout — caller (ExecutionProxy)
            // catches and falls back to local execution.
            throw java.io.IOException("$tier endpoint not reachable: $baseUrl")
        }

        val snapshot = contextManager.getLatestFeatures().rawSnapshot
        val response = api.offload("$baseUrl/api/v1/offload", OffloadingRequestDto.from(task, snapshot))
        if (!response.success) {
            throw OffloadingException(
                "remote task ${response.taskId} failed at ${response.executedAt}: " +
                    (response.errorMessage ?: "unknown error")
            )
        }
        return response.resultPayload
    }

    private enum class Tier { EDGE, CLOUD }

    private interface OffloadApi {
        @POST
        suspend fun offload(@Url url: String, @Body body: OffloadingRequestDto): OffloadingResponseDto
    }

    private data class OffloadingRequestDto(
        @SerializedName("task_id") val taskId: String,
        @SerializedName("task_name") val taskName: String,
        @SerializedName("input_size_bytes") val inputSizeBytes: Long,
        @SerializedName("complexity") val complexity: String,
        @SerializedName("input_payload") val inputPayload: ByteArray,
        @SerializedName("context") val context: ContextSnapshotDto
    ) {
        companion object {
            fun from(task: OffloadableTask, snapshot: ContextSnapshot) = OffloadingRequestDto(
                taskId = task.id,
                taskName = task.name,
                inputSizeBytes = task.inputSizeBytes,
                complexity = task.complexity.name,
                inputPayload = task.inputPayload,
                context = ContextSnapshotDto.from(snapshot)
            )
        }
    }

    private data class OffloadingResponseDto(
        @SerializedName("task_id") val taskId: String,
        @SerializedName("success") val success: Boolean,
        @SerializedName("result_payload") val resultPayload: ByteArray,
        @SerializedName("execution_time_ms") val executionTimeMs: Float,
        @SerializedName("executed_at") val executedAt: String,
        @SerializedName("error_message") val errorMessage: String? = null
    )

    private data class ContextSnapshotDto(
        @SerializedName("network") val network: NetworkDto,
        @SerializedName("cpu") val cpu: CpuDto,
        @SerializedName("battery") val battery: BatteryDto,
        @SerializedName("location") val location: LocationDto,
        @SerializedName("mobility") val mobility: MobilityDto,
        @SerializedName("timestamp") val timestamp: Long
    ) {
        companion object {
            fun from(s: ContextSnapshot) = ContextSnapshotDto(
                network = NetworkDto(
                    type = s.network.type.toWire(),
                    rttMs = s.network.rttMs,
                    bandwidthMbps = s.network.bandwidthMbps,
                    signalStrength = s.network.signalStrength
                ),
                cpu = CpuDto(
                    usagePercent = s.cpu.usagePercent,
                    availableCores = s.cpu.availableCores,
                    frequencyMhz = s.cpu.frequencyMhz
                ),
                battery = BatteryDto(
                    levelPercent = s.battery.levelPercent,
                    isCharging = s.battery.isCharging,
                    temperatureCelsius = s.battery.temperatureCelsius
                ),
                location = LocationDto(
                    latitude = s.location.latitude,
                    longitude = s.location.longitude,
                    accuracy = s.location.accuracy
                ),
                mobility = MobilityDto(
                    linearAccelerationMps2 = s.mobility.linearAccelerationMps2,
                    movementState = s.mobility.movementState.name
                ),
                timestamp = s.timestamp
            )
        }
    }

    private data class NetworkDto(
        @SerializedName("type") val type: String,
        @SerializedName("rtt_ms") val rttMs: Float,
        @SerializedName("bandwidth_mbps") val bandwidthMbps: Float,
        @SerializedName("signal_strength") val signalStrength: Int
    )

    private data class CpuDto(
        @SerializedName("usage_percent") val usagePercent: Float,
        @SerializedName("available_cores") val availableCores: Int,
        @SerializedName("frequency_mhz") val frequencyMhz: Int
    )

    private data class BatteryDto(
        @SerializedName("level_percent") val levelPercent: Int,
        @SerializedName("is_charging") val isCharging: Boolean,
        @SerializedName("temperature_celsius") val temperatureCelsius: Float
    )

    private data class LocationDto(
        @SerializedName("latitude") val latitude: Double,
        @SerializedName("longitude") val longitude: Double,
        @SerializedName("accuracy") val accuracy: Float
    )

    private data class MobilityDto(
        @SerializedName("linear_acceleration_mps2") val linearAccelerationMps2: Float,
        @SerializedName("movement_state") val movementState: String
    )

    companion object {
        private const val PLACEHOLDER_BASE_URL = "http://localhost/"

        private val BASE64_SERIALIZER = JsonSerializer<ByteArray> { src, _, _ ->
            JsonPrimitive(Base64.getEncoder().encodeToString(src))
        }
        private val BASE64_DESERIALIZER = JsonDeserializer<ByteArray> { json, _, _ ->
            Base64.getDecoder().decode(json.asString)
        }

        fun defaultClient(securityManager: SecurityManager): OkHttpClient {
            val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            return OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", securityManager.getAuthHeader())
                        .build()
                    chain.proceed(request)
                }
                .addInterceptor(logging)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        /** Dev / demo client without auth — pair with [securityManager] = null. */
        fun defaultClientNoAuth(): OkHttpClient {
            val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            return OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private fun NetworkType.toWire(): String = when (this) {
            NetworkType.WIFI -> "WIFI"
            NetworkType.LTE -> "LTE"
            NetworkType.FIVE_G -> "5G"
            NetworkType.NONE -> "NONE"
        }
    }
}

class OffloadingException(message: String) : RuntimeException(message)
