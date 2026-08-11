package com.craftworks.music.data.datasource.lrclib

import com.craftworks.music.data.model.LrcLibSearchResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LrclibDataSourceTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var dataSource: LrclibDataSource

    private fun searchResult(
        trackName: String = "",
        artistName: String = "",
        albumName: String = "",
        duration: Double = 0.0,
        instrumental: Boolean = false,
        plainLyrics: String? = "Some lyrics",
        syncedLyrics: String? = null,
        lyricsfile: String? = null
    ) = LrcLibSearchResult(
        id = 1,
        trackName = trackName,
        artistName = artistName,
        albumName = albumName,
        duration = duration,
        instrumental = instrumental,
        plainLyrics = plainLyrics,
        syncedLyrics = syncedLyrics,
        lyricsfile = lyricsfile
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val settingsManager = mockk<com.craftworks.music.managers.settings.MediaProviderSettingsManager>(relaxed = true)
        val context = io.mockk.mockk<android.content.Context>(relaxed = true)
        every { context.cacheDir } returns java.io.File(System.getProperty("java.io.tmpdir"))
        dataSource = LrclibDataSource(settingsManager, context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- artistVariants tests ---

    @Test
    fun `artistVariants - single artist returns only itself`() {
        val variants = dataSource.artistVariants("Hades66")
        assertEquals(1, variants.size)
        assertEquals("Hades66", variants[0])
    }

    @Test
    fun `artistVariants - comma-separated artist splits correctly`() {
        val variants = dataSource.artistVariants("Anubiis, Hades66")
        assertEquals(3, variants.size)
        assertEquals("Anubiis, Hades66", variants[0])
        assertTrue(variants.contains("Anubiis"))
        assertTrue(variants.contains("Hades66"))
    }

    @Test
    fun `artistVariants - ampersand-separated artist splits correctly`() {
        val variants = dataSource.artistVariants("Anubiis & Hades66")
        assertEquals(3, variants.size)
        assertEquals("Anubiis & Hades66", variants[0])
        assertTrue(variants.contains("Anubiis"))
        assertTrue(variants.contains("Hades66"))
    }

    @Test
    fun `artistVariants - semicolon-separated artist splits correctly`() {
        val variants = dataSource.artistVariants("Artist1; Artist2")
        assertEquals(3, variants.size)
        assertTrue(variants.contains("Artist1"))
        assertTrue(variants.contains("Artist2"))
    }

    @Test
    fun `artistVariants - trims whitespace`() {
        val variants = dataSource.artistVariants(" Anubiis , Hades66 ")
        assertTrue(variants.contains("Anubiis"))
        assertTrue(variants.contains("Hades66"))
    }

    // --- scoreCandidate tests ---
    // Weights: title=0.40, artist=0.35, duration=0.15, album=0.10
    // Fallbacks: no-duration=0.05, blank-album=0.05

    @Test
    fun `scoreCandidate - exact title and artist match`() {
        // albumName is blank (default), so album gives partial credit +0.05
        val candidate = searchResult(trackName = "Maradona", artistName = "Hades66", duration = 137.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.40) + artist(0.35) + duration_exact(0.15) + album_blank_partial(0.05) = 0.95
        assertEquals(0.95, score, 0.01)
    }

    @Test
    fun `scoreCandidate - title match but different artist`() {
        val candidate = searchResult(trackName = "Maradona", artistName = "Different Artist", duration = 137.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.40) + artist_no_match(0.0) + duration_exact(0.15) + album_blank_partial(0.05) = 0.60
        assertEquals(0.60, score, 0.01)
    }

    @Test
    fun `scoreCandidate - artist contains query artist`() {
        val candidate = searchResult(trackName = "Maradona", artistName = "Anubiis, Hades66", duration = 137.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.40) + artist_contains(0.35) + duration_exact(0.15) + album_blank_partial(0.05) = 0.95
        assertEquals(0.95, score, 0.01)
    }

    @Test
    fun `scoreCandidate - duration within 30s adds points`() {
        val candidate = searchResult(trackName = "Maradona", artistName = "Hades66", duration = 120.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.40) + artist(0.35) + duration_17s(0.10) + album_blank_partial(0.05) = 0.90
        assertEquals(0.90, score, 0.01)
    }

    @Test
    fun `scoreCandidate - duration far off adds less`() {
        val candidate = searchResult(trackName = "Maradona", artistName = "Hades66", duration = 300.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.40) + artist(0.35) + duration_163s_no_match(0.0) + album_blank_partial(0.05) = 0.80
        assertEquals(0.80, score, 0.01)
    }

    @Test
    fun `scoreCandidate - album match adds points`() {
        val candidate = searchResult(trackName = "Maradona", artistName = "Hades66", albumName = "MARADONA", duration = 137.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.40) + artist(0.35) + duration_exact(0.15) + album_exact(0.10) = 1.00
        assertEquals(1.00, score, 0.01)
    }

    @Test
    fun `scoreCandidate - no album match gives zero album points`() {
        val candidate = searchResult(trackName = "Maradona", artistName = "Hades66", albumName = "Different Album", duration = 137.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.40) + artist(0.35) + duration_exact(0.15) + album_no_match(0.0) = 0.90
        assertEquals(0.90, score, 0.01)
    }

    @Test
    fun `scoreCandidate - completely different song`() {
        val candidate = searchResult(trackName = "Bohemian Rhapsody", artistName = "Queen", duration = 354.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.0) + artist(0.0) + duration_far(0.0) + album_blank_partial(0.05) = 0.05
        assertEquals(0.05, score, 0.01)
    }

    @Test
    fun `scoreCandidate - title match but no duration available`() {
        val candidate = searchResult(trackName = "Maradona", artistName = "Hades66", duration = 0.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.40) + artist(0.35) + duration_cand_zero_partial(0.05) + album_blank_partial(0.05) = 0.85
        assertEquals(0.85, score, 0.01)
    }

    @Test
    fun `scoreCandidate - artist split match gives partial artist score`() {
        val candidate = searchResult(trackName = "Maradona", artistName = "Completely Unknown", duration = 137.0)
        val score = dataSource.scoreCandidate(candidate, "Maradona", "Hades66", "MARADONA", 137)
        // title(0.40) + artist_no_match(0.0) + duration_exact(0.15) + album_blank_partial(0.05) = 0.60
        assertEquals(0.60, score, 0.01)
    }

    // --- pickBestCandidate tests ---

    @Test
    fun `pickBestCandidate - selects highest scoring candidate`() {
        val candidates = listOf(
            searchResult(trackName = "Maradona", artistName = "Some Other", duration = 137.0),
            searchResult(trackName = "Maradona", artistName = "Hades66", duration = 137.0),
            searchResult(trackName = "Maradona", artistName = "Unknown", duration = 300.0)
        )
        val best = dataSource.pickBestCandidate(candidates, "Maradona", "Hades66", "MARADONA", 137)
        assertNotNull(best)
        assertEquals("Hades66", best!!.artistName)
    }

    @Test
    fun `pickBestCandidate - returns null when all below threshold`() {
        val candidates = listOf(
            searchResult(trackName = "Completely Different", artistName = "Nobody", duration = 999.0)
        )
        val best = dataSource.pickBestCandidate(candidates, "Maradona", "Hades66", "MARADONA", 137)
        assertNull(best)
    }

    @Test
    fun `pickBestCandidate - returns null for empty list`() {
        val best = dataSource.pickBestCandidate(emptyList(), "Maradona", "Hades66", "MARADONA", 137)
        assertNull(best)
    }

    @Test
    fun `pickBestCandidate - case insensitive title match`() {
        val candidates = listOf(
            searchResult(trackName = "maradona", artistName = "Hades66", duration = 137.0)
        )
        val best = dataSource.pickBestCandidate(candidates, "Maradona", "Hades66", "MARADONA", 137)
        assertNotNull(best)
    }

    @Test
    fun `pickBestCandidate - artist partial match in candidate`() {
        val candidates = listOf(
            searchResult(trackName = "Maradona", artistName = "Anubiis, Hades66", duration = 140.0)
        )
        val best = dataSource.pickBestCandidate(candidates, "Maradona", "Hades66", "MARADONA", 137)
        assertNotNull(best)
    }

    @Test
    fun `pickBestCandidate - instrumental results are skipped`() {
        val candidates = listOf(
            searchResult(trackName = "Maradona", artistName = "Hades66", duration = 137.0, instrumental = true),
            searchResult(trackName = "Maradona", artistName = "Hades66", duration = 137.0, plainLyrics = "Real lyrics")
        )
        val best = dataSource.pickBestCandidate(candidates, "Maradona", "Hades66", "MARADONA", 137)
        assertNotNull(best)
        assertEquals("Real lyrics", best!!.plainLyrics)
    }
}
