package com.audiohub.data.lyrics

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** Random-access byte source (a file channel on device, a byte array in tests). */
interface ByteSource {
    val size: Long
    /** Reads up to [length] bytes at [position]; shorter at end of file. */
    fun read(position: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, length: Int): ByteArray {
        if (position >= bytes.size) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), position + length).toInt()
        return bytes.copyOfRange(position.toInt(), end)
    }
}

class FileChannelSource(private val channel: FileChannel) : ByteSource {
    override val size: Long get() = channel.size()
    override fun read(position: Long, length: Int): ByteArray {
        val n = minOf(length.toLong(), (size - position).coerceAtLeast(0)).toInt()
        val buf = ByteBuffer.allocate(n)
        var pos = position
        while (buf.hasRemaining()) {
            val r = channel.read(buf, pos)
            if (r <= 0) break
            pos += r
        }
        return buf.array().copyOf(buf.position())
    }
}

/**
 * Extracts unsynchronised lyrics text from audio file tags without decoding audio:
 * ID3v2 USLT/ULT (MP3), Vorbis comment LYRICS/UNSYNCEDLYRICS (FLAC) and iTunes ©lyr (M4A/MP4).
 * The text may itself be LRC-formatted; feed it to [LrcParser].
 */
object EmbeddedLyrics {

    private const val MAX_TAG_BYTES = 16 * 1024 * 1024

    fun read(source: ByteSource): String? {
        val head = source.read(0, 12)
        return when {
            head.size >= 10 && head.startsWith("ID3") -> readId3(source)
            head.size >= 4 && head.startsWith("fLaC") -> readFlac(source)
            head.size >= 8 && head.copyOfRange(4, 8).ascii() == "ftyp" -> readMp4(source)
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- ID3v2

    private fun readId3(source: ByteSource): String? {
        val header = source.read(0, 10)
        val major = header[3].toInt()
        val tagSize = syncSafe(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return null
        val tag = source.read(10, tagSize)
        var pos = 0
        // Skip extended header if present (flag bit 6).
        if (header[5].toInt() and 0x40 != 0 && major >= 3) {
            val extSize = if (major == 4) syncSafe(tag, 0) else u32(tag, 0) + 4
            pos += extSize
        }
        val idLen = if (major == 2) 3 else 4
        val headerLen = if (major == 2) 6 else 10
        while (pos + headerLen <= tag.size) {
            val id = tag.copyOfRange(pos, pos + idLen).ascii()
            if (id.all { it == '\u0000' }) break // padding
            val size = when (major) {
                2 -> u24(tag, pos + 3)
                4 -> syncSafe(tag, pos + 4)
                else -> u32(tag, pos + 4)
            }
            val bodyStart = pos + headerLen
            if (size <= 0 || bodyStart + size > tag.size) break
            if (id == "USLT" || id == "ULT") {
                return decodeUslt(tag.copyOfRange(bodyStart, bodyStart + size))
            }
            pos = bodyStart + size
        }
        return null
    }

    /** USLT body: encoding(1) language(3) descriptor(\0-terminated) text. */
    private fun decodeUslt(body: ByteArray): String? {
        if (body.size < 5) return null
        val encoding = body[0].toInt()
        val charset = id3Charset(encoding)
        val wide = encoding == 1 || encoding == 2
        var i = 4
        // Skip descriptor up to its terminator (1 byte, or 2 aligned bytes for UTF-16).
        if (wide) {
            while (i + 1 < body.size && !(body[i].toInt() == 0 && body[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < body.size && body[i].toInt() != 0) i++
            i += 1
        }
        if (i >= body.size) return null
        return String(body, i, body.size - i, charset).trimEnd('\u0000')
    }

    private fun id3Charset(encoding: Int): Charset = when (encoding) {
        1 -> Charsets.UTF_16 // BOM-detected
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    // ---------------------------------------------------------------- FLAC

    private fun readFlac(source: ByteSource): String? {
        var pos = 4L
        while (pos + 4 <= source.size) {
            val h = source.read(pos, 4)
            if (h.size < 4) return null
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val length = u24(h, 1)
            if (type == 4) return vorbisLyrics(source.read(pos + 4, length))
            if (last) return null
            pos += 4 + length
        }
        return null
    }

    /** Vorbis comment block: little-endian lengths, "KEY=value" UTF-8 entries. */
    private fun vorbisLyrics(block: ByteArray): String? {
        var p = 0
        fun le32(): Int {
            if (p + 4 > block.size) return -1
            val v = (block[p].toInt() and 0xFF) or ((block[p + 1].toInt() and 0xFF) shl 8) or
                ((block[p + 2].toInt() and 0xFF) shl 16) or ((block[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendorLen = le32()
        if (vendorLen < 0) return null
        p += vendorLen
        val count = le32()
        repeat(count.coerceAtLeast(0)) {
            val len = le32()
            if (len < 0 || p + len > block.size) return null
            val entry = String(block, p, len, Charsets.UTF_8)
            p += len
            val key = entry.substringBefore('=').uppercase()
            if (key == "LYRICS" || key == "UNSYNCEDLYRICS") return entry.substringAfter('=')
        }
        return null
    }

    // ---------------------------------------------------------------- MP4

    private val LYRICS_ATOM = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())

    private fun readMp4(source: ByteSource): String? {
        val moov = findChild(source, 0, source.size, "moov") ?: return null
        val udta = findChild(source, moov.first, moov.second, "udta") ?: return null
        val meta = findChild(source, udta.first, udta.second, "meta") ?: return null
        // 'meta' is a full box: 4 bytes version/flags before its children.
        val ilst = findChild(source, meta.first + 4, meta.second, "ilst") ?: return null
        val lyr = findChild(source, ilst.first, ilst.second, null, LYRICS_ATOM) ?: return null
        val data = findChild(source, lyr.first, lyr.second, "data") ?: return null
        // data atom: type(4) locale(4) payload.
        val len = (data.second - data.first - 8).toInt()
        if (len <= 0 || len > MAX_TAG_BYTES) return null
        return String(source.read(data.first + 8, len), Charsets.UTF_8)
    }

    /** Returns the [start, end) payload range of the first child atom with the given type. */
    private fun findChild(
        source: ByteSource,
        start: Long,
        end: Long,
        type: String?,
        rawType: ByteArray? = null,
    ): Pair<Long, Long>? {
        var pos = start
        while (pos + 8 <= end) {
            val h = source.read(pos, 16)
            if (h.size < 8) return null
            var size = u32(h, 0).toLong() and 0xFFFFFFFFL
            var headerLen = 8
            if (size == 1L && h.size >= 16) { // 64-bit size
                size = ((u32(h, 8).toLong() and 0xFFFFFFFFL) shl 32) or (u32(h, 12).toLong() and 0xFFFFFFFFL)
                headerLen = 16
            } else if (size == 0L) {
                size = end - pos // extends to end
            }
            if (size < headerLen) return null
            val t = h.copyOfRange(4, 8)
            val hit = if (rawType != null) t.contentEquals(rawType) else t.ascii() == type
            if (hit) return (pos + headerLen) to minOf(pos + size, end)
            pos += size
        }
        return null
    }

    // ---------------------------------------------------------------- helpers

    private fun ByteArray.startsWith(prefix: String) =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    private fun ByteArray.ascii() = String(this, Charsets.ISO_8859_1)

    private fun syncSafe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun u24(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
}
