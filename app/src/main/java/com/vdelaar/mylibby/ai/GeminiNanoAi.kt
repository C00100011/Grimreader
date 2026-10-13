package com.vdelaar.mylibby.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion

/** Gemini Nano through Android AICore (ML Kit GenAI Prompt API). Reports [AiStatus.UNAVAILABLE] on any phone that cannot run it. */
class GeminiNanoAi : LocalAi {
    private val model: GenerativeModel? by lazy { runCatching { Generation.getClient() }.getOrNull() }

    override suspend fun status(): AiStatus {
        val m = model ?: return AiStatus.UNAVAILABLE
        return try {
            when (m.checkStatus()) {
                FeatureStatus.AVAILABLE -> AiStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> AiStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> AiStatus.DOWNLOADING
                else -> AiStatus.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // no AICore, unlocked bootloader, unsupported hardware, config not fetched yet, ...
            AiStatus.UNAVAILABLE
        }
    }

    override fun download(): Flow<AiDownload> {
        val m = model ?: return flowOf(AiDownload.Failed("unavailable"))
        var total = 0L
        return flow {
            m.download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadStarted -> { total = s.bytesToDownload; emit(AiDownload.Progress(if (total > 0) 0f else null, 0, total)) }
                    is DownloadStatus.DownloadProgress -> emit(AiDownload.Progress(if (total > 0) (s.totalBytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else null, s.totalBytesDownloaded, total))
                    is DownloadStatus.DownloadCompleted -> emit(AiDownload.Done)
                    is DownloadStatus.DownloadFailed -> emit(AiDownload.Failed(s.e.message ?: "download failed"))
                }
            }
        }
    }

    override suspend fun tokenLimit(): Int = runCatching { model?.getTokenLimit() }.getOrNull()?.takeIf { it > 0 } ?: 4000

    override suspend fun countTokens(prompt: String): Int {
        val m = model ?: return prompt.length / 3
        return runCatching { m.countTokens(generateContentRequest(TextPart(prompt)) {}).totalTokens }.getOrElse { prompt.length / 3 }
    }

    override suspend fun generate(system: String, prompt: String, maxOutputTokens: Int, onPartial: (String) -> Unit): String {
        val m = model ?: throw AiException("unavailable", retryable = false)
        try {
            val request = generateContentRequest(SystemInstruction(system), TextPart(prompt)) {
                temperature = 0.2f
                this.maxOutputTokens = maxOutputTokens
            }
            val text = StringBuilder()
            m.generateContentStream(request).collect { r ->
                r.candidates.firstOrNull()?.text?.let { text.append(it); onPartial(text.toString()) }
            }
            return text.toString().trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenAiException) {
            val later = e.errorCode in RETRYABLE
            throw AiException(e.message ?: "AI error ${e.errorCode}", retryable = later, cause = e)
        }
    }

    private companion object {
        val RETRYABLE = setOf(
            GenAiException.ErrorCode.BUSY,
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
        )
    }
}
