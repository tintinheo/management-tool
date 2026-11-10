package com.myguitarplayer.data

import com.myguitarplayer.platform.Platform
import com.myguitarplayer.music.ChordPro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Songs: Room plus the bundled public-domain samples in assets/songs (one .cho file each). */
class SongRepo(private val dao: SongDao) {

    fun all() = dao.all()
    fun byId(id: Long) = dao.byId(id)
    suspend fun bySample(file: String) = withContext(Dispatchers.IO) { dao.bySample(file) }

    /** Inserts any bundled sample not in the database yet (new samples arrive with app updates). */
    suspend fun seedSamples() = withContext(Dispatchers.IO) {
        val have = dao.sampleKeys().toSet()
        val files = Platform.current.listAssets(SAMPLE_DIR).filter { it.endsWith(".cho") && it !in have }
        for (file in files) {
            val body = Platform.current.readAsset("$SAMPLE_DIR/$file")
            dao.insert(fromChordPro(body, sample = file))
        }
    }

    /** Saves pasted text (ChordPro or chords-over-lyrics) as a new song or over [existing]. */
    suspend fun save(text: String, title: String, artist: String, existing: SavedSong?): Long = withContext(Dispatchers.IO) {
        val body = ChordPro.toChordPro(text)
        val song = ChordPro.parse(body, title.trim(), artist.trim())
        if (existing != null) {
            dao.update(existing.copy(title = song.title, artist = song.artist, body = body, updatedAt = System.currentTimeMillis()))
            existing.id
        } else {
            dao.insert(SavedSong(title = song.title, artist = song.artist, body = body, capo = song.capo, bpm = song.tempo ?: DEFAULT_BPM))
        }
    }

    /** Remembers transpose / capo / tempo / scroll speed without moving the song in the list. */
    suspend fun updateSettings(song: SavedSong) = withContext(Dispatchers.IO) { dao.update(song) }

    suspend fun delete(song: SavedSong) = withContext(Dispatchers.IO) { dao.delete(song) }

    companion object {
        const val SAMPLE_DIR = "songs"
        const val DEFAULT_BPM = 72

        fun fromChordPro(body: String, sample: String? = null): SavedSong {
            val song = ChordPro.parse(body)
            return SavedSong(
                title = song.title,
                artist = song.artist,
                body = body.trim(),
                capo = song.capo,
                bpm = song.tempo ?: DEFAULT_BPM,
                sample = sample,
                updatedAt = 0,
            )
        }
    }
}
