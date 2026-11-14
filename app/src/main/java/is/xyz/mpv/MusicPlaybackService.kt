package `is`.xyz.mpv

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle

/** Keeps the embedded player eligible for background playback and exposes transport controls. */
class MusicPlaybackService : Service() {
    interface Controller {
        fun play()

        fun pause()

        fun previous()

        fun next()

        fun seek(positionMs: Long)
    }

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeAfterFocus = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastTitle = ""
    private var lastPaused = true
    private var lastDuration = -1L
    private var pendingNoisyPause: Runnable? = null
    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (addedDevices.any { isExternalAudioSink(it) }) {
                    pendingNoisyPause?.let(handler::removeCallbacks)
                    pendingNoisyPause = null
                }
            }
        }
    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                scheduleNoisyRoutePause()
            }
        }
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    if (resumeAfterFocus) controller?.play()
                    resumeAfterFocus = false
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasFocus = false
                    resumeAfterFocus = false
                    controller?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    hasFocus = false
                    resumeAfterFocus = !lastPaused
                    controller?.pause()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        }
        NotificationManagerCompat.from(this)
            .createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(getString(R.string.now_playing_tab))
                    .build()
            )
        session = MediaSessionCompat(this, "UwU Music")
        session.setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (requestFocus()) controller?.play()
                }

                override fun onPause() {
                    controller?.pause()
                }

                override fun onSkipToPrevious() {
                    controller?.previous()
                }

                override fun onSkipToNext() {
                    controller?.next()
                }

                override fun onSeekTo(pos: Long) {
                    controller?.seek(pos)
                }

                override fun onStop() {
                    controller?.pause()
                }
            },
            handler,
        )
        session.isActive = true
        ContextCompat.registerReceiver(
            this,
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter foreground before audio-focus requests (required for target API 35+).
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else 0,
        )
        if (controller == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            "pause" -> controller?.pause()
            "play" -> if (requestFocus()) controller?.play()
            "previous" -> controller?.previous()
            "next" -> controller?.next()
            else -> if (!requestFocus()) controller?.pause()
        }
        return START_NOT_STICKY
    }

    private fun scheduleNoisyRoutePause() {
        pendingNoisyPause?.let(handler::removeCallbacks)
        val pending =
            Runnable {
                pendingNoisyPause = null
                if (!hasExternalAudioOutput()) {
                    // Pause only when the route really fell back to the built-in speaker.
                    // A newly connected output cancels this pending pause; playback and its
                    // current position are never stopped or reloaded during device handoff.
                    controller?.pause()
                }
            }
        pendingNoisyPause = pending
        handler.postDelayed(pending, AUDIO_ROUTE_SETTLE_DELAY_MS)
    }

    private fun hasExternalAudioOutput(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { isExternalAudioSink(it) }
    }

    private fun isExternalAudioSink(device: AudioDeviceInfo): Boolean {
        if (!device.isSink) return false
        return device.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
            device.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val granted =
            if (Build.VERSION.SDK_INT >= 26) {
                val request =
                    focusRequest
                        ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                            .setOnAudioFocusChangeListener(focusListener, handler)
                            .build()
                            .also { focusRequest = it }
                audioManager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        hasFocus = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun notification(): android.app.Notification {
        val launch =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        fun action(name: String, icon: Int, label: Int): NotificationCompat.Action {
            val intent = Intent(this, MusicPlaybackService::class.java).setAction(name)
            val pending =
                PendingIntent.getService(
                    this,
                    name.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            return NotificationCompat.Action(icon, getString(label), pending)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mpv_symbolic)
            .setContentTitle(lastTitle.ifBlank { getString(R.string.uwu_player_title) })
            .setContentText(getString(R.string.now_playing_tab))
            .setContentIntent(launch)
            .setOnlyAlertOnce(true)
            .setOngoing(!lastPaused)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action("previous", R.drawable.ic_waguri_previous, R.string.previous))
            .addAction(
                action(
                    if (lastPaused) "play" else "pause",
                    if (lastPaused) R.drawable.ic_waguri_play else R.drawable.ic_waguri_pause,
                    if (lastPaused) R.string.play else R.string.pause,
                )
            )
            .addAction(action("next", R.drawable.ic_waguri_next, R.string.next))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    // Notifications carrying an active media-session token are exempt from POST_NOTIFICATIONS.
    @android.annotation.SuppressLint("NotificationPermission", "MissingPermission")
    private fun update(title: String, paused: Boolean, positionMs: Long, durationMs: Long) {
        val changed = title != lastTitle || paused != lastPaused
        if (title != lastTitle || durationMs != lastDuration)
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                    .build()
            )
        lastDuration = durationMs
        lastTitle = title
        lastPaused = paused
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (paused) PlaybackStateCompat.STATE_PAUSED
                    else PlaybackStateCompat.STATE_PLAYING,
                    positionMs,
                    if (paused) 0f else 1f,
                )
                .build()
        )
        if (changed) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification())
            } catch (exception: SecurityException) {
                android.util.Log.w("UwUMusic", "Media notification update was rejected", exception)
            }
        }
    }

    override fun onDestroy() {
        pendingNoisyPause?.let(handler::removeCallbacks)
        pendingNoisyPause = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
        unregisterReceiver(noisyReceiver)
        session.isActive = false
        session.release()
        if (Build.VERSION.SDK_INT >= 26)
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        else {
            @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var controller: Controller? = null
        private var instance: MusicPlaybackService? = null
        private const val CHANNEL = "music_playback"
        private const val NOTIFICATION_ID = 12346
        private const val AUDIO_ROUTE_SETTLE_DELAY_MS = 900L

        fun publish(title: String, paused: Boolean, positionMs: Long, durationMs: Long) {
            instance?.update(title, paused, positionMs, durationMs)
        }

        fun ensureFocus(): Boolean = instance?.requestFocus() ?: true
    }
}
