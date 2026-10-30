package com.vdelaar.mylibby.data.dict

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/*
 * FreeDict dictionaries come as StarDict packages in a .tar.xz: an .ifo (about the dictionary), an .idx (every headword
 * with where its entry is) and a .dict (the entries, as HTML), the last two possibly gzipped (.idx.gz, .dict.dz).
 */

/** One line of the headword index: the [word] and where its entry is in the .dict file. */
class IdxEntry(val word: String, val offset: Long, val size: Int)

/** The facts in an .ifo that matter here. */
data class Ifo(val bookName: String, val wordCount: Int, val offsetBits: Int, val sameTypeSequence: String?)

fun parseIfo(text: String): Ifo {
    val map = text.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
    return Ifo(map["bookname"].orEmpty(), map["wordcount"]?.toIntOrNull() ?: 0, if (map["idxoffsetbits"] == "64") 64 else 32, map["sametypesequence"])
}

/** Reads the headword index: `word\0` followed by a big-endian offset (32 or 64 bits) and a 32-bit size, repeated. */
fun readIdx(input: InputStream, offsetBits: Int, onEntry: (IdxEntry) -> Unit) {
    val bytes = input.readBytes()
    val tail = if (offsetBits == 64) 12 else 8
    var i = 0
    while (i < bytes.size) {
        var j = i
        while (j < bytes.size && bytes[j] != 0.toByte()) j++
        if (j + 1 + tail > bytes.size) break
        val word = String(bytes, i, j - i, Charsets.UTF_8)
        var off = 0L
        val offLen = tail - 4
        for (k in 0 until offLen) off = (off shl 8) or (bytes[j + 1 + k].toLong() and 0xff)
        var size = 0
        for (k in 0 until 4) size = (size shl 8) or (bytes[j + 1 + offLen + k].toInt() and 0xff)
        onEntry(IdxEntry(word, off, size))
        i = j + 1 + tail
    }
}

/** Random access to the entries of a .dict or .dict.dz file. */
interface DictBody {
    fun read(offset: Long, size: Int): ByteArray
    fun close()
}

/** An uncompressed .dict. */
class RawDict(file: File) : DictBody {
    private val raf = RandomAccessFile(file, "r")
    @Synchronized override fun read(offset: Long, size: Int): ByteArray {
        raf.seek(offset)
        return ByteArray(size).also { raf.readFully(it) }
    }
    override fun close() = raf.close()
}

/**
 * A dictzip file: a gzip file whose deflate data is cut into chunks of a fixed size (listed in the header's `RA` field), so
 * any part can be read by inflating only the chunks it lies in. Returns null from [open] when the file has no such table.
 */
class DictzipDict private constructor(private val raf: RandomAccessFile, private val chunkLength: Int, private val chunkStart: LongArray) : DictBody {
    @Synchronized override fun read(offset: Long, size: Int): ByteArray {
        if (size == 0) return ByteArray(0)
        val out = ByteArrayOutputStream(size)
        val first = (offset / chunkLength).toInt()
        val last = ((offset + size - 1) / chunkLength).toInt()
        for (c in first..last) {
            val chunk = inflateChunk(c)
            val from = if (c == first) (offset - c.toLong() * chunkLength).toInt() else 0
            val to = if (c == last) (offset + size - c.toLong() * chunkLength).toInt() else chunk.size
            if (from > chunk.size || to > chunk.size) throw EOFException("entry beyond the dictionary")
            out.write(chunk, from, to - from)
        }
        return out.toByteArray()
    }

    private fun inflateChunk(c: Int): ByteArray {
        val len = (chunkStart[c + 1] - chunkStart[c]).toInt()
        val input = ByteArray(len)
        raf.seek(chunkStart[c]); raf.readFully(input)
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(chunkLength)
            val buf = ByteArray(8192)
            while (true) {
                val n = inflater.inflate(buf)
                if (n > 0) out.write(buf, 0, n)
                else if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
            }
            return out.toByteArray()
        } finally { inflater.end() }
    }

    override fun close() = raf.close()

    companion object {
        fun open(file: File): DictzipDict? {
            val raf = RandomAccessFile(file, "r")
            try {
                fun u8() = raf.read().also { if (it < 0) throw EOFException() }
                fun u16() = u8() or (u8() shl 8)
                if (u8() != 0x1f || u8() != 0x8b) { raf.close(); return null }
                u8() // method
                val flags = u8()
                raf.skipBytes(6) // mtime, xfl, os
                var chunkLength = 0
                var sizes: IntArray? = null
                if (flags and 4 != 0) {
                    val xlen = u16()
                    val end = raf.filePointer + xlen
                    while (raf.filePointer + 4 <= end) {
                        val si1 = u8(); val si2 = u8(); val len = u16()
                        if (si1 == 'R'.code && si2 == 'A'.code) {
                            u16() // version
                            chunkLength = u16()
                            val count = u16()
                            sizes = IntArray(count) { u16() }
                            raf.seek(raf.filePointer + (len - 6 - count * 2))
                        } else raf.skipBytes(len)
                    }
                    raf.seek(end)
                }
                if (flags and 8 != 0) while (u8() != 0) { /* name */ }
                if (flags and 16 != 0) while (u8() != 0) { /* comment */ }
                if (flags and 2 != 0) raf.skipBytes(2)
                if (sizes == null || chunkLength <= 0) { raf.close(); return null }
                val starts = LongArray(sizes.size + 1)
                starts[0] = raf.filePointer
                for (i in sizes.indices) starts[i + 1] = starts[i] + sizes[i]
                return DictzipDict(raf, chunkLength, starts)
            } catch (e: Exception) {
                raf.close()
                return null
            }
        }
    }
}

/** Writes the content of a gzip file (a .dict.dz without a chunk table, or an .idx.gz) next to it, uncompressed. */
fun gunzipTo(source: File, target: File) {
    GZIPInputStream(source.inputStream().buffered()).use { input -> target.outputStream().buffered().use { input.copyTo(it) } }
}

/** A minimal tar reader (ustar, GNU long names, pax headers skipped): names and sizes, with the data as a stream per entry. */
class TarReader(private val input: InputStream) {
    class Entry(val name: String, val size: Long, val isFile: Boolean)

    private var remaining = 0L
    private var padding = 0

    /** Calls [onEntry] with each entry and a stream of its data; the stream need not be read to the end. */
    fun forEach(onEntry: (Entry, InputStream) -> Unit) {
        val header = ByteArray(512)
        var longName: String? = null
        while (true) {
            skipRest()
            if (!readFully(header)) return
            if (header.all { it == 0.toByte() }) return
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            var name = cString(header, 0, 100)
            val prefix = if (String(header, 257, 5, Charsets.US_ASCII) == "ustar") cString(header, 345, 155) else ""
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            remaining = size
            padding = ((512 - size % 512) % 512).toInt()
            when (type) {
                'L' -> { longName = String(readData(size.toInt()), Charsets.UTF_8).trimEnd('\u0000') }
                'x', 'g' -> readData(size.toInt()) // pax headers: nothing needed
                else -> {
                    val entryName = longName ?: name
                    longName = null
                    val stream = object : InputStream() {
                        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (remaining <= 0) return -1
                            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
                            if (n < 0) throw EOFException("tar ends early")
                            remaining -= n
                            return n
                        }
                    }
                    onEntry(Entry(entryName, size, type == '0' || type == '\u0000'), stream)
                }
            }
        }
    }

    private fun readData(n: Int): ByteArray = ByteArray(n).also { if (n > 0 && !readFully(it)) throw EOFException(); remaining = 0 }

    private fun skipRest() {
        var skip = remaining + padding
        remaining = 0; padding = 0
        val buf = ByteArray(8192)
        while (skip > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), skip).toInt())
            if (n < 0) throw EOFException("tar ends early")
            skip -= n
        }
    }

    private fun readFully(buf: ByteArray): Boolean {
        var got = 0
        while (got < buf.size) {
            val n = input.read(buf, got, buf.size - got)
            if (n < 0) return false
            got += n
        }
        return true
    }

    private fun cString(b: ByteArray, from: Int, len: Int): String {
        var end = from
        while (end < from + len && b[end] != 0.toByte()) end++
        return String(b, from, end - from, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, from: Int, len: Int): Long {
        if (b[from].toInt() and 0x80 != 0) { // base-256 for sizes that do not fit
            var v = 0L
            for (i in 1 until len) v = (v shl 8) or (b[from + i].toLong() and 0xff)
            return v
        }
        val s = cString(b, from, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }
}
