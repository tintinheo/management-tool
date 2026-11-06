package com.audiohub.ui

import android.net.Uri

object Routes {
    // Tabs
    const val LIBRARY = "library"
    const val PODCASTS = "podcasts"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    // Library lists
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val GENRES = "genres"
    const val PLAYLISTS = "playlists"
    const val FAVORITES = "favorites"
    const val MOST_PLAYED = "most_played"
    const val RECENTLY_PLAYED = "recently_played"
    const val DOWNLOADS = "downloads"
    const val EQUALIZER = "equalizer"
    const val DIAGNOSTICS = "diagnostics"
    /** Full-screen playing queue (pushed on the current tab, so tabs stay usable). */
    const val QUEUE = "queue"
    /** Podcast "test your memory" page for one episode, and the list of all notes. */
    const val RECALL = "recall/{episodeId}"
    fun recall(episodeId: Long) = "recall/$episodeId"
    const val NOTES = "podcast_notes"
    /** Advanced: YouTube to audio; optional prefilled link (from a share). */
    const val YOUTUBE = "youtube?url={url}"
    fun youtube(url: String = "") = "youtube?url=${Uri.encode(url)}"

    // Details
    const val ALBUM = "album/{albumId}"
    const val ARTIST = "artist/{name}"
    const val GENRE = "genre/{name}"
    const val PLAYLIST = "playlist/{playlistId}"
    /** Folder playlists use a query parameter because paths contain slashes. */
    const val FOLDER = "folder?path={path}"

    /** A podcast show: by saved id, by feed URL (preview/follow), or by Apple id (resolved via lookup). */
    const val SHOW = "show?id={id}&feed={feed}&itunes={itunes}"

    fun show(podcastId: Long? = null, feedUrl: String? = null, itunesId: Long? = null) =
        "show?id=${podcastId ?: -1}&feed=${Uri.encode(feedUrl.orEmpty())}&itunes=${itunesId ?: -1}"

    fun album(albumId: Long) = "album/$albumId"
    fun artist(name: String) = "artist/${Uri.encode(name)}"
    fun genre(name: String) = "genre/${Uri.encode(name)}"
    fun playlist(playlistId: Long) = "playlist/$playlistId"
    fun folder(path: String) = "folder?path=${Uri.encode(path)}"

    const val RINGTONE = "ringtone/{trackId}"
    fun ringtone(trackId: Long) = "ringtone/$trackId"

    val TABS = setOf(LIBRARY, PODCASTS, SEARCH, SETTINGS)

    private val linkInText = Regex("""(?i)\b(?:https?|feed|pcast|itpc)://\S+""")

    /**
     * Screen for a shared/opened link: Apple Podcasts show links and RSS/feed URLs open the show.
     * Shared text often wraps the link ("Listen to X: https://…"), so the first link is used.
     */
    fun forSharedText(text: String?): String? {
        val link = text?.let { linkInText.find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') } ?: return null
        // YouTube links shared to the app open the (hidden) YouTube-to-audio screen, prefilled.
        if (com.audiohub.data.youtube.YoutubeLinks.videoId(link) != null) return youtube(link)
        return when (val input = com.audiohub.data.podcast.ApplePodcastLinks.classify(link)) {
            is com.audiohub.data.podcast.PodcastInput.Apple -> show(itunesId = input.itunesId)
            is com.audiohub.data.podcast.PodcastInput.Feed ->
                if (link.contains("apple.com", ignoreCase = true)) null else show(feedUrl = input.url)
            else -> null
        }
    }
}
