package com.audiohub.ui.podcasts

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.audiohub.data.db.EpisodeEntity
import com.audiohub.data.db.EpisodeFeedData
import com.audiohub.data.db.EpisodeWithPodcast
import com.audiohub.data.podcast.DownloadState
import com.audiohub.data.podcast.PodcastRepository
import com.audiohub.playback.EPISODE_ID_PREFIX
import com.audiohub.playback.STREAM_ID_PREFIX
import com.audiohub.playback.podcastEpisodeItem

/** One episode as shown in lists, whether saved (followed show) or from a feed preview. */
data class EpisodeUi(
    /** null for episodes of shows that aren't followed (preview): playable, progress not saved. */
    val episodeId: Long?,
    val title: String,
    val showTitle: String,
    val description: String,
    val pubDate: Long,
    val durationMs: Long,
    val positionMs: Long,
    val played: Boolean,
    val enclosureUrl: String,
    val localUri: String?,
    val artworkUrl: String?,
    val downloadState: Int = DownloadState.NONE,
) {
    val key: String get() = episodeId?.toString() ?: enclosureUrl
    val mediaId: String get() = episodeId?.let { "$EPISODE_ID_PREFIX$it" } ?: "$STREAM_ID_PREFIX$enclosureUrl"
    val inProgress: Boolean get() = !played && positionMs > 0
    /** A file the user put in the podcast folder (not downloadable/deletable by the app). */
    val isLocalFile: Boolean get() = enclosureUrl.startsWith("content://")
    val isDownloaded: Boolean get() = localUri != null && !isLocalFile
    val resumePositionMs: Long
        get() = if (played || PodcastRepository.isNearEnd(positionMs, durationMs)) 0 else positionMs

    fun toMediaItem() = podcastEpisodeItem(mediaId, title, showTitle, enclosureUrl, localUri, artworkUrl)
}

fun EpisodeWithPodcast.toUi() = episode.toUi(podcastTitle, podcastArtworkUrl)

fun EpisodeEntity.toUi(showTitle: String, showArtwork: String?) = EpisodeUi(
    episodeId = id, title = title, showTitle = showTitle, description = description, pubDate = pubDate,
    durationMs = durationMs, positionMs = positionMs, played = played, enclosureUrl = enclosureUrl,
    localUri = localUri, artworkUrl = artworkUrl ?: showArtwork, downloadState = downloadState,
)

fun EpisodeFeedData.toPreviewUi(showTitle: String, showArtwork: String?) = EpisodeUi(
    episodeId = null, title = title, showTitle = showTitle, description = description, pubDate = pubDate,
    durationMs = durationMs, positionMs = 0, played = false, enclosureUrl = enclosureUrl, localUri = null,
    artworkUrl = artworkUrl ?: showArtwork,
)

/** Everything an episode list can ask for. */
interface PodcastActions {
    fun play(episode: EpisodeUi)
    fun playNext(episode: EpisodeUi)
    fun addToQueue(episode: EpisodeUi)
    fun setPlayed(episode: EpisodeUi, played: Boolean)
    fun download(episode: EpisodeUi)
    fun cancelDownload(episode: EpisodeUi)
    fun removeDownload(episode: EpisodeUi)
    fun navigate(route: String)
    fun back()

    object None : PodcastActions {
        override fun play(episode: EpisodeUi) = Unit
        override fun playNext(episode: EpisodeUi) = Unit
        override fun addToQueue(episode: EpisodeUi) = Unit
        override fun setPlayed(episode: EpisodeUi, played: Boolean) = Unit
        override fun download(episode: EpisodeUi) = Unit
        override fun cancelDownload(episode: EpisodeUi) = Unit
        override fun removeDownload(episode: EpisodeUi) = Unit
        override fun navigate(route: String) = Unit
        override fun back() = Unit
    }
}

val LocalPodcastActions = staticCompositionLocalOf<PodcastActions> { PodcastActions.None }

/** Live download progress (0..1) by episode id. */
val LocalDownloadProgress = compositionLocalOf<Map<Long, Float>> { emptyMap() }
