package com.example.network

import com.example.model.FilterType
import com.example.model.MusicalScale
import com.example.model.OscillatorType
import com.example.model.SynthParams
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class MusicGenRequest(
    @Json(name = "prompt") val prompt: String,
    @Json(name = "duration") val duration: Int = 6,
    @Json(name = "temperature") val temperature: Float = 1.0f
)

@JsonClass(generateAdapter = true)
data class EffectsDto(
    @Json(name = "distortion_mix") val distortionMix: Float? = null,
    @Json(name = "reverb_size") val reverbSize: Float? = null,
    @Json(name = "delay_time") val delayTime: Float? = null,
    @Json(name = "bitcrusher") val bitcrusher: Int? = null
)

@JsonClass(generateAdapter = true)
data class SynthParamsDto(
    @Json(name = "oscillator_type") val oscillatorType: String? = null,
    @Json(name = "attack") val attack: Float? = null,
    @Json(name = "decay") val decay: Float? = null,
    @Json(name = "sustain") val sustain: Float? = null,
    @Json(name = "release") val release: Float? = null,
    @Json(name = "filter_cutoff") val filterCutoff: Int? = null,
    @Json(name = "filter_type") val filterType: String? = null,
    @Json(name = "distortion_amount") val distortionAmount: Float? = null,
    @Json(name = "reverb_wet") val reverbWet: Float? = null,
    @Json(name = "scale") val scale: String? = null
) {
    fun toDomain(
        fallbackScale: String? = null,
        effects: EffectsDto? = null
    ): SynthParams {
        val resolvedScale = MusicalScale.fromBackend(scale ?: fallbackScale)
        return SynthParams(
            oscillatorType = OscillatorType.fromBackend(oscillatorType),
            attack = (attack ?: 0.008f).coerceIn(0.004f, 0.025f),
            decay = decay ?: 0.2f,
            sustain = sustain ?: 0.2f,
            release = release ?: 1.0f,
            filterCutoff = filterCutoff ?: 2000,
            filterType = FilterType.fromBackend(filterType),
            distortionAmount = effects?.distortionMix ?: distortionAmount ?: 0.0f,
            reverbWet = effects?.reverbSize ?: reverbWet ?: 0.2f,
            delayTime = effects?.delayTime ?: 0.0f,
            bitcrusher = effects?.bitcrusher ?: 0,
            scale = resolvedScale
        ).sanitized()
    }
}

@JsonClass(generateAdapter = true)
data class MusicGenResponse(
    @Json(name = "job_id") val jobId: String,
    @Json(name = "status") val status: String,
    @Json(name = "progress") val progress: Float? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "scale") val scale: String? = null,
    @Json(name = "synth_params") val synthParams: SynthParamsDto? = null,
    @Json(name = "effects") val effects: EffectsDto? = null,
    @Json(name = "backing_track_url") val backingTrackUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class AbortResponse(
    @Json(name = "status") val status: String,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class HealthResponse(
    @Json(name = "status") val status: String
)

interface FastApiMusicGenService {

    @GET("health")
    suspend fun checkHealth(): Response<HealthResponse>

    @POST("generate")
    suspend fun generateMusic(@Body request: MusicGenRequest): Response<MusicGenResponse>

    @GET("status/{job_id}")
    suspend fun getJobStatus(@Path("job_id") jobId: String): Response<MusicGenResponse>

    @POST("abort/{job_id}")
    suspend fun abortJob(@Path("job_id") jobId: String): Response<AbortResponse>
}

object FastApiClient {
    const val HARDCODED_BASE_URL = "https://tissue-feast-stabilize.ngrok-free.dev/"

    @Volatile
    private var currentBaseUrl = HARDCODED_BASE_URL

    @Volatile
    private var clientInstance: FastApiMusicGenService? = null

    @Volatile
    private var okHttpClientInstance: OkHttpClient? = null

    private val ngrokHeaderInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("ngrok-skip-browser-warning", "true")
            .header("Accept", "application/json, */*")
            .build()
        chain.proceed(req)
    }

    fun getBaseUrl(): String = currentBaseUrl

    fun setBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return currentBaseUrl
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val normalized = if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        if (normalized != currentBaseUrl) {
            currentBaseUrl = normalized
            clientInstance = null
        }
        return currentBaseUrl
    }

    private fun getOkHttpClient(): OkHttpClient {
        if (okHttpClientInstance == null) {
            okHttpClientInstance = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(ngrokHeaderInterceptor)
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
                .build()
        }
        return okHttpClientInstance!!
    }

    fun getService(baseUrl: String = currentBaseUrl): FastApiMusicGenService {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (clientInstance == null || currentBaseUrl != normalized) {
            currentBaseUrl = normalized
            val okHttpClient = getOkHttpClient()

            val retrofit = Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()

            clientInstance = retrofit.create(FastApiMusicGenService::class.java)
        }
        return clientInstance!!
    }

    /**
     * Fast preflight check so offline devices fall back to the local parametric synthesizer
     * without waiting for a 15s socket timeout, while allowing enough time (5s)
     * for TLS + ngrok tunnel handshake.
     */
    fun isServerReachable(baseUrl: String = currentBaseUrl): Boolean {
        return try {
            val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val quickClient = OkHttpClient.Builder()
                .connectTimeout(5000, TimeUnit.MILLISECONDS)
                .readTimeout(5000, TimeUnit.MILLISECONDS)
                .addInterceptor(ngrokHeaderInterceptor)
                .build()

            val request = okhttp3.Request.Builder()
                .url("${normalized}health")
                .get()
                .build()

            quickClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
