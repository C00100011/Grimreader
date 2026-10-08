package com.vdelaar.mylibby.tts.neural

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device text-to-speech with the Supertonic 3 model (ONNX Runtime). A port of the reference Java
 * implementation: duration predictor -> text encoder -> flow-matching vector estimator -> vocoder.
 * Model licence: OpenRAIL-M. Runtime: ONNX Runtime (MIT).
 */
class SupertonicEngine(private val dir: File, threads: Int = defaultThreads()) : AutoCloseable {

    class Style(val ttl: FloatArray, val ttlShape: LongArray, val dp: FloatArray, val dpShape: LongArray)

    private val env = OrtEnvironment.getEnvironment()
    private val sessions = mutableListOf<OrtSession>()
    private val dp: OrtSession
    private val textEncoder: OrtSession
    private val vectorEstimator: OrtSession
    private val vocoder: OrtSession
    private val indexer: IntArray
    val sampleRate: Int
    private val baseChunkSize: Int
    private val chunkCompress: Int
    private val latentDim: Int
    private val styles = HashMap<String, Style>()
    private val random = Random()

    init {
        val cfg = JSONObject(File(dir, "tts.json").readText())
        val ae = cfg.getJSONObject("ae")
        val ttl = cfg.getJSONObject("ttl")
        sampleRate = ae.getInt("sample_rate")
        baseChunkSize = ae.getInt("base_chunk_size")
        chunkCompress = ttl.getInt("chunk_compress_factor")
        latentDim = ttl.getInt("latent_dim")
        val idx = JSONArray(File(dir, "unicode_indexer.json").readText())
        indexer = IntArray(idx.length()) { idx.getInt(it) }
        fun open(name: String): OrtSession {
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
            return env.createSession(File(dir, "$name.onnx").absolutePath, options).also { sessions += it }
        }
        dp = open("duration_predictor")
        textEncoder = open("text_encoder")
        vectorEstimator = open("vector_estimator")
        vocoder = open("vocoder")
    }

    fun style(id: String): Style = styles.getOrPut(id) {
        val root = JSONObject(File(dir, "voice_styles/$id.json").readText())
        fun part(key: String): Pair<FloatArray, LongArray> {
            val node = root.getJSONObject(key)
            val dims = node.getJSONArray("dims")
            val shape = LongArray(dims.length()) { dims.getLong(it) }
            val flat = FloatArray(shape.fold(1L) { a, b -> a * b }.toInt())
            var i = 0
            val data = node.getJSONArray("data")
            for (a in 0 until data.length()) {
                val rows = data.getJSONArray(a)
                for (b in 0 until rows.length()) {
                    val vals = rows.getJSONArray(b)
                    for (c in 0 until vals.length()) flat[i++] = vals.getDouble(c).toFloat()
                }
            }
            return flat to shape
        }
        val (t, ts) = part("style_ttl")
        val (d, ds) = part("style_dp")
        Style(t, ts, d, ds)
    }

    /** Speak [text] (any length) in [lang]; returns mono float PCM at [sampleRate]. */
    fun synthesize(text: String, lang: String, styleId: String, steps: Int, speed: Float): FloatArray {
        val style = style(styleId)
        val chunks = chunk(text, if (lang == "ko" || lang == "ja") 120 else 300)
        val pieces = ArrayList<FloatArray>()
        val gap = FloatArray((0.25f * sampleRate).toInt())
        for ((i, c) in chunks.withIndex()) {
            if (i > 0) pieces += gap
            pieces += infer(c, lang, style, steps, speed)
        }
        val out = FloatArray(pieces.sumOf { it.size })
        var o = 0
        for (p in pieces) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun infer(text: String, lang: String, style: Style, steps: Int, speed: Float): FloatArray {
        val ids = encode(preprocess(text, lang))
        val len = ids.size
        val closeables = ArrayList<AutoCloseable>()
        try {
            fun <T : AutoCloseable> T.track(): T = also { closeables += it }
            val textIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, len.toLong())).track()
            val textMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(len) { 1f }), longArrayOf(1, 1, len.toLong())).track()
            val styleDp = OnnxTensor.createTensor(env, FloatBuffer.wrap(style.dp), style.dpShape).track()
            val styleTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(style.ttl), style.ttlShape).track()

            val duration = dp.run(mapOf("text_ids" to textIds, "style_dp" to styleDp, "text_mask" to textMask)).track().let {
                (it[0] as OnnxTensor).floatBuffer.get(0) / speed
            }
            val textEmb = textEncoder.run(mapOf("text_ids" to textIds, "style_ttl" to styleTtl, "text_mask" to textMask)).track()[0] as OnnxTensor

            val wavLen = (duration * sampleRate).toLong()
            val chunkSize = baseChunkSize * chunkCompress
            val latentLen = max(1, ((wavLen + chunkSize - 1) / chunkSize).toInt())
            val dim = latentDim * chunkCompress
            var latent = FloatArray(dim * latentLen) { gaussian() }
            val latentMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(latentLen) { 1f }), longArrayOf(1, 1, latentLen.toLong())).track()
            val total = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(steps.toFloat())), longArrayOf(1)).track()

            for (step in 0 until steps) {
                val noisy = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), longArrayOf(1, dim.toLong(), latentLen.toLong()))
                val current = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(step.toFloat())), longArrayOf(1))
                val result = vectorEstimator.run(
                    mapOf(
                        "noisy_latent" to noisy, "text_emb" to textEmb, "style_ttl" to styleTtl,
                        "latent_mask" to latentMask, "text_mask" to textMask,
                        "current_step" to current, "total_step" to total,
                    )
                )
                val buf = (result[0] as OnnxTensor).floatBuffer
                latent = FloatArray(buf.remaining()).also { buf.get(it) }
                result.close(); noisy.close(); current.close()
            }

            val finalLatent = OnnxTensor.createTensor(env, FloatBuffer.wrap(latent), longArrayOf(1, dim.toLong(), latentLen.toLong())).track()
            val wavTensor = vocoder.run(mapOf("latent" to finalLatent)).track()[0] as OnnxTensor
            val buf = wavTensor.floatBuffer
            val wav = FloatArray(min(buf.remaining().toLong(), wavLen).toInt())
            buf.get(wav)
            return wav
        } finally {
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun gaussian(): Float {
        val u1 = max(1e-10, random.nextDouble())
        val u2 = random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)).toFloat()
    }

    private fun encode(text: String): LongArray {
        val ids = ArrayList<Long>(text.length)
        text.codePoints().forEach { cp -> if (cp in indexer.indices) ids += indexer[cp].toLong() }
        return ids.toLongArray()
    }

    private fun preprocess(input: String, lang: String): String {
        var text = if (lang == "nl") DutchTextNormalizer.normalize(input) else input
        text = Normalizer.normalize(text, Normalizer.Form.NFKD)
        text = stripEmoji(text)
        val map = mapOf(
            "–" to "-", "‑" to "-", "—" to "-", "_" to " ", "“" to "\"", "”" to "\"",
            "‘" to "'", "’" to "'", "´" to "'", "`" to "'", "[" to " ", "]" to " ",
            "|" to " ", "/" to " ", "#" to " ", "→" to " ", "←" to " ",
        )
        for ((k, v) in map) text = text.replace(k, v)
        text = text.replace(Regex("[♥☆♡©\\\\]"), "")
        for (p in listOf(",", ".", "!", "?", ";", ":", "'")) text = text.replace(" $p", p)
        while (text.contains("\"\"")) text = text.replace("\"\"", "\"")
        while (text.contains("''")) text = text.replace("''", "'")
        text = text.replace(Regex("\\s+"), " ").trim()
        if (!Regex(".*[.!?;:,'\"\\u201C\\u201D\\u2018\\u2019)\\]}…。」』】〉》›»]$").matches(text)) text += "."
        return "<$lang>$text</$lang>"
    }

    private fun stripEmoji(text: String): String {
        val sb = StringBuilder()
        text.codePoints().forEach { cp ->
            val emoji = cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F1E6..0x1F1FF
            if (!emoji) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** Split into sentence-sized pieces of at most [maxLen] characters. */
    fun splitText(text: String, maxLen: Int): List<String> = chunk(text, maxLen)

    private fun chunk(text: String, maxLen: Int): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxLen) return listOf(clean)
        val out = ArrayList<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim(); cur = StringBuilder() }
        for (sentence in clean.split(Regex("(?<=[.!?])\\s+"))) {
            if (cur.length + sentence.length + 1 > maxLen) flush()
            if (sentence.length <= maxLen) { cur.append(sentence).append(' '); continue }
            // Very long sentence: break at commas, then spaces.
            for (part in sentence.split(Regex("(?<=[,;:])\\s+"))) {
                if (cur.length + part.length + 1 > maxLen) flush()
                if (part.length <= maxLen) { cur.append(part).append(' '); continue }
                for (word in part.split(' ')) {
                    if (cur.length + word.length + 1 > maxLen) flush()
                    cur.append(word).append(' ')
                }
            }
        }
        flush()
        return out
    }

    override fun close() {
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    companion object {
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
