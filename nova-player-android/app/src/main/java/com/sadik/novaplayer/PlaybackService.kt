package com.sadik.novaplayer

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*

class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeAfterFocus = false
    private var duckedVolume: Double? = null
    private lateinit var wake: PowerManager.WakeLock
    private var art: android.graphics.Bitmap? = null
    private var artFor = ""
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeAfterFocus = false; NovaRuntime.pause(true) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeAfterFocus = !NovaRuntime.state.value.paused; NovaRuntime.pause(true) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { duckedVolume = NovaRuntime.state.value.volume; NovaRuntime.engine.setDouble("volume", 20.0) }
            AudioManager.AUDIOFOCUS_GAIN -> { duckedVolume?.let { NovaRuntime.engine.setDouble("volume", it) }; duckedVolume = null; if (resumeAfterFocus) NovaRuntime.pause(false); resumeAfterFocus = false }
        }
    }
    private val noisy = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { NovaRuntime.pause(true) } }
    override fun onCreate() {
        super.onCreate(); NovaRuntime.init(this)
        audio = getSystemService(AudioManager::class.java)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaPlayer:playback").apply { setReferenceCounted(false) }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("playback", "Playback", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "Nova Player").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { requestFocus(); NovaRuntime.pause(false) }
                override fun onPause() { NovaRuntime.pause(true) }
                override fun onStop() { NovaRuntime.close() }
                override fun onSeekTo(pos: Long) { NovaRuntime.seek(pos / 1000.0) }
                override fun onSkipToNext() { NovaRuntime.next(1) }
                override fun onSkipToPrevious() { NovaRuntime.next(-1) }
                override fun onFastForward() { NovaRuntime.seek(10.0, true) }
                override fun onRewind() { NovaRuntime.seek(-10.0, true) }
            }); isActive = true
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(noisy, filter)
        NovaRuntime.onChanged = { updateNotification() }
    }
    private fun action(name: String): PendingIntent = PendingIntent.getService(this, name.hashCode(), Intent(this, PlaybackService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(): Notification {
        val s = NovaRuntime.state.value
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "playback") else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_stat_nova).setContentTitle(s.video?.let { com.sadik.novaplayer.ui.prettyTitle(it.title) } ?: "Nova Player").apply { art?.let { setLargeIcon(it) } }
            .setContentText(if (s.paused) "Paused" else "Playing · ${s.speed}×").setContentIntent(launch)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(!s.paused).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Previous", action("previous")).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, if (s.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause), if (s.paused) "Play" else "Pause", action("toggle")).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next), "Next", action("next")).build())
            .setDeleteIntent(action("stop")).setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2)).build()
    }
    private fun updateNotification() {
        val s = NovaRuntime.state.value
        if (s.video == null) { stopSelf(); return }
        if (artFor != s.video.uri) { artFor = s.video.uri; art = runCatching { android.graphics.BitmapFactory.decodeFile(NovaRuntime.store.thumb(s.video).path) }.getOrNull() }
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, com.sadik.novaplayer.ui.prettyTitle(s.video.title))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, com.sadik.novaplayer.ui.folderLabel(s.video.folder)).putLong(MediaMetadata.METADATA_KEY_DURATION, (s.duration * 1000).toLong())
            .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }.build())
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if (s.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, (s.position * 1000).toLong(), s.speed.toFloat()).build())
        getSystemService(NotificationManager::class.java).notify(7, notification())
        if (!s.paused && !wake.isHeld) wake.acquire(6 * 60 * 60 * 1000L)
        if (s.paused && wake.isHeld) wake.release()
    }
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setOnAudioFocusChangeListener(focusListener).build()
            audio.requestAudioFocus(focusRequest!!)
        } else audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(7, notification())
        requestFocus()
        when (intent?.action) { "toggle" -> NovaRuntime.pause(); "previous" -> NovaRuntime.next(-1); "next" -> NovaRuntime.next(1); "stop" -> NovaRuntime.close() }
        updateNotification(); return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        NovaRuntime.onChanged = null
        if (wake.isHeld) wake.release()
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let(audio::abandonAudioFocusRequest) else audio.abandonAudioFocus(focusListener)
        runCatching { unregisterReceiver(noisy) }; session.release()
        if (NovaRuntime.state.value.video == null) NovaRuntime.destroyEngine()
        super.onDestroy()
    }
}
