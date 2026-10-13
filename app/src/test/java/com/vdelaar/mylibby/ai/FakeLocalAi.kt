package com.vdelaar.mylibby.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A stand-in for Gemini Nano in unit tests: no model, but the same plumbing. Summaries are the first sentences of the
 * passage; answers list which parts of the book were handed to the model, which makes spoiler handling visible.
 */
class FakeLocalAi(
    private var status: AiStatus = AiStatus.AVAILABLE,
    private val latencyMs: Long = 0,
    /** Like AICore often does: downloads in the background, reports no progress and no completion, and only the status changes. */
    private val silentDownload: Boolean = false,
    private val silentMs: Long = 8000,
) : LocalAi {
    val requests = mutableListOf<Pair<String, String>>()

    override suspend fun status() = status

    override fun download(): Flow<AiDownload> = flow {
        status = AiStatus.DOWNLOADING
        if (silentDownload) {
            emit(AiDownload.Progress(null))
            delay(silentMs)
            status = AiStatus.AVAILABLE
            return@flow
        }
        for (i in 1..4) { delay(400); emit(AiDownload.Progress(i / 4f)) }
        status = AiStatus.AVAILABLE
        emit(AiDownload.Done)
    }

    override suspend fun tokenLimit() = 4000

    override suspend fun countTokens(prompt: String) = prompt.length / 3

    override suspend fun generate(system: String, prompt: String, maxOutputTokens: Int, onPartial: (String) -> Unit): String {
        requests += system to prompt
        if (latencyMs > 0) delay(latencyMs)
        val out = if (system.startsWith("You write short notes")) {
            val passage = prompt.substringAfter("):\n").substringBefore("\n\nNotes:")
            passage.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ")
        } else {
            val question = prompt.substringAfter("Question: ").substringBefore("\nAnswer:").lowercase()
            val words = Regex("\\p{L}{4,}").findAll(question).map { it.value }.toSet()
            val notes = prompt.substringAfter("Notes on what has been read so far:\n", "").substringBefore("The passage the reader is reading").substringBefore("Conversation so far:").substringBefore("Question:")
            val blocks = notes.split("\n\n").map { it.trim() }.filter { it.startsWith("[Part") }
            val best = blocks.maxByOrNull { b -> words.count { it in b.lowercase() } }
            val parts = blocks.map { it.substringBefore(']').removePrefix("[") }
            "Simulated answer (no real model on this device). " +
                if (best == null) "Nothing in your notes yet." else
                    "Best match in your notes, ${best.substringBefore('\n')}: " + best.substringAfter('\n').split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ") +
                        "\n\n(Notes used: ${parts.size}. Nothing after your current page was given to the model.)"
        }
        onPartial(out)
        return out
    }
}
