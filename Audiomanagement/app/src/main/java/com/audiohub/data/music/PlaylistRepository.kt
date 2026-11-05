package com.audiohub.data.music

import android.content.Context
import android.net.Uri
import com.audiohub.data.db.PlaylistDao
import com.audiohub.data.db.PlaylistSummary
import com.audiohub.data.db.PlaylistTrack
import com.audiohub.data.db.TrackDao
import com.audiohub.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class PlaylistRepository(
    private val context: Context,
    private val dao: PlaylistDao,
    private val trackDao: TrackDao,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val summaries: Flow<List<PlaylistSummary>> = dao.observeSummaries()

    fun playlist(id: Long) = dao.observe(id)
    fun tracks(id: Long): Flow<List<PlaylistTrack>> = dao.observeTracks(id)

    suspend fun create(name: String, trackIds: List<Long> = emptyList()): Long =
        dao.create(name.trim().ifEmpty { "New Playlist" }, trackIds, clock())

    suspend fun rename(id: Long, name: String) {
        if (name.isNotBlank()) dao.rename(id, name.trim(), clock())
    }

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun add(id: Long, trackIds: List<Long>) = dao.append(id, trackIds, clock())
    suspend fun remove(id: Long, entryId: Long) = dao.removeEntry(id, entryId, clock())
    suspend fun move(id: Long, from: Int, to: Int) = dao.move(id, from, to, clock())

    suspend fun exportM3u(id: Long, target: Uri) = withContext(Dispatchers.IO) {
        val name = dao.observe(id).first()?.name ?: "Playlist"
        val tracks = dao.observeTracks(id).first().map { it.track }
        val text = M3u.format(name, tracks, settings.current().musicFolder)
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Can't write to $target")
    }

    data class ImportResult(val playlistId: Long, val name: String, val matched: Int, val total: Int)

    suspend fun importM3u(source: Uri, fallbackName: String): ImportResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Can't read $source")
        val parsed = M3u.parse(text)
        val matched = M3u.match(parsed.paths, trackDao.observeAll().first())
        val name = parsed.name ?: fallbackName
        val id = dao.create(name, matched.map { it.id }, clock())
        ImportResult(id, name, matched.size, parsed.paths.size)
    }
}
