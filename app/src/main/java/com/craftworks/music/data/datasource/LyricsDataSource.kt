package com.craftworks.music.data.datasource

import androidx.media3.common.MediaMetadata
import com.craftworks.music.data.model.LyricsResult

interface LyricsDataSource {
    val name: String
    suspend fun getLyrics(metadata: MediaMetadata?, ignoreCachedResponse: Boolean): LyricsResult
}
