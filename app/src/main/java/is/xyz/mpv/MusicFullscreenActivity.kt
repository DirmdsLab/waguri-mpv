package `is`.xyz.mpv

/** Runs the standard MPV controls in a process separate from music and external playback. */
class MusicFullscreenActivity : MPVActivity() {
    override val returnsToMusicPlayer = true

    companion object {
        const val EXTRA_PAUSED = "is.xyz.mpv.extra.PLAYBACK_PAUSED"
    }
}
