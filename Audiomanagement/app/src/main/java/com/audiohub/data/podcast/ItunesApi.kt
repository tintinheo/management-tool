package com.audiohub.data.podcast

import android.net.Uri
import org.json.JSONObject

data class PodcastSearchResult(
    val itunesId: Long,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    /** Missing for Top Charts entries; resolve with [ItunesApi.lookup]. */
    val feedUrl: String?,
    val genre: String?,
    val episodeCount: Int,
)

/** Apple's public, key-less catalog endpoints (iTunes Search API + marketing RSS charts). */
class ItunesApi(private val http: HttpClient) {

    suspend fun search(term: String, country: String, limit: Int = 30): List<PodcastSearchResult> {
        val url = Uri.parse("https://itunes.apple.com/search").buildUpon()
            .appendQueryParameter("media", "podcast")
            .appendQueryParameter("entity", "podcast")
            .appendQueryParameter("term", term)
            .appendQueryParameter("country", country.lowercase())
            .appendQueryParameter("limit", limit.toString())
            .build().toString()
        return ItunesJson.parseSearch(http.getText(url))
    }

    suspend fun lookup(itunesId: Long, country: String): PodcastSearchResult? {
        val url = "https://itunes.apple.com/lookup?entity=podcast&id=$itunesId&country=${country.lowercase()}"
        return ItunesJson.parseSearch(http.getText(url)).firstOrNull()
    }

    suspend fun topCharts(country: String, limit: Int = 50): List<PodcastSearchResult> {
        val url = "https://rss.marketingtools.apple.com/api/v2/${country.lowercase()}/podcasts/top/$limit/podcasts.json"
        return ItunesJson.parseTopCharts(http.getText(url))
    }
}

object ItunesJson {

    fun parseSearch(json: String): List<PodcastSearchResult> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("kind") != "podcast" && o.optString("wrapperType") != "track") return@mapNotNull null
            val id = o.optLong("collectionId").takeIf { it > 0 } ?: return@mapNotNull null
            PodcastSearchResult(
                itunesId = id,
                title = o.optString("collectionName").ifEmpty { o.optString("trackName") },
                author = o.optString("artistName"),
                artworkUrl = o.optString("artworkUrl600").ifEmpty { o.optString("artworkUrl100") }.ifEmpty { null },
                feedUrl = o.optString("feedUrl").ifEmpty { null },
                genre = o.optString("primaryGenreName").ifEmpty { null },
                episodeCount = o.optInt("trackCount"),
            )
        }
    }

    fun parseTopCharts(json: String): List<PodcastSearchResult> {
        val results = JSONObject(json).optJSONObject("feed")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").toLongOrNull() ?: return@mapNotNull null
            PodcastSearchResult(
                itunesId = id,
                title = o.optString("name"),
                author = o.optString("artistName"),
                // Charts only ship 100px art; Apple's image CDN serves any size from the same path.
                artworkUrl = o.optString("artworkUrl100").ifEmpty { null }?.replace("100x100bb", "600x600bb"),
                feedUrl = null,
                genre = o.optJSONArray("genres")?.optJSONObject(0)?.optString("name")?.ifEmpty { null },
                episodeCount = 0,
            )
        }
    }
}
