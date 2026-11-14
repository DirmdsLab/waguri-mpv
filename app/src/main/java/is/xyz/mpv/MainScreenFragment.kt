package `is`.xyz.mpv

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.transition.ChangeBounds
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.LinearLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.tabs.TabLayout
import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import `is`.xyz.mpv.databinding.ItemMediaFileBinding
import java.io.File
import java.text.DateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

internal data class MediaFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val lastModified: Long,
    var durationMs: Long? = null,
    val folderPath: String = "",
    val volumeName: String? = null,
    val playlistTitle: String? = null,
    val playlistOccurrence: Int = 0,
    val isAvailable: Boolean = true,
    val parentDocumentUri: Uri? = null,
    val temporaryYouTubeAudio: Boolean = false,
) {
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val isVideo: Boolean
        get() = mimeType.startsWith("video/") || extension in VIDEO_EXTENSIONS

    val isPlaylist: Boolean
        get() = extension in PLAYLIST_EXTENSIONS ||
            mimeType.substringBefore(';').trim().lowercase(Locale.ROOT) in PLAYLIST_MIME_TYPES

    val queueKey: String
        get() = "$playlistOccurrence:$uri"

    val cacheKey: String
        get() = "${uri}#${lastModified}"

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ts")
        private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
        private val PLAYLIST_MIME_TYPES = setOf(
            M3uPlaylist.MIME_TYPE, "audio/mpegurl", "application/mpegurl", "application/x-mpegurl",
            "application/vnd.apple.mpegurl",
        )
    }
}

internal fun List<MediaFile>.withPlaylistOccurrences(): List<MediaFile> {
    val occurrences = mutableMapOf<Uri, Int>()
    return map { track ->
        val occurrence = occurrences.getOrDefault(track.uri, 0)
        occurrences[track.uri] = occurrence + 1
        track.copy(playlistOccurrence = occurrence)
    }
}

class MainScreenFragment : Fragment(R.layout.fragment_main_screen) {

    private var _binding: FragmentMainScreenBinding? = null
    private val binding
        get() = requireNotNull(_binding)

    private val preferences by lazy {
        requireContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val libraryFolderIndex by lazy { LibraryFolderIndex(preferences) }

    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val folderExecutor = Executors.newSingleThreadExecutor()
    private var sharedFolderDialog: androidx.appcompat.app.AlertDialog? = null
    private var openingSharedFolders = false
    private val playbackHandler = Handler(Looper.getMainLooper())
    private var thumbnailLoaderInstance: MediaThumbnailLoader? = null
    private val thumbnailLoader: MediaThumbnailLoader
        get() =
            thumbnailLoaderInstance
                ?: MediaThumbnailLoader(requireContext().applicationContext).also {
                    thumbnailLoaderInstance = it
                }

    private lateinit var storagePermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var folderTreePickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var mediaFilePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var playlistFilePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var playlistFolderPickerLauncher: ActivityResultLauncher<Uri?>
    private lateinit var playlistExportLauncher: ActivityResultLauncher<String>
    private var pendingPlaylistFiles: List<Uri> = emptyList()
    private var pendingPlaylistExport: String? = null
    private lateinit var visualizerPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var fullscreenVideoLauncher: ActivityResultLauncher<Intent>
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var fullscreenLaunchMediaUri: Uri? = null
    private var fullscreenLaunchPositionMs: Long = 0L
    private var fullscreenLaunchPaused: Boolean = true

    private var selectedTreeUri: Uri? = null
    private var selectedSharedFolder: File? = null
    private var selectedFolderPath: String? = null
    private var selectedVolumeName: String? = null
    private var allMedia: List<MediaFile> = emptyList()
    private var folderMediaCount = 0
    private var scanningFolder = false
    private var folderScanFailed = false
    private var sortedMedia: List<MediaFile> = emptyList()
    private val removedTrackUris = mutableSetOf<Uri>()
    private var trackSwipeHelper: ItemTouchHelper? = null
    private var currentMedia: MediaFile? = null
    private var activePlaylist: PlaylistSelection? = null
    private var playlistRequestGeneration = 0

    private data class PlaylistSelection(
        val name: String,
        val tracks: List<MediaFile>,
        val savedName: String? = null,
    )

    @Volatile private var scanGeneration = 0
    private val queue = PlaybackQueue<MediaFile>(MediaFile::queueKey)
    private val playlistStore by lazy { PlaylistStore(requireContext()) }
    private val libraryStore by lazy { PlaylistStore(requireContext(), "imported_media") }
    private var showingLibrary = true
    private var portraitLibraryExpanded = false
    private val isPortraitLayout: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    private var playingFileName: String? = null
    private var youtubeBrowser: YouTubeBrowser? = null
    private val youtubeAudioExecutor = Executors.newFixedThreadPool(3)
    private val youtubeFormatsExecutor = Executors.newSingleThreadExecutor()
    private var youtubeAudioRequest: java.util.concurrent.Future<*>? = null
    private var youtubeAudioGeneration = 0
    private var pendingYouTubeTrack: MediaFile? = null
    private var loadingYouTubePlaylist = false
    private var youtubeChoiceDialog: androidx.appcompat.app.AlertDialog? = null
    private val deletingMedia = mutableSetOf<Uri>()
    private var pendingMediaDeletion: MediaFile? = null
    private var temporaryAudioCover: Bitmap? = null
    private var currentYouTubeAudioFormat: String? = null
    private var youtubeDownloadPage: YouTubeDownloadPage? = null
    private var showingYouTubeDownloadPage = false
    private var savingYouTubeFiles = false
    private var pendingYouTubeSave: String? = null
    private val savedYouTubeResults = mutableSetOf<Pair<Uri, String>>()
    private var youtubeBrowserClosed = false
    private var resettingYouTubeSession = false
    private var libraryFoldersDialog: androidx.appcompat.app.AlertDialog? = null
    private var playbackFinished = false
    @Volatile private var isStoppingPlayback = false
    private var durationCountdownEnabled = false
    private var mediaSwipeAnimationRunning = false
    private var videoModeEnabled = false
    private var isVideoFullscreen = false
    private var playerWideAspect = false
    private var portraitAspect = PlayerAspect.FILL
    private var playerZoomStep = 0
    private var artworkGeneration = 0
    private var ambientLight: AmbientLightDrawable? = null
    private var ambientEnabled = false
    private var ambientLibraryStyle = false
    private var defaultLibraryTabBackground: Drawable? = null
    private var defaultLibraryTabRipple: ColorStateList? = null
    private var ambientReach = 35
    private var ambientArtwork: Bitmap? = null
    private var ambientFrame: Bitmap? = null
    private var ambientCapturePending = false
    private var lastAmbientCapture = 0L
    private val ambientRootLocation = IntArray(2)
    private val ambientCoverLocation = IntArray(2)
    private val ambientCoverBounds = RectF()
    private var sortMode = SortMode.NAME
    private var sortAscending = true
    private var playerInitialized = false
    private var isUserSeeking = false
    private var visualizerEnabled = false
    private var autoLyricsEnabled = false
    private var audioSessionId = AudioManager.ERROR
    private var visualizer: Visualizer? = null

    @Volatile private var isReplacingMedia = false

    private val mediaAdapter by lazy {
        MediaAdapter(
            thumbnailLoader,
            onItemClick = ::playSelectedTrack,
            onItemMenu = ::showMediaActions,
        )
    }

    private fun playSelectedTrack(media: MediaFile) {
        if (!canPlayTrack(media)) {
            Toast.makeText(
                requireContext(), R.string.playlist_track_unavailable, Toast.LENGTH_SHORT
            ).show()
            return
        }
        queue.replace((activePlaylist?.tracks ?: sortedMedia).filter(::canPlayTrack))
        playMedia(media)
        showPanel(false)
    }

    private fun canPlayTrack(media: MediaFile): Boolean =
        media.isAvailable && media.uri !in removedTrackUris && media.uri !in deletingMedia

    private enum class SortMode(val label: Int) {
        NAME(R.string.sort_name),
        LAST_MODIFIED(R.string.sort_last_modified),
        DURATION(R.string.sort_duration),
    }

    private enum class PlayerAspect(val label: Int) {
        FILL(R.string.aspect_ratio_fill),
        SQUARE(R.string.aspect_ratio_square),
        WIDE(R.string.aspect_ratio_wide),
    }

    private val mpvObserver =
        object : MPVLib.EventObserver {
            override fun eventProperty(property: String) = Unit

            override fun eventProperty(property: String, value: Long) = Unit

            override fun eventProperty(property: String, value: Boolean) = Unit

            override fun eventProperty(property: String, value: String) = Unit

            override fun eventProperty(property: String, value: Double) = Unit

            override fun event(eventId: Int) {
                when (eventId) {
                    MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                        if (isStoppingPlayback) {
                            isStoppingPlayback = false
                        } else if (!isReplacingMedia) {
                            playbackHandler.post {
                                if (!isAdded || _binding == null || !playerInitialized) {
                                    return@post
                                }
                                handlePlaybackEnded()
                            }
                        }
                    }

                    MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                        isStoppingPlayback = false
                        isReplacingMedia = false
                        playbackHandler.removeCallbacks(loadTimeout)

                        playbackHandler.post {
                            if (!isAdded || _binding == null || !playerInitialized) return@post

                            val media = currentMedia ?: return@post
                            updateVideoModeButton()
                            updateMediaViewportControls()
                            val title = MPVLib.getPropertyString("metadata/by-key/title")
                            val artist = MPVLib.getPropertyString("metadata/by-key/artist")
                            updateNowPlayingText(media, title, artist, loaded = true)
                            if (!lyricsQueryEdited && !title.isNullOrBlank()) {
                                setLyricsQuery(
                                    listOfNotNull(artist, title).joinToString(" ").trim()
                                )
                            }
                            loadLocalLyricsForCurrentMedia()
                            updatePlaybackUi()
                            updateNavigationButtons()

                            if (visualizerEnabled) {
                                playbackHandler.removeCallbacks(startVisualizerRunnable)
                                playbackHandler.postDelayed(
                                    startVisualizerRunnable,
                                    VISUALIZER_START_DELAY_MS,
                                )
                            }
                        }
                    }
                }
            }
        }

    private val playbackTicker =
        object : Runnable {
            override fun run() {
                if (_binding == null || !playerInitialized) return
                updatePlaybackUi()
                updateAmbientLight()
                playbackHandler.postDelayed(this, PLAYBACK_UPDATE_INTERVAL_MS)
            }
        }

    private val loadTimeout = Runnable {
        if (_binding != null && isReplacingMedia) {
            isReplacingMedia = false
            playbackFinished = true
            showPlaybackError()
        }
    }

    private val startVisualizerRunnable = Runnable { startAudioVisualizer() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showingLibrary = savedInstanceState?.getBoolean(STATE_SHOWING_LIBRARY, true) ?: true
        portraitLibraryExpanded = savedInstanceState?.getBoolean(STATE_PORTRAIT_LIBRARY_EXPANDED) ?: false
        youtubeBrowserClosed = savedInstanceState?.getBoolean(STATE_YOUTUBE_BROWSER_CLOSED) ?: false
        pendingYouTubeSave = savedInstanceState?.getString(STATE_PENDING_YOUTUBE_SAVE)
        savedInstanceState?.let { state ->
            state.getString(STATE_MEDIA_DELETION_URI)?.let { value ->
                val uri = Uri.parse(value)
                pendingMediaDeletion = MediaFile(uri, state.getString(STATE_MEDIA_DELETION_NAME).orEmpty(), "", 0L)
                deletingMedia.add(uri)
            }
        }
        pendingPlaylistExport = savedInstanceState?.getString(STATE_PENDING_PLAYLIST_EXPORT)
        pendingPlaylistFiles = savedInstanceState?.getStringArrayList(STATE_PENDING_PLAYLIST_FILES)
            .orEmpty().map(Uri::parse)

        fullscreenVideoLauncher =
            registerForActivityResult(StartActivityForResult()) { result ->
                val mediaUri = fullscreenLaunchMediaUri
                if (mediaUri != null && currentMedia?.uri == mediaUri && playerInitialized) {
                    val data = result.data
                    val returnedPosition =
                        if (result.resultCode == android.app.Activity.RESULT_OK) {
                            data?.getIntExtra("position", -1) ?: -1
                        } else {
                            -1
                        }
                    val positionMs =
                        if (returnedPosition >= 0) returnedPosition.toLong()
                        else fullscreenLaunchPositionMs
                    val returnedPaused =
                        if (
                            result.resultCode == android.app.Activity.RESULT_OK &&
                                data?.hasExtra(MusicFullscreenActivity.EXTRA_PAUSED) == true
                        ) {
                            data.getBooleanExtra(
                                MusicFullscreenActivity.EXTRA_PAUSED,
                                fullscreenLaunchPaused,
                            )
                        } else {
                            fullscreenLaunchPaused
                        }

                    runCatching {
                        MPVLib.setPropertyDouble("time-pos", positionMs / 1000.0)
                        val shouldPause = returnedPaused || !MusicPlaybackService.ensureFocus()
                        MPVLib.setPropertyBoolean("pause", shouldPause)
                    }.onFailure {
                        Log.w(TAG, "Could not restore embedded playback after fullscreen video", it)
                    }

                    updatePlaybackUi()
                    updateNavigationButtons()
                    if (visualizerEnabled && !returnedPaused) {
                        playbackHandler.removeCallbacks(startVisualizerRunnable)
                        playbackHandler.postDelayed(
                            startVisualizerRunnable,
                            VISUALIZER_START_DELAY_MS,
                        )
                    }
                }
                fullscreenLaunchMediaUri = null
            }

        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants
                ->
                val anyGranted =
                    requiredStoragePermissions().any { permission ->
                        grants[permission] == true ||
                            ContextCompat.checkSelfPermission(requireContext(), permission) ==
                                PackageManager.PERMISSION_GRANTED
                    }

                if (anyGranted) {
                    showMediaFolderPicker()
                } else {
                    Toast.makeText(
                            requireContext(),
                            R.string.media_storage_permission_denied,
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    restoreFolderStatus()
                }
            }

        folderTreePickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri == null) {
                    pendingYouTubeSave = null
                    restoreFolderStatus()
                    return@registerForActivityResult
                }

                try {
                    requireContext()
                        .contentResolver
                        .takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                } catch (exception: SecurityException) {
                    Log.w(TAG, "Folder provider did not grant persistent read/write access; retrying read-only", exception)
                    runCatching {
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }.onFailure { Log.w(TAG, "Could not persist the selected folder read permission", it) }
                }

                if (uri !in readableLibraryFolders()) {
                    pendingYouTubeSave = null
                    Toast.makeText(requireContext(), R.string.library_folder_permission_failed, Toast.LENGTH_LONG)
                        .show()
                    return@registerForActivityResult
                }
                selectLibraryFolder(uri)
                pendingYouTubeSave?.let { path ->
                    pendingYouTubeSave = null
                    saveYouTubeResult(path, allowFolderPicker = false)
                }
            }

        mediaFilePickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isNotEmpty()) {
                    importSelectedFiles(uris)
                } else {
                    restoreFolderStatus()
                }
            }

        playlistFilePickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) openPlaylistFiles(listOf(uri))
            }

        playlistFolderPickerLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
                val files = pendingPlaylistFiles
                pendingPlaylistFiles = emptyList()
                if (folder != null && files.isNotEmpty()) {
                    persistMediaReadPermission(requireContext(), folder)
                    openPlaylistFiles(files, folder)
                }
            }

        playlistExportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument(M3uPlaylist.MIME_TYPE)) { uri ->
                val name = pendingPlaylistExport
                pendingPlaylistExport = null
                if (uri != null && name != null) writePlaylistExport(name, uri)
            }

        visualizerPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    visualizerEnabled = true
                    preferences.edit().putBoolean(PREFERENCE_VISUALIZER_ENABLED, true).apply()
                    updateVisualizerAppearance()
                    startAudioVisualizer()
                } else {
                    visualizerEnabled = false
                    preferences.edit().putBoolean(PREFERENCE_VISUALIZER_ENABLED, false).apply()
                    updateVisualizerAppearance()
                    Toast.makeText(
                            requireContext(),
                            R.string.visualizer_permission_denied,
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
            }
    }

    private val mediaDeletionLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val media = pendingMediaDeletion ?: return@registerForActivityResult
        pendingMediaDeletion = null
        if (result.resultCode != Activity.RESULT_OK) {
            deletingMedia.remove(media.uri)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            finishMediaDeletion(media)
        } else {
            deletingMedia.remove(media.uri)
            deleteMediaFile(media, canRequestAccess = false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = FragmentMainScreenBinding.bind(view)
        ambientLibraryStyle = false
        defaultLibraryTabBackground = binding.librarySections.background
        defaultLibraryTabRipple = binding.librarySections.tabRippleColor
        ambientEnabled = preferences.getBoolean(PREFERENCE_AMBIENT_ENABLED, false)
        ambientReach = preferences.getInt(PREFERENCE_AMBIENT_REACH, 35).coerceIn(0, 100)
        ambientLight = AmbientLightDrawable(
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface),
        ).also {
            binding.root.background = it
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, keyboard.bottom),
            )
            val player = binding.playerPanel
            val portraitKeyboard =
                isPortraitLayout && portraitLibraryExpanded && keyboard.bottom > bars.bottom
            // Give the library extra space for search results while the keyboard is open.
            val playerWeight = if (portraitKeyboard) 0.5f else 1f
            if ((player.layoutParams as LinearLayout.LayoutParams).weight != playerWeight) {
                player.updateLayoutParams<LinearLayout.LayoutParams> { weight = playerWeight }
            }
            insets
        }
        playerWideAspect = false
        playerZoomStep = 0
        durationCountdownEnabled = false
        preferences.edit()
            .remove(PREFERENCE_PLAYER_WIDE_ASPECT)
            .remove(PREFERENCE_PLAYER_ZOOM_STEP)
            .apply()
        binding.mediaVisualContainer.clipChildren = true
        binding.mediaVisualContainer.clipToPadding = true
        updateMediaViewportControls()
        binding.mediaVisualContainer.addOnLayoutChangeListener {
            _,
            left,
            top,
            right,
            bottom,
            oldLeft,
            oldTop,
            oldRight,
            oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                applyPlayerViewportShape()
            }
        }

        binding.mediaRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = mediaAdapter
            setHasFixedSize(true)
        }
        attachTrackSwipe()

        binding.chooseFileButton.setOnClickListener {
            mediaFilePickerLauncher.launch(arrayOf("*/*"))
        }
        binding.videoModeButton.isEnabled = false
        binding.videoModeButton.alpha = 0.35f
        binding.chooseFolderButton.setOnClickListener { openFolderBrowser() }

        binding.changeFolderButton.setOnClickListener { openFolderBrowser() }
        binding.resetPlaylistButton.setOnClickListener { resetTracks() }

        binding.clearMediaSearchButton.isVisible = !binding.mediaSearchInput.text.isNullOrEmpty()
        binding.mediaSearchInput.doAfterTextChanged { query ->
            binding.clearMediaSearchButton.isVisible = !query.isNullOrEmpty()
            renderMedia()
        }
        binding.clearMediaSearchButton.setOnClickListener { button ->
            if (button.hasFocus()) binding.mediaSearchInput.requestFocus()
            clearMediaSearch()
        }

        binding.sortName.setOnClickListener { setSortMode(SortMode.NAME) }
        binding.sortModified.setOnClickListener { setSortMode(SortMode.LAST_MODIFIED) }
        binding.sortDuration.setOnClickListener { setSortMode(SortMode.DURATION) }
        binding.portraitSortButton.setOnClickListener(::showTrackSortMenu)
        binding.jumpToTopButton.setOnClickListener { jumpToMediaListEdge(toBottom = false) }
        binding.jumpToBottomButton.setOnClickListener { jumpToMediaListEdge(toBottom = true) }
        updateMediaListJumpButtons()

        binding.playButton.setOnClickListener {
            if (currentMedia != null && playerInitialized) {
                if (playbackFinished) playMedia(requireNotNull(currentMedia))
                else if (
                    MPVLib.getPropertyBoolean("pause") != true || MusicPlaybackService.ensureFocus()
                )
                    binding.mpvSurface.cyclePause()
                updatePlaybackUi()
            }
        }

        binding.previousButton.setOnClickListener { animateMediaSwipe(-1) }

        binding.nextButton.setOnClickListener { animateMediaSwipe(1) }

        binding.videoModeButton.setOnClickListener { setVideoMode(!videoModeEnabled) }
        binding.aspectRatioButton.setOnClickListener { togglePlayerAspectRatio() }
        binding.coverZoomButton.setOnClickListener { cyclePlayerZoom() }

        binding.audioVisualizerButton.setOnClickListener { onVisualizerButtonClicked() }
        installCoverSwipeGesture()
        binding.playbackDuration.setOnClickListener {
            if (currentMedia != null) {
                durationCountdownEnabled = !durationCountdownEnabled
                updatePlaybackUi()
            }
        }

        binding.playbackSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) =
                    Unit

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    isUserSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    isUserSeeking = false
                    if (!playerInitialized || currentMedia == null) return
                    val duration = MPVLib.getPropertyDouble("duration/full") ?: 0.0
                    if (duration > 0.0) {
                        val target = duration * seekBar.progress / seekBar.max
                        MPVLib.setPropertyDouble("time-pos", target)
                    }
                }
            }
        )

        visualizerEnabled =
            preferences.getBoolean(PREFERENCE_VISUALIZER_ENABLED, false) &&
                hasVisualizerPermission()
        autoLyricsEnabled = preferences.getBoolean(PREFERENCE_AUTO_LYRICS_ENABLED, false)

        binding.playerMenuButton.setOnClickListener { showPlayerMenu(it) }
        binding.libraryMenuButton.setOnClickListener { showPlayerMenu(it) }
        installLibraryPanelHandle(binding.collapsedLibraryBar, expand = true)
        installLibraryPanelHandle(binding.collapseLibraryButton, expand = false)
        binding.fullscreenExitButton.setOnClickListener { exitVideoFullscreen() }
        binding.queueButton.setOnClickListener { showQueue() }
        binding.librarySections.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) =
                    showLibrarySection(tab.position)

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            }
        )
        binding.youtubeDownloadsButton.setOnClickListener {
            if (showingYouTubeDownloadPage) closeYouTubeDownloadPage() else openYouTubeDownloadPage()
        }
        binding.youtubeBackButton.setOnClickListener { youtubeBrowser?.goBack() }
        binding.youtubePlayAllButton.setOnClickListener {
            youtubeBrowser?.currentPlaylist()?.let(::playYouTubePlaylist)
        }
        binding.youtubeSaveLocation.setOnClickListener { openFolderBrowser() }
        binding.youtubeResetButton.setOnClickListener { resetYouTubeSession() }
        binding.youtubeCloseButton.setOnClickListener {
            youtubeBrowserClosed = true
            releaseYouTubeDownloadPage()
            releaseYouTubeBrowser()
            updateYouTubeControls()
        }
        binding.youtubeRespawnButton.setOnClickListener {
            youtubeBrowserClosed = false
            youtubeBrowser?.view?.reload()
            updateYouTubeBrowser()
        }
        binding.lyricsSearchInput.doAfterTextChanged {
            if (!settingLyricsQuery) lyricsQueryEdited = true
        }
        binding.lyricsSearchButton.setOnClickListener { searchLyrics() }
        binding.lyricsDownloadButton.setOnClickListener { downloadCurrentLyrics() }
        binding.saveLyricsDelayButton.setOnClickListener { downloadCurrentLyrics(saveDelay = true) }
        binding.lyricsDelayButton.setOnClickListener {
            binding.lyricsDelayControls.isVisible = !binding.lyricsDelayControls.isVisible
        }
        binding.lyricsDelaySlider.apply {
            max = MAX_LYRICS_DELAY_SECONDS * 2
            keyProgressIncrement = 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    lyricsDelaySeconds = progress - MAX_LYRICS_DELAY_SECONDS
                    updateLyricsDelayValue()
                    if (fromUser && playerInitialized) syncLyrics(playbackPositionSeconds())
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        binding.lyricsSearchInput.setOnEditorActionListener { _, action, _ ->
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) false
            else {
                if (binding.lyricsSearchButton.isEnabled) searchLyrics()
                true
            }
        }
        resetLyrics()
        updateYouTubeControls()
        binding.shuffleButton.setOnClickListener {
            queue.toggleShuffle(currentMedia)
            updateQueueControls()
        }
        binding.repeatButton.setOnClickListener {
            queue.repeat = PlaybackQueue.Repeat.entries[(queue.repeat.ordinal + 1) % 3]
            updateQueueControls()
        }
        binding.playButton.isEnabled = false
        binding.playbackDuration.isEnabled = false
        binding.playbackSeekBar.isEnabled = false
        requireActivity()
            .onBackPressedDispatcher
            .addCallback(
                viewLifecycleOwner,
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        when {
                            isVideoFullscreen -> exitVideoFullscreen()
                            isPortraitLayout && portraitLibraryExpanded &&
                                binding.librarySections.selectedTabPosition == TAB_YOUTUBE &&
                                goBackWithinYouTube() -> Unit
                            isPortraitLayout && portraitLibraryExpanded -> showPanel(false)
                            !isPortraitLayout && !showingLibrary -> showPanel(true)
                            !isPortraitLayout && handleYouTubeBack() -> Unit
                            musicServiceStarted -> requireActivity().moveTaskToBack(true)
                            else -> {
                                isEnabled = false
                                requireActivity().onBackPressedDispatcher.onBackPressed()
                            }
                        }
                    }
                },
            )
        savedInstanceState?.getInt(STATE_LIBRARY_TAB, TAB_TRACKS)?.let { tab ->
            binding.librarySections.getTabAt(tab)?.select()
        }
        updatePanelLayout()
        updateCollapsedLibraryInfo()
        updateQueueControls()
        updateSortButtons()
        updateVisualizerAppearance()
        initializePlayer()
        applyPlayerViewportShape()
        restoreSelectedFolder()
    }

    private var musicServiceStarted = false
    private val musicController =
        object : MusicPlaybackService.Controller {
            override fun play() {
                if (!playerInitialized) return
                if (playbackFinished) currentMedia?.let { playMedia(it) }
                else MPVLib.setPropertyBoolean("pause", false)
            }

            override fun pause() {
                if (playerInitialized) MPVLib.setPropertyBoolean("pause", true)
            }

            override fun previous() {
                playAdjacentMedia(-1)
            }

            override fun next() {
                playAdjacentMedia(1)
            }

            override fun seek(positionMs: Long) {
                if (playerInitialized)
                    MPVLib.setPropertyDouble("time-pos", positionMs.coerceAtLeast(0) / 1000.0)
            }
        }

    private fun startMusicService() {
        if (musicServiceStarted) return
        MusicPlaybackService.controller = musicController
        try {
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), MusicPlaybackService::class.java),
            )
            musicServiceStarted = true
        } catch (exception: Exception) {
            Log.w(TAG, "Background playback service is unavailable", exception)
        }
    }

    private fun stopMusicService() {
        if (MusicPlaybackService.controller === musicController) {
            MusicPlaybackService.controller = null
        }
        requireContext().stopService(Intent(requireContext(), MusicPlaybackService::class.java))
        musicServiceStarted = false
    }

    private val lyricsExecutor = Executors.newSingleThreadExecutor()
    private val lyricsRepository by lazy { LyricsRepository(requireContext().applicationContext) }
    private val lyricsSidecarStore by lazy { LyricsSidecarStore(requireContext().applicationContext) }
    private var lyricsGeneration = 0
    private var autoLyricsGeneration = 0
    private var localLyricsGeneration = 0
    private var lyricsDownloadGeneration = 0
    private var activeLyricsResult: LyricsResult? = null
    private var lyricsSavedBesideMedia = false
    private var checkingLocalLyrics = false
    private var savingLyrics = false
    private var lyricLines: List<LyricLine> = emptyList()
    private var lyricText = ""
    private var highlightedLyric = -2
    private var lyricsDelaySeconds = 0
    private var lyricsQueryEdited = false
    private var settingLyricsQuery = false

    private fun installLibraryPanelHandle(handle: View, expand: Boolean) {
        val touchSlop = ViewConfiguration.get(handle.context).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        handle.setOnClickListener { showPanel(expand) }
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val deltaX = event.rawX - startX
                    val deltaY = event.rawY - startY
                    val distance = if (expand) -deltaY else deltaY
                    if (distance > touchSlop && distance > abs(deltaX)) {
                        view.isPressed = false
                        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    private fun showPanel(library: Boolean) {
        val animatePortrait = isPortraitLayout && portraitLibraryExpanded != library
        if (isPortraitLayout) portraitLibraryExpanded = library else showingLibrary = library
        updatePanelLayout(animatePortrait)
    }

    private fun updatePanelLayout(animatePortrait: Boolean = false) {
        val currentBinding = _binding ?: return
        if (isVideoFullscreen) return

        val portrait = isPortraitLayout
        val wide = !portrait && resources.configuration.screenWidthDp >= 600
        val compact = !portrait && resources.configuration.screenHeightDp < 480
        val library = if (portrait) portraitLibraryExpanded else showingLibrary
        val libraryVisible = wide || library
        val playerVisible = portrait || wide || !library

        if (animatePortrait && currentBinding.contentPanels.isLaidOut) {
            val slide = Slide(Gravity.BOTTOM).apply {
                addTarget(currentBinding.libraryPanel)
                addTarget(currentBinding.collapsedLibraryBar)
            }
            val resize = ChangeBounds().apply {
                addTarget(currentBinding.playerPanel)
                addTarget(currentBinding.panelDivider)
            }
            val transition = TransitionSet().apply {
                addTransition(slide)
                addTransition(resize)
                duration = 200L
            }
            TransitionManager.beginDelayedTransition(currentBinding.contentPanels, transition)
        }

        currentBinding.contentPanels.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        currentBinding.playerPanel.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else 0
            height = if (portrait) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        }
        currentBinding.libraryPanel.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else 0
            height = if (portrait) 0 else ViewGroup.LayoutParams.MATCH_PARENT
        }
        currentBinding.panelDivider.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (portrait) ViewGroup.LayoutParams.MATCH_PARENT else dp(1)
            height = if (portrait) dp(1) else ViewGroup.LayoutParams.MATCH_PARENT
        }
        currentBinding.libraryPanel.setPadding(
            dp(16), dp(if (portrait) 0 else 16), dp(16), dp(if (portrait) 4 else 16)
        )
        currentBinding.collapsedLibraryBar.visibility = if (portrait && !library) View.VISIBLE else View.GONE
        currentBinding.collapseLibraryButton.visibility = if (portrait) View.VISIBLE else View.GONE
        currentBinding.libraryPanel.visibility = if (libraryVisible) View.VISIBLE else View.GONE
        currentBinding.playerPanel.visibility = if (playerVisible) View.VISIBLE else View.GONE
        currentBinding.panelDivider.visibility = if (portrait || wide) View.VISIBLE else View.GONE
        currentBinding.libraryMenuButton.visibility = if (!portrait && !wide && library) View.VISIBLE else View.GONE

        currentBinding.nowPlayingTitle.visibility = if (!portrait || library) View.VISIBLE else View.GONE
        currentBinding.nowPlayingSubtitle.visibility = if (!portrait && !compact) View.VISIBLE else View.GONE
        currentBinding.mediaVisualContainer.visibility =
            if (compact && !videoModeEnabled) View.GONE else View.VISIBLE
        currentBinding.playerPanel.setPadding(
            dp(if (portrait) 0 else 16),
            dp(if (portrait || compact) 4 else 16),
            dp(if (portrait) 0 else 16),
            dp(if (portrait || compact) 4 else 16),
        )
        currentBinding.playerHeader.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = dp(if (portrait) 12 else 0)
            marginEnd = marginStart
        }
        currentBinding.playerContentFrame.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = dp(if (portrait) 16 else 0)
            marginEnd = marginStart
            topMargin = dp(if (portrait) 4 else 12)
        }
        val compactTransport = portrait && library
        currentBinding.playbackTimeline.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = dp(if (compactTransport) 4 else 12)
        }
        currentBinding.playbackControls.updateLayoutParams<LinearLayout.LayoutParams> {
            height = dp(if (compactTransport) 56 else 76)
        }
        currentBinding.playButton.updateLayoutParams<FrameLayout.LayoutParams> {
            width = dp(if (compactTransport) 48 else 60)
            height = width
        }
        currentBinding.playerHeaderTitle.visibility =
            if (portrait && resources.configuration.screenWidthDp < 320) View.GONE else View.VISIBLE

        updateTrackSortVisibility()
        currentBinding.emptyStateArtwork.visibility = if (portrait) View.GONE else View.VISIBLE
        currentBinding.folderStatus.maxLines = if (portrait) 1 else 2
        currentBinding.folderStatusRow.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = dp(if (portrait) 2 else 12)
            bottomMargin = dp(if (portrait) 2 else 8)
        }
        currentBinding.mediaSearchField.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = dp(if (portrait) 2 else 10)
        }

        if (!libraryVisible) {
            currentBinding.mediaSearchInput.clearFocus()
            currentBinding.lyricsSearchInput.clearFocus()
            youtubeBrowser?.view?.clearFocus()
            WindowCompat.getInsetsController(requireActivity().window, currentBinding.root)
                .hide(WindowInsetsCompat.Type.ime())
        }
        ViewCompat.requestApplyInsets(currentBinding.root)
        updateYouTubeBrowser()
        updateAmbientLight()
        updateMediaViewportControls()

        if (!playerVisible) {
            stopAudioVisualizer()
        } else if (visualizerEnabled && currentMedia != null && isResumed && visualizer == null) {
            playbackHandler.removeCallbacks(startVisualizerRunnable)
            playbackHandler.postDelayed(startVisualizerRunnable, VISUALIZER_START_DELAY_MS)
        }

        currentBinding.mediaVisualContainer.post {
            if (_binding === currentBinding && !isVideoFullscreen) applyPlayerViewportShape()
        }
        if (libraryVisible) scrollToCurrentTrack()
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        _binding?.contentPanels?.let { TransitionManager.endTransitions(it) }
        updatePanelLayout()
    }

    private fun updateQueueControls() {
        val currentBinding = _binding ?: return
        currentBinding.shuffleButton.contentDescription =
            getString(if (queue.shuffled) R.string.shuffle_on else R.string.shuffle_off)
        currentBinding.shuffleButton.alpha = if (queue.shuffled) 1f else 0.55f
        currentBinding.repeatButton.contentDescription =
            getString(
                when (queue.repeat) {
                    PlaybackQueue.Repeat.OFF -> R.string.repeat_off
                    PlaybackQueue.Repeat.ALL -> R.string.repeat_all
                    PlaybackQueue.Repeat.ONE -> R.string.repeat_one
                }
            )
        currentBinding.repeatButton.setImageResource(
            if (queue.repeat == PlaybackQueue.Repeat.ONE) R.drawable.ic_repeat_one_24dp
            else R.drawable.ic_repeat_24dp
        )
        currentBinding.repeatButton.alpha =
            if (queue.repeat == PlaybackQueue.Repeat.OFF) 0.55f else 1f
        updateNavigationButtons()
    }

    private fun showQueue() {
        if (queue.items.isEmpty()) {
            Toast.makeText(requireContext(), R.string.empty_queue, Toast.LENGTH_SHORT).show()
            return
        }
        val snapshot = queue.items.toList()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.queue_title)
            .setItems(snapshot.map { it.displayName }.toTypedArray()) { _, index ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(snapshot[index].displayName)
                    .setPositiveButton(R.string.play) { _, _ ->
                        val track = snapshot[index]
                        if (canPlayTrack(track)) {
                            playMedia(track)
                            showPanel(false)
                        }
                    }
                    .setNeutralButton(R.string.remove_from_queue) { _, _ ->
                        // Keep the currently playing item in the queue until another track is
                        // selected.
                        if (snapshot[index].queueKey != currentMedia?.queueKey) {
                            val currentIndex = queue.items.indexOfFirst {
                                it.queueKey == snapshot[index].queueKey
                            }
                            if (currentIndex >= 0) queue.remove(currentIndex)
                        }
                        updateNavigationButtons()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setPositiveButton(R.string.save_queue) { _, _ -> saveQueue() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveQueue(
        items: List<MediaFile> = queue.items,
        titleRes: Int = R.string.save_queue,
    ) {
        val snapshot = items.filter { !it.temporaryYouTubeAudio && canPlayTrack(it) }
        if (snapshot.isEmpty()) return
        val input =
            EditText(requireContext()).apply {
                hint = getString(R.string.playlist_name)
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(80))
            }
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setView(input)
                .setPositiveButton(R.string.save_action, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog
                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val name = input.text.toString().trim()
                    if (name.isEmpty()) {
                        input.error = getString(R.string.playlist_name)
                        return@setOnClickListener
                    }
                    fun save() {
                        playlistStore.save(name, snapshot)
                        dialog.dismiss()
                        Toast.makeText(
                                requireContext(),
                                R.string.playlist_saved,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                    if (name in playlistStore.names()) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.replace_playlist)
                            .setPositiveButton(R.string.save_action) { _, _ -> save() }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else save()
                }
        }
        dialog.show()
    }

    private fun showMediaActions(media: MediaFile) {
        val actions = buildList {
            add(R.string.play_next)
            add(R.string.add_to_queue)
            add(R.string.add_to_playlist)
            if (!media.isPlaylist) {
                add(R.string.remove_from_current_playlist)
                add(R.string.add_to_quick_playlist)
            }
            if (media.isAvailable && !media.temporaryYouTubeAudio && media.uri.scheme in listOf("file", "content")) {
                add(R.string.delete_media_file)
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(media.displayName)
            .setItems(actions.map(::getString).toTypedArray()) { _, index ->
                when (actions[index]) {
                    R.string.play_next -> {
                        if (canPlayTrack(media)) queue.playNext(currentMedia, media)
                        updateNavigationButtons()
                    }
                    R.string.add_to_queue -> {
                        if (canPlayTrack(media)) queue.append(media)
                        updateNavigationButtons()
                    }
                    R.string.add_to_playlist -> choosePlaylistDestination(media)
                    R.string.remove_from_current_playlist -> removeFromCurrentPlaylist(media)
                    R.string.add_to_quick_playlist -> addToQuickPlaylist(media)
                    R.string.delete_media_file -> confirmDeleteMediaFile(media)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteMediaFile(media: MediaFile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_media_file)
            .setMessage(getString(R.string.delete_media_file_confirmation, media.displayName))
            .setPositiveButton(R.string.delete_action) { _, _ -> deleteMediaFile(media) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteMediaFile(media: MediaFile, canRequestAccess: Boolean = true) {
        if (pendingMediaDeletion != null) return
        if (!deletingMedia.add(media.uri)) return
        val context = requireContext().applicationContext
        val resolver = context.contentResolver
        folderExecutor.execute {
            val deleted = runCatching {
                when {
                    media.uri.scheme == "file" -> media.uri.path?.let { File(it).delete() } == true
                    DocumentsContract.isDocumentUri(context, media.uri) ->
                        DocumentsContract.deleteDocument(resolver, media.uri)
                    else -> resolver.delete(media.uri, null, null) > 0
                }
            }
            playbackHandler.post {
                deletingMedia.remove(media.uri)
                if (_binding == null) return@post
                val error = deleted.exceptionOrNull()
                if (canRequestAccess && error is SecurityException && media.uri.authority == "media" &&
                    requestMediaDeletion(media, error)
                ) return@post
                deleted.exceptionOrNull()?.let { Log.w(TAG, "Could not delete media file", it) }
                if (deleted.getOrNull() != true) {
                    Toast.makeText(context, R.string.media_file_delete_failed, Toast.LENGTH_LONG).show()
                    return@post
                }

                finishMediaDeletion(media)
            }
        }
    }

    private fun requestMediaDeletion(media: MediaFile, error: SecurityException): Boolean {
        if (pendingMediaDeletion != null) return false
        return runCatching {
            val request = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createDeleteRequest(requireContext().contentResolver, listOf(media.uri))
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException ->
                    error.userAction.actionIntent
                else -> return false
            }
            pendingMediaDeletion = media
            deletingMedia.add(media.uri)
            mediaDeletionLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(request).build())
        }.onFailure {
            pendingMediaDeletion = null
            deletingMedia.remove(media.uri)
            Log.w(TAG, "Could not request permission to delete media", it)
        }.isSuccess
    }

    private fun finishMediaDeletion(media: MediaFile) {
        deletingMedia.remove(media.uri)
        val context = context ?: return
        removedTrackUris.add(media.uri)
        val playing = currentMedia?.uri == media.uri
        val next = if (playing) {
            queue.adjacent(currentMedia, 1)?.takeIf(::canPlayTrack)
        } else null
        queue.removeMatching { it.uri == media.uri }
        val originalCount = allMedia.size
        allMedia = allMedia.filterNot { it.uri == media.uri }
        folderMediaCount = (folderMediaCount - (originalCount - allMedia.size)).coerceAtLeast(0)
        val imports = libraryStore.read("files")
        if (imports.any { it.uri == media.uri }) {
            libraryStore.save("files", imports.filterNot { it.uri == media.uri })
        }
        activePlaylist = activePlaylist?.let { playlist ->
            playlist.copy(tracks = playlist.tracks.filterNot { it.uri == media.uri })
        }
        if (_binding == null) return
        if (playing) {
            if (next != null) playMedia(next) else stopCurrentPlayback(showToast = false)
        }
        renderMedia()
        Toast.makeText(context, R.string.media_file_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun attachTrackSwipe() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                val media = mediaAdapter.itemAt(viewHolder.bindingAdapterPosition) ?: return 0
                if (media.isPlaylist) return 0
                return if (media.isAvailable) super.getSwipeDirs(recyclerView, viewHolder)
                    else ItemTouchHelper.RIGHT
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val media = mediaAdapter.itemAt(position) ?: return
                when (direction) {
                    ItemTouchHelper.RIGHT -> removeFromCurrentPlaylist(media)
                    ItemTouchHelper.LEFT -> {
                        ItemTouchHelper.Callback.getDefaultUIUtil().clearView(viewHolder.itemView)
                        mediaAdapter.notifyItemChanged(position)
                        addToQuickPlaylist(media)
                    }
                }
            }
        }
        trackSwipeHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(binding.mediaRecycler) }
    }

    private fun removeFromCurrentPlaylist(media: MediaFile) {
        if (!removedTrackUris.add(media.uri)) return
        val playing = currentMedia?.takeIf { it.uri == media.uri }
        val next = playing?.let { current ->
            val position = queue.items.indexOfFirst { it.queueKey == current.queueKey }
            if (position < 0) return@let null
            queue.items.asSequence().drop(position + 1).firstOrNull(::canPlayTrack)
                ?: queue.items.firstOrNull(::canPlayTrack)
        }
        queue.removeMatching { it.uri == media.uri }
        if (playing != null) {
            if (next != null) playMedia(next) else stopCurrentPlayback(showToast = false)
        }
        renderMedia(removedTrackKey = media.queueKey)
        Toast.makeText(requireContext(), R.string.track_removed_from_current_playlist, Toast.LENGTH_SHORT)
            .show()
    }

    private fun addToQuickPlaylist(media: MediaFile) {
        val name = playlistStore.names()
            .firstOrNull { it.equals(QUICK_PLAYLIST_NAME, ignoreCase = true) }
            ?: QUICK_PLAYLIST_NAME
        val message = if (addToSavedPlaylist(name, media)) R.string.track_added_to_quick_playlist
            else R.string.track_already_in_quick_playlist
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun addToSavedPlaylist(name: String, media: MediaFile): Boolean {
        val tracks = playlistStore.read(name)
        if (tracks.any { it.uri == media.uri }) return false
        val updated = tracks + media.copy(playlistOccurrence = 0)
        playlistStore.save(name, updated)
        activePlaylist?.takeIf { it.savedName == name }?.let {
            activePlaylist = it.copy(tracks = updated)
            renderMedia()
        }
        return true
    }

    private fun choosePlaylistDestination(media: MediaFile) {
        val names = playlistStore.names()
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.add_to_playlist)
        if (names.isEmpty()) {
            builder.setMessage(R.string.no_playlist_destination)
        } else {
            builder.setItems(names.toTypedArray()) { _, index ->
                addToSavedPlaylist(names[index], media)
                Toast.makeText(requireContext(), R.string.playlist_saved, Toast.LENGTH_SHORT).show()
            }
        }
        builder
            .setPositiveButton(R.string.new_playlist) { _, _ ->
                saveQueue(listOf(media), R.string.new_playlist)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPlaylists() {
        val names = playlistStore.names()
        val builder = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.playlists_title)
        if (names.isEmpty()) {
            builder.setMessage(R.string.playlist_empty)
        } else {
            builder.setItems(names.toTypedArray()) { _, index -> showSavedPlaylistActions(names[index]) }
        }
        val playlist = activePlaylist
        builder
            .setPositiveButton(R.string.choose_playlist_file) { _, _ ->
                playlistFilePickerLauncher.launch(arrayOf("*/*"))
            }
            .setNeutralButton(
                if (playlist == null) R.string.save_queue else R.string.save_playlist
            ) { _, _ ->
                if (playlist == null) saveQueue()
                else saveQueue(playlist.tracks, R.string.save_playlist)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSavedPlaylistActions(name: String) {
        val actions = intArrayOf(
            R.string.view_playlist_tracks, R.string.play_action,
            R.string.export_playlist_m3u, R.string.delete_action,
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(name)
            .setItems(actions.map { getString(it) }.toTypedArray()) { _, index ->
                when (actions[index]) {
                    R.string.view_playlist_tracks ->
                        showPlaylistTracks(name, playlistStore.read(name), savedName = name)
                    R.string.play_action -> {
                        val tracks = playlistStore.read(name)
                        showPlaylistTracks(name, tracks, savedName = name)
                        val playable = tracks.filter(::canPlayTrack)
                        playable.firstOrNull()?.let {
                            queue.replace(playable)
                            playMedia(it)
                            showPanel(false)
                        }
                    }
                    R.string.export_playlist_m3u -> launchPlaylistExport(name)
                    R.string.delete_action -> confirmPlaylistDeletion(name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmPlaylistDeletion(name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_playlist)
            .setMessage(name)
            .setPositiveButton(R.string.delete_action) { _, _ ->
                playlistStore.delete(name)
                if (activePlaylist?.savedName == name) {
                    clearPlaylistSelection()
                    clearMediaSearch()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchPlaylistExport(name: String) {
        val sanitized = name.replace('/', '_').replace('\\', '_')
        val filename = if (sanitized.endsWith(".m3u", ignoreCase = true)) sanitized
            else "$sanitized.m3u"
        pendingPlaylistExport = name
        try {
            playlistExportLauncher.launch(filename)
        } catch (exception: android.content.ActivityNotFoundException) {
            pendingPlaylistExport = null
            Log.w(TAG, "Android document creator is unavailable", exception)
            Toast.makeText(requireContext(), R.string.playlist_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun writePlaylistExport(name: String, destination: Uri) {
        val appContext = requireContext().applicationContext
        val tracks = playlistStore.read(name)
        folderExecutor.execute {
            val result = runCatching { M3uPlaylistFiles(appContext).export(destination, tracks) }
            result.onFailure { Log.w(TAG, "Could not export playlist", it) }
            playbackHandler.post {
                val context = context ?: return@post
                val message = if (result.isSuccess) R.string.playlist_exported
                    else R.string.playlist_export_failed
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openPlaylistFiles(uris: List<Uri>, relativeFolder: Uri? = null) {
        val appContext = requireContext().applicationContext
        val generation = ++playlistRequestGeneration
        folderExecutor.execute {
            val reader = M3uPlaylistFiles(appContext)
            val opened = uris.mapNotNull { uri ->
                runCatching {
                    persistMediaReadPermission(appContext, uri)
                    uri to reader.open(uri, relativeFolder)
                }.onFailure { Log.w(TAG, "Could not read M3U playlist", it) }.getOrNull()
            }
            playbackHandler.post {
                if (_binding == null || generation != playlistRequestGeneration) return@post
                if (opened.size < uris.size) {
                    Toast.makeText(requireContext(), R.string.playlist_open_failed, Toast.LENGTH_LONG)
                        .show()
                }
                if (opened.isEmpty()) return@post

                val playlists = opened.map { it.second }
                val name = playlists.singleOrNull()?.name ?: getString(R.string.m3u_playlist)
                val tracks = playlists.flatMap { it.tracks }.withPlaylistOccurrences()
                showPlaylistTracks(name, tracks)
                val playable = tracks.filter(::canPlayTrack)
                if (playable.isNotEmpty()) {
                    queue.replace(playable)
                    val currentKey = currentMedia?.queueKey
                    if (relativeFolder == null || playable.none { it.queueKey == currentKey }) {
                        playMedia(playable.first())
                    }
                    updateNavigationButtons()
                }
                val unavailable = playlists.sumOf { it.unavailable }
                if (unavailable > 0) offerPlaylistFolder(opened.map { it.first }, unavailable)
            }
        }
    }

    private fun offerPlaylistFolder(uris: List<Uri>, unavailable: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlist_tracks_unavailable)
            .setMessage(resources.getQuantityString(
                R.plurals.playlist_unavailable_tracks, unavailable, unavailable
            ))
            .setPositiveButton(R.string.choose_folder) { _, _ ->
                pendingPlaylistFiles = uris
                playlistFolderPickerLauncher.launch(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPlaylistTracks(name: String, tracks: List<MediaFile>, savedName: String? = null) {
        playlistRequestGeneration++
        activePlaylist = PlaylistSelection(name, tracks, savedName)
        binding.librarySections.selectTab(binding.librarySections.getTabAt(TAB_TRACKS))
        clearMediaSearch()
        showPanel(true)
    }

    private fun clearMediaSearch() {
        val input = binding.mediaSearchInput.text
        if (input.isNullOrEmpty()) renderMedia() else input.clear()
    }

    private fun clearPlaylistSelection() {
        playlistRequestGeneration++
        activePlaylist = null
    }

    private fun resetTracks() {
        val hadRemovedTracks = removedTrackUris.isNotEmpty()
        removedTrackUris.clear()
        clearPlaylistSelection()
        clearMediaSearch()
        if (hadRemovedTracks) restoreFolderQueue()
    }

    private fun restoreFolderQueue() {
        queue.replace(sortedMedia.filter(::canPlayTrack))
        updateNavigationButtons()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOWING_LIBRARY, showingLibrary)
        outState.putBoolean(STATE_PORTRAIT_LIBRARY_EXPANDED, portraitLibraryExpanded)
        _binding?.librarySections?.let { outState.putInt(STATE_LIBRARY_TAB, it.selectedTabPosition) }
        outState.putBoolean(STATE_YOUTUBE_BROWSER_CLOSED, youtubeBrowserClosed)
        outState.putString(STATE_PENDING_YOUTUBE_SAVE, pendingYouTubeSave)
        pendingMediaDeletion?.let { media ->
            outState.putString(STATE_MEDIA_DELETION_URI, media.uri.toString())
            outState.putString(STATE_MEDIA_DELETION_NAME, media.displayName)
        }
        outState.putString(STATE_PENDING_PLAYLIST_EXPORT, pendingPlaylistExport)
        outState.putStringArrayList(
            STATE_PENDING_PLAYLIST_FILES, ArrayList(pendingPlaylistFiles.map(Uri::toString))
        )
    }


    private fun stopCurrentPlayback(showToast: Boolean = true) {
        val currentBinding = _binding ?: return
        cancelYouTubeAudioRequest()
        if (isVideoFullscreen) exitVideoFullscreen()

        playbackHandler.removeCallbacks(loadTimeout)
        isStoppingPlayback = true
        isReplacingMedia = true
        playbackFinished = true
        durationCountdownEnabled = false
        mediaSwipeAnimationRunning = false
        stopAudioVisualizer()

        var stopSucceeded = true
        if (playerInitialized) {
            try {
                MPVLib.command(arrayOf("stop"))
            } catch (exception: Exception) {
                stopSucceeded = false
                isStoppingPlayback = false
                Log.e(TAG, "Could not stop MPV playback", exception)
            }
        }

        isReplacingMedia = false
        currentMedia = null
        currentYouTubeAudioFormat = null
        temporaryAudioCover = null
        playingFileName = null
        ambientArtwork = null
        updateAmbientLight()
        currentBinding.mpvSurface.alpha = 0f
        currentBinding.artworkImage.visibility = View.VISIBLE
        showAppIconPlaceholder(currentBinding.artworkImage)
        currentBinding.playerCircleContainer.animate().cancel()
        currentBinding.playerCircleContainer.translationX = 0f
        currentBinding.playerCircleContainer.alpha = 1f

        currentBinding.nowPlayingTitle.text = getString(R.string.uwu_player_title)
        currentBinding.nowPlayingSubtitle.text = getString(R.string.no_media_selected)
        updateCollapsedLibraryInfo()
        currentBinding.playbackPosition.text = ZERO_TIME
        currentBinding.playbackDuration.text = ZERO_TIME
        currentBinding.playbackDuration.contentDescription = getString(R.string.total_duration)
        currentBinding.playbackDuration.isEnabled = false
        currentBinding.playbackSeekBar.progress = 0
        currentBinding.playbackSeekBar.isEnabled = false
        currentBinding.playButton.isEnabled = false
        currentBinding.playButton.alpha = 0.45f
        currentBinding.playButton.setImageResource(R.drawable.ic_waguri_play)
        currentBinding.playButton.contentDescription = getString(R.string.play)

        currentBinding.audioSpectrumView.setActive(false)
        currentBinding.audioSpectrumView.clear()
        mediaAdapter.setSelectedKey(null)
        resetLyrics()
        updateMediaViewportControls()
        updateNavigationButtons()
        updateVisualizerAppearance()
        stopMusicService()

        if (showToast) {
            Toast.makeText(
                requireContext(),
                if (stopSucceeded) R.string.playback_stopped else R.string.stop_playback_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }



    private fun exitPlayer() {
        stopCurrentPlayback(showToast = false)
        playerWideAspect = false
        playerZoomStep = 0
        durationCountdownEnabled = false
        preferences.edit()
            .remove(PREFERENCE_PLAYER_WIDE_ASPECT)
            .remove(PREFERENCE_PLAYER_ZOOM_STEP)
            .apply()
        updateMediaViewportControls()
        applyPlayerViewportShape()
        requireActivity().finish()
    }

    private fun showPlayerMenu(anchor: View) {
        val menu = PopupMenu(requireContext(), anchor)
        menu.menu.add(0, MENU_LIBRARY, 0, getString(R.string.library_tab))
        menu.menu.add(0, MENU_NOW_PLAYING, 1, getString(R.string.now_playing_tab))
        menu.menu.add(0, MENU_PLAYLISTS, 2, getString(R.string.playlists_title))
        menu.menu.add(0, MENU_LYRICS, 3, getString(R.string.lyrics_title))
        menu.menu.add(0, MENU_AMBIENT_LIGHT, 7, getString(R.string.ambient_light))

        val media = currentMedia
        val fullscreenItem =
            menu.menu.add(0, MENU_FULLSCREEN, 4, getString(R.string.fullscreen))
        fullscreenItem.isEnabled =
            playerInitialized && !isReplacingMedia && !playbackFinished && media?.isVideo == true

        val stopItem =
            menu.menu.add(0, MENU_STOP, 5, getString(R.string.stop_playback))
        stopItem.isEnabled = playerInitialized && media != null

        val autoLyricsItem =
            menu.menu.add(0, MENU_AUTO_LYRICS, 6, getString(R.string.auto_lyrics))
        autoLyricsItem.isCheckable = true
        autoLyricsItem.isChecked = autoLyricsEnabled
        menu.menu.add(0, MENU_EXIT, 8, getString(R.string.exit_player))

        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_LIBRARY -> {
                    showPanel(true)
                    binding.librarySections.selectTab(binding.librarySections.getTabAt(TAB_TRACKS))
                    true
                }

                MENU_NOW_PLAYING -> {
                    showPanel(false)
                    true
                }

                MENU_PLAYLISTS -> {
                    showPlaylists()
                    true
                }

                MENU_LYRICS -> {
                    showPanel(true)
                    binding.librarySections.selectTab(binding.librarySections.getTabAt(TAB_LYRICS))
                    true
                }

                MENU_FULLSCREEN -> {
                    enterVideoFullscreen()
                    true
                }

                MENU_STOP -> {
                    stopCurrentPlayback()
                    true
                }

                MENU_AUTO_LYRICS -> {
                    setAutoLyricsEnabled(!autoLyricsEnabled)
                    true
                }

                MENU_AMBIENT_LIGHT -> {
                    showAmbientLightSettings()
                    true
                }

                MENU_EXIT -> {
                    exitPlayer()
                    true
                }

                else -> false
            }
        }
        menu.show()
    }


    /**
     * Switches the selected MPV video track without reloading the file or
     * changing the pause state. Audio continues in the same libmpv instance.
     */
    private fun setVideoMode(enabled: Boolean) {
        val media = currentMedia ?: return
        if (!playerInitialized || isReplacingMedia || playbackFinished || !media.isVideo || videoModeEnabled == enabled) return

        if (!enabled && isVideoFullscreen) {
            exitVideoFullscreen()
        }

        try {
            MPVLib.setPropertyString("vid", if (enabled) "auto" else "no")
            videoModeEnabled = enabled

            val currentBinding = _binding ?: return
            currentBinding.mpvSurface.alpha = if (enabled) 1f else 0f
            currentBinding.artworkImage.visibility = if (enabled) View.GONE else View.VISIBLE
            updateVideoModeButton()
            applyPlayerViewportShape()
        } catch (exception: Exception) {
            Log.e(TAG, "Could not switch MPV video track mode", exception)
            Toast.makeText(
                requireContext(),
                R.string.video_mode_switch_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }


    private fun showLibrarySection(position: Int) {
        val lyrics = position == TAB_LYRICS
        binding.trackListPanel.visibility = if (position == TAB_TRACKS) View.VISIBLE else View.GONE
        binding.lyricsPanel.visibility = if (lyrics) View.VISIBLE else View.GONE
        binding.youtubePanel.visibility = if (position == TAB_YOUTUBE) View.VISIBLE else View.GONE
        updateYouTubeBrowser()
        if (position == TAB_TRACKS) scrollToCurrentTrack()
        if (lyrics && playerInitialized) {
            highlightedLyric = -2
            syncLyrics(playbackPositionSeconds())
            if (!lyricsSavedBesideMedia && !checkingLocalLyrics && !savingLyrics) {
                loadLocalLyricsForCurrentMedia(searchOnlineIfMissing = false)
            }
        }
    }

    private fun updateYouTubeBrowser() {
        val currentBinding = _binding ?: return
        updateYouTubeControls()
        if (youtubeBrowserClosed || resettingYouTubeSession || !isResumed || isVideoFullscreen ||
            currentBinding.libraryPanel.visibility != View.VISIBLE ||
            currentBinding.youtubePanel.visibility != View.VISIBLE
        ) {
            youtubeBrowser?.view?.onPause()
            youtubeDownloadPage?.view?.onPause()
            return
        }

        if (showingYouTubeDownloadPage) {
            youtubeBrowser?.view?.visibility = View.GONE
            youtubeBrowser?.view?.onPause()
            youtubeDownloadPage?.view?.visibility = View.VISIBLE
            youtubeDownloadPage?.view?.onResume()
            return
        }

        youtubeDownloadPage?.view?.visibility = View.GONE
        youtubeDownloadPage?.view?.onPause()
        val browser = youtubeBrowser ?: YouTubeBrowser(
            requireContext(), ::showYouTubeVideoActions, ::updateYouTubeNavigationButtons, ::playYouTubePlaylist,
        )
            .also {
                youtubeBrowser = it
                it.view.alpha = if (ambientLibraryStyle) AMBIENT_BROWSER_ALPHA else 1f
                currentBinding.youtubeBrowserContainer.addView(
                    it.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                it.load()
            }
        browser.view.visibility = View.VISIBLE
        browser.view.onResume()
        updateYouTubeControls()
    }

    private fun updateYouTubeControls() {
        val currentBinding = _binding ?: return
        currentBinding.youtubeDownloadsButton.isEnabled = !youtubeBrowserClosed && !resettingYouTubeSession
        currentBinding.youtubeDownloadsButton.setText(
            if (showingYouTubeDownloadPage) R.string.youtube_tab else R.string.youtube_downloads
        )
        currentBinding.youtubeDownloadsButton.contentDescription =
            if (showingYouTubeDownloadPage) getString(R.string.youtube_back_to_browse) else null
        updateYouTubeNavigationButtons()
        val folder = selectedTreeUri?.let(::getIndexedFolderName)
            ?: selectedSharedFolder?.path
            ?: selectedFolderPath?.let(::getFolderName)
        currentBinding.youtubeSaveLocation.text = if (folder == null) {
            getString(R.string.youtube_choose_save_folder)
        } else {
            getString(R.string.youtube_save_folder, folder)
        }
        currentBinding.youtubeResetButton.isEnabled = !resettingYouTubeSession
        currentBinding.youtubeCloseButton.isEnabled = !youtubeBrowserClosed && !resettingYouTubeSession
        currentBinding.youtubeRespawnButton.isEnabled = !resettingYouTubeSession
        val status = when {
            resettingYouTubeSession -> R.string.youtube_reset_in_progress
            youtubeBrowserClosed -> R.string.youtube_browser_closed
            else -> null
        }
        currentBinding.youtubeBrowserStatus.visibility = if (status == null) View.GONE else View.VISIBLE
        status?.let(currentBinding.youtubeBrowserStatus::setText)
    }

    private fun updateYouTubeNavigationButtons() {
        val currentBinding = _binding ?: return
        val button = currentBinding.youtubeBackButton
        button.isVisible = !showingYouTubeDownloadPage
        button.isEnabled = button.isVisible && !youtubeBrowserClosed && !resettingYouTubeSession &&
            youtubeBrowser?.canGoBack() == true
        currentBinding.youtubePlayAllButton.isVisible = !showingYouTubeDownloadPage &&
            !youtubeBrowserClosed && !resettingYouTubeSession && youtubeBrowser?.currentPlaylist() != null
    }

    private fun releaseYouTubeBrowser() {
        val browser = youtubeBrowser ?: return
        youtubeBrowser = null
        _binding?.youtubeBrowserContainer?.removeView(browser.view)
        browser.destroy()
    }

    private fun resetYouTubeSession() {
        if (resettingYouTubeSession) return
        resettingYouTubeSession = true
        youtubeBrowserClosed = false
        releaseYouTubeDownloadPage()
        releaseYouTubeBrowser()
        updateYouTubeControls()
        try {
            YouTubeBrowser.clearSession(requireContext().applicationContext) {
                resettingYouTubeSession = false
                updateYouTubeBrowser()
            }
        } catch (exception: RuntimeException) {
            resettingYouTubeSession = false
            youtubeBrowserClosed = true
            Log.w(TAG, "Could not reset the YouTube browser session", exception)
            updateYouTubeControls()
            Toast.makeText(requireContext(), R.string.youtube_reset_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleYouTubeBack(): Boolean {
        if (binding.librarySections.selectedTabPosition != TAB_YOUTUBE) return false
        if (!goBackWithinYouTube()) {
            binding.librarySections.selectTab(binding.librarySections.getTabAt(TAB_TRACKS))
        }
        return true
    }

    private fun goBackWithinYouTube(): Boolean {
        if (showingYouTubeDownloadPage) {
            closeYouTubeDownloadPage()
            return true
        }
        return youtubeBrowser?.goBack() == true
    }

    private fun showYouTubeVideoActions(url: String) {
        if (!canShowYouTubeChoice()) return
        val choices = intArrayOf(R.string.youtube_downloads, R.string.youtube_play_audio)
        showYouTubeChoice(MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.youtube_video_actions)
            .setItems(choices.map(::getString).toTypedArray()) { dialog, index ->
                dialog.dismiss()
                if (index == 0) openYouTubeDownloadPage(url) else showYouTubeAudioChoices(url)
            })
    }

    private fun showYouTubeAudioChoices(url: String) {
        val choices = intArrayOf(R.string.youtube_play_audio_default, R.string.youtube_audio_formats)
        showYouTubeChoice(MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.youtube_play_audio)
            .setItems(choices.map(::getString).toTypedArray()) { dialog, index ->
                dialog.dismiss()
                if (index == 0) playSingleYouTubeAudio(url) else showYouTubeAudioFormats(url)
            })
    }

    private fun showYouTubeAudioFormats(url: String) {
        showYouTubeChoice(MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.youtube_audio_formats_title)
            .setMessage(R.string.youtube_audio_formats_loading))
        val loadingDialog = youtubeChoiceDialog ?: return
        youtubeFormatsExecutor.execute {
            val result = runCatching { YouTubeTempAudio.formats(url) }
            playbackHandler.post {
                if (youtubeChoiceDialog !== loadingDialog || !loadingDialog.isShowing) return@post
                loadingDialog.dismiss()
                if (!canShowYouTubeChoice()) return@post
                val formats = result.getOrElse { error ->
                    Log.w(TAG, "YouTube format lookup failed", error)
                    Toast.makeText(requireContext(), R.string.youtube_audio_failed, Toast.LENGTH_LONG).show()
                    return@post
                }
                val choices = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.youtube_audio_formats_title)
                if (formats.isEmpty()) {
                    choices.setMessage(R.string.youtube_audio_formats_empty)
                } else {
                    choices.setItems(formats.map { it.label }.toTypedArray()) { dialog, index ->
                        dialog.dismiss()
                        playSingleYouTubeAudio(url, formats[index].code)
                    }
                }
                showYouTubeChoice(choices)
            }
        }
    }

    private fun showYouTubeChoice(builder: MaterialAlertDialogBuilder) {
        val dialog = builder.setNegativeButton(android.R.string.cancel, null).create()
        youtubeChoiceDialog?.dismiss()
        youtubeChoiceDialog = dialog
        dialog.setOnDismissListener { if (youtubeChoiceDialog === dialog) youtubeChoiceDialog = null }
        dialog.show()
    }

    private fun canShowYouTubeChoice(): Boolean = isResumed && !showingYouTubeDownloadPage &&
        _binding?.youtubePanel?.isShown == true && youtubeChoiceDialog?.isShowing != true

    private fun playSingleYouTubeAudio(url: String, format: String? = null) {
        val track = makeYouTubeAudioTrack(YouTubeTempAudio.Track(url, getString(R.string.youtube_audio_track)), 0)
        prepareYouTubeTrack(track, format, replacementQueue = listOf(track))
        showPanel(false)
    }

    private fun playYouTubePlaylist(url: String) {
        if (!canShowYouTubeChoice()) return
        runYouTubeAudioRequest(R.string.youtube_playlist_loading, R.string.youtube_playlist_failed,
            { YouTubeTempAudio.playlist(url) }, onFailure = {
                if (playbackFinished) updateEndedPlaybackUi()
            }) { tracks ->
            val media = tracks.mapIndexed { index, track -> makeYouTubeAudioTrack(track, index) }
            val first = media.firstOrNull() ?: return@runYouTubeAudioRequest
            prepareYouTubeTrack(first, replacementQueue = media, skipUnavailable = true)
            showPanel(false)
        }
        loadingYouTubePlaylist = true
    }

    private fun makeYouTubeAudioTrack(track: YouTubeTempAudio.Track, index: Int) = MediaFile(
        Uri.parse(track.url), track.title, "audio/unknown", 0L,
        playlistOccurrence = index, temporaryYouTubeAudio = true,
    )

    private fun prepareYouTubeTrack(
        track: MediaFile,
        format: String? = null,
        replacementQueue: List<MediaFile>? = null,
        skipUnavailable: Boolean = false,
    ) {
        if (pendingYouTubeTrack?.queueKey == track.queueKey && replacementQueue == null) return
        runYouTubeAudioRequest(R.string.youtube_audio_loading, R.string.youtube_audio_failed,
            { YouTubeTempAudio.prepare(track.uri.toString(), format) }, onFailure = {
                pendingYouTubeTrack = null
                val candidates = replacementQueue ?: queue.items
                val index = candidates.indexOfFirst { it.queueKey == track.queueKey }
                val next = if (skipUnavailable && index >= 0) candidates.getOrNull(index + 1) else null
                if (next != null) {
                    prepareYouTubeTrack(next, replacementQueue = replacementQueue, skipUnavailable = true)
                } else if (playbackFinished) {
                    updateEndedPlaybackUi()
                }
            }) { audio ->
            pendingYouTubeTrack = null
            if (replacementQueue != null) queue.replace(replacementQueue)
            playMedia(track.copy(displayName = audio.title, durationMs = audio.durationMs), audio)
        }
        pendingYouTubeTrack = track
    }

    private fun <T> runYouTubeAudioRequest(
        loading: Int,
        failure: Int,
        work: () -> T,
        onFailure: () -> Unit = {},
        onSuccess: (T) -> Unit,
    ) {
        cancelYouTubeAudioRequest()
        val generation = youtubeAudioGeneration
        Toast.makeText(requireContext(), loading, Toast.LENGTH_SHORT).show()
        youtubeAudioRequest = youtubeAudioExecutor.submit {
            val result = runCatching(work)
            playbackHandler.post {
                if (_binding == null || generation != youtubeAudioGeneration) return@post
                youtubeAudioRequest = null
                loadingYouTubePlaylist = false
                result.fold(onSuccess) { error ->
                    Log.w(TAG, "YouTube audio request failed", error)
                    Toast.makeText(requireContext(), failure, Toast.LENGTH_LONG).show()
                    onFailure()
                }
            }
        }
    }

    private fun cancelYouTubeAudioRequest() {
        youtubeAudioGeneration++
        youtubeAudioRequest?.cancel(true)
        youtubeAudioRequest = null
        loadingYouTubePlaylist = false
        pendingYouTubeTrack = null
    }

    private fun openYouTubeDownloadPage(videoUrl: String? = null) {
        val currentBinding = _binding ?: return
        if (!isResumed || !currentBinding.youtubePanel.isShown || youtubeBrowserClosed || resettingYouTubeSession) return
        val existingPage = youtubeDownloadPage
        val page = existingPage ?: YouTubeDownloadPage(requireContext()) { path ->
            if (isResumed && showingYouTubeDownloadPage && _binding?.youtubePanel?.isShown == true) {
                saveYouTubeResult(path)
            }
        }
            .also {
                youtubeDownloadPage = it
                it.view.alpha = if (ambientLibraryStyle) AMBIENT_BROWSER_ALPHA else 1f
                currentBinding.youtubeBrowserContainer.addView(
                    it.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        if (existingPage == null || videoUrl != null) page.load(videoUrl)
        showingYouTubeDownloadPage = true
        updateYouTubeBrowser()
    }

    private fun closeYouTubeDownloadPage() {
        showingYouTubeDownloadPage = false
        updateYouTubeBrowser()
    }

    private fun releaseYouTubeDownloadPage() {
        showingYouTubeDownloadPage = false
        val page = youtubeDownloadPage ?: return
        youtubeDownloadPage = null
        _binding?.youtubeBrowserContainer?.removeView(page.view)
        page.destroy()
    }

    private fun saveYouTubeResult(path: String, allowFolderPicker: Boolean = true) {
        if (_binding == null || !YouTubeLibraryCopy.isManifestPath(path)) return
        val context = requireContext()
        if (savingYouTubeFiles) {
            Toast.makeText(context, R.string.youtube_copy_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        val tree = selectedTreeUri
        val resolver = context.contentResolver
        if (tree == null || resolver.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) {
            if (allowFolderPicker) {
                Toast.makeText(context, R.string.youtube_select_writable_folder, Toast.LENGTH_LONG).show()
                pendingYouTubeSave = path
                val initialFolder = tree ?: selectedFolderPath?.let { folder ->
                    val volume = selectedVolumeName?.takeUnless { it.startsWith("external") } ?: "primary"
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "$volume:${folder.trim('/')}",
                    )
                }
                launchFolderPicker(initialFolder)
            } else {
                Toast.makeText(context, R.string.youtube_save_folder_read_only, Toast.LENGTH_LONG).show()
            }
            return
        }
        val resultKey = tree to path
        if (resultKey in savedYouTubeResults) {
            Toast.makeText(context, R.string.youtube_result_already_saved, Toast.LENGTH_SHORT).show()
            return
        }
        val folder = getIndexedFolderName(tree)
        savingYouTubeFiles = true
        Toast.makeText(context, getString(R.string.youtube_saving_to_tracks, folder), Toast.LENGTH_SHORT).show()
        val callbackHandler = Handler(Looper.getMainLooper())
        folderExecutor.execute {
            val result = runCatching { YouTubeLibraryCopy.copy(resolver, tree, path) }
            callbackHandler.post {
                savingYouTubeFiles = false
                if (result.isSuccess) savedYouTubeResults.add(resultKey)
                if (_binding == null) return@post
                result.onSuccess { count ->
                    val message = resources.getQuantityString(R.plurals.youtube_saved_to_tracks, count, count, folder)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    if (selectedTreeUri == tree) loadMedia(preserveSelection = true)
                }.onFailure { error ->
                    Log.w(TAG, "Could not copy the YouTube result to Tracks", error)
                    Toast.makeText(context, R.string.youtube_save_to_tracks_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setLyricsQuery(query: String) {
        settingLyricsQuery = true
        binding.lyricsSearchInput.setText(query)
        settingLyricsQuery = false
    }

    private fun setAutoLyricsEnabled(enabled: Boolean) {
        if (autoLyricsEnabled == enabled) return
        autoLyricsEnabled = enabled
        preferences.edit().putBoolean(PREFERENCE_AUTO_LYRICS_ENABLED, enabled).apply()

        if (enabled) {
            loadLocalLyricsForCurrentMedia()
        } else {
            // Invalidate only the automatic lookup; manual searches remain independent.
            autoLyricsGeneration++
        }
    }


    private fun loadLocalLyricsForCurrentMedia(searchOnlineIfMissing: Boolean = true) {
        val media = currentMedia ?: return
        if (media.isPlaylist || savingLyrics) return

        val generation = ++localLyricsGeneration
        val store = lyricsSidecarStore
        checkingLocalLyrics = true
        updateLyricsDownloadButton()
        lyricsExecutor.execute {
            val result = runCatching { store.load(media) }
            playbackHandler.post {
                if (
                    _binding == null || generation != localLyricsGeneration ||
                        currentMedia?.uri != media.uri
                ) {
                    return@post
                }

                checkingLocalLyrics = false
                val localLyrics = result.getOrNull()
                if (localLyrics != null) {
                    displayLocalLyrics(localLyrics)
                } else {
                    updateLyricsDownloadButton()
                    result.onFailure { Log.w(TAG, "Could not inspect the media folder for an LRC sidecar", it) }
                    if (searchOnlineIfMissing) loadAutoLyricsForCurrentMedia()
                }
            }
        }
    }

    private fun loadAutoLyricsForCurrentMedia() {
        val media = currentMedia ?: return
        if (!autoLyricsEnabled || media.isPlaylist || lyricsSavedBesideMedia || savingLyrics) return

        val currentBinding = _binding ?: return
        val mediaKey = media.cacheKey
        val generation = ++autoLyricsGeneration
        lyricsGeneration++
        val query =
            currentBinding.lyricsSearchInput.text
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: LyricsRepository.filenameQuery(media.displayName)
        if (query.isBlank()) return

        val repository = lyricsRepository
        activeLyricsResult = null
        lyricsSavedBesideMedia = false
        updateLyricsDownloadButton()
        currentBinding.lyricsStatus.setText(R.string.lyrics_auto_searching)

        lyricsExecutor.execute {
            val result =
                runCatching {
                    runCatching { repository.loadOffline(mediaKey) }.getOrNull()
                        ?: repository.search(query).firstOrNull()?.also { found ->
                            runCatching { repository.saveOffline(mediaKey, found) }
                                .onFailure { Log.w(TAG, "Could not save automatic lyrics", it) }
                        }
                }

            playbackHandler.post {
                val activeBinding = _binding ?: return@post
                if (
                    generation != autoLyricsGeneration ||
                        currentMedia?.cacheKey != mediaKey ||
                        !autoLyricsEnabled || lyricsSavedBesideMedia || savingLyrics
                ) {
                    return@post
                }

                result.fold(
                    onSuccess = { lyrics ->
                        if (lyrics != null) {
                            displayLyrics(lyrics, persistOffline = false)
                        } else {
                            lyricLines = emptyList()
                            lyricText = ""
                            updateLyricsDelayAvailability()
                            activeBinding.lyricsBody.text = ""
                            activeBinding.lyricsStatus.setText(R.string.lyrics_auto_not_found)
                        }
                    },
                    onFailure = { error ->
                        Log.w(TAG, "Automatic lyrics lookup failed", error)
                        activeBinding.lyricsStatus.setText(
                            if (error is LyricsRepository.RateLimited) {
                                R.string.lyrics_rate_limit
                            } else {
                                R.string.lyrics_error
                            }
                        )
                    },
                )
            }
        }
    }

    private fun resetLyrics() {
        lyricsGeneration++
        autoLyricsGeneration++
        localLyricsGeneration++
        lyricsDownloadGeneration++
        lyricLines = emptyList()
        lyricText = ""
        highlightedLyric = -2
        resetLyricsDelay()
        activeLyricsResult = null
        lyricsSavedBesideMedia = false
        checkingLocalLyrics = false
        savingLyrics = false
        lyricsQueryEdited = false
        val media = currentMedia
        setLyricsQuery(media?.let { LyricsRepository.filenameQuery(it.displayName) }.orEmpty())
        binding.lyricsSearchInput.isEnabled = media != null
        binding.lyricsSearchButton.isEnabled = media != null
        binding.lyricsStatus.setText(
            if (media == null) R.string.lyrics_select_track else R.string.lyrics_disclosure
        )
        binding.lyricsBody.text = ""
        binding.lyricsResults.removeAllViews()
        binding.lyricsScroll.scrollTo(0, 0)
        updateLyricsDelayAvailability()
        updateLyricsDownloadButton()
    }


    private fun searchLyrics() {
        val query = binding.lyricsSearchInput.text.toString().trim()
        val media = currentMedia ?: return
        if (query.isEmpty()) return
        if (lyricsSavedBesideMedia || savingLyrics) return

        val repository = lyricsRepository
        val sidecarStore = lyricsSidecarStore
        val mediaKey = media.cacheKey
        val generation = ++lyricsGeneration
        autoLyricsGeneration++
        localLyricsGeneration++
        checkingLocalLyrics = true
        activeLyricsResult = null
        lyricsSavedBesideMedia = false
        updateLyricsDownloadButton()
        lyricsQueryEdited = true
        lyricLines = emptyList()
        updateLyricsDelayAvailability()
        binding.lyricsBody.text = ""
        binding.lyricsResults.removeAllViews()
        binding.lyricsSearchButton.isEnabled = false
        binding.lyricsStatus.setText(R.string.lyrics_loading)
        binding.lyricsSearchInput.clearFocus()
        WindowCompat.getInsetsController(requireActivity().window, binding.root)
            .hide(WindowInsetsCompat.Type.ime())

        lyricsExecutor.execute {
            val localLyrics = runCatching { sidecarStore.load(media) }
                .onFailure { Log.w(TAG, "Could not inspect the media folder for an LRC sidecar", it) }
                .getOrNull()
            val result = runCatching {
                if (localLyrics != null) return@runCatching emptyList()
                val offline = runCatching { repository.loadOffline(mediaKey) }.getOrNull()
                val online = try {
                    repository.search(query)
                } catch (exception: Exception) {
                    if (offline == null) throw exception
                    emptyList()
                }
                listOfNotNull(offline) + online.filterNot { candidate ->
                    offline != null &&
                        candidate.title.equals(offline.title, ignoreCase = true) &&
                        candidate.artist.equals(offline.artist, ignoreCase = true)
                }
            }

            playbackHandler.post {
                val currentBinding = _binding ?: return@post
                if (generation != lyricsGeneration || currentMedia?.cacheKey != mediaKey) return@post
                checkingLocalLyrics = false
                currentBinding.lyricsSearchButton.isEnabled = true
                if (localLyrics != null) {
                    displayLocalLyrics(localLyrics)
                    return@post
                }
                updateLyricsDownloadButton()
                result.fold(
                    onSuccess = { matches ->
                        currentBinding.lyricsStatus.setText(
                            if (matches.isEmpty()) R.string.lyrics_no_results
                            else R.string.lyrics_select_match
                        )
                        for (match in matches) {
                            val row =
                                com.google.android.material.button
                                    .MaterialButton(
                                        requireContext(),
                                        null,
                                        com.google.android.material.R.attr
                                            .materialButtonOutlinedStyle,
                                    )
                                    .apply {
                                        text =
                                            listOf(match.artist, match.title)
                                                .filter { it.isNotBlank() }
                                                .joinToString(" — ")
                                        isAllCaps = false
                                        setOnClickListener {
                                            if (!lyricsSavedBesideMedia && !savingLyrics) displayLyrics(match)
                                        }
                                    }
                            currentBinding.lyricsResults.addView(row)
                        }
                    },
                    onFailure = { error ->
                        currentBinding.lyricsStatus.setText(
                            if (error is LyricsRepository.RateLimited) R.string.lyrics_rate_limit
                            else R.string.lyrics_error
                        )
                    },
                )
            }
        }
    }

    private fun displayLocalLyrics(result: LyricsResult) {
        lyricsGeneration++
        autoLyricsGeneration++
        localLyricsGeneration++
        checkingLocalLyrics = false
        displayLyrics(
            result,
            persistOffline = false,
            savedBesideMedia = true,
            sourceLabelRes = R.string.lyrics_local_file,
        )
    }


    private fun displayLyrics(
        result: LyricsResult,
        persistOffline: Boolean = true,
        savedBesideMedia: Boolean = false,
        sourceLabelRes: Int? = null,
    ) {
        activeLyricsResult = result
        lyricsSavedBesideMedia = savedBesideMedia
        lyricLines = result.lines
        lyricText =
            when {
                lyricLines.isNotEmpty() -> lyricLines.joinToString("\n") { it.text }
                result.instrumental -> getString(R.string.lyrics_instrumental)
                else -> result.plain.ifBlank { getString(R.string.lyrics_no_results) }
            }
        highlightedLyric = -2
        binding.lyricsResults.removeAllViews()
        binding.lyricsStatus.text =
            listOf(
                result.artist,
                result.title,
                getString(sourceLabelRes ?: R.string.lyrics_source),
            )
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        binding.lyricsBody.text = lyricText
        binding.lyricsScroll.scrollTo(0, 0)
        updateLyricsDelayAvailability()
        syncLyrics(playbackPositionSeconds())
        updateLyricsDownloadButton()

        if (persistOffline) {
            val mediaKey = currentMedia?.cacheKey
            if (mediaKey != null) {
                val repository = lyricsRepository
                lyricsExecutor.execute {
                    runCatching { repository.saveOffline(mediaKey, result) }
                        .onFailure { Log.w(TAG, "Could not save offline lyrics", it) }
                }
            }
        }
    }




    private fun updateLyricsDownloadButton() {
        val button = _binding?.lyricsDownloadButton ?: return
        button.isEnabled = currentMedia != null && activeLyricsResult != null &&
            !lyricsSavedBesideMedia && !checkingLocalLyrics && !savingLyrics
        button.text =
            getString(
                if (lyricsSavedBesideMedia) R.string.lyrics_downloaded
                else R.string.lyrics_download
            )
        button.contentDescription =
            getString(
                if (lyricsSavedBesideMedia) R.string.lyrics_downloaded
                else R.string.lyrics_download_description
            )
        updateLyricsDelaySaveButton()
    }

    private fun downloadCurrentLyrics(saveDelay: Boolean = false) {
        if (checkingLocalLyrics || savingLyrics || (lyricsSavedBesideMedia && !saveDelay)) return
        val media = currentMedia ?: return
        val result = activeLyricsResult ?: return
        val delayMs = if (saveDelay) {
            if (result.lines.isEmpty() || lyricsDelaySeconds == 0) return
            lyricsDelaySeconds * 1_000L
        } else null
        val generation = ++lyricsDownloadGeneration
        val store = lyricsSidecarStore

        savingLyrics = true
        autoLyricsGeneration++
        updateLyricsDownloadButton()
        binding.lyricsStatus.setText(R.string.lyrics_saving_to_media_folder)

        lyricsExecutor.execute {
            val saved = runCatching {
                if (delayMs == null) store.saveIfAbsent(media, result)
                else store.saveWithDelay(media, result, delayMs)
            }
            playbackHandler.post {
                if (
                    _binding == null || generation != lyricsDownloadGeneration ||
                        currentMedia?.uri != media.uri
                ) {
                    return@post
                }

                savingLyrics = false
                saved.fold(
                    onSuccess = { localLyrics ->
                        if (saveDelay) resetLyricsDelay()
                        displayLocalLyrics(localLyrics)
                        Toast.makeText(
                            requireContext(),
                            if (saveDelay) R.string.lyrics_delay_saved else R.string.lyrics_saved_to_media_folder,
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                    onFailure = { error ->
                        Log.w(TAG, "Could not save LRC beside the media file", error)
                        updateLyricsDownloadButton()
                        binding.lyricsStatus.setText(R.string.lyrics_save_location_error)
                        Toast.makeText(
                            requireContext(),
                            R.string.lyrics_save_location_error,
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    private fun updateLyricsDelayAvailability() {
        val hasTimedLyrics = lyricLines.isNotEmpty()
        binding.lyricsDelayButton.isEnabled = hasTimedLyrics
        if (!hasTimedLyrics) binding.lyricsDelayControls.isVisible = false
    }

    private fun resetLyricsDelay() {
        lyricsDelaySeconds = 0
        binding.lyricsDelaySlider.progress = MAX_LYRICS_DELAY_SECONDS
        updateLyricsDelayValue()
    }

    private fun updateLyricsDelaySaveButton() {
        val currentBinding = _binding ?: return
        currentBinding.lyricsDelaySlider.isEnabled = !savingLyrics
        currentBinding.saveLyricsDelayButton.isEnabled = currentMedia != null &&
            activeLyricsResult?.lines?.isNotEmpty() == true && lyricsDelaySeconds != 0 &&
            !checkingLocalLyrics && !savingLyrics
    }

    private fun updateLyricsDelayValue() {
        val sign = when {
            lyricsDelaySeconds < 0 -> "−"
            lyricsDelaySeconds > 0 -> "+"
            else -> ""
        }
        val value = sign + formatDurationSeconds(abs(lyricsDelaySeconds).toDouble())
        binding.lyricsDelayValue.text = getString(R.string.lyrics_delay_value, value)
        ViewCompat.setStateDescription(binding.lyricsDelaySlider, value)
        updateLyricsDelaySaveButton()
    }

    private fun syncLyrics(positionSeconds: Double) {
        if (lyricLines.isEmpty() || !binding.lyricsPanel.isShown) return
        val positionMs = positionSeconds * 1_000
        val delayMs = lyricsDelaySeconds * 1_000L
        val index = lyricLines.indexOfLast { (it.milliseconds + delayMs).coerceAtLeast(0L) <= positionMs }
        if (index == highlightedLyric) return
        highlightedLyric = index
        val text = binding.lyricsBody
        if (index < 0) {
            text.text = lyricText
            binding.lyricsScroll.scrollTo(0, 0)
            return
        }
        val start = lyricLines.take(index).sumOf { it.text.length + 1 }
        val end = start + lyricLines[index].text.length
        text.text =
            android.text.SpannableString(lyricText).apply {
                if (start == end) return@apply
                setSpan(
                    android.text.style.ForegroundColorSpan(
                        MaterialColors.getColor(text, androidx.appcompat.R.attr.colorPrimary)
                    ),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        val scroll = binding.lyricsScroll
        text.post {
            if (highlightedLyric != index || _binding?.lyricsBody !== text) return@post
            text.layout?.let { layout ->
                val top = layout.getLineTop(layout.getLineForOffset(start)) + text.top
                scroll.smoothScrollTo(0, (top - scroll.height / 3).coerceAtLeast(0))
            }
        }
    }

    private fun initializePlayer() {
        try {
            Utils.copyAssets(requireContext())

            // Keep transparent areas of the embedded SurfaceView clear instead of
            // painting a separate opaque black rectangle behind the player.
            binding.mpvSurface.holder.setFormat(PixelFormat.TRANSLUCENT)

            binding.mpvSurface.initialize(
                requireContext().filesDir.path,
                requireContext().cacheDir.path,
            )

            binding.mpvSurface.alpha = 0f
            showAppIconPlaceholder(binding.artworkImage)

            val audioManager =
                requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

            registerAudioDeviceCallback(audioManager)

            audioSessionId = audioManager.generateAudioSessionId()
            if (audioSessionId != AudioManager.ERROR) {
                binding.mpvSurface.setAudioSessionId(audioSessionId)
            } else {
                Log.w(TAG, "AudioManager returned an invalid audio session ID")
            }

            MPVLib.addObserver(mpvObserver)
            playerInitialized = true
            MPVLib.setPropertyString("keep-open", "yes")

            playbackHandler.removeCallbacks(playbackTicker)
            playbackHandler.post(playbackTicker)
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to initialize the embedded MPV player", exception)
            showEmptyState(
                getString(R.string.player_initialization_error),
                getString(R.string.player_initialization_error_summary),
                false,
            )
        }
    }

    private fun registerAudioDeviceCallback(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioDeviceCallback != null) return

        val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    handleAudioOutputRouteChange(addedDevices)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    handleAudioOutputRouteChange(removedDevices)
                }
            }

        audioDeviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, playbackHandler)
    }

    private fun handleAudioOutputRouteChange(devices: Array<out AudioDeviceInfo>) {
        if (devices.none { it.isSink }) return

        playbackHandler.post {
            val activeUri = currentMedia?.uri ?: return@post
            if (!playerInitialized || _binding == null || currentMedia?.uri != activeUri) return@post

            // Android/libmpv owns audio-track routing. Never reload the file, seek, stop,
            // or toggle pause here. Rebind only the optional output visualizer.
            if (visualizerEnabled && MPVLib.getPropertyBoolean("pause") != true) {
                stopAudioVisualizer()
                playbackHandler.postDelayed(
                    startVisualizerRunnable,
                    AUDIO_ROUTE_VISUALIZER_REBIND_DELAY_MS,
                )
            }
            Log.d(TAG, "Audio output route changed; retaining the active MPV session")
        }
    }

    private fun unregisterAudioDeviceCallback() {
        val callback = audioDeviceCallback ?: return
        val manager =
            context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching { manager?.unregisterAudioDeviceCallback(callback) }
        audioDeviceCallback = null
    }

    private fun onVisualizerButtonClicked() {
        if (visualizerEnabled) {
            visualizerEnabled = false
            preferences.edit().putBoolean(PREFERENCE_VISUALIZER_ENABLED, false).apply()
            stopAudioVisualizer()
            updateVisualizerAppearance()
            return
        }

        if (!hasVisualizerPermission()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.visualizer_permission_title)
                .setMessage(R.string.visualizer_permission_explanation)
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    visualizerPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        visualizerEnabled = true
        preferences.edit().putBoolean(PREFERENCE_VISUALIZER_ENABLED, true).apply()
        updateVisualizerAppearance()
        startAudioVisualizer()
    }

    private fun hasVisualizerPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateVisualizerAppearance() {
        val currentBinding = _binding ?: return

        currentBinding.audioSpectrumView.visibility =
            if (visualizerEnabled && currentMedia != null) View.VISIBLE else View.GONE

        val tint =
            MaterialColors.getColor(
                currentBinding.audioVisualizerButton,
                if (visualizerEnabled) {
                    androidx.appcompat.R.attr.colorPrimary
                } else {
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                },
            )
        currentBinding.audioVisualizerButton.imageTintList = ColorStateList.valueOf(tint)
        currentBinding.audioVisualizerButton.alpha = if (visualizerEnabled) 1f else 0.86f

        if (!visualizerEnabled) {
            currentBinding.audioSpectrumView.clear()
        }
    }

    private fun startAudioVisualizer() {
        if (
            !visualizerEnabled ||
                !playerInitialized ||
                currentMedia == null ||
                audioSessionId == AudioManager.ERROR ||
                _binding == null ||
                !isResumed ||
                !binding.playerPanel.isShown
        ) {
            return
        }

        if (!hasVisualizerPermission()) {
            return
        }

        stopAudioVisualizer()

        try {
            val effect = Visualizer(audioSessionId)
            visualizer = effect
            val captureRange = Visualizer.getCaptureSizeRange()
            val preferredSize = minOf(captureRange[1], 1024)
            val captureSize = Integer.highestOneBit(preferredSize)

            if (captureSize < captureRange[0]) {
                effect.release()
                Log.w(TAG, "No valid Visualizer capture size is available")
                return
            }

            effect.captureSize = captureSize
            effect.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED)
            effect.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int,
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) {
                        val data = fft?.clone() ?: return
                        playbackHandler.post {
                            if (this@MainScreenFragment.visualizer === visualizer)
                                _binding?.audioSpectrumView?.setFftData(data)
                        }
                    }
                },
                minOf(Visualizer.getMaxCaptureRate(), 24_000),
                false,
                true,
            )

            effect.enabled = true
            visualizer = effect
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to start the audio visualizer", exception)
            stopAudioVisualizer()
            _binding?.audioSpectrumView?.clear()
        }
    }

    private fun stopAudioVisualizer() {
        playbackHandler.removeCallbacks(startVisualizerRunnable)

        val activeVisualizer = visualizer
        visualizer = null

        if (activeVisualizer != null) {
            try {
                activeVisualizer.enabled = false
                activeVisualizer.setDataCaptureListener(null, 0, false, false)
                activeVisualizer.release()
            } catch (exception: Exception) {
                Log.d(TAG, "Visualizer cleanup completed with a warning", exception)
            }
        }

        _binding?.audioSpectrumView?.clear()
    }

    private fun setSortMode(mode: SortMode) {
        if (sortMode == mode) {
            sortAscending = !sortAscending
        } else {
            sortMode = mode
            // Default order: A-Z, newest first, and shortest first.
            sortAscending = mode != SortMode.LAST_MODIFIED
        }
        renderMedia()
    }


    private fun updateSortButtons() {
        val currentBinding = _binding ?: return
        updateSortButton(currentBinding.sortName, SortMode.NAME)
        updateSortButton(currentBinding.sortModified, SortMode.LAST_MODIFIED)
        updateSortButton(currentBinding.sortDuration, SortMode.DURATION)
        currentBinding.portraitSortButton.contentDescription = getString(
            R.string.sort_tracks_current,
            describeSortMode(sortMode),
        )
    }

    private fun updateTrackSortVisibility() {
        val currentBinding = _binding ?: return
        val enabled = activePlaylist == null
        currentBinding.portraitSortButton.visibility =
            if (enabled && isPortraitLayout) View.VISIBLE else View.GONE
        currentBinding.mediaSortControls.visibility =
            if (enabled && !isPortraitLayout) View.VISIBLE else View.GONE
    }

    private fun showTrackSortMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        SortMode.entries.forEach { mode ->
            popup.menu.add(0, mode.ordinal, mode.ordinal, describeSortMode(mode))
        }
        popup.setOnMenuItemClickListener { item ->
            setSortMode(SortMode.entries[item.itemId])
            true
        }
        popup.show()
    }

    private fun describeSortMode(mode: SortMode): String {
        val label = getString(mode.label)
        if (mode != sortMode) return label
        return getString(
            if (sortAscending) R.string.sort_order_ascending else R.string.sort_order_descending,
            label,
        )
    }

    private fun updateSortButton(button: TextView, mode: SortMode) {
        val selected = sortMode == mode
        val label = getString(mode.label)
        button.text =
            if (selected) {
                "$label ${if (sortAscending) "↑" else "↓"}"
            } else {
                label
            }

        button.setBackgroundResource(
            if (selected) {
                R.drawable.bg_waguri_chip_selected
            } else {
                R.drawable.bg_waguri_chip_unselected
            }
        )
        button.background.mutate().alpha = when {
            !ambientLibraryStyle -> 255
            selected -> AMBIENT_SORT_SELECTED_ALPHA
            else -> AMBIENT_SORT_UNSELECTED_ALPHA
        }

        val colorAttribute =
            if (selected) {
                com.google.android.material.R.attr.colorOnSecondaryContainer
            } else {
                com.google.android.material.R.attr.colorOnSurface
            }

        MaterialColors.getColor(button, colorAttribute).let(button::setTextColor)
        button.contentDescription = describeSortMode(mode)
    }


    private val allFilesSettingsLauncher =
        registerForActivityResult(StartActivityForResult()) {
            if (_binding != null && SharedStorageFolders.hasAllFilesAccess())
                openSharedFolderBrowser()
        }

    private fun openFolderBrowser() {
        val folders = libraryFolderIndex.read()
        if (folders.isEmpty()) {
            launchFolderPicker()
            return
        }

        val readable = readableLibraryFolders()
        val labels = folders.map { uri ->
            val name = getIndexedFolderName(uri)
            if (uri in readable) name else getString(R.string.library_folder_unavailable, name)
        }
        val selectedIndex = folders.indexOfFirst { it == selectedTreeUri }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.library_folders)
            .setSingleChoiceItems(labels.toTypedArray(), selectedIndex) { list, index ->
                list.dismiss()
                val folder = folders[index]
                if (folder in readableLibraryFolders()) selectLibraryFolder(folder)
                else launchFolderPicker(folder)
            }
            .setPositiveButton(R.string.library_add_folder) { _, _ -> launchFolderPicker() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        libraryFoldersDialog = dialog
        dialog.setOnDismissListener {
            if (libraryFoldersDialog === dialog) libraryFoldersDialog = null
        }
    }

    private fun readableLibraryFolders(): Set<Uri> =
        requireContext().contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .mapTo(mutableSetOf()) { it.uri }

    private fun selectLibraryFolder(uri: Uri) {
        selectedTreeUri = uri
        selectedSharedFolder = null
        selectedFolderPath = null
        selectedVolumeName = null
        libraryFolderIndex.write(preferences.edit(), libraryFolderIndex.read() + uri)
            .putString(PREFERENCE_TREE_URI, uri.toString())
            .remove(PREFERENCE_SHARED_FOLDER)
            .remove(PREFERENCE_MEDIASTORE_FOLDER)
            .remove(PREFERENCE_MEDIASTORE_VOLUME)
            .remove(PREFERENCE_FOLDER_PATH)
            .apply()
        binding.mediaSearchInput.text?.clear()
        loadMedia()
    }

    private fun launchFolderPicker(initialFolder: Uri? = selectedTreeUri) {
        try {
            folderTreePickerLauncher.launch(initialFolder)
        } catch (exception: android.content.ActivityNotFoundException) {
            pendingYouTubeSave = null
            Log.e(TAG, "Android document-tree picker is unavailable", exception)
            Toast.makeText(
                requireContext(),
                R.string.folder_access_error,
                Toast.LENGTH_LONG,
            ).show()
            restoreFolderStatus()
        } catch (exception: IllegalStateException) {
            pendingYouTubeSave = null
            Log.e(TAG, "Could not launch the document-tree picker", exception)
            Toast.makeText(
                requireContext(),
                R.string.folder_access_error,
                Toast.LENGTH_LONG,
            ).show()
            restoreFolderStatus()
        }
    }


    private fun openSharedFolderBrowser() {
        if (
            Build.VERSION.SDK_INT < 30 ||
                openingSharedFolders ||
                sharedFolderDialog?.isShowing == true
        )
            return
        val appContext = requireContext().applicationContext
        val owner = viewLifecycleOwner
        val savedFolder = selectedSharedFolder
        openingSharedFolders = true
        folderExecutor.execute {
            val result = runCatching {
                val volumes = SharedStorageFolders.volumes(appContext)
                val initialFolder =
                    savedFolder?.let(SharedStorageFolders::canonicalFile)?.takeIf { directory ->
                        directory.isDirectory &&
                            volumes.any { SharedStorageFolders.contains(it.root, directory) }
                    }
                volumes to initialFolder
            }
            playbackHandler.post {
                if (_binding == null || viewLifecycleOwner !== owner) return@post
                openingSharedFolders = false
                val (volumes, initialFolder) =
                    result.getOrElse {
                        Log.w(TAG, "Could not list shared storage volumes", it)
                        emptyList<SharedStorageFolders.Volume>() to null
                    }
                if (volumes.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.no_shared_storage, Toast.LENGTH_LONG)
                        .show()
                    return@post
                }
                val browser =
                    SharedFolderBrowser(requireContext(), volumes, initialFolder, folderExecutor) {
                        folder ->
                        selectedSharedFolder = folder
                        selectedTreeUri = null
                        selectedFolderPath = null
                        selectedVolumeName = null
                        preferences
                            .edit()
                            .putString(PREFERENCE_SHARED_FOLDER, folder.path)
                            .remove(PREFERENCE_TREE_URI)
                            .remove(PREFERENCE_MEDIASTORE_FOLDER)
                            .remove(PREFERENCE_MEDIASTORE_VOLUME)
                            .apply()
                        loadMedia()
                    }
                sharedFolderDialog = browser.dialog
                browser.dialog.setOnDismissListener {
                    if (sharedFolderDialog === browser.dialog) sharedFolderDialog = null
                }
                browser.dialog.show()
            }
        }
    }

    private fun openIndexedFolderBrowser() {
        val permissions = requiredStoragePermissions()
        if (
            permissions.any {
                ContextCompat.checkSelfPermission(requireContext(), it) ==
                    PackageManager.PERMISSION_GRANTED
            }
        ) {
            showMediaFolderPicker()
        } else {
            storagePermissionLauncher.launch(permissions)
        }
    }

    private fun persistMediaReadPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (exception: SecurityException) {
            Log.w(TAG, "Could not persist read access for the selected document", exception)
        }
    }

    private fun importSelectedFiles(uris: List<Uri>) {
        val appContext = requireContext().applicationContext
        val generation = ++playlistRequestGeneration
        folderExecutor.execute {
            var failed = 0
            val imported =
                uris.distinct().mapNotNull { uri ->
                    try {
                        val resolver = appContext.contentResolver
                        try {
                            resolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        } catch (exception: SecurityException) {
                            Log.w(TAG, "Provider did not grant persistent read/write access; retrying read-only", exception)
                            runCatching {
                                resolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                        }
                        val name =
                            resolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                                ?: uri.lastPathSegment.orEmpty()
                        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val mime = resolver.getType(uri).orEmpty()
                        val media = MediaFile(uri, name, resolveMimeType(mime, extension), 0L)
                        if (
                            extension !in SUPPORTED_EXTENSIONS &&
                                !media.isPlaylist &&
                                !mime.startsWith("audio/") &&
                                !mime.startsWith("video/")
                        ) {
                            failed++
                            null
                        } else {
                            if (!media.isPlaylist) media.durationMs = readDuration(appContext, uri)
                            media
                        }
                    } catch (exception: Exception) {
                        failed++
                        Log.w(TAG, "Could not import selected document", exception)
                        null
                    }
                }
            playbackHandler.post {
                if (_binding == null || generation != playlistRequestGeneration) return@post
                val (playlists, files) = imported.partition { it.isPlaylist }
                if (files.isNotEmpty()) {
                    libraryStore.save(
                        "files", (importedLibraryFiles() + files).distinctBy { it.uri }
                    )
                    allMedia = (allMedia + files).distinctBy { it.uri }
                }
                if (playlists.isNotEmpty()) {
                    openPlaylistFiles(playlists.map { it.uri })
                } else if (files.isNotEmpty()) {
                    clearPlaylistSelection()
                    clearMediaSearch()
                    val playable = files.filter(::canPlayTrack)
                    playable.firstOrNull()?.let {
                        queue.replace(playable)
                        playMedia(it)
                        showPanel(false)
                    }
                }
                if (files.isNotEmpty() || failed > 0) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.import_summary, files.size, failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun requiredStoragePermissions(): Array<String> {
        val targetsAndroid13OrNewer =
            requireContext().applicationInfo.targetSdkVersion >= Build.VERSION_CODES.TIRAMISU

        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && targetsAndroid13OrNewer
        ) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun showMediaFolderPicker() {
        val appContext = requireContext().applicationContext
        val owner = viewLifecycleOwner
        scanExecutor.execute {
            val result = runCatching { queryMediaStoreFiles(appContext, null) }
            playbackHandler.post {
                if (_binding == null || viewLifecycleOwner !== owner) return@post
                result.onSuccess(::browseIndexedFolders).onFailure { error ->
                    Log.w(TAG, "Could not read indexed folders", error)
                    Toast.makeText(
                            requireContext(),
                            R.string.folder_access_error,
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
            }
        }
    }

    private data class IndexedFolder(val volume: String?, val path: String)

    private fun browseIndexedFolders(files: List<MediaFile>) {
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_indexed_media, Toast.LENGTH_LONG).show()
            return
        }

        val volumes = files.groupBy { it.volumeName }
        val folders = linkedSetOf<IndexedFolder>()
        for (file in files) {
            var path = file.folderPath
            while (path.isNotEmpty()) {
                folders.add(IndexedFolder(file.volumeName, path))
                path = normalizeRelativeFolderPath(path.trimEnd('/').substringBeforeLast('/', ""))
            }
        }

        val storageManager = requireContext().getSystemService(StorageManager::class.java)
        fun volumeLabel(name: String?): String {
            if (name == null || name == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                return getString(R.string.internal_storage_label)
            }
            return if (Build.VERSION.SDK_INT >= 24) {
                storageManager
                    ?.storageVolumes
                    ?.firstOrNull { it.uuid.equals(name, ignoreCase = true) }
                    ?.getDescription(requireContext()) ?: name
            } else name
        }

        val layout =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_folder_picker, null)
        val search = layout.findViewById<EditText>(R.id.folderSearchInput)
        val list = layout.findViewById<ListView>(R.id.folderListView)
        val pathLabel = layout.findViewById<TextView>(R.id.folderBreadcrumb)
        val empty = layout.findViewById<TextView>(R.id.folderEmptyText)
        val up = layout.findViewById<View>(R.id.folderUpButton)
        list.emptyView = empty
        list.layoutParams =
            list.layoutParams.apply {
                height = dp((resources.configuration.screenHeightDp - 220).coerceIn(96, 260))
            }
        val adapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1)
        list.adapter = adapter

        val saved =
            folders.singleOrNull {
                it.path == selectedFolderPath &&
                    (selectedVolumeName == null || it.volume == selectedVolumeName)
            }
        var folder =
            saved ?: if (volumes.size == 1) IndexedFolder(volumes.keys.first(), "") else null
        var rows: List<IndexedFolder> = emptyList()

        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.choose_folder)
                .setView(layout)
                .setPositiveButton(R.string.use_this_folder, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

        fun refresh() {
            val current = folder
            val query = search.text.toString().trim()
            val storageRoots = volumes.keys.map { IndexedFolder(it, "") }
            rows =
                when {
                    query.isNotEmpty() ->
                        folders.filter {
                            (current == null ||
                                it.volume == current.volume && it.path.startsWith(current.path)) &&
                                it != current &&
                                it.path.contains(query, ignoreCase = true)
                        }
                    current == null -> storageRoots
                    else ->
                        folders.filter {
                            it.volume == current.volume &&
                                it.path != current.path &&
                                normalizeRelativeFolderPath(
                                    it.path.trimEnd('/').substringBeforeLast('/', "")
                                ) == current.path
                        }
                }.sortedBy {
                    if (it.path.isEmpty()) volumeLabel(it.volume).lowercase(Locale.ROOT)
                    else it.path.lowercase(Locale.ROOT)
                }
            pathLabel.text =
                if (current == null) getString(R.string.indexed_storage_label)
                else
                    listOf(volumeLabel(current.volume), current.path.trimEnd('/'))
                        .filter { it.isNotBlank() }
                        .joinToString(" / ")
            up.isEnabled = current != null && (current.path.isNotEmpty() || volumes.size > 1)
            empty.setText(
                if (query.isEmpty()) R.string.no_indexed_subfolders
                else R.string.no_matching_folders
            )
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled =
                current != null
            adapter.clear()
            adapter.addAll(
                rows.map {
                    when {
                        it.path.isEmpty() -> volumeLabel(it.volume)
                        query.isNotEmpty() -> "${volumeLabel(it.volume)} / ${it.path.trimEnd('/')}"
                        else -> it.path.trimEnd('/').substringAfterLast('/')
                    }
                }
            )
            list.setSelection(0)
        }

        dialog.setOnShowListener {
            search.doAfterTextChanged { refresh() }
            up.setOnClickListener {
                val current = folder ?: return@setOnClickListener
                folder =
                    if (current.path.isEmpty()) null
                    else
                        current.copy(
                            path =
                                normalizeRelativeFolderPath(
                                    current.path.trimEnd('/').substringBeforeLast('/', "")
                                )
                        )
                search.text?.clear()
                refresh()
            }
            list.setOnItemClickListener { _, _, index, _ ->
                folder = rows.getOrNull(index) ?: return@setOnItemClickListener
                search.text?.clear()
                search.clearFocus()
                dialog.window?.let { window ->
                    WindowCompat.getInsetsController(window, layout)
                        .hide(WindowInsetsCompat.Type.ime())
                }
                refresh()
            }
            dialog
                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val chosen = folder ?: return@setOnClickListener
                    selectedSharedFolder = null
                    selectedFolderPath = chosen.path
                    selectedVolumeName = chosen.volume
                    selectedTreeUri = null
                    preferences
                        .edit()
                        .remove(PREFERENCE_SHARED_FOLDER)
                        .putString(PREFERENCE_MEDIASTORE_FOLDER, chosen.path)
                        .putString(PREFERENCE_MEDIASTORE_VOLUME, chosen.volume)
                        .remove(PREFERENCE_FOLDER_PATH)
                        .remove(PREFERENCE_TREE_URI)
                        .apply()
                    dialog.dismiss()
                    loadMedia()
                }
            refresh()
        }
        dialog.show()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
    }

    private fun importedLibraryFiles(): List<MediaFile> =
        libraryStore.read("files").filterNot { it.isPlaylist }

    private fun restoreFolderStatus() {
        val currentBinding = _binding ?: return
        updateYouTubeControls()
        currentBinding.changeFolderButton.setText(
            if (selectedTreeUri != null || selectedFolderPath != null || selectedSharedFolder != null)
                R.string.library_folders else R.string.choose_folder
        )
        val playlist = activePlaylist
        currentBinding.resetPlaylistButton.visibility =
            if (playlist == null && removedTrackUris.isEmpty()) View.GONE else View.VISIBLE
        if (playlist != null) {
            currentBinding.folderStatus.text =
                getString(
                    R.string.folder_summary, playlist.name,
                    playlist.tracks.count { it.uri !in removedTrackUris },
                )
            return
        }
        if (scanningFolder) {
            currentBinding.folderStatus.text = getString(R.string.scanning_media)
            return
        }
        val folderName =
            selectedSharedFolder?.path
                ?: selectedFolderPath?.let(::getFolderName)
                ?: selectedTreeUri?.let(::getFolderName)

        if (folderName != null) {
            currentBinding.folderStatus.text =
                getString(R.string.folder_summary, folderName, folderMediaCount)
        } else {
            currentBinding.folderStatus.text = getString(R.string.folder_not_selected)
        }
    }

    private fun restoreSelectedFolder() {
        allMedia = importedLibraryFiles()
        // The custom all-files-access folder browser has been retired.
        // Re-select the folder through Android's document-tree picker instead.
        selectedSharedFolder = null
        preferences.edit().remove(PREFERENCE_SHARED_FOLDER).apply()

        val savedRelativePath = preferences.getString(PREFERENCE_MEDIASTORE_FOLDER, null)

        if (savedRelativePath != null) {
            selectedVolumeName = preferences.getString(PREFERENCE_MEDIASTORE_VOLUME, null)
            selectedFolderPath = normalizeRelativeFolderPath(savedRelativePath)
            selectedTreeUri = null
            loadMedia()
            return
        }

        // v6 stored a raw absolute path. It cannot be used under scoped storage.
        preferences.edit().remove(PREFERENCE_FOLDER_PATH).apply()

        val folders = libraryFolderIndex.read()
        val readable = readableLibraryFolders()
        val savedUri = preferences.getString(PREFERENCE_TREE_URI, null)?.let(Uri::parse)
        val uri = savedUri?.takeIf { it in readable } ?: folders.firstOrNull { it in readable }
        val editor = libraryFolderIndex.write(preferences.edit(), folders)
        if (uri == null) {
            editor.remove(PREFERENCE_TREE_URI)
        } else {
            editor.putString(PREFERENCE_TREE_URI, uri.toString())
        }
        editor.apply()
        selectedTreeUri = uri
        selectedFolderPath = null
        if (uri == null) renderMedia() else loadMedia()
    }

    private fun showFolderPrompt() {
        val currentBinding = _binding ?: return
        currentBinding.folderStatus.text = getString(R.string.folder_not_selected)
        showEmptyState(
            getString(R.string.select_media_folder),
            getString(R.string.select_media_folder_summary),
            true,
        )
    }

    private fun showEmptyState(title: String, message: String, showChooseButton: Boolean) {
        val currentBinding = _binding ?: return
        currentBinding.emptyStateTitle.text = title
        currentBinding.emptyStateMessage.text = message
        currentBinding.chooseFolderButton.visibility =
            if (showChooseButton) View.VISIBLE else View.GONE
        currentBinding.emptyState.visibility = View.VISIBLE
        currentBinding.mediaRecycler.visibility = View.GONE
    }

    private fun loadMedia(preserveSelection: Boolean = false) {
        val restoreQueueFromFolder = !preserveSelection && removedTrackUris.isNotEmpty() && activePlaylist == null
        if (!preserveSelection) {
            removedTrackUris.clear()
            clearPlaylistSelection()
        }
        val sharedFolder = selectedSharedFolder
        val folderPath = selectedFolderPath
        val volumeName = selectedVolumeName
        val treeUri = selectedTreeUri

        if (sharedFolder == null && folderPath == null && treeUri == null) {
            showFolderPrompt()
            return
        }

        val generation = ++scanGeneration
        val appContext = requireContext().applicationContext
        if (!preserveSelection) {
            allMedia = emptyList()
            folderMediaCount = 0
        }
        scanningFolder = true
        folderScanFailed = false
        renderMedia()

        scanExecutor.execute {
            try {
                val files = (
                    when {
                        sharedFolder != null ->
                            scanSharedFolder(appContext, sharedFolder, generation)
                        folderPath != null ->
                            queryMediaStoreFiles(appContext, folderPath, volumeName)
                        else -> scanMediaTree(appContext, requireNotNull(treeUri))
                    }
                ).filterNot { it.isPlaylist }

                activity?.runOnUiThread {
                    if (!isAdded || _binding == null || generation != scanGeneration) {
                        return@runOnUiThread
                    }

                    folderMediaCount = files.size
                    scanningFolder = false
                    allMedia = (files + importedLibraryFiles()).distinctBy { it.uri }
                    renderMedia()
                    if (restoreQueueFromFolder && activePlaylist == null) restoreFolderQueue()
                }
                files.forEach { mediaFile ->
                    if (Thread.currentThread().isInterrupted || generation != scanGeneration)
                        return@execute
                    val duration = readDuration(appContext, mediaFile.uri)
                    playbackHandler.post {
                        if (_binding != null && generation == scanGeneration)
                            mediaFile.durationMs = duration
                    }
                }
                playbackHandler.post {
                    if (_binding != null && generation == scanGeneration) renderMedia()
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to scan the selected media folder", exception)
                activity?.runOnUiThread {
                    if (!isAdded || _binding == null || generation != scanGeneration) {
                        return@runOnUiThread
                    }

                    scanningFolder = false
                    folderScanFailed = true
                    if (!preserveSelection) allMedia = importedLibraryFiles()
                    renderMedia()
                    if (restoreQueueFromFolder && activePlaylist == null) restoreFolderQueue()
                    if (activePlaylist != null || allMedia.isNotEmpty()) {
                        Toast.makeText(
                                requireContext(),
                                R.string.folder_access_error,
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                }
            }
        }
    }

    private fun scanSharedFolder(
        context: Context,
        selected: File,
        generation: Int,
    ): List<MediaFile> {
        if (Build.VERSION.SDK_INT < 30 || !SharedStorageFolders.hasAllFilesAccess()) {
            throw java.io.IOException("All files access is unavailable")
        }
        val root =
            SharedStorageFolders.canonicalFile(selected)
                ?: throw java.io.IOException("Invalid folder")
        check(
            SharedStorageFolders.volumes(context).any {
                SharedStorageFolders.contains(it.root, root)
            }
        )
        val pending = ArrayDeque<File>().apply { add(root) }
        val result = mutableListOf<MediaFile>()
        val mimeMap = android.webkit.MimeTypeMap.getSingleton()
        while (pending.isNotEmpty()) {
            if (Thread.currentThread().isInterrupted || generation != scanGeneration) {
                throw InterruptedException("Folder scan cancelled")
            }
            val folder = pending.removeLast()
            val entries = SharedStorageFolders.readableEntries(folder)
            if (entries == null && folder == root)
                throw java.io.IOException("Folder is not readable")
            for (entry in entries.orEmpty()) {
                if (entry.isDirectory) {
                    pending.add(entry)
                } else if (entry.isFile) {
                    val extension = entry.extension.lowercase(Locale.ROOT)
                    val mime = mimeMap.getMimeTypeFromExtension(extension).orEmpty()
                    if (
                        extension !in SUPPORTED_EXTENSIONS &&
                            !mime.startsWith("audio/") &&
                            !mime.startsWith("video/")
                    )
                        continue
                    result.add(
                        MediaFile(
                            uri = Uri.fromFile(entry),
                            displayName = entry.name,
                            mimeType = resolveMimeType(mime, extension),
                            lastModified = entry.lastModified(),
                            folderPath = folder.path,
                        )
                    )
                }
            }
        }
        return result
    }

    private fun updateMediaListJumpButtons() {
        val currentBinding = _binding ?: return
        val hasItems = mediaAdapter.itemCount > 0
        currentBinding.jumpToTopButton.isEnabled = hasItems
        currentBinding.jumpToBottomButton.isEnabled = hasItems
        val alpha = if (hasItems) 1f else 0.38f
        currentBinding.jumpToTopButton.alpha = alpha
        currentBinding.jumpToBottomButton.alpha = alpha
    }

    private fun jumpToMediaListEdge(toBottom: Boolean) {
        val currentBinding = _binding ?: return
        val itemCount = mediaAdapter.itemCount
        if (itemCount <= 0) return

        val targetPosition = if (toBottom) itemCount - 1 else 0
        val layoutManager =
            currentBinding.mediaRecycler.layoutManager as? LinearLayoutManager ?: return
        currentBinding.mediaRecycler.stopScroll()
        layoutManager.scrollToPositionWithOffset(targetPosition, 0)
        // This is an immediate position jump, not an animated traversal of every item.
        // Do not change currentMedia or the selected URI: playback and its marker stay intact.
    }

    private fun scrollToCurrentTrack(smooth: Boolean = false) {
        val media = currentMedia ?: return
        val currentBinding = _binding ?: return
        if (currentBinding.libraryPanel.visibility != View.VISIBLE) return

        val index = sortedMedia.indexOfFirst { it.queueKey == media.queueKey }
        if (index < 0) return

        currentBinding.mediaRecycler.post {
            if (_binding !== currentBinding) return@post
            val layoutManager =
                currentBinding.mediaRecycler.layoutManager as? LinearLayoutManager ?: return@post

            if (smooth) {
                currentBinding.mediaRecycler.smoothScrollToPosition(index)
            } else {
                layoutManager.scrollToPositionWithOffset(index, dp(12))
            }
        }
    }

    private fun renderMedia(removedTrackKey: String? = null) {
        val currentBinding = _binding ?: return
        val playlist = activePlaylist
        restoreFolderStatus()
        updateTrackSortVisibility()
        updateSortButtons()

        val baseSorted =
            playlist?.tracks ?: when (sortMode) {
                SortMode.NAME ->
                    if (sortAscending) {
                        allMedia.sortedBy { it.displayName.lowercase(Locale.ROOT) }
                    } else {
                        allMedia.sortedByDescending { it.displayName.lowercase(Locale.ROOT) }
                    }

                SortMode.LAST_MODIFIED -> {
                    val knownDates = allMedia.filter { it.lastModified > 0L }
                    val unknownDates = allMedia.filter { it.lastModified <= 0L }
                    val sortedKnown =
                        if (sortAscending) {
                            knownDates.sortedBy { it.lastModified }
                        } else {
                            knownDates.sortedByDescending { it.lastModified }
                        }
                    sortedKnown + unknownDates
                }

                SortMode.DURATION -> {
                    // Unknown durations stay at the bottom in either direction.
                    val knownDurations = allMedia.filter { it.durationMs != null }
                    val unknownDurations = allMedia.filter { it.durationMs == null }
                    val sortedKnown =
                        if (sortAscending) {
                            knownDurations.sortedBy { it.durationMs }
                        } else {
                            knownDurations.sortedByDescending { it.durationMs }
                        }
                    sortedKnown + unknownDurations
                }
            }

        val query = currentBinding.mediaSearchInput.text?.toString()?.trim().orEmpty()

        sortedMedia = baseSorted.filter {
            it.uri !in removedTrackUris &&
                (query.isBlank() || it.displayName.contains(query, ignoreCase = true) ||
                    it.playlistTitle?.contains(query, ignoreCase = true) == true)
        }

        mediaAdapter.submitList(sortedMedia, currentMedia?.queueKey, removedTrackKey)
        updateMediaListJumpButtons()
        if (removedTrackKey == null) scrollToCurrentTrack()

        if (sortedMedia.isEmpty()) {
            when {
                baseSorted.isNotEmpty() && baseSorted.all { it.uri in removedTrackUris } ->
                    showEmptyState(
                        getString(R.string.all_tracks_removed),
                        getString(R.string.all_tracks_removed_summary),
                        false,
                    )

                query.isNotBlank() ->
                    showEmptyState(
                        getString(R.string.no_search_results),
                        getString(R.string.no_search_results_summary, query),
                        false,
                    )

                playlist != null ->
                    showEmptyState(
                        getString(R.string.no_playlist_tracks),
                        getString(R.string.no_playlist_tracks_summary),
                        false,
                    )

                scanningFolder ->
                    showEmptyState(
                        getString(R.string.scanning_media),
                        getString(R.string.scanning_media_summary),
                        false,
                    )

                folderScanFailed ->
                    showEmptyState(
                        getString(R.string.folder_access_error),
                        getString(
                            if (selectedSharedFolder != null && !SharedStorageFolders.hasAllFilesAccess())
                                R.string.all_files_folder_explanation
                            else R.string.folder_access_error_summary
                        ),
                        true,
                    )

                selectedTreeUri == null &&
                    selectedFolderPath == null &&
                    selectedSharedFolder == null &&
                    allMedia.isEmpty() -> showFolderPrompt()

                else ->
                    showEmptyState(
                        getString(R.string.no_media_found),
                        getString(R.string.no_media_found_summary),
                        true,
                    )
            }
        } else {
            currentBinding.emptyState.visibility = View.GONE
            currentBinding.mediaRecycler.visibility = View.VISIBLE
        }

        updateNavigationButtons()
    }


    private fun playMedia(mediaFile: MediaFile, temporaryAudio: YouTubeTempAudio.Audio? = null) {
        if (!canPlayTrack(mediaFile)) return
        if (mediaFile.temporaryYouTubeAudio && temporaryAudio == null) {
            val format = currentYouTubeAudioFormat.takeIf { currentMedia?.queueKey == mediaFile.queueKey }
            prepareYouTubeTrack(mediaFile, format)
            return
        }
        cancelYouTubeAudioRequest()
        if (!playerInitialized) {
            showEmptyState(
                getString(R.string.player_initialization_error),
                getString(R.string.player_initialization_error_summary),
                false,
            )
            return
        }

        startMusicService()
        val mediaPath = temporaryAudio?.uri?.toString() ?: resolveMpvPath(mediaFile.uri)
        playbackFinished = false
        durationCountdownEnabled = false
        mediaSwipeAnimationRunning = false
        if (mediaPath.isBlank()) {
            showPlaybackError()
            return
        }

        stopAudioVisualizer()
        binding.playerCircleContainer.animate().cancel()
        binding.playerCircleContainer.translationX = 0f
        binding.playerCircleContainer.alpha = 1f

        if (isVideoFullscreen) exitVideoFullscreen()
        currentMedia = mediaFile
        currentYouTubeAudioFormat = temporaryAudio?.format
        temporaryAudioCover = temporaryAudio?.cover
        resetLyrics()
        updateMediaViewportControls()
        binding.artworkImage.visibility = View.VISIBLE

        try {
            // Suppress the END_FILE event generated by replacing the current item.
            isReplacingMedia = true
            updateVideoModeButton()
            playbackHandler.removeCallbacks(loadTimeout)
            playbackHandler.postDelayed(loadTimeout, 20_000)

            // Preserve the selected video mode while moving between tracks.
            // Audio files always remain audio-only; a later video inherits the session mode.
            val showVideo = mediaFile.isVideo && videoModeEnabled
            MPVLib.setPropertyString("vid", if (showVideo) "auto" else "no")
            binding.mpvSurface.alpha = if (showVideo) 1f else 0f
            binding.artworkImage.visibility = if (showVideo) View.GONE else View.VISIBLE

            // Audio playback must not wait for a visible video surface (Library/background).
            MPVLib.command(arrayOf("loadfile", mediaPath, "replace"))

            MPVLib.setPropertyBoolean("pause", !MusicPlaybackService.ensureFocus())
        } catch (exception: Exception) {
            isReplacingMedia = false
            Log.e(TAG, "Failed to load media: ${mediaFile.displayName}", exception)
            currentMedia = null
            showPlaybackError()
            return
        }

        updateNowPlayingText(mediaFile)

        binding.playbackPosition.text = ZERO_TIME
        binding.playbackDuration.text =
            mediaFile.durationMs?.let { formatDurationMs(it) } ?: ZERO_TIME
        binding.playbackDuration.isEnabled = true
        binding.playbackDuration.contentDescription = getString(R.string.total_duration)

        binding.playbackSeekBar.progress = 0
        binding.playbackSeekBar.isEnabled = true

        binding.playButton.isEnabled = true
        binding.playButton.alpha = 1f
        binding.playButton.setImageResource(R.drawable.ic_waguri_pause)
        binding.playButton.contentDescription = getString(R.string.pause)

        binding.artworkImage.visibility =
            if (mediaFile.isVideo && videoModeEnabled) View.GONE else View.VISIBLE
        binding.mpvSurface.alpha = if (mediaFile.isVideo && videoModeEnabled) 1f else 0f
        updateVideoModeButton()

        applyPlayerViewportShape()
        mediaAdapter.setSelectedKey(mediaFile.queueKey)
        scrollToCurrentTrack(smooth = true)
        updateNavigationButtons()
        updateVisualizerAppearance()
        binding.audioSpectrumView.setActive(true)
        loadArtwork(mediaFile)
    }

    private fun updateNowPlayingText(
        media: MediaFile,
        title: String? = null,
        artist: String? = null,
        loaded: Boolean = false,
    ) {
        val nativeEntry = if (loaded && media.isPlaylist) {
            val index = MPVLib.getPropertyInt("playlist-pos") ?: -1
            MPVLib.getPropertyString("playlist/$index/filename")
                ?.takeUnless { it == resolveMpvPath(media.uri) }
                ?.let(Utils::fileBasename)
                ?.substringAfterLast('/')
        } else null
        val name = nativeEntry?.takeIf { it.isNotBlank() } ?: media.displayName
        playingFileName = name
        binding.nowPlayingTitle.text =
            if (media.temporaryYouTubeAudio) media.displayName
            else title?.takeIf { it.isNotBlank() } ?: media.playlistTitle ?: name.substringBeforeLast('.')
        binding.nowPlayingSubtitle.text =
            if (media.temporaryYouTubeAudio) getString(R.string.youtube_tab)
            else artist?.takeIf { it.isNotBlank() }
                ?: if (media.isPlaylist && nativeEntry == null) getString(R.string.m3u_playlist)
                else name.substringAfterLast('.', "").uppercase(Locale.ROOT)
        updateCollapsedLibraryInfo()
    }

    private fun updateCollapsedLibraryInfo(playbackError: Boolean = false) {
        val currentBinding = _binding ?: return
        val title = if (currentMedia == null && !playbackError) {
            getString(R.string.no_media_selected)
        } else {
            currentBinding.nowPlayingTitle.text
        }
        val detail = playingFileName?.takeUnless { it == title.toString() }
            ?: currentBinding.nowPlayingSubtitle.text.takeIf {
                (currentMedia != null || playbackError) && it.isNotBlank()
            }
            ?: getString(R.string.library_panel_tabs)
        currentBinding.collapsedLibraryTitle.text = title
        currentBinding.collapsedLibraryDetails.text = detail
        currentBinding.collapsedLibraryBar.contentDescription =
            getString(R.string.expand_library_panel, title, detail)
    }

    private fun enterVideoFullscreen() {
        val media = currentMedia ?: return
        if (!media.isVideo || !playerInitialized || isReplacingMedia || playbackFinished) return

        val positionMs =
            ((MPVLib.getPropertyDouble("time-pos") ?: 0.0) * 1000.0)
                .toLong()
                .coerceAtLeast(0L)
        val wasPaused = MPVLib.getPropertyBoolean("pause") ?: false
        fullscreenLaunchMediaUri = media.uri
        fullscreenLaunchPositionMs = positionMs
        fullscreenLaunchPaused = wasPaused

        val fullscreenIntent =
            Intent(requireContext(), MusicFullscreenActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                setDataAndType(media.uri, media.mimeType.ifBlank { "video/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("position", positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                putExtra(MusicFullscreenActivity.EXTRA_PAUSED, wasPaused)
                putExtra("title", media.displayName)
            }

        try {
            // Pause the embedded instance while the original MPV UI owns video playback.
            // Its existing Back to Music button returns position and pause state in the result.
            MPVLib.setPropertyBoolean("pause", true)
            updatePlaybackUi()
            stopAudioVisualizer()
            fullscreenVideoLauncher.launch(fullscreenIntent)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not open the original MPV fullscreen player", exception)
            fullscreenLaunchMediaUri = null
            val shouldPause = wasPaused || !MusicPlaybackService.ensureFocus()
            runCatching {
                MPVLib.setPropertyDouble("time-pos", positionMs / 1000.0)
                MPVLib.setPropertyBoolean("pause", shouldPause)
            }
            updatePlaybackUi()
            Toast.makeText(
                requireContext(),
                R.string.fullscreen_launch_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun exitVideoFullscreen() {
        val currentBinding = _binding ?: return
        if (!isVideoFullscreen) return

        isVideoFullscreen = false
        WindowCompat.getInsetsController(requireActivity().window, currentBinding.root)
            .show(WindowInsetsCompat.Type.systemBars())
        currentBinding.root.keepScreenOn = false

        currentBinding.fullscreenExitButton.visibility = View.GONE
        currentBinding.playerHeader.visibility = View.VISIBLE
        currentBinding.playerContentFrame.visibility = View.VISIBLE
        currentBinding.playerPanel.setPadding(dp(16), dp(16), dp(16), dp(16))
        currentBinding.playerPanel.background = null
        currentBinding.mediaVisualContainer.setBackgroundColor(Color.TRANSPARENT)

        val visualParams =
            currentBinding.mediaVisualContainer.layoutParams as LinearLayout.LayoutParams
        visualParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        visualParams.height = 0
        visualParams.weight = 1f
        visualParams.topMargin = dp(8)
        currentBinding.mediaVisualContainer.layoutParams = visualParams

        currentBinding.playerCircleContainer.setBackgroundResource(
            R.drawable.bg_waguri_media_circle,
        )
        currentBinding.playerCircleContainer.clipToOutline = true
        currentBinding.mpvSurface.alpha = if (videoModeEnabled) 1f else 0f
        currentBinding.artworkImage.visibility =
            if (videoModeEnabled) View.GONE else View.VISIBLE

        updatePanelLayout()
        applyPlayerViewportShape()
        currentBinding.root.requestLayout()
    }

    private fun applyPlayerViewportShape() {
        val currentBinding = _binding ?: return
        val artwork = currentBinding.playerCircleContainer
        val params = artwork.layoutParams as FrameLayout.LayoutParams
        val aspect = currentPlayerAspect()

        if (isVideoFullscreen) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.CENTER
            if (artwork.background == null) artwork.setBackgroundColor(Color.BLACK)
            artwork.clipToOutline = false
        } else {
            val availableWidth = currentBinding.mediaVisualContainer.width.coerceAtLeast(dp(48))
            val availableHeight = currentBinding.mediaVisualContainer.height.coerceAtLeast(dp(48))

            val baseWidth: Int
            val baseHeight: Int
            if (aspect == PlayerAspect.FILL) {
                baseWidth = availableWidth
                baseHeight = availableHeight
            } else if (aspect == PlayerAspect.WIDE) {
                val targetRatio = 16f / 9f
                if (availableWidth.toFloat() / availableHeight.toFloat() > targetRatio) {
                    baseHeight = availableHeight
                    baseWidth = (baseHeight * targetRatio).toInt()
                } else {
                    baseWidth = availableWidth
                    baseHeight = (baseWidth / targetRatio).toInt()
                }
            } else {
                val squareSize = minOf(availableWidth, availableHeight)
                baseWidth = squareSize
                baseHeight = squareSize
            }

            val zoomFactor =
                when (playerZoomStep.coerceIn(0, 3)) {
                    1 -> 1.25f
                    2 -> 1.50f
                    3 -> 1.75f
                    else -> 1f
                }

            params.width = (baseWidth * zoomFactor).toInt().coerceAtLeast(dp(48))
            params.height = (baseHeight * zoomFactor).toInt().coerceAtLeast(dp(48))
            params.gravity = Gravity.CENTER
            artwork.setBackgroundResource(
                if (aspect == PlayerAspect.FILL) 0 else R.drawable.bg_waguri_media_circle
            )
            artwork.clipToOutline = aspect != PlayerAspect.FILL
        }

        artwork.layoutParams = params
    }




    private fun updateVideoModeButton() {
        val currentBinding = _binding ?: return
        val hasVideo = currentMedia?.isVideo == true
        currentBinding.videoModeButton.isEnabled =
            hasVideo && playerInitialized && !isReplacingMedia && !playbackFinished
        currentBinding.videoModeButton.alpha =
            if (currentBinding.videoModeButton.isEnabled) 1f else 0.35f
        currentBinding.videoModeButton.setImageResource(
            if (videoModeEnabled) R.drawable.ic_waguri_audio_mode
            else R.drawable.ic_waguri_video_mode
        )
        currentBinding.videoModeButton.contentDescription =
            getString(if (videoModeEnabled) R.string.audio_only else R.string.video_mode)
    }

    private fun updateMediaViewportControls() {
        val currentBinding = _binding ?: return

        val aspectLabel = getString(currentPlayerAspect().label)
        currentBinding.aspectRatioButton.text = aspectLabel
        currentBinding.aspectRatioButton.contentDescription =
            getString(R.string.aspect_ratio_current, aspectLabel)

        val zoomLabel =
            getString(
                when (playerZoomStep.coerceIn(0, 3)) {
                    1 -> R.string.zoom_label_25
                    2 -> R.string.zoom_label_50
                    3 -> R.string.zoom_label_75
                    else -> R.string.zoom_label_100
                }
            )
        currentBinding.coverZoomButton.text = zoomLabel
        currentBinding.coverZoomButton.contentDescription =
            getString(
                when (playerZoomStep.coerceIn(0, 3)) {
                    1 -> R.string.zoom_description_25
                    2 -> R.string.zoom_description_50
                    3 -> R.string.zoom_description_75
                    else -> R.string.zoom_description_100
                }
            )
        currentBinding.aspectRatioButton.isEnabled = !isVideoFullscreen
        currentBinding.coverZoomButton.isEnabled = !isVideoFullscreen
        updateVideoModeButton()
    }


    private fun togglePlayerAspectRatio() {
        if (isPortraitLayout) {
            portraitAspect = PlayerAspect.entries[(portraitAspect.ordinal + 1) % PlayerAspect.entries.size]
        } else {
            playerWideAspect = !playerWideAspect
        }
        updateMediaViewportControls()
        applyPlayerViewportShape()
    }

    private fun currentPlayerAspect(): PlayerAspect = when {
        isPortraitLayout -> portraitAspect
        playerWideAspect -> PlayerAspect.WIDE
        else -> PlayerAspect.SQUARE
    }

    private fun cyclePlayerZoom() {
        playerZoomStep = (playerZoomStep + 1) % 4
        updateMediaViewportControls()
        applyPlayerViewportShape()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun resolveMpvPath(uri: Uri): String =
        if (uri.scheme == "file") uri.path.orEmpty() else uri.toString()

    private fun showAmbientLightSettings() {
        val context = requireContext()
        val enabledSwitch = MaterialSwitch(context).apply {
            text = getString(R.string.ambient_light)
            isChecked = ambientEnabled
        }
        val label = TextView(context).apply {
            text = getString(R.string.ambient_light_reach, ambientReach)
        }
        val slider = SeekBar(context).apply {
            max = 100
            progress = ambientReach
            isEnabled = ambientEnabled
            contentDescription = getString(R.string.ambient_light_reach, ambientReach)
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(enabledSwitch, ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            addView(label, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(slider, ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
        enabledSwitch.setOnCheckedChangeListener { _, enabled ->
            ambientEnabled = enabled
            slider.isEnabled = enabled
            preferences.edit().putBoolean(PREFERENCE_AMBIENT_ENABLED, enabled).apply()
            updateAmbientLight()
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                ambientReach = progress
                label.text = getString(R.string.ambient_light_reach, progress)
                seekBar.contentDescription = label.text
                updateAmbientLight()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                preferences.edit().putInt(PREFERENCE_AMBIENT_REACH, ambientReach).apply()
            }
        })
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ambient_light_settings)
            .setView(controls)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .setOnDismissListener {
                preferences.edit().putInt(PREFERENCE_AMBIENT_REACH, ambientReach).apply()
            }
    }

    private fun updateAmbientLight() {
        val currentBinding = _binding ?: return
        val light = ambientLight ?: return
        val coverVisible = currentBinding.mediaVisualContainer.isShown
        val ambientActive = ambientEnabled && ambientReach > 0 && currentMedia != null &&
            (coverVisible || ambientReach == 100) && !isVideoFullscreen
        updateAmbientLibraryStyle(ambientActive)
        if (!ambientActive) {
            light.reach = 0
            return
        }

        if (coverVisible) {
            val cover = currentBinding.playerCircleContainer
            currentBinding.root.getLocationInWindow(ambientRootLocation)
            cover.getLocationInWindow(ambientCoverLocation)
            val left = (ambientCoverLocation[0] - ambientRootLocation[0]).toFloat()
            val top = (ambientCoverLocation[1] - ambientRootLocation[1]).toFloat()
            ambientCoverBounds.set(left, top, left + cover.width, top + cover.height)
        } else {
            val width = currentBinding.root.width.toFloat()
            val height = currentBinding.root.height.toFloat()
            ambientCoverBounds.set(width * 0.35f, height * 0.35f, width * 0.65f, height * 0.65f)
        }
        light.setCoverBounds(ambientCoverBounds)
        light.reach = ambientReach

        if (!coverVisible) {
            if (!videoModeEnabled || currentMedia?.isVideo != true || ambientFrame == null) {
                light.setSource(ambientArtwork)
            }
            return
        }
        val videoVisible = videoModeEnabled && currentMedia?.isVideo == true &&
            currentBinding.mpvSurface.alpha > 0f
        if (!videoVisible || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            light.setSource(ambientArtwork)
            return
        }
        if (!isResumed || isReplacingMedia || playbackFinished || ambientCapturePending ||
            !currentBinding.mpvSurface.holder.surface.isValid
        ) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAmbientCapture < AMBIENT_CAPTURE_INTERVAL_MS) return
        lastAmbientCapture = now
        val frame = ambientFrame ?: Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            .also { ambientFrame = it }
        val generation = artworkGeneration
        ambientCapturePending = true
        try {
            PixelCopy.request(currentBinding.mpvSurface, frame, { result ->
                if (_binding !== currentBinding) return@request
                ambientCapturePending = false
                if (result == PixelCopy.SUCCESS && generation == artworkGeneration &&
                    videoModeEnabled && ambientEnabled && ambientReach > 0 && isResumed
                ) {
                    light.setSource(frame, refresh = true)
                } else if (result != PixelCopy.SUCCESS && generation == artworkGeneration) {
                    light.setSource(ambientArtwork)
                }
            }, playbackHandler)
        } catch (_: IllegalArgumentException) {
            ambientCapturePending = false
            light.setSource(ambientArtwork)
        }
    }

    private fun updateAmbientLibraryStyle(enabled: Boolean) {
        if (ambientLibraryStyle == enabled) return
        ambientLibraryStyle = enabled
        val tabs = binding.librarySections
        val primary = MaterialColors.getColor(tabs, androidx.appcompat.R.attr.colorPrimary)
        val onSurface = MaterialColors.getColor(tabs, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant =
            MaterialColors.getColor(tabs, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val surface = MaterialColors.getColor(tabs, com.google.android.material.R.attr.colorSurface)
        val normal =
            if (enabled) ColorUtils.blendARGB(onSurfaceVariant, surface, 0.20f) else onSurfaceVariant
        val selected =
            if (enabled) ColorUtils.blendARGB(onSurface, onSurfaceVariant, 0.55f) else primary
        if (enabled) {
            tabs.background = null
        } else {
            tabs.background = defaultLibraryTabBackground
        }
        tabs.setTabTextColors(normal, selected)
        tabs.setSelectedTabIndicatorColor(
            if (enabled) ColorUtils.setAlphaComponent(selected, 110) else primary
        )
        tabs.tabRippleColor = if (enabled) {
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(selected, 35))
        } else {
            defaultLibraryTabRipple
        }
        youtubeBrowser?.view?.alpha = if (enabled) AMBIENT_BROWSER_ALPHA else 1f
        youtubeDownloadPage?.view?.alpha = if (enabled) AMBIENT_BROWSER_ALPHA else 1f
        binding.mediaSearchField.background.mutate().alpha = if (enabled) AMBIENT_SEARCH_ALPHA else 255
        updateSortButtons()
        mediaAdapter.setAmbientRows(enabled)
    }

    private fun loadArtwork(mediaFile: MediaFile) {
        val generation = ++artworkGeneration
        ambientArtwork = null
        ambientFrame = null

        showAppIconPlaceholder(binding.artworkImage)
        ambientLight?.setSource(null)

        if (mediaFile.temporaryYouTubeAudio) {
            temporaryAudioCover?.let { cover ->
                showMediaThumbnail(binding.artworkImage, cover)
                ambientArtwork = cover
                updateAmbientLight()
            }
            return
        }

        thumbnailLoader.load(mediaFile) { bitmap ->
            if (
                !isAdded ||
                    _binding == null ||
                    generation != artworkGeneration ||
                    currentMedia?.uri != mediaFile.uri
            ) {
                return@load
            }

            if (bitmap != null) {
                showMediaThumbnail(binding.artworkImage, bitmap)
            } else {
                showAppIconPlaceholder(binding.artworkImage)
            }

            ambientArtwork = bitmap ?: (binding.artworkImage.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            updateAmbientLight()
            updateVisualizerAppearance()
        }
    }

    private fun playbackPositionSeconds(): Double =
        if (playbackFinished) 0.0
        else MPVLib.getPropertyDouble("time-pos/full") ?: MPVLib.getPropertyDouble("time-pos") ?: 0.0

    private fun updatePlaybackUi() {
        if (
            currentMedia == null ||
                !playerInitialized ||
                _binding == null
        )
            return

        val position = playbackPositionSeconds()

        val duration =
            MPVLib.getPropertyDouble("duration/full")
                ?: currentMedia?.durationMs?.div(1000.0)
                ?: 0.0

        if (
            !isReplacingMedia &&
                !playbackFinished &&
                MPVLib.getPropertyBoolean("eof-reached") == true
        ) {
            handlePlaybackEnded()
            return
        }
        syncLyrics(position)
        val paused = playbackFinished || (MPVLib.getPropertyBoolean("pause") ?: false)
        binding.audioSpectrumView.setActive(!paused && !playbackFinished)
        MusicPlaybackService.publish(
            binding.nowPlayingTitle.text.toString(),
            paused || playbackFinished,
            (position * 1000).toLong(),
            (duration * 1000).toLong(),
        )

        if (!isUserSeeking && duration > 0.0) {
            binding.playbackSeekBar.progress =
                ((position / duration) * binding.playbackSeekBar.max)
                    .toInt()
                    .coerceIn(0, binding.playbackSeekBar.max)
        }

        binding.playbackPosition.text = formatDurationSeconds(position)
        val remaining = (duration - position).coerceAtLeast(0.0)
        binding.playbackDuration.text =
            if (durationCountdownEnabled && duration > 0.0) "-${formatDurationSeconds(remaining)}"
            else formatDurationSeconds(duration)
        val showingRemainingTime = durationCountdownEnabled && duration > 0.0
        binding.playbackDuration.contentDescription =
            getString(if (showingRemainingTime) R.string.remaining_time else R.string.total_duration)

        binding.playButton.setImageResource(
            if (paused) R.drawable.ic_waguri_play else R.drawable.ic_waguri_pause
        )
        binding.playButton.contentDescription =
            getString(if (paused) R.string.play else R.string.pause)
    }

    private fun handlePlaybackEnded() {
        if (
            _binding == null ||
                !playerInitialized ||
                currentMedia == null
        )
            return

        val reachedEnd = MPVLib.getPropertyBoolean("eof-reached") ?: false
        if (!reachedEnd) return

        // libmpv advances M3U/M3U8 entries internally. Wait until its final
        // playlist entry before advancing the app's library queue.
        if (currentMedia?.isPlaylist == true) {
            val playlistCount = MPVLib.getPropertyInt("playlist-count") ?: 0
            val playlistPosition = MPVLib.getPropertyInt("playlist-pos") ?: -1

            if (
                playlistCount > 0 && playlistPosition >= 0 && playlistPosition < playlistCount - 1
            ) {
                return
            }
        }

        if (playbackFinished || isReplacingMedia) return
        playbackFinished = true
        if (pendingYouTubeTrack != null || loadingYouTubePlaylist) return
        val next = queue.adjacent(currentMedia, 1, automatic = true)
        when {
            next?.temporaryYouTubeAudio == true -> {
                val format = currentYouTubeAudioFormat.takeIf { next.queueKey == currentMedia?.queueKey }
                prepareYouTubeTrack(next, format, skipUnavailable = true)
            }
            next != null -> playMedia(next)
            else -> updateEndedPlaybackUi()
        }
    }

    private fun updateEndedPlaybackUi() {
        val currentBinding = _binding ?: return
        currentBinding.playbackSeekBar.progress = currentBinding.playbackSeekBar.max
        currentBinding.playButton.setImageResource(R.drawable.ic_waguri_play)
        currentBinding.playButton.contentDescription = getString(R.string.play)
        currentBinding.audioSpectrumView.setActive(false)
        updateNavigationButtons()
    }

    private fun updateNavigationButtons() {
        val currentBinding = _binding ?: return
        currentBinding.previousButton.isEnabled = currentMedia != null
        currentBinding.nextButton.isEnabled =
            queue.adjacent(currentMedia, 1) != null ||
                (playerInitialized &&
                    currentMedia?.isPlaylist == true &&
                    (MPVLib.getPropertyInt("playlist-pos") ?: 0) <
                        (MPVLib.getPropertyInt("playlist-count") ?: 0) - 1)
        currentBinding.previousButton.alpha =
            if (currentBinding.previousButton.isEnabled) 1f else 0.38f
        currentBinding.nextButton.alpha = if (currentBinding.nextButton.isEnabled) 1f else 0.38f
    }

    private fun playAdjacentMedia(offset: Int) {
        if (!playerInitialized || currentMedia == null) return

        val media = requireNotNull(currentMedia)
        if (media.isPlaylist) {
            val index = MPVLib.getPropertyInt("playlist-pos") ?: -1
            val count = MPVLib.getPropertyInt("playlist-count") ?: 0
            if (index + offset in 0 until count) {
                MPVLib.command(arrayOf(if (offset > 0) "playlist-next" else "playlist-prev"))
                return
            }
        }

        val adjacent = queue.adjacent(media, offset)
        if (adjacent != null) {
            playMedia(adjacent)
            return
        }

        if (offset < 0) {
            if (playbackFinished) {
                playMedia(media)
            } else if ((MPVLib.getPropertyDouble("time-pos") ?: 0.0) > 0.0) {
                MPVLib.setPropertyDouble("time-pos", 0.0)
            }
        }
    }



    private fun installCoverSwipeGesture() {
        val detector =
            GestureDetector(
                requireContext(),
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(event: MotionEvent): Boolean = true

                    override fun onFling(
                        start: MotionEvent?,
                        end: MotionEvent,
                        velocityX: Float,
                        velocityY: Float,
                    ): Boolean {
                        if (start == null) return false
                        val deltaX = end.x - start.x
                        val deltaY = end.y - start.y
                        if (
                            abs(deltaX) < dp(64) ||
                                abs(velocityX) < dp(120) ||
                                abs(deltaX) < abs(deltaY) * 1.25f
                        ) {
                            return false
                        }
                        animateMediaSwipe(if (deltaX < 0f) 1 else -1)
                        return true
                    }
                },
            )

        val swipeTouchListener = View.OnTouchListener { _, event -> detector.onTouchEvent(event) }
        binding.artworkImage.setOnTouchListener(swipeTouchListener)
        binding.mpvSurface.setOnTouchListener(swipeTouchListener)
    }

    private fun animateMediaSwipe(offset: Int) {
        val media = currentMedia ?: return
        val currentBinding = _binding ?: return
        if (
            !playerInitialized ||
                isReplacingMedia ||
                mediaSwipeAnimationRunning ||
                isVideoFullscreen
        ) {
            return
        }

        val canMoveWithinPlaylist =
            if (media.isPlaylist) {
                val index = MPVLib.getPropertyInt("playlist-pos") ?: -1
                val count = MPVLib.getPropertyInt("playlist-count") ?: 0
                index + offset in 0 until count
            } else {
                false
            }
        val canMoveInQueue =
            queue.adjacent(media, offset) != null ||
                (offset < 0 && (MPVLib.getPropertyDouble("time-pos") ?: 0.0) > 0.0)
        if (!canMoveWithinPlaylist && !canMoveInQueue) return

        val container = currentBinding.playerCircleContainer
        val travel = maxOf(container.width, dp(160)).toFloat()
        val outgoingX = if (offset > 0) -travel else travel
        mediaSwipeAnimationRunning = true

        container.animate().cancel()
        container.animate()
            .translationX(outgoingX)
            .alpha(0.72f)
            .setDuration(150L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (_binding !== currentBinding) {
                    mediaSwipeAnimationRunning = false
                    return@withEndAction
                }

                playAdjacentMedia(offset)
                mediaSwipeAnimationRunning = true
                container.translationX = -outgoingX
                container.alpha = 0.72f
                container.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(230L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        mediaSwipeAnimationRunning = false
                    }
                    .start()
            }
            .start()
    }

    private fun showPlaybackError() {
        val currentBinding = _binding ?: return
        updateVideoModeButton()
        currentBinding.mpvSurface.alpha = 0f
        currentBinding.artworkImage.visibility = View.VISIBLE
        showAppIconPlaceholder(currentBinding.artworkImage)
        currentBinding.nowPlayingTitle.text = getString(R.string.playback_error)
        currentBinding.nowPlayingSubtitle.text = getString(R.string.playback_error_summary)
        playingFileName = currentMedia?.displayName
        updateCollapsedLibraryInfo(playbackError = true)
        playbackFinished = true
        updateVideoModeButton()
        currentBinding.playButton.isEnabled = currentMedia != null
        currentBinding.playButton.setImageResource(R.drawable.ic_waguri_play)
        currentBinding.playButton.alpha = 1f
        currentBinding.playbackSeekBar.isEnabled = false
    }

    private fun getFolderName(uri: Uri): String {
        return try {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val selectedPath = documentId.substringAfter(':', documentId).trim('/')
            if (selectedPath.isBlank()) {
                getString(R.string.selected_storage)
            } else {
                "/$selectedPath"
            }
        } catch (_: Exception) {
            getString(R.string.selected_folder)
        }
    }

    private fun getFolderName(path: String): String {
        val normalized = path.trimEnd('/')
        return if (normalized.isBlank()) {
            getString(R.string.selected_folder)
        } else {
            "/$normalized"
        }
    }

    private fun getIndexedFolderName(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return getString(R.string.selected_folder)
        val volume = id.substringBefore(':', "")
        val name = getFolderName(uri)
        return if (volume.isBlank() || volume == "primary") name else "$volume · $name"
    }

    override fun onPause() {
        youtubeBrowser?.view?.onPause()
        youtubeDownloadPage?.view?.onPause()
        stopAudioVisualizer()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        updateYouTubeBrowser()

        if (visualizerEnabled && currentMedia != null) {
            playbackHandler.removeCallbacks(startVisualizerRunnable)
            playbackHandler.postDelayed(startVisualizerRunnable, VISUALIZER_START_DELAY_MS)
        }
    }

    override fun onDestroyView() {
        cancelYouTubeAudioRequest()
        youtubeChoiceDialog?.dismiss()
        temporaryAudioCover = null
        TransitionManager.endTransitions(binding.contentPanels)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, null)
        defaultLibraryTabBackground = null
        defaultLibraryTabRipple = null
        ambientLight = null
        ambientArtwork = null
        ambientFrame = null
        ambientCapturePending = false
        libraryFoldersDialog?.dismiss()
        releaseYouTubeDownloadPage()
        releaseYouTubeBrowser()
        trackSwipeHelper?.attachToRecyclerView(null)
        trackSwipeHelper = null
        if (isVideoFullscreen) {
            WindowCompat.getInsetsController(requireActivity().window, binding.root)
                .show(WindowInsetsCompat.Type.systemBars())
            binding.root.keepScreenOn = false
            isVideoFullscreen = false
        }
        sharedFolderDialog?.dismiss()
        openingSharedFolders = false
        scanGeneration++
        clearPlaylistSelection()
        scanningFolder = false
        folderScanFailed = false
        artworkGeneration++
        lyricsGeneration++
        autoLyricsGeneration++
        localLyricsGeneration++
        lyricsDownloadGeneration++

        playbackHandler.removeCallbacksAndMessages(null)
        playbackHandler.removeCallbacks(playbackTicker)
        playbackHandler.removeCallbacks(startVisualizerRunnable)

        unregisterAudioDeviceCallback()
        stopAudioVisualizer()
        stopMusicService()

        if (playerInitialized) {
            try {
                isReplacingMedia = true
                MPVLib.removeObserver(mpvObserver)
                MPVLib.command(arrayOf("stop"))
            } catch (exception: Exception) {
                Log.w(TAG, "Player shutdown completed with a warning", exception)
            }

            try {
                binding.mpvSurface.destroy()
            } catch (exception: Exception) {
                Log.w(TAG, "Failed to destroy the embedded MPV view", exception)
            }

            playerInitialized = false
        }

        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        scanGeneration++
        artworkGeneration++
        scanExecutor.shutdownNow()
        folderExecutor.shutdownNow()
        youtubeAudioExecutor.shutdownNow()
        youtubeFormatsExecutor.shutdownNow()
        lyricsExecutor.shutdownNow()

        thumbnailLoaderInstance?.close()
        thumbnailLoaderInstance = null

        super.onDestroy()
    }

    companion object {
        private const val TAG = "WaguriPlayer"
        private const val TAB_TRACKS = 0
        private const val TAB_LYRICS = 1
        private const val TAB_YOUTUBE = 2
        private const val MENU_LIBRARY = 1
        private const val MENU_NOW_PLAYING = 2
        private const val MENU_PLAYLISTS = 3
        private const val MENU_FULLSCREEN = 5
        private const val MENU_AUTO_LYRICS = 6
        private const val MENU_STOP = 7
        private const val MENU_EXIT = 8
        private const val MENU_LYRICS = 9
        private const val MENU_AMBIENT_LIGHT = 10
        private const val PREFERENCE_AMBIENT_ENABLED = "ambient_light_enabled"
        private const val PREFERENCE_AMBIENT_REACH = "ambient_light_reach"
        private const val AMBIENT_CAPTURE_INTERVAL_MS = 400L
        private const val AMBIENT_BROWSER_ALPHA = 0.78f
        private const val AMBIENT_SEARCH_ALPHA = 64
        private const val AMBIENT_SORT_SELECTED_ALPHA = 100
        private const val AMBIENT_SORT_UNSELECTED_ALPHA = 44
        private const val PREFERENCES_NAME = "waguri_player_preferences"
        private const val STATE_SHOWING_LIBRARY = "showing_library"
        private const val STATE_PORTRAIT_LIBRARY_EXPANDED = "portrait_library_expanded"
        private const val STATE_LIBRARY_TAB = "library_tab"
        private const val STATE_PENDING_PLAYLIST_EXPORT = "pending_playlist_export"
        private const val STATE_PENDING_PLAYLIST_FILES = "pending_playlist_files"
        private const val STATE_YOUTUBE_BROWSER_CLOSED = "youtube_browser_closed"
        private const val STATE_PENDING_YOUTUBE_SAVE = "pending_youtube_save"
        private const val STATE_MEDIA_DELETION_URI = "media_deletion_uri"
        private const val STATE_MEDIA_DELETION_NAME = "media_deletion_name"
        private const val QUICK_PLAYLIST_NAME = "Quick"
        private const val PREFERENCE_TREE_URI = LibraryFolderIndex.ACTIVE_FOLDER_KEY
        private const val PREFERENCE_FOLDER_PATH = "media_folder_path"
        private const val PREFERENCE_SHARED_FOLDER = "shared_storage_folder"
        private const val PREFERENCE_MEDIASTORE_FOLDER = "media_store_relative_folder"
        private const val PREFERENCE_MEDIASTORE_VOLUME = "media_store_volume"
        private const val PREFERENCE_VISUALIZER_ENABLED = "audio_visualizer_enabled"
        private const val PREFERENCE_AUTO_LYRICS_ENABLED = "auto_lyrics_enabled"
        private const val PREFERENCE_PLAYER_WIDE_ASPECT = "player_wide_aspect"
        private const val PREFERENCE_PLAYER_ZOOM_STEP = "player_zoom_step"
        private const val PLAYBACK_UPDATE_INTERVAL_MS = 200L
        private const val MAX_LYRICS_DELAY_SECONDS = 120
        private const val AUDIO_ROUTE_VISUALIZER_REBIND_DELAY_MS = 350L
        private const val VISUALIZER_START_DELAY_MS = 450L
        private const val ZERO_TIME = "00:00"

        private val SUPPORTED_EXTENSIONS =
            setOf(
                "mp4",
                "mkv",
                "mp3",
                "webm",
                "m3u",
                "m3u8",
                "flac",
                "wav",
                "ogg",
                "opus",
                "m4a",
                "aac",
                "aiff",
                "alac",
                "wma",
                "mov",
                "avi",
                "m4v",
                "ts",
            )

        private fun normalizeRelativeFolderPath(path: String): String {
            val normalized = path.trim().trim('/')
            return if (normalized.isBlank()) "" else "$normalized/"
        }

        private fun mediaStoreProjection(): Array<String> {
            val columns =
                mutableListOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA,
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                columns.add(MediaStore.MediaColumns.RELATIVE_PATH)
                columns.add(MediaStore.MediaColumns.VOLUME_NAME)
            }

            return columns.toTypedArray()
        }

        private fun relativeFolderPathFromCursor(
            cursor: android.database.Cursor,
            relativePathIndex: Int,
            dataIndex: Int,
        ): String {
            if (relativePathIndex >= 0 && !cursor.isNull(relativePathIndex)) {
                val relativePath = cursor.getString(relativePathIndex)
                if (!relativePath.isNullOrBlank()) {
                    return normalizeRelativeFolderPath(relativePath)
                }
            }

            if (dataIndex >= 0 && !cursor.isNull(dataIndex)) {
                val absolutePath = cursor.getString(dataIndex).orEmpty()
                val parentPath = File(absolutePath).parent.orEmpty()
                val storageRoot = Environment.getExternalStorageDirectory().absolutePath
                val relativePath =
                    if (parentPath.startsWith(storageRoot)) {
                        parentPath.removePrefix(storageRoot)
                    } else {
                        parentPath
                    }
                return normalizeRelativeFolderPath(relativePath)
            }

            return ""
        }

        private fun queryMediaStoreFiles(
            context: Context,
            selectedFolderPath: String?,
            selectedVolume: String? = null,
        ): List<MediaFile> {
            val resolver = context.contentResolver
            val result = mutableListOf<MediaFile>()
            val normalizedSelection =
                selectedFolderPath?.let { normalizeRelativeFolderPath(it) }.orEmpty()
            val projection = mediaStoreProjection()

            val collections =
                listOf(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                )

            for (collection in collections) {
                val permission =
                    if (Build.VERSION.SDK_INT >= 33) {
                        if (collection == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                            Manifest.permission.READ_MEDIA_AUDIO
                        else Manifest.permission.READ_MEDIA_VIDEO
                    } else Manifest.permission.READ_EXTERNAL_STORAGE
                if (
                    ContextCompat.checkSelfPermission(context, permission) !=
                        PackageManager.PERMISSION_GRANTED
                )
                    continue
                resolver
                    .query(
                        collection,
                        projection,
                        null,
                        null,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC",
                    )
                    ?.use { cursor ->
                        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameIndex =
                            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                        val modifiedIndex =
                            cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                        val relativePathIndex =
                            cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                        val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val volumeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)

                        while (cursor.moveToNext()) {
                            val displayName = cursor.getString(nameIndex) ?: continue
                            val extension =
                                displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)

                            val volume =
                                if (volumeIndex >= 0) cursor.getString(volumeIndex) else null
                            if (selectedVolume != null && volume != selectedVolume) continue
                            val mimeType =
                                if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                            if (
                                extension !in SUPPORTED_EXTENSIONS &&
                                    !mimeType.startsWith("audio/") &&
                                    !mimeType.startsWith("video/")
                            )
                                continue

                            val folderPath =
                                relativeFolderPathFromCursor(cursor, relativePathIndex, dataIndex)

                            if (
                                normalizedSelection.isNotBlank() &&
                                    !folderPath.startsWith(normalizedSelection)
                            ) {
                                continue
                            }

                            val modifiedSeconds =
                                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                                    cursor.getLong(modifiedIndex)
                                } else {
                                    0L
                                }

                            val mediaUri =
                                ContentUris.withAppendedId(collection, cursor.getLong(idIndex))

                            result.add(
                                MediaFile(
                                    uri = mediaUri,
                                    displayName = displayName,
                                    mimeType = resolveMimeType(mimeType, extension),
                                    lastModified = modifiedSeconds * 1000L,
                                    folderPath = folderPath,
                                    volumeName = volume,
                                )
                            )
                        }
                    }
            }

            return result.distinctBy { it.uri }
        }

        private fun scanMediaTree(context: Context, treeUri: Uri): List<MediaFile> {
            val resolver = context.contentResolver
            val result = mutableListOf<MediaFile>()
            val pendingDirectories = ArrayDeque<String>()
            val visitedDirectories = mutableSetOf<String>()

            pendingDirectories.addLast(DocumentsContract.getTreeDocumentId(treeUri))

            val projection =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )

            while (pendingDirectories.isNotEmpty()) {
                if (Thread.currentThread().isInterrupted)
                    throw InterruptedException("Scan cancelled")
                val parentId = pendingDirectories.removeFirst()
                if (!visitedDirectories.add(parentId)) continue

                val childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)

                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIndex =
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex =
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val modifiedIndex =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        val documentId = cursor.getString(idIndex) ?: continue
                        val displayName = cursor.getString(nameIndex) ?: continue
                        if (displayName.startsWith('.')) continue

                        val mimeType =
                            if (mimeIndex >= 0) {
                                cursor.getString(mimeIndex).orEmpty()
                            } else {
                                ""
                            }

                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            pendingDirectories.addLast(documentId)
                            continue
                        }

                        val extension =
                            displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)

                        if (extension !in SUPPORTED_EXTENSIONS) continue

                        val lastModified =
                            if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                                cursor.getLong(modifiedIndex)
                            } else {
                                0L
                            }

                        val documentUri =
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

                        result.add(
                            MediaFile(
                                uri = documentUri,
                                displayName = displayName,
                                mimeType = resolveMimeType(mimeType, extension),
                                lastModified = lastModified,
                                parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                                    treeUri, parentId,
                                ),
                            )
                        )
                    }
                }
            }

            return result
        }

        private fun resolveMimeType(mimeType: String, extension: String): String {
            if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) {
                return mimeType
            }

            return when (extension) {
                "mp3" -> "audio/mpeg"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp4" -> "video/mp4"
                "m3u" -> "audio/x-mpegurl"
                "m3u8" -> "application/vnd.apple.mpegurl"
                else -> "application/octet-stream"
            }
        }

        private fun readDuration(context: Context, uri: Uri): Long? {
            val retriever = android.media.MediaMetadataRetriever()

            return try {
                retriever.setDataSource(context, uri)
                retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            } catch (_: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                    // Ignore metadata cleanup failures.
                }
            }
        }
    }
}

private fun showAppIconPlaceholder(imageView: ImageView, circular: Boolean = false) {
    imageView.setImageResource(R.drawable.uwu_player_placeholder)
    imageView.imageTintList = null
    imageView.background =
        if (circular) {
            androidx.core.content.ContextCompat.getDrawable(
                imageView.context,
                R.drawable.bg_waguri_icon_circle,
            )
        } else {
            null
        }
    imageView.clipToOutline = circular
    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
}

private fun showMediaThumbnail(imageView: ImageView, bitmap: android.graphics.Bitmap) {
    imageView.imageTintList = null
    imageView.background = null
    imageView.clipToOutline = false
    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
    imageView.setImageBitmap(bitmap)
}

private class MediaAdapter(
    private val thumbnailLoader: MediaThumbnailLoader,
    private val onItemClick: (MediaFile) -> Unit,
    private val onItemMenu: (MediaFile) -> Unit,
) : RecyclerView.Adapter<MediaAdapter.MediaViewHolder>() {

    private val items = mutableListOf<MediaFile>()
    private var selectedKey: String? = null
    private var ambientRows = false

    fun setAmbientRows(enabled: Boolean) {
        if (ambientRows == enabled) return
        ambientRows = enabled
        notifyItemRangeChanged(0, itemCount, ROW_BACKGROUND)
    }

    private fun bindRowBackground(holder: MediaViewHolder, position: Int) {
        val selected = items[position].queueKey == selectedKey
        holder.itemView.setBackgroundResource(
            if (selected) R.drawable.bg_waguri_chip_selected else R.drawable.bg_waguri_media_item
        )
        holder.itemView.background.mutate().alpha = when {
            !ambientRows -> 255
            selected -> 110
            else -> 44
        }
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == ROW_BACKGROUND }) {
            bindRowBackground(holder, position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    fun itemAt(position: Int): MediaFile? = items.getOrNull(position)

    fun submitList(newItems: List<MediaFile>, selected: String?, removedTrackKey: String? = null) {
        if (removedTrackKey != null && selectedKey == selected && items.size == newItems.size + 1) {
            val index = items.indexOfFirst { it.queueKey == removedTrackKey }
            val remaining = items.filterNot { it.queueKey == removedTrackKey }
            if (index >= 0 && remaining == newItems) {
                items.clear()
                items.addAll(newItems)
                notifyItemRemoved(index)
                return
            }
        }
        items.clear()
        items.addAll(newItems)
        selectedKey = selected
        notifyDataSetChanged()
    }

    fun setSelectedKey(key: String?) {
        selectedKey = key
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding =
            ItemMediaFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val mediaFile = items[position]
        val context = holder.itemView.context

        val extension = mediaFile.extension.uppercase(Locale.ROOT)
        val modifiedDate =
            if (mediaFile.lastModified > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(mediaFile.lastModified))
            } else {
                context.getString(R.string.unknown_date)
            }

        holder.binding.fileName.text = mediaFile.playlistTitle ?: mediaFile.displayName
        holder.binding.fileMetadata.text = when {
            !mediaFile.isAvailable && mediaFile.playlistTitle != null ->
                context.getString(R.string.unavailable_playlist_filename, mediaFile.displayName)
            !mediaFile.isAvailable -> context.getString(R.string.playlist_track_unavailable)
            mediaFile.playlistTitle != null -> mediaFile.displayName
            else -> "$extension | $modifiedDate"
        }
        holder.binding.fileDuration.text =
            mediaFile.durationMs?.let { formatDurationMs(it) } ?: "--:--"

        val imageView = holder.binding.fileArtwork
        val cacheKey = mediaFile.cacheKey

        imageView.tag = cacheKey
        showAppIconPlaceholder(imageView, circular = true)

        if (mediaFile.isAvailable) {
            thumbnailLoader.load(mediaFile) { bitmap ->
                if (imageView.tag == cacheKey) {
                    if (bitmap != null) {
                        showMediaThumbnail(imageView, bitmap)
                    } else {
                        showAppIconPlaceholder(imageView, circular = true)
                    }
                }
            }
        }

        bindRowBackground(holder, position)

        holder.binding.fileMenu.visibility = if (mediaFile.isAvailable) View.VISIBLE else View.INVISIBLE
        holder.binding.fileMenu.setOnClickListener { onItemMenu(mediaFile) }
        holder.itemView.setOnLongClickListener(
            if (mediaFile.isAvailable) View.OnLongClickListener {
                onItemMenu(mediaFile)
                true
            } else null
        )
        holder.itemView.isLongClickable = mediaFile.isAvailable
        holder.itemView.setOnClickListener { onItemClick(mediaFile) }
    }

    override fun getItemCount(): Int = items.size

    class MediaViewHolder(val binding: ItemMediaFileBinding) :
        RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val ROW_BACKGROUND = "row_background"
    }
}

private fun formatDurationMs(durationMs: Long): String {
    return formatDurationSeconds(durationMs / 1000.0)
}

private fun formatDurationSeconds(durationSeconds: Double): String {
    val totalSeconds = durationSeconds.toLong().coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
