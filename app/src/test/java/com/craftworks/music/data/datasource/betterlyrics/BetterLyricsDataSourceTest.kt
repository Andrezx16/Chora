package com.craftworks.music.data.datasource.betterlyrics

import com.craftworks.music.data.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BetterLyricsDataSourceTest {

    private lateinit var dataSource: BetterLyricsDataSource

    @Before
    fun setup() {
        dataSource = BetterLyricsDataSource()
    }

    @Test
    fun `parseTTML with word-level sync returns words`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">
                    <span begin="00:00:18.234" end="00:00:18.567">The</span>
                    <span begin="00:00:18.567" end="00:00:18.901">club</span>
                    <span begin="00:00:18.901" end="00:00:19.234">isn't</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(1, lyrics.size)
        val line = lyrics[0]
        assertEquals(18234, line.startMs)
        assertEquals(21891, line.endMs)
        assertNotNull(line.words)
        assertEquals(3, line.words!!.size)
        assertEquals("The", line.words!![0].text)
        assertEquals(18234, line.words!![0].startMs)
        assertEquals(18567, line.words!![0].endMs)
        assertEquals("club", line.words!![1].text)
        assertEquals("isn't", line.words!![2].text)
        assertEquals("The club isn't", line.text[0])
    }

    @Test
    fun `parseTTML with line-level sync returns lines without words`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">The club isn't the best place</p>
                  <p begin="00:00:22.000" end="00:00:25.000">To find a lover</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(2, lyrics.size)
        assertEquals(18234, lyrics[0].startMs)
        assertEquals(21891, lyrics[0].endMs)
        assertNull(lyrics[0].words)
        assertEquals("The club isn't the best place", lyrics[0].text[0])
        assertEquals(22000, lyrics[1].startMs)
        assertEquals("To find a lover", lyrics[1].text[0])
    }

    @Test
    fun `parseTTML with empty div returns empty list`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div></div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)
        assertTrue(lyrics.isEmpty())
    }

    @Test
    fun `parseTTML with multiple lines preserves order`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="0:05.000" end="0:08.000">First line</p>
                  <p begin="0:10.000" end="0:13.000">Second line</p>
                  <p begin="0:15.000" end="0:18.000">Third line</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(3, lyrics.size)
        assertEquals(5000, lyrics[0].startMs)
        assertEquals(10000, lyrics[1].startMs)
        assertEquals(15000, lyrics[2].startMs)
    }

    @Test
    fun `parseTTML handles short time format`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="1:30.500" end="1:33.000">Short format time</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(1, lyrics.size)
        assertEquals(90500, lyrics[0].startMs)
        assertEquals(93000, lyrics[0].endMs)
    }

    @Test
    fun `parseTTML with blank text line is skipped`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="0:05.000" end="0:08.000">   </p>
                  <p begin="0:10.000" end="0:13.000">Real line</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(1, lyrics.size)
        assertEquals("Real line", lyrics[0].text[0])
    }

    @Test
    fun `parseTTML with malformed XML returns empty list`() {
        val ttml = "this is not valid xml at all"

        val lyrics = dataSource.parseTTML(ttml)
        assertTrue(lyrics.isEmpty())
    }

    @Test
    fun `parseTTML word sync type is detected correctly`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">
                    <span begin="00:00:18.234" end="00:00:18.567">Hello </span>
                    <span begin="00:00:18.567" end="00:00:18.901">world</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)
        val hasWords = lyrics.any { !it.words.isNullOrEmpty() }
        assertTrue(hasWords)
    }

    @Test
    fun `parseTTML line sync type is detected correctly`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">No individual word timestamps</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)
        assertTrue(lyrics.isNotEmpty())
        val hasWords = lyrics.any { !it.words.isNullOrEmpty() }
        val hasTimestamps = lyrics.any { it.startMs >= 0 }
        assertTrue(hasTimestamps)
        assertTrue(!hasWords)
    }

    @Test
    fun `provider name is BetterLyrics`() {
        assertEquals("BetterLyrics", dataSource.name)
    }

    @Test
    fun `parseTTML with timing=None returns unsynced lyrics with startMs=-1`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="None">
              <body>
                <div>
                  <p>The club isn't the best place</p>
                  <p>To find a lover</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(1, lyrics.size)
        assertEquals(-1, lyrics[0].startMs)
        assertEquals("The club isn't the best place\nTo find a lover", lyrics[0].text[0])
        assertNull(lyrics[0].words)
    }

    @Test
    fun `parseTTML with timing=None ignores begin-end attributes`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="None">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">The club isn't the best place</p>
                  <p begin="00:00:22.000" end="00:00:25.000">To find a lover</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(1, lyrics.size)
        assertEquals(-1, lyrics[0].startMs)
    }

    @Test
    fun `parseTTML with timing=Line returns synced lines`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">The club isn't the best place</p>
                  <p begin="00:00:22.000" end="00:00:25.000">To find a lover</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(2, lyrics.size)
        assertEquals(18234, lyrics[0].startMs)
        assertEquals(21891, lyrics[0].endMs)
        assertEquals("The club isn't the best place", lyrics[0].text[0])
        assertEquals(22000, lyrics[1].startMs)
        assertEquals(25000, lyrics[1].endMs)
        assertEquals("To find a lover", lyrics[1].text[0])
    }

    @Test
    fun `parseTTML with timing=Word returns synced words`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">
                    <span begin="00:00:18.234" end="00:00:18.567">The</span>
                    <span begin="00:00:18.567" end="00:00:18.901">club</span>
                    <span begin="00:00:18.901" end="00:00:19.234">isn't</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(1, lyrics.size)
        assertNotNull(lyrics[0].words)
        assertEquals(3, lyrics[0].words!!.size)
        assertEquals("The club isn't", lyrics[0].text[0])
    }

    @Test
    fun `parseTTML without itunes timing attribute works like Line mode`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">The club isn't the best place</p>
                  <p begin="00:00:22.000" end="00:00:25.000">To find a lover</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(2, lyrics.size)
        assertEquals(18234, lyrics[0].startMs)
        assertEquals(22000, lyrics[1].startMs)
    }

    @Test
    fun `parseTTML without timing and without begin-end returns separate unsynced lines`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body>
                <div>
                  <p>The club isn't the best place</p>
                  <p>To find a lover</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)

        assertEquals(2, lyrics.size)
        assertEquals(-1, lyrics[0].startMs)
        assertEquals(-1, lyrics[1].startMs)
        assertEquals("The club isn't the best place", lyrics[0].text[0])
        assertEquals("To find a lover", lyrics[1].text[0])
    }

    @Test
    fun `detectSyncType with timing=None returns NONE`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="None">
              <body>
                <div>
                  <p>The club isn't the best place</p>
                  <p>To find a lover</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)
        val syncType = dataSource.detectSyncTypePublic(lyrics)
        assertEquals(SyncType.NONE, syncType)
    }

    @Test
    fun `detectSyncType with timing=Line returns LINE`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">The club isn't the best place</p>
                  <p begin="00:00:22.000" end="00:00:25.000">To find a lover</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)
        val syncType = dataSource.detectSyncTypePublic(lyrics)
        assertEquals(SyncType.LINE, syncType)
    }

    @Test
    fun `detectSyncType with timing=Word returns WORD`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
              <body>
                <div>
                  <p begin="00:00:18.234" end="00:00:21.891">
                    <span begin="00:00:18.234" end="00:00:18.567">The</span>
                    <span begin="00:00:18.567" end="00:00:18.901">club</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = dataSource.parseTTML(ttml)
        val syncType = dataSource.detectSyncTypePublic(lyrics)
        assertEquals(SyncType.WORD, syncType)
    }
}
