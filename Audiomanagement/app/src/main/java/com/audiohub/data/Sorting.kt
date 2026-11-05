package com.audiohub.data

import com.audiohub.data.db.TrackEntity

/** What a list can be ordered by (same options for songs and podcast episodes). */
enum class SortField(val label: String) {
    NAME("Name"),
    TITLE("Title"),
    ALBUM("Album"),
    ARTIST("Artist"),
    DATE_ADDED("Date added"),
    DATE_MODIFIED("Date modified");

    val isDate get() = this == DATE_ADDED || this == DATE_MODIFIED
}

data class SortOrder(val field: SortField, val descending: Boolean = field.isDate) {
    fun encode() = "${field.name}:${if (descending) "desc" else "asc"}"

    /** Picking the active field flips direction; a new field starts in its natural direction. */
    fun select(newField: SortField) = if (newField == field) copy(descending = !descending) else SortOrder(newField)

    companion object {
        fun decode(raw: String?, default: SortOrder): SortOrder {
            val parts = raw?.split(':') ?: return default
            val field = parts.getOrNull(0)?.let { n -> SortField.entries.firstOrNull { it.name == n } } ?: return default
            return SortOrder(field, parts.getOrNull(1) == "desc")
        }
    }
}

/**
 * Accent-insensitive "natural" sort key: "Track 2" < "Track 10", "Đà Lạt" sorts with "Da Lat".
 * Normalising is the expensive part, so keys are built once per value (see [KeyCache]).
 */
class NaturalKey private constructor(private val chunks: List<String>) : Comparable<NaturalKey> {
    override fun compareTo(other: NaturalKey): Int {
        val ca = chunks
        val cb = other.chunks
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                x.compareTo(y)
            }
            if (c != 0) return c
        }
        return ca.size - cb.size
    }

    companion object {
        private val chunk = Regex("\\d+|\\D+")
        fun of(text: String) = NaturalKey(chunk.findAll(SearchText.normalize(text)).map { it.value }.toList())
    }
}

object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int = NaturalKey.of(a).compareTo(NaturalKey.of(b))
}

/** Memoises sort keys for one sort call (a 5 000-song sort otherwise normalises ~120 000 strings). */
private class KeyCache {
    private val keys = HashMap<String, NaturalKey>()
    fun key(s: String): NaturalKey = keys.getOrPut(s) { NaturalKey.of(s) }
}

private fun <T> textThen(cache: KeyCache, key: (T) -> String, tie: Comparator<T>): Comparator<T> =
    Comparator<T> { a, b -> cache.key(key(a)).compareTo(cache.key(key(b))) }.then(tie)

object TrackSorter {
    fun comparator(order: SortOrder): Comparator<TrackEntity> {
        val cache = KeyCache()
        val byTitle = Comparator<TrackEntity> { a, b -> cache.key(a.title).compareTo(cache.key(b.title)) }
        val inAlbum = compareBy<TrackEntity>({ it.discNumber }, { it.trackNumber }).then(byTitle)
        val base: Comparator<TrackEntity> = when (order.field) {
            SortField.NAME -> textThen(cache, { it.displayName }, byTitle)
            SortField.TITLE -> textThen(cache, { it.title }, Comparator { a, b -> cache.key(a.artist).compareTo(cache.key(b.artist)) })
            SortField.ALBUM -> textThen(cache, { it.album }, inAlbum)
            SortField.ARTIST -> textThen(cache, { it.albumArtist ?: it.artist }, textThen(cache, { it.album }, inAlbum))
            SortField.DATE_ADDED -> compareBy<TrackEntity> { it.dateAddedSec }.then(byTitle)
            SortField.DATE_MODIFIED -> compareBy<TrackEntity> { it.dateModifiedSec }.then(byTitle)
        }
        return if (order.descending) base.reversed() else base
    }

    fun sort(tracks: List<TrackEntity>, order: SortOrder): List<TrackEntity> = tracks.sortedWith(comparator(order))
}

/** Fields an episode exposes for sorting (kept separate from UI classes so it can be unit-tested). */
interface SortableEpisode {
    val sortName: String
    val title: String
    val showTitle: String
    val showAuthor: String
    val pubDate: Long
    val modifiedAt: Long
}

object EpisodeSorter {
    fun <T : SortableEpisode> sort(episodes: List<T>, order: SortOrder): List<T> {
        val cache = KeyCache()
        val byTitle = Comparator<T> { a, b -> cache.key(a.title).compareTo(cache.key(b.title)) }
        val newest = compareByDescending<T> { it.pubDate }
        val base: Comparator<T> = when (order.field) {
            SortField.NAME -> textThen(cache, { it.sortName }, byTitle)
            SortField.TITLE -> byTitle.then(newest)
            SortField.ALBUM -> textThen(cache, { it.showTitle }, newest)
            SortField.ARTIST -> textThen(cache, { it.showAuthor }, textThen(cache, { it.showTitle }, newest))
            SortField.DATE_ADDED -> compareBy<T> { it.pubDate }.then(byTitle)
            SortField.DATE_MODIFIED -> compareBy<T> { if (it.modifiedAt > 0) it.modifiedAt else it.pubDate }.then(byTitle)
        }
        return episodes.sortedWith(if (order.descending) base.reversed() else base)
    }
}
