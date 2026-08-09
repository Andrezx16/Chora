package com.craftworks.music.data.repository

import androidx.media3.common.MediaMetadata
import com.craftworks.music.data.datasource.LyricsDataSource
import com.craftworks.music.data.model.Lyric
import com.craftworks.music.data.model.LyricsResult
import com.craftworks.music.data.model.SyncType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRepositoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: LyricsRepository

    private fun lyric(startMs: Int) = Lyric(startMs = startMs, text = listOf("line $startMs"))

    private fun fakeSource(
        sourceName: String,
        result: List<Lyric>,
        syncType: SyncType = SyncType.NONE
    ): LyricsDataSource {
        val source = mockk<LyricsDataSource>(relaxed = true)
        every { source.name } returns sourceName
        coEvery { source.getLyrics(any(), any()) } returns LyricsResult(result, sourceName, syncType)
        return source
    }

    private fun radioMetadata(): MediaMetadata {
        return MediaMetadata.Builder()
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()
    }

    private fun normalMetadata(): MediaMetadata {
        return MediaMetadata.Builder()
            .setTitle("Test Song")
            .setArtist("Test Artist")
            .build()
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        LyricsState.lyrics.value = emptyList()
        LyricsState.loading.value = false
        LyricsState.currentResult.value = null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `first source with synced lyrics wins`() = runTest {
        val source1 = fakeSource("LrcLib", listOf(lyric(0), lyric(1000), lyric(2000)), SyncType.LINE)
        val source2 = fakeSource("Navidrome", listOf(lyric(0), lyric(500)), SyncType.LINE)

        repository = LyricsRepository(listOf(source1, source2))
        repository.getLyrics(normalMetadata())

        assertEquals(3, LyricsState.lyrics.value.size)
        assertEquals("LrcLib", LyricsState.currentResult.value?.provider)
        assertEquals(SyncType.LINE, LyricsState.currentResult.value?.syncType)
        coVerify(exactly = 1) { source1.getLyrics(any(), any()) }
        coVerify(exactly = 0) { source2.getLyrics(any(), any()) }
    }

    @Test
    fun `fallback to second source when first returns empty`() = runTest {
        val source1 = fakeSource("LrcLib", emptyList(), SyncType.NONE)
        val source2 = fakeSource("Navidrome", listOf(lyric(0), lyric(1000)), SyncType.LINE)

        repository = LyricsRepository(listOf(source1, source2))
        repository.getLyrics(normalMetadata())

        assertEquals(2, LyricsState.lyrics.value.size)
        assertEquals(1000, LyricsState.lyrics.value.last().startMs)
        assertEquals("Navidrome", LyricsState.currentResult.value?.provider)
        assertEquals(SyncType.LINE, LyricsState.currentResult.value?.syncType)
    }

    @Test
    fun `fallback to plain lyrics when no source has synced`() = runTest {
        val source1 = fakeSource("LrcLib", listOf(lyric(-1)), SyncType.NONE)
        val source2 = fakeSource("Navidrome", emptyList(), SyncType.NONE)

        repository = LyricsRepository(listOf(source1, source2))
        repository.getLyrics(normalMetadata())

        assertEquals(1, LyricsState.lyrics.value.size)
        assertEquals(-1, LyricsState.lyrics.value.first().startMs)
        assertEquals("LrcLib", LyricsState.currentResult.value?.provider)
        assertEquals(SyncType.NONE, LyricsState.currentResult.value?.syncType)
    }

    @Test
    fun `all sources empty results in empty lyrics`() = runTest {
        val source1 = fakeSource("LrcLib", emptyList(), SyncType.NONE)
        val source2 = fakeSource("Navidrome", emptyList(), SyncType.NONE)
        val source3 = fakeSource("NetEase", emptyList(), SyncType.NONE)

        repository = LyricsRepository(listOf(source1, source2, source3))
        repository.getLyrics(normalMetadata())

        assertTrue(LyricsState.lyrics.value.isEmpty())
        assertNull(LyricsState.currentResult.value)
    }

    @Test
    fun `source exception does not break other sources`() = runTest {
        val source1 = mockk<LyricsDataSource>(relaxed = true)
        every { source1.name } returns "LrcLib"
        coEvery { source1.getLyrics(any(), any()) } throws RuntimeException("network error")

        val source2 = fakeSource("Navidrome", listOf(lyric(0), lyric(1000)), SyncType.LINE)

        repository = LyricsRepository(listOf(source1, source2))
        repository.getLyrics(normalMetadata())

        assertEquals(2, LyricsState.lyrics.value.size)
        assertEquals("Navidrome", LyricsState.currentResult.value?.provider)
        assertEquals(SyncType.LINE, LyricsState.currentResult.value?.syncType)
        coVerify(exactly = 1) { source1.getLyrics(any(), any()) }
        coVerify(exactly = 1) { source2.getLyrics(any(), any()) }
    }

    @Test
    fun `all sources throwing exceptions results in empty lyrics`() = runTest {
        val source1 = mockk<LyricsDataSource>(relaxed = true)
        every { source1.name } returns "LrcLib"
        coEvery { source1.getLyrics(any(), any()) } throws RuntimeException("error")

        val source2 = mockk<LyricsDataSource>(relaxed = true)
        every { source2.name } returns "Navidrome"
        coEvery { source2.getLyrics(any(), any()) } throws RuntimeException("error")

        repository = LyricsRepository(listOf(source1, source2))
        repository.getLyrics(normalMetadata())

        assertTrue(LyricsState.lyrics.value.isEmpty())
        assertNull(LyricsState.currentResult.value)
    }

    @Test
    fun `priority order is respected`() = runTest {
        val source1 = fakeSource("LrcLib", listOf(lyric(0), lyric(1000)), SyncType.LINE)
        val source2 = fakeSource("Navidrome", listOf(lyric(0), lyric(500), lyric(1000)), SyncType.LINE)
        val source3 = fakeSource("NetEase", listOf(lyric(0), lyric(2000)), SyncType.LINE)

        repository = LyricsRepository(listOf(source1, source2, source3))
        repository.getLyrics(normalMetadata())

        assertEquals(2, LyricsState.lyrics.value.size)
        assertEquals(1000, LyricsState.lyrics.value.last().startMs)
        assertEquals("LrcLib", LyricsState.currentResult.value?.provider)
        coVerify(exactly = 0) { source2.getLyrics(any(), any()) }
        coVerify(exactly = 0) { source3.getLyrics(any(), any()) }
    }

    @Test
    fun `plain lyrics from first source used when second has synced`() = runTest {
        val source1 = fakeSource("LrcLib", listOf(lyric(-1)), SyncType.NONE)
        val source2 = fakeSource("Navidrome", listOf(lyric(0), lyric(1000)), SyncType.LINE)

        repository = LyricsRepository(listOf(source1, source2))
        repository.getLyrics(normalMetadata())

        assertEquals(2, LyricsState.lyrics.value.size)
        assertEquals(1000, LyricsState.lyrics.value.last().startMs)
        assertEquals("Navidrome", LyricsState.currentResult.value?.provider)
        assertEquals(SyncType.LINE, LyricsState.currentResult.value?.syncType)
    }

    @Test
    fun `radio station returns empty immediately`() = runTest {
        val source1 = fakeSource("LrcLib", listOf(lyric(0), lyric(1000)), SyncType.LINE)

        repository = LyricsRepository(listOf(source1))
        repository.getLyrics(radioMetadata())

        assertTrue(LyricsState.lyrics.value.isEmpty())
        assertNull(LyricsState.currentResult.value)
        coVerify(exactly = 0) { source1.getLyrics(any(), any()) }
    }

    @Test
    fun `single source works correctly`() = runTest {
        val source = fakeSource("LrcLib", listOf(lyric(0), lyric(1000), lyric(2000)), SyncType.LINE)

        repository = LyricsRepository(listOf(source))
        repository.getLyrics(normalMetadata())

        assertEquals(3, LyricsState.lyrics.value.size)
        assertEquals("LrcLib", LyricsState.currentResult.value?.provider)
        assertEquals(SyncType.LINE, LyricsState.currentResult.value?.syncType)
    }

    @Test
    fun `empty datasource list results in empty lyrics`() = runTest {
        repository = LyricsRepository(emptyList())
        repository.getLyrics(normalMetadata())

        assertTrue(LyricsState.lyrics.value.isEmpty())
        assertNull(LyricsState.currentResult.value)
    }

    @Test
    fun `word sync type is preserved`() = runTest {
        val source = fakeSource("LrcLib", listOf(lyric(0), lyric(1000)), SyncType.WORD)

        repository = LyricsRepository(listOf(source))
        repository.getLyrics(normalMetadata())

        assertEquals(2, LyricsState.lyrics.value.size)
        assertEquals("LrcLib", LyricsState.currentResult.value?.provider)
        assertEquals(SyncType.WORD, LyricsState.currentResult.value?.syncType)
    }

    @Test
    fun `none sync type plain fallback preserves provider`() = runTest {
        val source1 = fakeSource("LrcLib", emptyList(), SyncType.NONE)
        val source2 = fakeSource("NetEase", listOf(lyric(-1)), SyncType.NONE)

        repository = LyricsRepository(listOf(source1, source2))
        repository.getLyrics(normalMetadata())

        assertEquals(1, LyricsState.lyrics.value.size)
        assertEquals("NetEase", LyricsState.currentResult.value?.provider)
        assertEquals(SyncType.NONE, LyricsState.currentResult.value?.syncType)
    }
}
