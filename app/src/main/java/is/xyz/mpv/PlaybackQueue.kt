package `is`.xyz.mpv

/** A playback snapshot, deliberately independent of library filtering and sorting. */
internal class PlaybackQueue<T>(private val identity: (T) -> String) {
    var items: List<T> = emptyList()
        private set

    private var original: List<T> = emptyList()
    var shuffled = false
        private set

    var repeat = Repeat.OFF

    enum class Repeat {
        OFF,
        ALL,
        ONE,
    }

    fun replace(values: List<T>) {
        original = values.distinctBy(identity)
        items = if (shuffled) original.shuffled() else original
    }

    fun toggleShuffle(current: T?) {
        shuffled = !shuffled
        items =
            if (shuffled) {
                val rest = original.filterNot { identity(it) == current?.let(identity) }.shuffled()
                if (current != null && original.any { identity(it) == identity(current) })
                    listOf(current) + rest
                else rest
            } else original
    }

    fun adjacent(current: T?, offset: Int, automatic: Boolean = false): T? {
        if (items.isEmpty() || current == null) return null
        val index = items.indexOfFirst { identity(it) == identity(current) }
        if (index < 0) return null
        if (automatic && repeat == Repeat.ONE) return items[index]
        val next = index + offset
        return if (next in items.indices) items[next]
        else if (repeat == Repeat.ALL) items[(next % items.size + items.size) % items.size]
        else null
    }

    fun append(item: T) {
        if (items.any { identity(it) == identity(item) }) return
        original = original + item
        items = items + item
    }

    fun playNext(current: T?, item: T) {
        if (current != null && identity(current) == identity(item)) return
        append(item)
        val remaining = items.filterNot { identity(it) == identity(item) }.toMutableList()
        val currentIndex = remaining.indexOfFirst { identity(it) == current?.let(identity) }
        remaining.add((currentIndex + 1).coerceIn(0, remaining.size), item)
        items = remaining
        if (!shuffled) original = items.toList()
    }

    fun remove(index: Int) {
        val item = items.getOrNull(index) ?: return
        val key = identity(item)
        removeMatching { identity(it) == key }
    }

    fun removeMatching(matches: (T) -> Boolean) {
        original = original.filterNot(matches)
        items = items.filterNot(matches)
    }
}
