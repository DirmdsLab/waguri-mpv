package `is`.xyz.mpv

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LyricsParsingTest {
    @Test
    fun parsesRepeatedTimestampsAndFractionPrecision() {
        val lines = LyricsRepository.parseLrc("[00:01.2][01:02.345]Repeated\n[00:03.45]Next")
        assertEquals(listOf(1200L, 3450L, 62345L), lines.map { it.milliseconds })
        assertEquals(listOf("Repeated", "Next", "Repeated"), lines.map { it.text })
    }

    @Test
    fun ignoresMetadataAndInvalidSecondsAndAppliesOffset() {
        val lines =
            LyricsRepository.parseLrc("[ar:Artist]\n[offset:100]\n[00:02.00]Line\n[00:99]Bad")
        assertEquals(listOf(LyricLine(1900, "Line")), lines)
    }

    @Test
    fun cleansFilenameWithoutRemovingArtistOrSong() {
        assertEquals(
            "Artist - Song",
            LyricsRepository.filenameQuery("01 Artist_-_Song (Official Audio).flac"),
        )
        assertEquals("Artist - Song", LyricsRepository.filenameQuery("Artist - Song [lyrics].mp3"))
    }

    @Test
    fun resolvesSiblingFolderForStorageRootAndNestedDocuments() {
        assertEquals("primary:", LyricsSidecarStore.externalParentId("primary:Clip.mkv"))
        assertEquals("primary:Movies", LyricsSidecarStore.externalParentId("primary:Movies/Clip.mkv"))
        assertEquals(
            "ABCD-1234:Movies/Concert",
            LyricsSidecarStore.externalParentId("ABCD-1234:Movies/Concert/Clip.mkv"),
        )
        assertTrue(LyricsSidecarStore.isWithinTree("primary:Movies", "primary:"))
        assertTrue(LyricsSidecarStore.isWithinTree("primary:Movies/Concert", "primary:Movies"))
        assertFalse(LyricsSidecarStore.isWithinTree("primary:Movies2", "primary:Movies"))
    }

    @Test
    fun findsExistingSidecarInTheMediaFolderByEitherFilename() {
        val folder = Files.createTempDirectory("lyrics-sidecar").toFile()
        try {
            val names = LyricsSidecarStore.sidecarCandidates("Concert.lrc", "Concert.mkv")
            val anotherFolder = folder.resolve("other").apply { mkdir() }
            anotherFolder.resolve("Concert.lrc").writeText("Wrong folder")
            assertNull(LyricsSidecarStore.findFileChild(folder, names))

            val legacy = folder.resolve("Concert.lrc.txt").apply { writeText("Legacy filename") }
            assertEquals(legacy, LyricsSidecarStore.findFileChild(folder, names))

            val fullName = folder.resolve("Concert.mkv.lrc").apply { writeText("Full filename") }
            assertEquals(fullName, LyricsSidecarStore.findFileChild(folder, names))

            val standard = folder.resolve("Concert.LRC").apply { writeText("Standard filename") }
            assertEquals(standard, LyricsSidecarStore.findFileChild(folder, names))
        } finally {
            folder.deleteRecursively()
        }
    }
}
