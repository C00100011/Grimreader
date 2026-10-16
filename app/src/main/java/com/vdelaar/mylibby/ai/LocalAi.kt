package com.vdelaar.mylibby.ai

import kotlinx.coroutines.flow.Flow

/** Whether the on-device model can be used. Anything but [AVAILABLE] and [DOWNLOADABLE] hides the feature. */
enum class AiStatus { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

sealed interface AiDownload {
    /** [fraction] is null when the system does not say how big the download is; [bytes] and [total] are 0 when unknown. */
    data class Progress(val fraction: Float?, val bytes: Long = 0, val total: Long = 0) : AiDownload
    data object Done : AiDownload
    data class Failed(val reason: String) : AiDownload
}

/** Why a request did not run; [retryable] means "later" (quota, busy, app in background), not "never". */
class AiException(message: String, val retryable: Boolean, cause: Throwable? = null) : Exception(message, cause)

/**
 * The text model that lives on the phone. The only real implementation is Gemini Nano through ML Kit GenAI
 * ([GeminiNanoAi]); nothing ever leaves the device. Kept as an interface so the chat can be tested without one.
 */
interface LocalAi {
    suspend fun status(): AiStatus

    fun download(): Flow<AiDownload>

    /** Input + output budget in tokens for one request. */
    suspend fun tokenLimit(): Int

    suspend fun countTokens(prompt: String): Int

    /** One answer; [system] carries the rules, [prompt] the material and the question. */
    suspend fun generate(system: String, prompt: String, maxOutputTokens: Int, onPartial: (String) -> Unit = {}): String
}
