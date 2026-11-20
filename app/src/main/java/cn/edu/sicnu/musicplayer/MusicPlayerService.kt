package cn.edu.sicnu.musicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

/**
 * 音乐播放服务。
 *
 * 实验内容 (2)：所有与音乐播放相关的功能都在 Service 里实现 —— 播放、暂停、停止、
 * 上一首、下一首、拖动进度。Activity 只通过 [MusicBinder] 调用这些方法，并通过
 * [PlaybackListener] 拿到当前歌曲名、播放进度等信息。
 *
 * 实验内容 (3)：进度在 [HandlerThread]（子线程）里每 500ms 读一次
 * MediaPlayer.currentPosition，再切回主线程派发给 Activity 的 SeekBar。
 *
 * 实验内容 (4)：换歌 / 播放 / 暂停时用 Notification 把当前歌曲名显示在通知栏，
 * 通知上还带上一首、播放/暂停、下一首、停止四个按钮。
 *
 * 服务既是「绑定服务」（onBind）又是「启动服务 + 前台服务」（startForeground），
 * 这样退出 Activity 之后音乐还能继续播放，重新打开界面时状态可以恢复。
 */
class MusicPlayerService : Service() {

    companion object {
        private const val TAG = "MusicPlayerService"

        const val ACTION_TOGGLE = "cn.edu.sicnu.musicplayer.action.TOGGLE"
        const val ACTION_PREVIOUS = "cn.edu.sicnu.musicplayer.action.PREVIOUS"
        const val ACTION_NEXT = "cn.edu.sicnu.musicplayer.action.NEXT"
        const val ACTION_STOP = "cn.edu.sicnu.musicplayer.action.STOP"

        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1001

        /** 子线程刷新进度的间隔：500ms */
        private const val PROGRESS_INTERVAL_MS = 500L

        fun intent(context: Context): Intent = Intent(context, MusicPlayerService::class.java)
    }

    /** Binder：Activity 通过它拿到 Service 实例，直接调用播放控制方法 */
    inner class MusicBinder : Binder() {
        val service: MusicPlayerService
            get() = this@MusicPlayerService
    }

    private val binder = MusicBinder()

    /** 主线程 Handler，用来把子线程的进度切回主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 进度线程：专门在后台读播放位置 */
    private lateinit var progressThread: HandlerThread
    private lateinit var progressHandler: Handler
    private var progressTicker: Runnable? = null

    private var mediaPlayer: MediaPlayer? = null
    private var queue: List<Song> = emptyList()
    private var currentIndex = -1
    private var status = PlaybackStatus.IDLE
    private var isPrepared = false
    private var playWhenPrepared = false
    private var startForegrounded = false

    /** 绑定的界面回调，只在主线程读写 */
    private val listeners = mutableListOf<PlaybackListener>()

    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private lateinit var mediaSession: MediaSessionCompat
    private var audioFocusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null

    // =====================================================================
    // 生命周期
    // =====================================================================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)

        progressThread = HandlerThread("music-progress").apply { start() }
        progressHandler = Handler(progressThread.looper)

        createNotificationChannel()
        createMediaSession()
        registerNoisyReceiver()
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知栏按钮点进来时走这里
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_PREVIOUS -> previous()
            ACTION_NEXT -> next()
            ACTION_STOP -> stopPlayback()
        }
        // 耳机 / 蓝牙的媒体按键交给 MediaSessionCompat 处理
        if (intent != null) MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopProgressTicker()
        releasePlayer()
        abandonAudioFocus()
        unregisterNoisyReceiver()
        mediaSession.isActive = false
        mediaSession.release()
        progressThread.quitSafely()
        if (startForegrounded) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            startForegrounded = false
        }
        listeners.clear()
        super.onDestroy()
    }

    // =====================================================================
    // 对外提供的播放控制（全部在服务内完成）
    // =====================================================================

    /** 当前歌曲，可能为 null */
    val currentSong: Song?
        get() = queue.getOrNull(currentIndex)

    /** 界面用：当前状态快照 */
    fun snapshot(): PlaybackSnapshot {
        val player = mediaPlayer
        val prepared = isPrepared && player != null
        val duration = if (prepared) runCatching { player!!.duration.toLong() }.getOrDefault(0L) else 0L
        val position = if (prepared) runCatching { player!!.currentPosition.toLong() }.getOrDefault(0L) else 0L
        return PlaybackSnapshot(
            song = currentSong,
            status = status,
            positionMs = position,
            durationMs = duration,
            index = currentIndex,
            queueSize = queue.size
        )
    }

    /** 更新播放列表。正在播放的歌如果在新的列表里，位置会被保留。 */
    fun setQueue(songs: List<Song>, startIndex: Int = -1) {
        // Activity 刚绑定上、音乐还没读出来时会先传空列表进来，
        // 这时不能清空正在后台播放的队列。
        if (songs.isEmpty()) return
        val playingSong = currentSong
        queue = songs
        currentIndex = when {
            startIndex in songs.indices -> startIndex
            playingSong != null -> songs.indexOfFirst { it.id == playingSong.id }
            else -> -1
        }
        if (currentIndex < 0 && playingSong != null) {
            // 正在播放的歌被删掉了，直接停止，避免越界
            handlePlaybackError(getString(R.string.error_playback, "歌曲已不在列表中"))
        }
        notifyPlaybackChanged()
    }

    /** 播放列表中的第 index 首 */
    fun playAt(index: Int) {
        if (index !in queue.indices) {
            Log.w(TAG, "playAt: 下标越界 $index / ${queue.size}")
            return
        }
        if (index == currentIndex && mediaPlayer != null && isPrepared) {
            // 点的就是当前这首：回到开头，继续播放
            seekTo(0L)
            if (status != PlaybackStatus.PLAYING) resume()
            return
        }
        openSong(index)
    }

    /** 播放指定的歌（按 id 在队列里找） */
    fun playSong(song: Song): Boolean {
        val index = queue.indexOfFirst { it.id == song.id }
        if (index < 0) return false
        playAt(index)
        return true
    }

    /** 播放 / 暂停 切换 */
    fun togglePlayPause() {
        when (status) {
            PlaybackStatus.PLAYING -> pause()
            PlaybackStatus.PAUSED -> resume()
            PlaybackStatus.STOPPED, PlaybackStatus.IDLE -> {
                when {
                    queue.isEmpty() -> Unit
                    currentIndex >= 0 -> playAt(currentIndex)
                    else -> playAt(0)
                }
            }
        }
    }

    fun pause() {
        if (status != PlaybackStatus.PLAYING) {
            // 还在 prepare 阶段就点了暂停：准备完成后不要自动播放
            if (!isPrepared && mediaPlayer != null) {
                playWhenPrepared = false
                status = PlaybackStatus.PAUSED
                notifyPlaybackChanged()
            }
            return
        }
        val player = mediaPlayer
        if (player != null && isPrepared && player.isPlaying) {
            runCatching { player.pause() }
        }
        status = PlaybackStatus.PAUSED
        stopProgressTicker()
        updateMediaSession()
        ensureForeground()
        notifyPlaybackChanged()
        Log.i(TAG, "pause: ${currentSong?.title}")
    }

    fun resume() {
        if (status == PlaybackStatus.PLAYING) return
        val player = mediaPlayer
        if (player != null && isPrepared) {
            requestAudioFocus()
            runCatching { player.start() }
            status = PlaybackStatus.PLAYING
            ensureForeground()
            startProgressTicker()
            updateMediaSession()
            notifyPlaybackChanged()
            Log.i(TAG, "resume: ${currentSong?.title}")
        } else if (queue.isNotEmpty()) {
            playAt(if (currentIndex >= 0) currentIndex else 0)
        }
    }

    /** 停止播放：回到开头，撤掉通知，服务可以退出 */
    fun stopPlayback() {
        Log.i(TAG, "stopPlayback")
        stopProgressTicker()
        mediaPlayer?.let { player ->
            if (isPrepared) {
                runCatching { player.pause() }
                runCatching { player.seekTo(0) }
            }
        }
        status = PlaybackStatus.STOPPED
        abandonAudioFocus()
        removeForeground()
        updateMediaSession()
        notifyPlaybackChanged()
        stopSelf()
    }

    fun next() = skip(1)

    fun previous() = skip(-1)

    private fun skip(delta: Int) {
        if (queue.isEmpty()) return
        val start = if (currentIndex < 0) 0 else currentIndex
        val target = (start + delta + queue.size) % queue.size
        playAt(target)
    }

    /**
     * 拖动 SeekBar 时调用。
     * @param positionMs 目标位置（毫秒）
     */
    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        if (!isPrepared) return
        val duration = runCatching { player.duration.toLong() }.getOrDefault(0L)
        val target = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        runCatching { player.seekTo(target.toInt()) }
        updateMediaSession()
        notifyProgress(target, duration)
        Log.d(TAG, "seekTo ${target}ms")
    }

    // =====================================================================
    // 界面回调注册
    // =====================================================================

    fun addListener(listener: PlaybackListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    // =====================================================================
    // MediaPlayer
    // =====================================================================

    private fun openSong(index: Int) {
        val song = queue.getOrNull(index) ?: return
        Log.i(TAG, "openSong[$index]: ${song.title} -> ${song.uri}")
        releasePlayer()
        currentIndex = index
        playWhenPrepared = true
        isPrepared = false
        status = PlaybackStatus.IDLE
        notifyPlaybackChanged()

        val player = MediaPlayer()
        mediaPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)

        player.setOnPreparedListener { prepared ->
            if (prepared !== mediaPlayer) return@setOnPreparedListener
            isPrepared = true
            Log.i(TAG, "onPrepared: duration=${prepared.duration}ms")
            if (playWhenPrepared) {
                requestAudioFocus()
                runCatching { prepared.start() }
                status = PlaybackStatus.PLAYING
                ensureForeground()
                startProgressTicker()
            }
            updateMediaSession()
            notifyPlaybackChanged()
        }

        player.setOnCompletionListener { completed ->
            if (completed !== mediaPlayer) return@setOnCompletionListener
            Log.i(TAG, "onCompletion: 自动播放下一首")
            status = PlaybackStatus.STOPPED
            stopProgressTicker()
            next()
        }

        player.setOnErrorListener { failed, what, extra ->
            if (failed !== mediaPlayer) return@setOnErrorListener true
            Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
            handlePlaybackError(getString(R.string.error_playback, "what=$what, extra=$extra"))
            true
        }

        try {
            player.setDataSource(this, song.uri)
            player.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "setDataSource 失败", e)
            handlePlaybackError(getString(R.string.error_playback, e.message ?: "setDataSource"))
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.let { player ->
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnCompletionListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        mediaPlayer = null
        isPrepared = false
    }

    private fun handlePlaybackError(message: String) {
        status = PlaybackStatus.STOPPED
        stopProgressTicker()
        releasePlayer()
        abandonAudioFocus()
        removeForeground()
        updateMediaSession()
        mainHandler.post {
            listeners.toList().forEach { it.onPlaybackError(message) }
            notifyPlaybackChanged()
        }
    }

    // =====================================================================
    // 进度线程：实验内容 (3) 的多线程部分
    // =====================================================================

    private fun startProgressTicker() {
        if (progressTicker != null) return
        val ticker = object : Runnable {
            override fun run() {
                val player = mediaPlayer
                if (status == PlaybackStatus.PLAYING && player != null && isPrepared) {
                    val position = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
                    val duration = runCatching { player.duration.toLong() }.getOrDefault(0L)
                    // 子线程算好，切回主线程更新 SeekBar
                    mainHandler.post {
                        if (status == PlaybackStatus.PLAYING) {
                            listeners.toList().forEach { it.onProgress(position, duration) }
                        }
                    }
                }
                progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
        progressTicker = ticker
        progressHandler.post(ticker)
    }

    private fun stopProgressTicker() {
        progressTicker?.let { progressHandler.removeCallbacks(it) }
        progressTicker = null
    }

    // =====================================================================
    // 通知栏：实验内容 (4)
    // =====================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val song = currentSong
        val songTitle = song?.title ?: getString(R.string.notification_unknown_song)
        val contentText = if (song != null) {
            getString(R.string.notification_text_format, song.artist, song.album)
        } else {
            getString(R.string.channel_description)
        }
        val playing = status == PlaybackStatus.PLAYING
        val playIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val playLabel = getString(
            if (playing) R.string.notification_action_pause else R.string.notification_action_play
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(ArtworkFactory.create(192))
            .setContentTitle(songTitle)
            .setContentText(contentText)
            .setSubText(
                getString(
                    if (playing) R.string.label_now_playing else R.string.label_paused
                )
            )
            .setContentIntent(openAppIntent())
            .setDeleteIntent(actionIntent(ACTION_STOP, 100))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setOngoing(playing)
            .addAction(
                R.drawable.ic_skip_previous,
                getString(R.string.notification_action_previous),
                actionIntent(ACTION_PREVIOUS, 101)
            )
            .addAction(playIcon, playLabel, actionIntent(ACTION_TOGGLE, 102))
            .addAction(
                R.drawable.ic_skip_next,
                getString(R.string.notification_action_next),
                actionIntent(ACTION_NEXT, 103)
            )
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.notification_action_stop),
                actionIntent(ACTION_STOP, 104)
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** 播放中 / 暂停时把服务置于前台并刷新通知 */
    private fun ensureForeground() {
        if (status != PlaybackStatus.PLAYING && status != PlaybackStatus.PAUSED) return
        val notification = buildNotification()
        if (!startForegrounded) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            startForegrounded = true
            Log.i(TAG, "startForeground: ${currentSong?.title}")
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun removeForeground() {
        if (!startForegrounded) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        startForegrounded = false
        Log.i(TAG, "stopForeground")
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            200,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = intent(this).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // =====================================================================
    // MediaSession：锁屏 / 通知栏媒体控制
    // =====================================================================

    private fun createMediaSession() {
        mediaSession = MediaSessionCompat(this, "MusicPlayerService").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
            })
            isActive = true
        }
    }

    private fun updateMediaSession() {
        val song = currentSong
        if (song != null) {
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot().durationMs)
                    .build()
            )
        }
        val state = when (status) {
            PlaybackStatus.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackStatus.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlaybackStatus.STOPPED -> PlaybackStateCompat.STATE_STOPPED
            PlaybackStatus.IDLE -> PlaybackStateCompat.STATE_NONE
        }
        val snapshot = snapshot()
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    state,
                    snapshot.positionMs,
                    if (status == PlaybackStatus.PLAYING) 1f else 0f
                )
                .build()
        )
    }

    // =====================================================================
    // 音频焦点 / 拔耳机自动暂停
    // =====================================================================

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        mainHandler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (status == PlaybackStatus.PLAYING) pause()
                AudioManager.AUDIOFOCUS_GAIN -> Unit
            }
        }
    }

    private fun requestAudioFocus() {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "requestAudioFocus 未获得焦点: $result")
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun registerNoisyReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                    status == PlaybackStatus.PLAYING
                ) {
                    Log.i(TAG, "耳机拔出，自动暂停")
                    pause()
                }
            }
        }
        noisyReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterNoisyReceiver() {
        noisyReceiver?.let { runCatching { unregisterReceiver(it) } }
        noisyReceiver = null
    }

    // =====================================================================
    // 状态派发
    // =====================================================================

    private fun notifyPlaybackChanged() {
        val snapshot = snapshot()
        dispatchOnMain { listeners.toList().forEach { it.onPlaybackChanged(snapshot) } }
    }

    private fun notifyProgress(positionMs: Long, durationMs: Long) {
        dispatchOnMain { listeners.toList().forEach { it.onProgress(positionMs, durationMs) } }
    }

    private fun dispatchOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
