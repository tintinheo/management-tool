package com.audiohub.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.audiohub.data.db.EpisodeWithPodcast
import com.audiohub.data.db.TrackEntity
import com.audiohub.data.music.MusicScanner
import com.audiohub.data.podcast.PodcastRepository

const val TRACK_ID_PREFIX = "track:"
const val EPISODE_ID_PREFIX = "episode:"
/** Episodes of shows that aren't followed: playable, but progress isn't saved. */
const val STREAM_ID_PREFIX = "stream:"

/** MediaStore id of a library track, or null for other media (podcast episodes, tests). */
fun trackIdOf(mediaId: String?): Long? =
    mediaId?.takeIf { it.startsWith(TRACK_ID_PREFIX) }?.removePrefix(TRACK_ID_PREFIX)?.toLongOrNull()

/** Database id of a saved podcast episode, or null. */
fun episodeIdOf(mediaId: String?): Long? =
    mediaId?.takeIf { it.startsWith(EPISODE_ID_PREFIX) }?.removePrefix(EPISODE_ID_PREFIX)?.toLongOrNull()

fun isPodcastMediaId(mediaId: String?): Boolean =
    mediaId != null && (mediaId.startsWith(EPISODE_ID_PREFIX) || mediaId.startsWith(STREAM_ID_PREFIX))

fun trackContentUri(trackId: Long): Uri =
    android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)

fun TrackEntity.toMediaItem(): MediaItem {
    val contentUri = Uri.parse(uri)
    return MediaItem.Builder()
        .setMediaId("$TRACK_ID_PREFIX$id")
        .setUri(contentUri)
        // Survives the controller -> session hand-off; see MediaItem.resolveForPlayback().
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(contentUri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setGenre(genre)
                .setTrackNumber(trackNumber.takeIf { it > 0 })
                .setDiscNumber(discNumber.takeIf { it > 0 })
                .setReleaseYear(year.takeIf { it > 0 })
                .setArtworkUri(MusicScanner.albumArtUri(albumId))
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
}

/** A podcast episode; plays the downloaded file when there is one, else streams the enclosure. */
fun podcastEpisodeItem(
    mediaId: String,
    title: String,
    showTitle: String,
    streamUrl: String,
    localUri: String?,
    artworkUrl: String?,
): MediaItem {
    val uri = Uri.parse(localUri ?: streamUrl)
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(showTitle)
                .setAlbumTitle(showTitle)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
}

fun EpisodeWithPodcast.toMediaItem(): MediaItem = podcastEpisodeItem(
    mediaId = "$EPISODE_ID_PREFIX${episode.id}",
    title = episode.title,
    showTitle = podcastTitle,
    streamUrl = episode.enclosureUrl,
    localUri = episode.localUri,
    artworkUrl = episode.artworkUrl ?: podcastArtworkUrl,
)

/** Where to resume: saved position unless finished or almost finished. */
fun EpisodeWithPodcast.resumePositionMs(): Long =
    if (episode.played || PodcastRepository.isNearEnd(episode.positionMs, episode.durationMs)) 0
    else episode.positionMs

/** Media3 strips local configuration when items cross from controller to session; restore the URI. */
fun MediaItem.resolveForPlayback(): MediaItem {
    if (localConfiguration != null) return this
    val uri = requestMetadata.mediaUri ?: return this
    return buildUpon().setUri(uri).build()
}
