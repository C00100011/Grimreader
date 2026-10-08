package com.vdelaar.mylibby.tts.neural

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

sealed interface NeuralModelState {
    data object NotInstalled : NeuralModelState
    data class Downloading(val done: Long, val total: Long) : NeuralModelState
    data object Installed : NeuralModelState
    data class Failed(val message: String) : NeuralModelState
}

/** A Supertonic voice preset; [id] is the file name of the style (M1..M5, F1..F5). */
data class NeuralVoice(val id: String, val female: Boolean, val number: Int) {
    /** Value stored in the settings, e.g. "supertonic:M1". */
    val key: String get() = "$PREFIX$id"

    companion object {
        const val PREFIX = "supertonic:"
        val all = (1..5).map { NeuralVoice("F$it", true, it) } + (1..5).map { NeuralVoice("M$it", false, it) }
        fun fromKey(key: String?): NeuralVoice? = key?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.let { id -> all.firstOrNull { it.id == id } }
    }
}

/**
 * Downloads, stores and loads the Supertonic 3 model (Hugging Face, OpenRAIL-M). The files live in
 * app storage (~400 MB) and are only fetched when the user asks for the natural voice.
 */
class NeuralVoiceStore(context: Context, private val scope: CoroutineScope) {

    private data class Remote(val path: String, val size: Long?)

    private val dir = File(context.filesDir, "tts/supertonic3")
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private val files: List<Remote> = listOf(
        Remote("onnx/duration_predictor.onnx", 3_700_147),
        Remote("onnx/text_encoder.onnx", 36_416_150),
        Remote("onnx/vector_estimator.onnx", 256_534_781),
        Remote("onnx/vocoder.onnx", 101_424_195),
        Remote("onnx/tts.json", 8_253),
        Remote("onnx/unicode_indexer.json", 277_676),
    ) + NeuralVoice.all.map { Remote("voice_styles/${it.id}.json", null) }

    private val _state = MutableStateFlow<NeuralModelState>(if (isComplete()) NeuralModelState.Installed else NeuralModelState.NotInstalled)
    val state: StateFlow<NeuralModelState> = _state.asStateFlow()

    private var job: Job? = null
    private var engine: SupertonicEngine? = null

    val installed: Boolean get() = _state.value is NeuralModelState.Installed

    private fun local(r: Remote) = File(dir, r.path.removePrefix("onnx/"))

    private fun isComplete() = files.all { r -> local(r).let { it.isFile && (r.size == null || it.length() == r.size) } }

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val total = TOTAL_BYTES
            var done = files.sumOf { r -> local(r).takeIf { it.isFile }?.length() ?: 0L }
            _state.value = NeuralModelState.Downloading(done, total)
            try {
                dir.resolve("voice_styles").mkdirs()
                for (r in files) {
                    val target = local(r)
                    if (target.isFile && (r.size == null || target.length() == r.size)) continue
                    done -= target.takeIf { it.isFile }?.length() ?: 0L
                    target.delete()
                    done += fetch(r, target) { delta -> done += delta; _state.value = NeuralModelState.Downloading(done, total) }
                }
                _state.value = if (isComplete()) NeuralModelState.Installed else NeuralModelState.Failed("incomplete")
            } catch (e: Exception) {
                if (isActive) _state.value = NeuralModelState.Failed(e.message ?: e.javaClass.simpleName)
                else _state.value = NeuralModelState.NotInstalled
            }
        }
    }

    /** Download into `<file>.part`, resuming an earlier attempt. Returns the final size minus what progress already counted. */
    private fun fetch(r: Remote, target: File, onBytes: (Long) -> Unit): Long {
        val part = File(target.path + ".part")
        val have = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url("$BASE/${r.path}").apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 206) throw IOException("HTTP ${resp.code}")
            val resume = resp.code == 206
            if (!resume) part.delete()
            val startAt = if (resume) have else 0L
            if (resume && have > 0) onBytes(have)
            var written = 0L
            RandomAccessFile(part, "rw").use { out ->
                out.seek(startAt)
                val body = resp.body ?: throw IOException("empty body")
                val buf = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        onBytes(n.toLong())
                    }
                }
            }
            if (r.size != null && part.length() != r.size) throw IOException("size mismatch for ${r.path}")
            if (!part.renameTo(target)) throw IOException("cannot move ${part.name}")
        }
        // Progress was reported live; the caller's running total is already up to date.
        return 0L
    }

    fun cancelDownload() { job?.cancel() }

    suspend fun delete() = withContext(Dispatchers.IO) {
        job?.cancel()
        unload()
        dir.deleteRecursively()
        _state.value = NeuralModelState.NotInstalled
    }

    @Synchronized
    fun engine(): SupertonicEngine = engine ?: SupertonicEngine(dir).also { engine = it }

    @Synchronized
    fun unload() {
        engine?.close()
        engine = null
    }

    companion object {
        private const val REVISION = "3cadd1ee6394adea1bd021217a0e650ede09a323"
        private const val BASE = "https://huggingface.co/Supertone/supertonic-3/resolve/$REVISION"
        /** Approximate download size shown to the user. */
        const val TOTAL_BYTES = 398_500_000L
    }
}
