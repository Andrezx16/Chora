package com.craftworks.music.data.datasource.betterlyrics

import android.util.Log
import androidx.media3.common.MediaMetadata
import com.craftworks.music.data.datasource.LyricsDataSource
import com.craftworks.music.data.model.Lyric
import com.craftworks.music.data.model.LyricsResult
import com.craftworks.music.data.model.SyncType
import com.craftworks.music.data.model.SyncedWord
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BetterLyricsResponse(
    val ttml: String? = null,
    val score: Int? = null
)

@Singleton
class BetterLyricsDataSource @Inject constructor() : LyricsDataSource {

    override val name: String = "BetterLyrics"

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
            logger = Logger.SIMPLE
        }
    }

    override suspend fun getLyrics(metadata: MediaMetadata?, ignoreCachedResponse: Boolean): LyricsResult {
        return try {
            fetchLyrics(metadata)
        } catch (e: Exception) {
            Log.e("BETTER_LYRICS", "Failed to fetch lyrics", e)
            LyricsResult(emptyList(), name, SyncType.NONE)
        }
    }

    private suspend fun fetchLyrics(metadata: MediaMetadata?): LyricsResult = withContext(Dispatchers.IO) {
        val title = metadata?.title?.toString()
            ?: return@withContext LyricsResult(emptyList(), name, SyncType.NONE)
        val artist = metadata.extras?.getString("lyricsArtist")
            ?: return@withContext LyricsResult(emptyList(), name, SyncType.NONE)
        val album = metadata.albumTitle
        val duration = metadata.durationMs?.let { it / 1000 }

        try {
            val response = client.get("https://lyrics-api.boidu.dev/getLyrics") {
                parameter("s", title)
                parameter("a", artist)
                album?.let { parameter("al", it) }
                duration?.let { parameter("d", it) }
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val body: BetterLyricsResponse = response.body()
                    val ttml = body.ttml
                    if (ttml.isNullOrBlank()) {
                        Log.d("BETTER_LYRICS", "Empty TTML response")
                        return@withContext LyricsResult(emptyList(), name, SyncType.NONE)
                    }
                    val lyrics = parseTTML(ttml)
                    val syncType = detectSyncType(lyrics)
                    Log.d("BETTER_LYRICS", "Got ${lyrics.size} lines, syncType=$syncType")
                    LyricsResult(lyrics, name, syncType)
                }
                HttpStatusCode.NotFound -> {
                    Log.d("BETTER_LYRICS", "Lyrics not found (404)")
                    LyricsResult(emptyList(), name, SyncType.NONE)
                }
                HttpStatusCode.Unauthorized -> {
                    Log.d("BETTER_LYRICS", "API key required (401)")
                    LyricsResult(emptyList(), name, SyncType.NONE)
                }
                else -> {
                    Log.d("BETTER_LYRICS", "HTTP ${response.status}")
                    LyricsResult(emptyList(), name, SyncType.NONE)
                }
            }
        } catch (e: Exception) {
            Log.e("BETTER_LYRICS", "Network error", e)
            LyricsResult(emptyList(), name, SyncType.NONE)
        }
    }

    internal fun parseTTML(ttml: String): List<Lyric> {
        val lyrics = mutableListOf<Lyric>()

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(ttml))

            var eventType = parser.eventType
            var timingMode: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.substringAfterLast(':') == "tt") {
                    timingMode = parser.getAttributeValue("http://music.apple.com/lyric-ttml-internal", "timing")
                    if (timingMode == null) {
                        for (i in 0 until parser.attributeCount) {
                            if (parser.getAttributeName(i).substringAfterLast(':') == "timing") {
                                timingMode = parser.getAttributeValue(i)
                                break
                            }
                        }
                    }
                    break
                }
                eventType = parser.next()
            }

            if (timingMode == "None") {
                parser.setInput(StringReader(ttml))
                eventType = parser.eventType
                val pTexts = mutableListOf<String>()
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name.substringAfterLast(':') == "p") {
                        val sb = StringBuilder()
                        var depth = 1
                        eventType = parser.next()
                        while (eventType != XmlPullParser.END_DOCUMENT && depth > 0) {
                            if (eventType == XmlPullParser.START_TAG) depth++
                            if (eventType == XmlPullParser.END_TAG) depth--
                            if (eventType == XmlPullParser.TEXT && depth == 1) {
                                sb.append(parser.text ?: "")
                            }
                            eventType = parser.next()
                        }
                        val text = sb.toString().trim()
                        if (text.isNotBlank()) pTexts.add(text)
                    } else {
                        eventType = parser.next()
                    }
                }
                if (pTexts.isNotEmpty()) {
                    return listOf(Lyric(startMs = -1, text = listOf(pTexts.joinToString("\n"))))
                }
                return emptyList()
            }

            parser.setInput(StringReader(ttml))
            eventType = parser.eventType
            var inP = false
            var inSpan = false
            var pBeginMs = 0
            var pEndMs = 0
            val currentWords = mutableListOf<SyncedWord>()
            val lineText = StringBuilder()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.substringAfterLast(':')
                        when (tag) {
                            "p" -> {
                                inP = true
                                inSpan = false
                                currentWords.clear()
                                lineText.clear()
                                pBeginMs = parseTime(parser.getAttributeValue(null, "begin"))
                                pEndMs = parseTime(parser.getAttributeValue(null, "end"))
                            }
                            "span" -> {
                                if (inP) {
                                    val spanBegin = parser.getAttributeValue(null, "begin")
                                    val spanEnd = parser.getAttributeValue(null, "end")
                                    if (spanBegin != null) {
                                        inSpan = true
                                        currentWords.add(
                                            SyncedWord(
                                                text = "",
                                                startMs = parseTime(spanBegin),
                                                endMs = spanEnd?.let { parseTime(it) }
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inP && !inSpan) {
                            lineText.append(parser.text ?: "")
                        } else if (inP && inSpan && currentWords.isNotEmpty()) {
                            val text = parser.text ?: ""
                            val lastIdx = currentWords.size - 1
                            val lastWord = currentWords[lastIdx]
                            currentWords[lastIdx] = lastWord.copy(text = lastWord.text + text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.substringAfterLast(':')
                        when (tag) {
                            "span" -> {
                                inSpan = false
                            }
                            "p" -> {
                                if (inP) {
                                    val text = if (currentWords.isNotEmpty()) {
                                        currentWords.joinToString(" ") { it.text }.trim()
                                    } else {
                                        lineText.toString().trim()
                                    }
                                    if (text.isNotBlank() || currentWords.isNotEmpty()) {
                                        lyrics.add(
                                            Lyric(
                                                startMs = pBeginMs,
                                                endMs = pEndMs,
                                                text = listOf(text),
                                                words = if (currentWords.isNotEmpty()) currentWords.toList() else null
                                            )
                                        )
                                    }
                                    inP = false
                                    inSpan = false
                                    currentWords.clear()
                                    lineText.clear()
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("BETTER_LYRICS", "TTML parse error", e)
        }

        return lyrics
    }

    private fun parseTime(timeStr: String?): Int {
        if (timeStr.isNullOrBlank()) return -1

        return try {
            val parts = timeStr.split(":")
            when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toDouble()
                    ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toInt()
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toDouble()
                    (minutes * 60 * 1000 + seconds * 1000).toInt()
                }
                1 -> {
                    (parts[0].toDouble() * 1000).toInt()
                }
                else -> -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun detectSyncType(lyrics: List<Lyric>): SyncType {
        if (lyrics.isEmpty()) return SyncType.NONE

        val hasWords = lyrics.any { !it.words.isNullOrEmpty() }
        if (hasWords) return SyncType.WORD

        val hasTimestamps = lyrics.any { it.startMs >= 0 }
        if (hasTimestamps) return SyncType.LINE

        return SyncType.NONE
    }

    internal fun detectSyncTypePublic(lyrics: List<Lyric>): SyncType = detectSyncType(lyrics)
}
