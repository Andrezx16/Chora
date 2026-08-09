package com.craftworks.music.data.datasource.lrclib

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaMetadata
import com.craftworks.music.data.datasource.LyricsDataSource
import com.craftworks.music.data.model.LrcLibLyrics
import com.craftworks.music.data.model.LrcLibSearchResult
import com.craftworks.music.data.model.Lyric
import com.craftworks.music.data.model.LyricsResult
import com.craftworks.music.data.model.SyncType
import com.craftworks.music.data.model.toLyrics
import com.craftworks.music.managers.settings.MediaProviderSettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LrclibDataSource @Inject constructor(
    private val settingsManager: MediaProviderSettingsManager,
    @ApplicationContext context: Context
) : LyricsDataSource {

    override val name: String = "LrcLib"

    override suspend fun getLyrics(metadata: MediaMetadata?, ignoreCachedResponse: Boolean): LyricsResult {
        val enabled = settingsManager.lrcLibLyricsFlow.first()
        if (!enabled) return LyricsResult(emptyList(), name, SyncType.NONE)
        return getLrcLibLyrics(metadata, ignoreCachedResponse)
    }

    @OptIn(InternalAPI::class)
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }

        install(HttpCache) {
            val cacheDir = File(context.cacheDir, "lrclib_http_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            publicStorage(FileStorage(cacheDir))
        }

        install(Logging) {
            level = LogLevel.INFO
            logger = Logger.SIMPLE
        }

        expectSuccess = true
    }

    suspend fun getLrcLibLyrics(metadata: MediaMetadata?, ignoreCachedResponse: Boolean = false): LyricsResult = withContext(Dispatchers.IO) {
        val baseUrl = settingsManager.lrcLibEndpointFlow.first()
        val artist = metadata?.extras?.getString("lyricsArtist")
        val title = metadata?.title?.toString()
        val album = metadata?.albumTitle?.toString()
        val duration = metadata?.durationMs?.let { (it / 1000).toInt() }

        if (title.isNullOrBlank() || artist.isNullOrBlank()) {
            return@withContext LyricsResult(emptyList(), name, SyncType.NONE)
        }

        // Stage 1: exact match with all parameters
        val exactResult = tryGetExact(baseUrl, artist, title, album, duration, ignoreCachedResponse)
        if (exactResult != null) {
            Log.d("LRCLIB", "Stage 1: exact match found")
            return@withContext exactResult
        }

        // Stage 2: same complete artist, no album/duration
        val relaxedResult = tryGetRelaxed(baseUrl, artist, title, ignoreCachedResponse)
        if (relaxedResult != null) {
            Log.d("LRCLIB", "Stage 2: relaxed match (no album/duration)")
            return@withContext relaxedResult
        }

        // Stage 3: search endpoint - title + artist, evaluate candidates with scoring
        val searchResult = trySearch(baseUrl, title, artist, album, duration, ignoreCachedResponse)
        if (searchResult != null) {
            Log.d("LRCLIB", "Stage 3: search match found")
            return@withContext searchResult
        }

        Log.d("LRCLIB", "No lyrics found after all stages")
        return@withContext LyricsResult(emptyList(), name, SyncType.NONE)
    }

    private suspend fun tryGetExact(
        baseUrl: String,
        artist: String,
        title: String,
        album: String?,
        duration: Int?,
        ignoreCachedResponse: Boolean
    ): LyricsResult? {
        return try {
            val response = client.get(baseUrl) {
                url { appendPathSegments("api", "get") }
                parameter("artist_name", artist)
                parameter("track_name", title)
                parameter("album_name", album)
                parameter("duration", duration)
                header(HttpHeaders.UserAgent, USER_AGENT)
                cacheControlHeader(ignoreCachedResponse)
            }
            val lyrics: LrcLibLyrics = response.body()
            if (lyrics.instrumental || lyrics.isLyricsEmpty()) null
            else LyricsResult(lyrics.toLyrics(), name, detectSyncType(lyrics))
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) null
            else { e.printStackTrace(); null }
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    private suspend fun tryGetRelaxed(
        baseUrl: String,
        artist: String,
        title: String,
        ignoreCachedResponse: Boolean
    ): LyricsResult? {
        return try {
            val response = client.get(baseUrl) {
                url { appendPathSegments("api", "get") }
                parameter("artist_name", artist)
                parameter("track_name", title)
                header(HttpHeaders.UserAgent, USER_AGENT)
                cacheControlHeader(ignoreCachedResponse)
            }
            val lyrics: LrcLibLyrics = response.body()
            if (lyrics.instrumental || lyrics.isLyricsEmpty()) null
            else LyricsResult(lyrics.toLyrics(), name, detectSyncType(lyrics))
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) null
            else { e.printStackTrace(); null }
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    private suspend fun trySearch(
        baseUrl: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int?,
        ignoreCachedResponse: Boolean
    ): LyricsResult? {
        return try {
            val response = client.get(baseUrl) {
                url { appendPathSegments("api", "search") }
                parameter("track_name", title)
                parameter("artist_name", artist)
                header(HttpHeaders.UserAgent, USER_AGENT)
                cacheControlHeader(ignoreCachedResponse)
            }
            val candidates: List<LrcLibSearchResult> = response.body()
            val best = pickBestCandidate(candidates, title, artist, album, duration) ?: return null
            Log.d("LRCLIB", "Search best: '${best.trackName}' by '${best.artistName}' (score computed)")
            if (best.instrumental || best.isLyricsEmpty()) null
            else LyricsResult(best.toLyrics(), name, detectSyncType(best))
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) null
            else { e.printStackTrace(); null }
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    // --- Matching logic (internal for testing) ---

    internal fun artistVariants(artist: String): List<String> {
        val variants = linkedSetOf<String>()
        variants.add(artist)
        artist.split(",", "&", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { variants.add(it) }
        return variants.toList()
    }

    internal fun scoreCandidate(
        candidate: LrcLibSearchResult,
        queryTitle: String,
        queryArtist: String,
        queryAlbum: String?,
        queryDuration: Int?
    ): Double {
        var score = 0.0

        // Title: +0.40
        if (candidate.trackName.equals(queryTitle, ignoreCase = true)) {
            score += 0.40
        }

        // Artist: +0.35
        val candLower = candidate.artistName.lowercase()
        val queryLower = queryArtist.lowercase()
        if (candLower == queryLower
            || candLower.contains(queryLower)
            || queryLower.contains(candLower)
        ) {
            score += 0.35
        } else {
            // Check individual parts
            val candParts = candLower.split(",", "&", ";").map { it.trim() }
            val queryParts = queryLower.split(",", "&", ";").map { it.trim() }
            if (candParts.any { c -> queryParts.any { q -> c.contains(q) || q.contains(c) } }) {
                score += 0.25
            }
        }

        // Duration: +0.15 (within 30s) or +0.05 (within 60s)
        if (queryDuration != null && candidate.duration > 0) {
            val diff = kotlin.math.abs(queryDuration - candidate.duration)
            when {
                diff <= 5 -> score += 0.15
                diff <= 30 -> score += 0.10
                diff <= 60 -> score += 0.05
            }
        } else {
            // No duration available: give partial credit
            score += 0.05
        }

        // Album: +0.10
        if (queryAlbum != null && candidate.albumName.isNotBlank()) {
            if (candidate.albumName.equals(queryAlbum, ignoreCase = true)) {
                score += 0.10
            } else if (candidate.albumName.lowercase().contains(queryAlbum.lowercase())
                || queryAlbum.lowercase().contains(candidate.albumName.lowercase())
            ) {
                score += 0.05
            }
        } else {
            // No album to compare: give partial credit
            score += 0.05
        }

        return score
    }

    internal fun pickBestCandidate(
        candidates: List<LrcLibSearchResult>,
        title: String,
        artist: String,
        album: String?,
        duration: Int?
    ): LrcLibSearchResult? {
        if (candidates.isEmpty()) return null

        return candidates
            .filter { !it.instrumental && !it.isLyricsEmpty() }
            .map { it to scoreCandidate(it, title, artist, album, duration) }
            .filter { it.second >= 0.5 }
            .maxByOrNull { it.second }
            ?.first
    }

    // --- Helpers ---

    private fun detectSyncType(lyrics: LrcLibLyrics): SyncType = when {
        lyrics.instrumental -> SyncType.NONE
        !lyrics.lyricsfile.isNullOrBlank() -> SyncType.WORD
        !lyrics.syncedLyrics.isNullOrBlank() -> SyncType.LINE
        else -> SyncType.NONE
    }

    private fun detectSyncType(lyrics: LrcLibSearchResult): SyncType = when {
        lyrics.instrumental -> SyncType.NONE
        !lyrics.lyricsfile.isNullOrBlank() -> SyncType.WORD
        !lyrics.syncedLyrics.isNullOrBlank() -> SyncType.LINE
        else -> SyncType.NONE
    }

    private fun LrcLibLyrics.isLyricsEmpty(): Boolean {
        return plainLyrics.isNullOrBlank()
            && syncedLyrics.isNullOrBlank()
            && lyricsfile.isNullOrBlank()
    }

    private fun LrcLibSearchResult.isLyricsEmpty(): Boolean {
        return plainLyrics.isNullOrBlank()
            && syncedLyrics.isNullOrBlank()
            && lyricsfile.isNullOrBlank()
    }

    private fun LrcLibSearchResult.toLyrics(): List<Lyric> {
        val temp = LrcLibLyrics(
            id = id,
            instrumental = instrumental,
            plainLyrics = plainLyrics,
            syncedLyrics = syncedLyrics,
            lyricsfile = lyricsfile
        )
        return temp.toLyrics()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.cacheControlHeader(ignoreCached: Boolean) {
        if (ignoreCached)
            header(HttpHeaders.CacheControl, "no-cache")
        else
            header(HttpHeaders.CacheControl, "max-stale=2592000")
    }

    companion object {
        private const val USER_AGENT = "Chora - Navidrome Client (https://github.com/CraftWorksMC/Chora)"
    }
}
