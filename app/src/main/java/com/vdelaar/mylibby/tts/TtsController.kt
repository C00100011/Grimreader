package com.vdelaar.mylibby.tts

import android.content.Context
import com.vdelaar.mylibby.core.str
import com.vdelaar.mylibby.R
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.vdelaar.mylibby.core.datastore.SettingsRepository
import com.vdelaar.mylibby.tts.neural.NeuralSpeaker
import com.vdelaar.mylibby.tts.neural.NeuralVoice
import com.vdelaar.mylibby.tts.neural.NeuralVoiceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class TtsSegment(val mark: String, val text: String)

data class VoiceOption(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val requiresNetwork: Boolean,
    val installed: Boolean = true,
) {
    /** Google voice names look like "nl-nl-x-tfb-local"; the 3-letter code identifies the speaker. */
    private val speaker: String
        get() = Regex("-x-([a-z0-9]+)").find(name)?.groupValues?.get(1)?.uppercase()
            ?: str(R.string.voice_standard)

    val label: String
        get() {
            val region = locale.displayCountry.ifBlank { locale.country }
            val q = when {
                quality >= Voice.QUALITY_VERY_HIGH -> str(R.string.quality_very_high)
                quality >= Voice.QUALITY_HIGH -> str(R.string.quality_high)
                quality >= Voice.QUALITY_NORMAL -> str(R.string.quality_normal)
                else -> str(R.string.quality_low)
            }
            val extra = when {
                !installed -> str(R.string.voice_download)
                requiresNetwork -> str(R.string.voice_online)
                else -> ""
            }
            return str(R.string.voice_label, region, speaker, q, extra)
        }
}

enum class TtsStatus { IDLE, PLAYING, PAUSED }

data class TtsState(
    val status: TtsStatus = TtsStatus.IDLE,
    val ready: Boolean = false,
    val bookTitle: String = "",
    val chapter: String = "",
    val sleepTimerEndsAt: Long? = null,
    val error: String? = null,
)

/** Implemented by the reader, which owns the text source (the WebView). */
interface TtsSource {
    fun onSegmentStarted(mark: String)
    fun onBlockFinished()
    fun onPauseRequested()
    fun onResumeRequested()
    fun onNextRequested()
    fun onPreviousRequested()
    fun onStopRequested()
}

/**
 * Speaks the text blocks the reader sends, using Android's on-device TTS. Designed around a
 * [TtsSource] so a cloud voice engine can be swapped in later without touching the reader.
 */
class TtsController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var blockSeq = 0
    private var lastIndex = -1
    private var sleepJob: Job? = null
    var source: TtsSource? = null

    /** The downloadable natural voice (Supertonic 3), used instead of the system voice when selected. */
    val neuralStore = NeuralVoiceStore(context, scope)
    private val neural = NeuralSpeaker(neuralStore, scope)

    private fun neuralVoiceFor(lang: String): NeuralVoice? {
        if (!neuralStore.installed) return null
        val dutch = lang.lowercase().startsWith("nl")
        return NeuralVoice.fromKey(if (dutch) settings.tts.value.dutchVoice else settings.tts.value.englishVoice)
    }

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val listeners = mutableSetOf<() -> Unit>()

    fun addStateListener(l: () -> Unit) { listeners += l }
    fun removeStateListener(l: () -> Unit) { listeners -= l }

    private fun setState(f: (TtsState) -> TtsState) {
        _state.update(f)
        main.post { listeners.toList().forEach { it() } }
    }

    fun ensureEngine(onReady: (() -> Unit)? = null) {
        if (tts != null) {
            if (_state.value.ready) onReady?.invoke()
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(progressListener)
                loadVoices()
                setState { it.copy(ready = true, error = null) }
                main.post { onReady?.invoke() }
            } else {
                setState { it.copy(ready = false, error = str(R.string.tts_unavailable)) }
            }
        }
    }

    private fun loadVoices() {
        val all = runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
        _voices.value = all
            .filter { it.locale.language in setOf("nl", "en") }
            .map {
                VoiceOption(
                    it.name, it.locale, it.quality, it.isNetworkConnectionRequired,
                    installed = !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
                )
            }
            .distinctBy { it.name }
            .sortedWith(
                compareBy<VoiceOption> { it.locale.language }
                    .thenByDescending { it.installed }
                    .thenBy { it.requiresNetwork }
                    .thenBy { it.locale.country != (if (it.locale.language == "nl") "NL" else "GB") }
                    .thenByDescending { it.quality }
                    .thenBy { it.name }
            )
    }

    fun clearError() = setState { it.copy(error = null) }

    private fun localeFor(lang: String): Locale =
        if (lang.lowercase().startsWith("nl")) Locale.forLanguageTag("nl-NL") else Locale.forLanguageTag(lang.ifBlank { "en" }.substringBefore('-').let { if (it == "en") "en-GB" else it })

    fun voicesFor(language: String): List<VoiceOption> = _voices.value.filter { it.locale.language == language }

    private fun pickVoice(lang: String): Voice? {
        val engine = tts ?: return null
        val language = if (lang.lowercase().startsWith("nl")) "nl" else "en"
        val preferred = if (language == "nl") settings.tts.value.dutchVoice else settings.tts.value.englishVoice
        val voices = engine.voices.orEmpty()
        return voices.firstOrNull { it.name == preferred }
            ?: voices.filter { it.locale.language == language && !it.isNetworkConnectionRequired && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareByDescending<Voice> { if (language == "nl") it.locale.country == "NL" else it.locale.country == "GB" || it.locale.country == "US" }.thenByDescending { it.quality })
                .firstOrNull()
            ?: voices.firstOrNull { it.locale.language == language }
    }

    fun startSession(bookTitle: String, chapter: String) {
        setState { it.copy(status = TtsStatus.PLAYING, bookTitle = bookTitle, chapter = chapter) }
        if (neuralStore.installed) neural.warmUp()
        runCatching { context.startService(Intent(context, TtsService::class.java)) }
    }

    fun updateChapter(chapter: String) = setState { it.copy(chapter = chapter) }

    /** Speak one block of segments. Marks are reported back through [TtsSource.onSegmentStarted]. */
    fun speakBlock(segments: List<TtsSegment>, lang: String) {
        val engine = tts ?: return
        val seq = ++blockSeq
        engine.stop()
        val neuralVoice = neuralVoiceFor(lang)
        if (neuralVoice != null) {
            speakNeural(segments, neuralVoice, lang)
            return
        }
        neuralGen++
        neural.stop()
        speakSystem(engine, seq, segments, lang)
    }

    private var neuralGen = 0
    private var neuralBlock: List<TtsSegment> = emptyList()

    private fun speakNeural(segments: List<TtsSegment>, voice: NeuralVoice, lang: String) {
        val parts = segments.filter { it.text.isNotBlank() }
        if (parts.isEmpty()) {
            val gen = neuralGen
            main.post { if (gen == neuralGen) source?.onBlockFinished() }
            return
        }
        val language = if (lang.lowercase().startsWith("nl")) "nl" else "en"
        val speed = settings.tts.value.speechRate.coerceIn(0.5f, 2.0f)
        neuralBlock = parts
        setState { it.copy(status = TtsStatus.PLAYING, error = null) }
        // The previous block is usually still playing its last sentence: join it so there is no pause.
        if (neural.append(parts, voice, language, speed)) return
        val gen = ++neuralGen
        neural.speak(
            segments = parts,
            voice = voice,
            lang = language,
            speed = speed,
            steps = NEURAL_STEPS,
            onSegmentStarted = { mark -> main.post { if (gen == neuralGen) source?.onSegmentStarted(mark) } },
            onBlockAlmostDone = { main.post { if (gen == neuralGen) source?.onBlockFinished() } },
            onFailed = { _ ->
                // Out of memory, model files damaged, …: carry on with the system voice.
                main.post {
                    if (gen == neuralGen) {
                        neuralStore.unload()
                        setState { it.copy(error = str(R.string.neural_failed)) }
                        tts?.let { speakSystem(it, ++blockSeq, neuralBlock, lang) }
                    }
                }
            },
        )
    }

    private fun speakSystem(engine: TextToSpeech, seq: Int, segments: List<TtsSegment>, lang: String) {
        val voice = pickVoice(lang)
        if (voice != null) {
            runCatching { engine.voice = voice }
        } else {
            // Engines like Samsung's don't always list voices: ask for the language directly.
            val locale = localeFor(lang)
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                setState { it.copy(error = str(R.string.tts_missing_lang, locale.getDisplayLanguage(context.resources.configuration.locales[0]))) }
            }
        }
        if (voice != null && _state.value.error != null) setState { it.copy(error = null) }
        engine.setSpeechRate(settings.tts.value.speechRate)
        engine.setPitch(settings.tts.value.pitch)
        val max = TextToSpeech.getMaxSpeechInputLength() - 10
        val parts = segments.flatMap { s -> s.text.chunked(max).map { TtsSegment(s.mark, it) } }
        lastIndex = parts.lastIndex
        if (parts.isEmpty()) {
            main.post { if (seq == blockSeq) source?.onBlockFinished() }
            return
        }
        parts.forEachIndexed { i, s ->
            val params = Bundle()
            engine.speak(s.text, TextToSpeech.QUEUE_ADD, params, "$seq|$i|${s.mark}")
        }
        setState { it.copy(status = TtsStatus.PLAYING) }
    }

    fun pause() {
        blockSeq++
        neuralGen++
        tts?.stop()
        neural.stop()
        setState { it.copy(status = TtsStatus.PAUSED) }
    }

    fun stop() {
        blockSeq++
        neuralGen++
        tts?.stop()
        neural.stop()
        sleepJob?.cancel()
        setState { it.copy(status = TtsStatus.IDLE, sleepTimerEndsAt = null) }
        runCatching { context.stopService(Intent(context, TtsService::class.java)) }
    }

    // Called from the media session (lock screen, headset buttons)
    fun requestPlayPause() {
        when (_state.value.status) {
            TtsStatus.PLAYING -> source?.onPauseRequested()
            TtsStatus.PAUSED -> source?.onResumeRequested()
            TtsStatus.IDLE -> Unit
        }
    }

    fun requestNext() = source?.onNextRequested()
    fun requestPrevious() = source?.onPreviousRequested()
    fun requestStop() = source?.onStopRequested() ?: stop()

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            setState { it.copy(sleepTimerEndsAt = null) }
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        setState { it.copy(sleepTimerEndsAt = endsAt) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            setState { it.copy(sleepTimerEndsAt = null) }
            main.post { source?.onPauseRequested() ?: pause() }
        }
    }

    /** Preview a voice in the settings screen. */
    fun preview(voiceName: String, sample: String, lang: String = "nl") {
        NeuralVoice.fromKey(voiceName)?.let { v ->
            if (!neuralStore.installed) return
            blockSeq++
            tts?.stop()
            neuralGen++
            neural.speak(listOf(TtsSegment("preview", sample)), v, lang, settings.tts.value.speechRate.coerceIn(0.5f, 2.0f), NEURAL_STEPS, {}, {}, { })
            return
        }
        neural.stop()
        ensureEngine {
            val engine = tts ?: return@ensureEngine
            engine.voices.orEmpty().firstOrNull { it.name == voiceName }?.let { engine.voice = it }
            engine.setSpeechRate(settings.tts.value.speechRate)
            engine.setPitch(settings.tts.value.pitch)
            blockSeq++
            engine.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview")
        }
    }

    fun openSystemTtsSettings() {
        runCatching {
            context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun installVoiceData() {
        runCatching {
            context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (seq, _, mark) = parse(utteranceId) ?: return
            if (seq != blockSeq) return
            main.post { if (seq == blockSeq) source?.onSegmentStarted(mark) }
        }

        override fun onDone(utteranceId: String?) {
            val (seq, index, _) = parse(utteranceId) ?: return
            if (seq != blockSeq || index != lastIndex) return
            main.post { if (seq == blockSeq) source?.onBlockFinished() }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val (seq, index, _) = parse(utteranceId) ?: return
            if (seq != blockSeq) return
            // Skip unspeakable segments instead of stalling.
            if (index == lastIndex) main.post { if (seq == blockSeq) source?.onBlockFinished() }
        }

        private fun parse(id: String?): Triple<Int, Int, String>? {
            val parts = id?.split('|', limit = 3) ?: return null
            if (parts.size < 3) return null
            return Triple(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2])
        }
    }

    private companion object {
        /** Denoising steps of the natural voice: more is smoother but slower. */
        const val NEURAL_STEPS = 8
    }

    fun shutdown() {
        neural.shutdown()
        tts?.shutdown()
        tts = null
        setState { TtsState() }
    }
}
