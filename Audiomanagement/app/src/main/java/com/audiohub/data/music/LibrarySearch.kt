package com.audiohub.data.music

import com.audiohub.data.SearchText
import com.audiohub.data.db.AlbumSummary
import com.audiohub.data.db.ArtistSummary
import com.audiohub.data.db.TrackEntity

data class SearchResults(
    val songs: List<TrackEntity>,
    val albums: List<AlbumSummary>,
    val artists: List<ArtistSummary>,
) {
    val isEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()

    companion object {
        val EMPTY = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/** In-memory, accent-insensitive search over the library (a few thousand tracks is instant). */
object LibrarySearch {

    fun search(tracks: List<TrackEntity>, query: String, limit: Int = 50): SearchResults {
        if (SearchText.normalize(query).isEmpty()) return SearchResults.EMPTY

        val songs = tracks.filter { SearchText.matches(it.searchText.ifEmpty { keyOf(it) }, query) }

        val albums = tracks.groupBy { it.albumId }
            .mapNotNull { (albumId, list) ->
                val first = list.first()
                val artist = first.albumArtist ?: first.artist
                if (!SearchText.matches(SearchText.of(first.album, artist), query)) return@mapNotNull null
                AlbumSummary(albumId, first.album, artist, list.maxOf { it.year }, list.size)
            }
            .sortedBy { it.title.lowercase() }

        val artists = tracks.groupBy { it.artist }
            .filterKeys { SearchText.matches(SearchText.normalize(it), query) }
            .map { (name, list) -> ArtistSummary(name, list.distinctBy { it.albumId }.size, list.size) }
            .sortedBy { it.name.lowercase() }

        return SearchResults(songs.take(limit), albums.take(limit), artists.take(limit))
    }

    private fun keyOf(t: TrackEntity) = SearchText.of(t.title, t.artist, t.album, t.albumArtist)
}
