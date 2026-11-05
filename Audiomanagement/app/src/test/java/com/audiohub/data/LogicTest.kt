package com.audiohub.data

import com.audiohub.data.db.TrackEntity
import com.audiohub.data.lyrics.ByteArraySource
import com.audiohub.data.lyrics.EmbeddedLyrics
import com.audiohub.data.lyrics.LrcParser
import com.audiohub.data.lyrics.Lyrics
import com.audiohub.data.music.LibrarySearch
import com.audiohub.data.music.M3u
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SearchTextTest {
    @Test
    fun stripsVietnameseAccentsAndCase() {
        assertEquals("lac troi", SearchText.normalize("Lạc Trôi"))
        assertEquals("son tung m-tp", SearchText.normalize("  Sơn   Tùng M-TP "))
        assertEquals("dong kiem em", SearchText.normalize("ĐÔNG KIẾM EM"))
        assertEquals("cafe", SearchText.normalize("Café"))
    }

    @Test
    fun matchesAllWordsInAnyOrder() {
        val key = SearchText.of("Lạc Trôi", "Sơn Tùng M-TP", "Single")
        assertTrue(SearchText.matches(key, "troi son"))
        assertTrue(SearchText.matches(key, "LẠC"))
        assertFalse(SearchText.matches(key, "lac hong"))
        assertFalse(SearchText.matches(key, "   "))
    }
}

class LrcParserTest {
    @Test
    fun parsesSyncedLyricsWithMultipleTagsAndOffset() {
        val lrc = """
            [ar:Someone]
            [ti:Song]
            [offset:+500]
            [00:12.00]First line
            [00:15.5]Second line
            [01:02.345][01:30.00]Chorus
        """.trimIndent()
        val parsed = LrcParser.parse(lrc) as Lyrics.Synced
        assertEquals(listOf(11_500L, 15_000L, 61_845L, 89_500L), parsed.lines.map { it.timeMs })
        assertEquals(listOf("First line", "Second line", "Chorus", "Chorus"), parsed.lines.map { it.text })
    }

    @Test
    fun indexAtFindsCurrentLine() {
        val synced = LrcParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c") as Lyrics.Synced
        assertEquals(-1, synced.indexAt(500))
        assertEquals(0, synced.indexAt(1_000))
        assertEquals(1, synced.indexAt(8_999))
        assertEquals(2, synced.indexAt(60_000))
    }

    @Test
    fun plainTextStaysPlainWithoutIdTags() {
        val parsed = LrcParser.parse("[ar:X]\nHello\nWorld\r\n") as Lyrics.Plain
        assertEquals("Hello\nWorld", parsed.text)
        assertNull(LrcParser.parse("   "))
    }
}

class EmbeddedLyricsTest {

    @Test
    fun readsId3v23UsltUtf16() = assertEquals("Lời bài hát\nDòng 2", EmbeddedLyrics.read(ByteArraySource(id3(3, usltUtf16("Lời bài hát\nDòng 2")))))

    @Test
    fun readsId3v24UsltUtf8() = assertEquals("[00:01.00]Xin chào", EmbeddedLyrics.read(ByteArraySource(id3(4, usltUtf8("[00:01.00]Xin chào")))))

    @Test
    fun returnsNullWhenNoLyricsFrame() {
        val tit2 = frame(3, "TIT2", byteArrayOf(3) + "Title".toByteArray())
        assertNull(EmbeddedLyrics.read(ByteArraySource(id3(3, tit2))))
        assertNull(EmbeddedLyrics.read(ByteArraySource("not audio".toByteArray())))
    }

    @Test
    fun readsFlacVorbisComment() {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        out.write(byteArrayOf(0, 0, 0, 34)); out.write(ByteArray(34)) // STREAMINFO
        val comments = ByteArrayOutputStream().apply {
            le32(this, 6); write("vendor".toByteArray())
            val entries = listOf("TITLE=Song", "LYRICS=Hello FLAC")
            le32(this, entries.size)
            entries.forEach { val b = it.toByteArray(); le32(this, b.size); write(b) }
        }.toByteArray()
        out.write(byteArrayOf((0x80 or 4).toByte()) + be24(comments.size)); out.write(comments)
        out.write(ByteArray(100)) // audio frames
        assertEquals("Hello FLAC", EmbeddedLyrics.read(ByteArraySource(out.toByteArray())))
    }

    @Test
    fun readsMp4LyricsAtomAfterMdat() {
        val data = atom("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + "M4A lyrics".toByteArray())
        val lyr = atom(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), data)
        val ilst = atom("ilst", atom(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), ByteArray(8)) + lyr)
        val meta = atom("meta", ByteArray(4) + atom("hdlr", ByteArray(25)) + ilst)
        val moov = atom("moov", atom("mvhd", ByteArray(100)) + atom("udta", meta))
        val file = atom("ftyp", "M4A ".toByteArray() + ByteArray(4)) + atom("mdat", ByteArray(5000)) + moov
        assertEquals("M4A lyrics", EmbeddedLyrics.read(ByteArraySource(file)))
    }

    // ---- builders

    private fun usltUtf16(text: String): ByteArray {
        val body = byteArrayOf(1) + "vie".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) +
            text.toByteArray(Charsets.UTF_16) // includes BOM
        return frame(3, "USLT", body)
    }

    private fun usltUtf8(text: String) =
        frame(4, "USLT", byteArrayOf(3) + "eng".toByteArray() + "desc".toByteArray() + byteArrayOf(0) + text.toByteArray())

    private fun frame(version: Int, id: String, body: ByteArray): ByteArray {
        val size = if (version == 4) syncSafe(body.size) else be32(body.size)
        return id.toByteArray() + size + byteArrayOf(0, 0) + body
    }

    private fun id3(version: Int, frames: ByteArray): ByteArray {
        val tag = frames + ByteArray(64) // padding
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + syncSafe(tag.size) + tag + ByteArray(200)
    }

    private fun atom(type: String, payload: ByteArray) = atom(type.toByteArray(), payload)
    private fun atom(type: ByteArray, payload: ByteArray) = be32(8 + payload.size) + type + payload

    private fun syncSafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
    )
    private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun be24(n: Int) = byteArrayOf((n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun le32(out: ByteArrayOutputStream, n: Int) =
        out.write(byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()))
}

class M3uAndSearchTest {

    private val library = listOf(
        track(1, "Lạc Trôi", "Sơn Tùng M-TP", "Lạc Trôi", 10, "Music/AudioHub/Vpop/", "lac-troi.mp3"),
        track(2, "Hotel California", "Eagles", "Hotel California", 20, "Music/AudioHub/Rock/", "hotel.flac"),
        track(3, "New Kid in Town", "Eagles", "Hotel California", 20, "Music/AudioHub/Rock/", "new-kid.flac"),
        track(4, "Take Five", "Dave Brubeck", "Time Out", 30, "Music/AudioHub/", "take five.m4a"),
    )

    @Test
    fun m3uRoundTrip() {
        val text = M3u.format("Road trip", listOf(library[1], library[0]), "Music/AudioHub/")
        assertTrue(text.startsWith("#EXTM3U\n#PLAYLIST:Road trip\n"))
        assertTrue(text.contains("Rock/hotel.flac\n"))
        val parsed = M3u.parse(text)
        assertEquals("Road trip", parsed.name)
        assertEquals(listOf(2L, 1L), M3u.match(parsed.paths, library).map { it.id })
    }

    @Test
    fun m3uMatchesForeignPathsByNameAndSkipsMissing() {
        val foreign = "﻿#EXTM3U\r\nC:\\Users\\me\\Music\\take five.m4a\r\n/storage/emulated/0/Music/AudioHub/Rock/new-kid.flac\r\nmissing.mp3\r\n"
        val parsed = M3u.parse(foreign)
        assertNull(parsed.name)
        assertEquals(listOf(4L, 3L), M3u.match(parsed.paths, library).map { it.id })
    }

    @Test
    fun searchFindsSongsAlbumsArtistsWithoutAccents() {
        val r = LibrarySearch.search(library, "lac troi")
        assertEquals(listOf(1L), r.songs.map { it.id })
        assertEquals(listOf("Lạc Trôi"), r.albums.map { it.title })

        val eagles = LibrarySearch.search(library, "eagles")
        assertEquals(2, eagles.songs.size)
        assertEquals(1, eagles.albums.size)
        assertEquals(listOf("Eagles"), eagles.artists.map { it.name })
        assertEquals(2, eagles.artists.single().trackCount)

        assertTrue(LibrarySearch.search(library, " ").isEmpty)
        assertTrue(LibrarySearch.search(library, "zzz").isEmpty)
    }

    private fun track(id: Long, title: String, artist: String, album: String, albumId: Long, path: String, file: String) =
        TrackEntity(
            id = id, uri = "content://x/$id", title = title, artist = artist, albumArtist = null, album = album,
            albumId = albumId, trackNumber = 1, discNumber = 0, year = 2000, genre = null, durationMs = 200_000,
            dateAddedSec = 0, relativePath = path, displayName = file,
            searchText = SearchText.of(title, artist, album),
        )
}
