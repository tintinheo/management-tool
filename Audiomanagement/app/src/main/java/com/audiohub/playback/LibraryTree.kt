package com.audiohub.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audiohub.data.TrackSorter
import com.audiohub.data.music.LibrarySearch
import com.audiohub.data.music.MusicRepository
import com.audiohub.data.music.MusicScanner
import com.audiohub.data.music.PlaylistRepository
import com.audiohub.data.podcast.PodcastRepository
import com.audiohub.data.settings.SettingsRepository
import com.audiohub.data.storage.AudioFolders
import kotlinx.coroutines.flow.first

/**
 * Browse tree for Android Auto / Bluetooth / other media browsers:
 * Playlists, Folders, Albums, Artists, Songs, Podcasts → items.
 */
class LibraryTree(
    private val music: MusicRepository,
    private val playlists: PlaylistRepository,
    private val podcasts: PodcastRepository,
    private val settings: SettingsRepository,
) {
    fun root(): MediaItem = folder(ROOT, "My Audio Hub", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(FOLDERS, "Folders", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            folder(ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
            folder(ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
            folder(SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            folder(PODCASTS, "Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
        )
        parentId == PLAYLISTS -> playlists.summaries.first().map {
            folder("$PLAYLIST${it.id}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, it.coverAlbumId?.let(MusicScanner::albumArtUri))
        }
        parentId == FOLDERS -> {
            val root = AudioFolders.normalize(settings.current().musicFolder)
            music.folders.first().filter { it.path != root }.map {
                folder("$FOLDER${it.path}", folderDisplayName(it.path, root), MediaMetadata.MEDIA_TYPE_PLAYLIST, it.coverAlbumId?.let(MusicScanner::albumArtUri))
            }
        }
        parentId == ALBUMS -> music.albums.first().map {
            folder("$ALBUM${it.albumId}", it.title, MediaMetadata.MEDIA_TYPE_ALBUM, MusicScanner.albumArtUri(it.albumId), subtitle = it.artist)
        }
        parentId == ARTISTS -> music.artists.first().map { folder("$ARTIST${it.name}", it.name, MediaMetadata.MEDIA_TYPE_ARTIST) }
        parentId == SONGS -> TrackSorter.sort(music.tracks.first(), settings.current().songSort).take(MAX_ITEMS).map { it.toMediaItem() }
        parentId == PODCASTS -> podcasts.summaries.first().map {
            folder("$SHOW${it.id}", it.title, MediaMetadata.MEDIA_TYPE_PODCAST, it.artworkUrl?.let(Uri::parse), subtitle = it.author)
        }
        parentId.startsWith(PLAYLIST) -> parentId.removePrefix(PLAYLIST).toLongOrNull()
            ?.let { id -> playlists.tracks(id).first().map { it.track.toMediaItem() } }.orEmpty()
        parentId.startsWith(FOLDER) -> TrackSorter.sort(music.folderTracks(parentId.removePrefix(FOLDER)).first(), settings.current().songSort)
            .map { it.toMediaItem() }
        parentId.startsWith(ALBUM) -> parentId.removePrefix(ALBUM).toLongOrNull()
            ?.let { id -> music.albumTracks(id).first().map { it.toMediaItem() } }.orEmpty()
        parentId.startsWith(ARTIST) -> music.artistTracks(parentId.removePrefix(ARTIST)).first().map { it.toMediaItem() }
        parentId.startsWith(SHOW) -> parentId.removePrefix(SHOW).toLongOrNull()?.let { id ->
            val show = podcasts.podcast(id).first() ?: return@let emptyList()
            podcasts.episodes(id).first().take(MAX_EPISODES).map { e ->
                podcastEpisodeItem(
                    "$EPISODE_ID_PREFIX${e.id}", e.title, show.title, e.enclosureUrl, e.localUri, e.artworkUrl ?: show.artworkUrl,
                )
            }
        }.orEmpty()
        else -> emptyList()
    }

    /** Voice search: matching songs (best first); an empty query means "play my music" (shuffled). */
    suspend fun search(query: String, limit: Int = 100): List<MediaItem> {
        val tracks = music.tracks.first()
        if (query.isBlank()) return tracks.shuffled().take(limit).map { it.toMediaItem() }
        val r = LibrarySearch.search(tracks, query, limit)
        val songs = r.songs.ifEmpty {
            // "play <artist>" / "play <album>": expand the best artist/album match.
            r.artists.firstOrNull()?.let { a -> tracks.filter { it.artist == a.name } }
                ?: r.albums.firstOrNull()?.let { al -> tracks.filter { it.albumId == al.albumId } }
                ?: emptyList()
        }
        return songs.take(limit).map { it.toMediaItem() }
    }

    /** Full, playable item for a bare media id (browsers send ids only). */
    suspend fun playable(mediaId: String): MediaItem? {
        trackIdOf(mediaId)?.let { id -> return music.track(id)?.toMediaItem() }
        episodeIdOf(mediaId)?.let { id -> return podcasts.episode(id)?.toMediaItem() }
        return null
    }

    /** Where a saved episode should resume, or null for anything else. */
    suspend fun resumePosition(mediaId: String): Long? =
        episodeIdOf(mediaId)?.let { podcasts.episode(it)?.resumePositionMs() }

    suspend fun item(mediaId: String): MediaItem? =
        if (mediaId == ROOT) root() else playable(mediaId) ?: folder(mediaId, mediaId.substringAfter(':'), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    private fun folder(id: String, title: String, type: Int, art: Uri? = null, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(art)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .build(),
            )
            .build()

    companion object {
        const val ROOT = "root"
        const val PLAYLISTS = "playlists"
        const val FOLDERS = "folders"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val SONGS = "songs"
        const val PODCASTS = "podcasts"
        const val PLAYLIST = "playlist:"
        const val FOLDER = "folder:"
        const val ALBUM = "album:"
        const val ARTIST = "artist:"
        const val SHOW = "show:"
        private const val MAX_ITEMS = 500
        private const val MAX_EPISODES = 50

        /** "Music/AudioHub/Rock/80s/" → "Rock › 80s". */
        fun folderDisplayName(path: String, root: String): String = AudioFolders.displayName(path, root)
    }
}
