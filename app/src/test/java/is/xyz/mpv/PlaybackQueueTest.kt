package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    @Test
    fun queueIsIndependentOfSourceMutation() {
        val source = mutableListOf("a", "b", "c")
        val queue = PlaybackQueue<String> { it }
        queue.replace(source)
        source.clear()
        assertEquals("b", queue.adjacent("a", 1))
        assertEquals(listOf("a", "b", "c"), queue.items)
    }

    @Test
    fun repeatOneAppliesOnlyToAutomaticAdvancement() {
        val queue = PlaybackQueue<String> { it }
        queue.replace(listOf("a", "b"))
        queue.repeat = PlaybackQueue.Repeat.ONE
        assertEquals("a", queue.adjacent("a", 1, automatic = true))
        assertEquals("b", queue.adjacent("a", 1))
    }

    @Test
    fun repeatAllWrapsBothDirections() {
        val queue = PlaybackQueue<String> { it }
        queue.replace(listOf("a", "b"))
        assertNull(queue.adjacent("b", 1))
        queue.repeat = PlaybackQueue.Repeat.ALL
        assertEquals("a", queue.adjacent("b", 1))
        assertEquals("b", queue.adjacent("a", -1))
    }

    @Test
    fun shufflePreservesCurrentAndCanRestoreOriginalOrder() {
        val queue = PlaybackQueue<String> { it }
        val original = listOf("a", "b", "c", "d")
        queue.replace(original)
        queue.toggleShuffle("c")
        assertEquals("c", queue.items.first())
        assertEquals(original.toSet(), queue.items.toSet())
        queue.toggleShuffle("c")
        assertEquals(original, queue.items)
    }

    @Test
    fun duplicatesAndRemovalDoNotReturnWhenShuffleIsDisabled() {
        val queue = PlaybackQueue<String> { it }
        queue.replace(listOf("a", "a", "b"))
        assertEquals(2, queue.items.size)
        queue.remove(1)
        queue.toggleShuffle("a")
        queue.toggleShuffle("a")
        assertEquals(listOf("a"), queue.items)
        assertNull(queue.adjacent("missing", 1))
    }

    @Test
    fun removeMatchingDropsEveryOccurrenceFromPlaybackAndShuffle() {
        data class Track(val file: String, val occurrence: Int = 0)

        val first = Track("first")
        val removed = Track("removed")
        val last = Track("last")
        val source = listOf(first, removed, removed.copy(occurrence = 1), last)
        val queue = PlaybackQueue<Track> { "${it.file}:${it.occurrence}" }

        queue.replace(source)
        queue.toggleShuffle(first)
        queue.removeMatching { it.file == removed.file }

        assertEquals(setOf(first, last), queue.items.toSet())
        queue.toggleShuffle(first)
        assertEquals(last, queue.adjacent(first, 1, automatic = true))
        assertEquals(first, queue.adjacent(last, -1))

        queue.replace(source)
        assertEquals(source, queue.items)
    }

    @Test
    fun playNextMovesExistingItemWithoutDuplicatingIt() {
        val queue = PlaybackQueue<String> { it }
        queue.replace(listOf("a", "b", "c"))
        queue.playNext("a", "c")
        assertEquals(listOf("a", "c", "b"), queue.items)
        queue.append("d")
        queue.append("d")
        assertEquals(listOf("a", "c", "b", "d"), queue.items)
        queue.playNext("a", "a")
        assertEquals(4, queue.items.size)
    }
}
