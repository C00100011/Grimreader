package com.vdelaar.mylibby.tts.neural

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.vdelaar.mylibby.BuildConfig
import com.vdelaar.mylibby.tts.TtsSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Plays text with the neural voice as one continuous stream. Text goes into a queue; a synthesiser works
 * a few pieces ahead of playback and the audio is streamed through an [AudioTrack]. New blocks can be
 * appended while the previous one is still playing, so there is no gap between paragraphs.
 *
 * Every segment is announced when its audio starts, so the reader can highlight it like with the system voice.
 */
class NeuralSpeaker(private val store: NeuralVoiceStore, private val scope: CoroutineScope) {

    private class Piece(val mark: String?, val text: String, val lastOfBlock: Boolean)
    private class Played(val mark: String?, val pcm: ShortArray, val lastOfBlock: Boolean)

    private inner class Pipeline(
        val voice: NeuralVoice,
        val lang: String,
        val speed: Float,
        val steps: Int,
        val onSegmentStarted: (String) -> Unit,
        val onBlockAlmostDone: () -> Unit,
        val onFailed: (Throwable) -> Unit,
    ) {
        val input = Channel<Piece>(Channel.UNLIMITED)
        lateinit var job: Job
        @Volatile var firstPiece = true

        fun enqueue(segments: List<TtsSegment>, engine: SupertonicEngine?) {
            segments.forEachIndexed { si, seg ->
                // Smaller pieces: the first audio of a sentence arrives sooner, and long sentences stream.
                val maxLen = if (firstPiece) FIRST_PIECE_CHARS else PIECE_CHARS
                firstPiece = false
                val parts = (engine?.splitText(seg.text, maxLen) ?: splitSimple(seg.text, maxLen)).ifEmpty { listOf(seg.text) }
                parts.forEachIndexed { pi, part ->
                    val last = si == segments.lastIndex && pi == parts.lastIndex
                    input.trySend(Piece(if (pi == 0) seg.mark else null, part, last))
                }
            }
        }
    }

    @Volatile private var pipeline: Pipeline? = null
    @Volatile private var track: AudioTrack? = null
    private var unloadJob: Job? = null

    /** Audio seconds produced per second of computing for the last piece (below 1 = slower than playback). */
    @Volatile var lastRealtimeFactor: Float = 0f
        private set

    val isActive: Boolean get() = pipeline?.job?.isActive == true

    /** Load the model in the background so the first sentence does not wait for it. */
    fun warmUp() {
        scope.launch(Dispatchers.IO) { runCatching { store.engine() } }
    }

    fun speak(
        segments: List<TtsSegment>,
        voice: NeuralVoice,
        lang: String,
        speed: Float,
        steps: Int,
        onSegmentStarted: (String) -> Unit,
        onBlockAlmostDone: () -> Unit,
        onFailed: (Throwable) -> Unit,
    ) {
        stopPlayback()
        unloadJob?.cancel()
        val p = Pipeline(voice, lang, speed, steps, onSegmentStarted, onBlockAlmostDone, onFailed)
        pipeline = p
        p.enqueue(segments, null)
        p.job = scope.launch(Dispatchers.Default) { run(p) }
    }

    /** Add the next block to a running stream. Returns false when there is no compatible stream to join. */
    fun append(segments: List<TtsSegment>, voice: NeuralVoice, lang: String, speed: Float): Boolean {
        val p = pipeline ?: return false
        if (!p.job.isActive || p.voice != voice || p.lang != lang || p.speed != speed) return false
        p.enqueue(segments, null)
        return true
    }

    /** Stops playback immediately; the model is unloaded a few minutes later to free memory. */
    fun stop() {
        stopPlayback()
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(UNLOAD_AFTER_MS)
            store.unload()
        }
    }

    fun shutdown() {
        stopPlayback()
        unloadJob?.cancel()
        store.unload()
    }

    private suspend fun run(p: Pipeline) {
        try {
            val engine = store.engine()
            val ready = Channel<Played>(capacity = READY_AHEAD)
            val producer = scope.launch(Dispatchers.Default) {
                var steps = p.steps
                try {
                    for (piece in p.input) {
                        ensureActive()
                        val started = System.nanoTime()
                        val pcm = engine.synthesize(piece.text, p.lang, p.voice.id, steps, p.speed)
                        val took = (System.nanoTime() - started) / 1e9f
                        val seconds = pcm.size / engine.sampleRate.toFloat()
                        if (took > 0f) {
                            lastRealtimeFactor = seconds / took
                            // Stay ahead of playback: fewer denoising steps on slow phones, more when there is headroom.
                            if (lastRealtimeFactor < 1.7f && steps > MIN_STEPS) steps--
                            else if (lastRealtimeFactor > 3.0f && steps < p.steps) steps++
                        }
                        ready.send(Played(piece.mark, toPcm16(trimSilence(pcm, engine.sampleRate)), piece.lastOfBlock))
                    }
                } finally {
                    ready.close()
                }
            }
            val audio = openTrack(engine.sampleRate)
            track = audio
            // Build a small lead before the first sound so playback does not run dry straight away.
            val head = ArrayList<Played>()
            ready.receiveCatching().getOrNull()?.let { head += it }
            if (head.isNotEmpty()) withTimeoutOrNull(PREBUFFER_WAIT_MS) { ready.receiveCatching().getOrNull() }?.let { head += it }
            audio.play()
            val gapShort = ShortArray((engine.sampleRate * GAP_SENTENCE_S).toInt())
            val gapBlock = ShortArray((engine.sampleRate * GAP_BLOCK_S).toInt())
            suspend fun play(played: Played) {
                currentCoroutineContext().ensureActive()
                played.mark?.let { p.onSegmentStarted(it) }
                // Ask for the next block as the last sentence starts, so it is synthesised while this one plays.
                if (played.lastOfBlock) p.onBlockAlmostDone()
                write(audio, played.pcm)
                write(audio, if (played.lastOfBlock) gapBlock else gapShort)
                if (BuildConfig.DEBUG) android.util.Log.d("NeuralTts", "piece done mark=${played.mark} last=${played.lastOfBlock} rtf=${"%.2f".format(lastRealtimeFactor)} underruns=${audio.underrunCount}")
            }
            head.forEach { play(it) }
            for (played in ready) play(played)
            producer.join()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            p.onFailed(e)
        } finally {
            if (pipeline === p) releaseTrack()
        }
    }

    private suspend fun write(audio: AudioTrack, pcm: ShortArray) {
        var off = 0
        while (off < pcm.size) {
            currentCoroutineContext().ensureActive()
            val n = audio.write(pcm, off, min(CHUNK, pcm.size - off))
            if (n < 0) error("AudioTrack write failed: $n")
            off += n
        }
    }

    private fun stopPlayback() {
        pipeline?.let { it.job.cancel(); it.input.close() }
        pipeline = null
        runCatching { track?.pause(); track?.flush() }
        releaseTrack()
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    private fun openTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // About half a second of audio: small enough that highlights stay close to what is heard.
        val size = max(minBuffer, sampleRate)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** The model pads every piece with a little silence; cut it so sentences follow each other naturally. */
    private fun trimSilence(pcm: FloatArray, rate: Int): FloatArray {
        val threshold = 0.012f
        var start = 0
        while (start < pcm.size && abs(pcm[start]) < threshold) start++
        var end = pcm.size
        while (end > start && abs(pcm[end - 1]) < threshold) end--
        val margin = (rate * 0.03f).toInt()
        val from = max(0, start - margin)
        val to = min(pcm.size, end + margin)
        return if (to > from) pcm.copyOfRange(from, to) else pcm
    }

    private fun toPcm16(pcm: FloatArray): ShortArray = ShortArray(pcm.size) { i ->
        (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }

    private fun splitSimple(text: String, maxLen: Int): List<String> {
        val clean = text.trim()
        if (clean.length <= maxLen) return listOf(clean)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (word in clean.split(' ')) {
            if (cur.length + word.length + 1 > maxLen && cur.isNotEmpty()) { out += cur.toString().trim(); cur = StringBuilder() }
            cur.append(word).append(' ')
            if (cur.length >= maxLen / 2 && word.endsWith(",") || word.endsWith(";") || word.endsWith(".")) { out += cur.toString().trim(); cur = StringBuilder() }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private companion object {
        const val CHUNK = 4096
        const val MIN_STEPS = 4
        const val READY_AHEAD = 4
        const val PREBUFFER_WAIT_MS = 6000L
        const val FIRST_PIECE_CHARS = 90
        const val PIECE_CHARS = 150
        const val GAP_SENTENCE_S = 0.12f
        const val GAP_BLOCK_S = 0.35f
        const val UNLOAD_AFTER_MS = 3 * 60_000L
    }
}
