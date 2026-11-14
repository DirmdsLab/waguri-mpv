package `is`.xyz.mpv

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedStorageFoldersTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun folderListingIncludesEmptyAndUnindexedDirectories() {
        val root = temporaryFolder.newFolder().canonicalFile
        val empty = File(root, "Empty").apply { mkdir() }
        val unindexed =
            File(root, "Unindexed").apply {
                mkdir()
                File(this, ".nomedia").createNewFile()
                File(this, "track.mp3").createNewFile()
            }
        File(root, "regular-file.txt").createNewFile()

        val folders = SharedStorageFolders.readableEntries(root).orEmpty().filter(File::isDirectory)
        assertEquals(setOf(empty, unindexed), folders.toSet())
    }

    @Test
    fun folderListingDoesNotFollowLinksOutsideStorage() {
        val root = temporaryFolder.newFolder().canonicalFile
        val outside = temporaryFolder.newFolder().canonicalFile
        try {
            Files.createSymbolicLink(File(root, "Outside").toPath(), outside.toPath())
        } catch (error: IOException) {
            assumeNoException(error)
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
        }

        assertEquals(emptyList<File>(), SharedStorageFolders.readableEntries(root))
        assertFalse(SharedStorageFolders.contains(root, File(root.path + "-other")))
        assertTrue(SharedStorageFolders.contains(root, File(root, "Child")))
    }
}
