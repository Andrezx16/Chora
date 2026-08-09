package com.craftworks.music.data.repository

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaMetadata
import com.craftworks.music.data.datasource.LyricsDataSource
import com.craftworks.music.data.model.Lyric
import com.craftworks.music.data.model.LyricsResult
import com.craftworks.music.data.model.SyncType
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

object LyricsState {
    val lyrics = MutableStateFlow<List<Lyric>>(emptyList())
    val loading = MutableStateFlow(false)
    var currentResult = MutableStateFlow<LyricsResult?>(null)
    var open = mutableStateOf(false)
    var useLrcLib by mutableStateOf(true)
    var useNetEase by mutableStateOf(false)
}

@Singleton
class LyricsRepository @Inject constructor(
    private val lyricsDataSources: List<@JvmSuppressWildcards LyricsDataSource>
) {
    private var lyricsFetchJob: Job? = null

    suspend fun getLyrics(metadata: MediaMetadata?, ignoreCachedResponse: Boolean = false) {
        if (metadata?.mediaType == MediaMetadata.MEDIA_TYPE_RADIO_STATION) {
            LyricsState.lyrics.value = listOf()
            LyricsState.currentResult.value = null
            return
        }

        lyricsFetchJob?.cancel()

        coroutineScope {
            lyricsFetchJob = launch {
                LyricsState.loading.value = true

                var fallback: LyricsResult? = null

                for (source in lyricsDataSources) {
                    val result = try {
                        source.getLyrics(metadata, ignoreCachedResponse)
                    } catch (e: Exception) {
                        Log.e("LYRICS", "${source.name} failed", e)
                        LyricsResult(emptyList(), source.name, SyncType.NONE)
                    }

                    if (result.syncType != SyncType.NONE && result.lyrics.isNotEmpty()) {
                        Log.d("LYRICS", "Using ${result.provider} ${result.syncType} Lyrics")
                        LyricsState.lyrics.value = result.lyrics
                        LyricsState.currentResult.value = result
                        LyricsState.loading.value = false
                        return@launch
                    }

                    if (result.lyrics.isNotEmpty() && fallback == null) {
                        fallback = result
                    }
                }

                if (fallback != null) {
                    Log.d("LYRICS", "Using plain lyrics fallback")
                    LyricsState.lyrics.value = fallback!!.lyrics
                    LyricsState.currentResult.value = fallback
                } else {
                    Log.d("LYRICS", "No lyrics found.")
                    LyricsState.lyrics.value = listOf()
                    LyricsState.currentResult.value = null
                }

                LyricsState.loading.value = false
            }
        }
    }
}
