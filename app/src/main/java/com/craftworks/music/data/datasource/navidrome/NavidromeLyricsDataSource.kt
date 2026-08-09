package com.craftworks.music.data.datasource.navidrome

import android.util.Log
import androidx.media3.common.MediaMetadata
import com.craftworks.music.data.datasource.LyricsDataSource
import com.craftworks.music.data.model.LyricsResult
import com.craftworks.music.data.model.SyncType
import com.craftworks.music.managers.NavidromeManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavidromeLyricsDataSource @Inject constructor(
    private val navidromeDataSource: NavidromeDataSource
) : LyricsDataSource {

    override val name: String = "Navidrome"

    override suspend fun getLyrics(metadata: MediaMetadata?, ignoreCachedResponse: Boolean): LyricsResult {
        val isLocal = metadata?.extras?.getString("navidromeID")?.startsWith("Local_") ?: false
        if (!NavidromeManager.checkActiveServers() || isLocal) return LyricsResult(emptyList(), name, SyncType.NONE)

        val songId = metadata?.extras?.getString("navidromeID") ?: return LyricsResult(emptyList(), name, SyncType.NONE)

        val synced = try {
            navidromeDataSource.getNavidromeSyncedLyrics(songId, ignoreCachedResponse)
        } catch (e: Exception) {
            Log.e("LYRICS", "Navidrome synced lyrics error", e)
            emptyList()
        }

        if (synced.size > 1) {
            Log.d("LYRICS", "Got Navidrome synced lyrics")
            val syncType = if (synced.any { it.startMs >= 0 }) SyncType.LINE else SyncType.NONE
            return LyricsResult(synced, name, syncType)
        }

        val plain = try {
            navidromeDataSource.getNavidromePlainLyrics(metadata, ignoreCachedResponse)
        } catch (e: Exception) {
            Log.e("LYRICS", "Navidrome plain lyrics error", e)
            emptyList()
        }

        if (plain.isNotEmpty()) {
            Log.d("LYRICS", "Using Navidrome Plain Lyrics")
            return LyricsResult(plain, name, SyncType.NONE)
        }

        return LyricsResult(emptyList(), name, SyncType.NONE)
    }
}
