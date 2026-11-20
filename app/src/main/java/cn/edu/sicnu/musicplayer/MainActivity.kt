package cn.edu.sicnu.musicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.sicnu.musicplayer.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 音乐播放界面。
 *
 * 实验要求 (2b)：Activity 通过绑定服务拿到 Binder，再从服务里读取歌曲名、播放进度等信息；
 * 播放/暂停/停止/上一首/下一首等操作也全部转发给服务执行，Activity 自己不持有 MediaPlayer。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SongAdapter

    /** MediaStore 查出来的全部歌曲 */
    private var allSongs: List<Song> = emptyList()

    /** 按搜索框过滤之后的歌曲 */
    private var filteredSongs: List<Song> = emptyList()

    private var musicService: MusicPlayerService? = null
    private var bound = false

    /** 用户正在拖动 SeekBar 时，暂停服务推送过来的进度 */
    private var isUserSeeking = false

    private var emptyMode = EmptyMode.HIDDEN
    private var permissionRequestCount = 0

    /** 上一次自动滚动到的歌曲，避免每次进度刷新都把列表拉回去 */
    private var lastPlayedSongId: Long? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 查 MediaStore 是耗时操作，放到单线程池里做（多线程编程） */
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private enum class EmptyMode { HIDDEN, PERMISSION, NO_MUSIC, NO_SEARCH_RESULT }

    // =====================================================================
    // 绑定服务
    // =====================================================================

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val musicBinder = binder as? MusicPlayerService.MusicBinder ?: return
            Log.i(TAG, "onServiceConnected")
            musicService = musicBinder.service
            bound = true
            musicBinder.service.addListener(playbackListener)
            musicBinder.service.setQueue(allSongs)
            updateBindingState(connected = true)
            renderSnapshot(musicBinder.service.snapshot(), fromService = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected")
            musicService?.removeListener(playbackListener)
            musicService = null
            bound = false
            updateBindingState(connected = false)
        }
    }

    /** 服务主动推送：换歌、播放/暂停/停止、进度、错误 */
    private val playbackListener = object : PlaybackListener {
        override fun onPlaybackChanged(snapshot: PlaybackSnapshot) {
            renderSnapshot(snapshot, fromService = true)
        }

        override fun onProgress(positionMs: Long, durationMs: Long) {
            if (isUserSeeking) return
            binding.seekBar.progress = progressOf(positionMs, durationMs)
            binding.positionText.text = Song.formatDuration(positionMs)
            binding.durationText.text = Song.formatDuration(durationMs)
        }

        override fun onPlaybackError(message: String) {
            renderSnapshot(musicService?.snapshot() ?: PlaybackSnapshot(), fromService = true)
            toast(message)
        }
    }

    // =====================================================================
    // 权限
    // =====================================================================

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionRequestCount++
        val audioGranted = result[audioPermission()] ?: hasAudioPermission()
        if (audioGranted) {
            loadSongs()
        } else {
            updateEmptyState()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            result[Manifest.permission.POST_NOTIFICATIONS] == false
        ) {
            toast(getString(R.string.toast_notification_denied))
        }
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupSongList()
        setupPlayerControls()
        setupSearch()
        updateBindingState(connected = false)
        updateEmptyState()
        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        bindMusicService()
    }

    override fun onStop() {
        if (bound) {
            musicService?.removeListener(playbackListener)
            runCatching { unbindService(serviceConnection) }
            bound = false
        }
        musicService = null
        updateBindingState(connected = false)
        super.onStop()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun bindMusicService() {
        if (bound) return
        val intent = MusicPlayerService.intent(this)
        try {
            bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService -> $bound")
        } catch (t: Throwable) {
            Log.e(TAG, "bindService 失败", t)
            bound = false
        }
        if (!bound) updateBindingState(connected = false)
    }

    // =====================================================================
    // 界面初始化
    // =====================================================================

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.headerContent.updatePadding(top = bars.top + dp(20))
            binding.playerContent.updatePadding(bottom = bars.bottom + dp(12))
            binding.root.updatePadding(left = bars.left, right = bars.right)
            insets
        }
    }

    private fun setupSongList() {
        adapter = SongAdapter { song -> playSong(song) }
        binding.songList.layoutManager = LinearLayoutManager(this)
        binding.songList.adapter = adapter
        binding.songList.itemAnimator = null
    }

    private fun setupPlayerControls() {
        binding.btnPlay.setOnClickListener {
            performPlaybackAction { service ->
                service.togglePlayPause()
            }
        }
        binding.btnPrevious.setOnClickListener {
            performPlaybackAction { it.previous() }
        }
        binding.btnNext.setOnClickListener {
            performPlaybackAction { it.next() }
        }
        binding.btnStop.setOnClickListener {
            musicService?.stopPlayback() ?: toast(getString(R.string.toast_service_unavailable))
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = musicService?.snapshot()?.durationMs ?: 0L
                if (duration > 0) {
                    binding.positionText.text = Song.formatDuration(duration * progress / 1000)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = false
                val duration = musicService?.snapshot()?.durationMs ?: 0L
                if (duration > 0) {
                    val target = duration * seekBar.progress / 1000
                    // 实验内容 (3)：拖动 SeekBar 调整播放进度
                    musicService?.seekTo(target)
                    binding.positionText.text = Song.formatDuration(target)
                }
            }
        })
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty()
                binding.clearSearch.isVisible = keyword.isNotEmpty()
                applyFilter(keyword)
            }
        })
        binding.clearSearch.setOnClickListener {
            binding.searchInput.setText("")
            hideKeyboard()
        }
        binding.emptyAction.setOnClickListener { onEmptyActionClicked() }
    }

    // =====================================================================
    // 读取音乐（ContentResolver + 子线程）
    // =====================================================================

    private fun requestPermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (!hasAudioPermission()) missing += audioPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) {
            loadSongs()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun loadSongs() {
        if (!hasAudioPermission()) {
            updateEmptyState()
            return
        }
        binding.songCount.text = getString(R.string.toast_scanning)
        ioExecutor.execute {
            val result = runCatching { SongRepository.query(this) }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                result.onSuccess { songs ->
                    allSongs = songs
                    musicService?.setQueue(allSongs)
                    applyFilter(binding.searchInput.text?.toString().orEmpty())
                    Log.i(TAG, "加载完成，共 ${songs.size} 首")
                }.onFailure { error ->
                    Log.e(TAG, "读取音乐失败", error)
                    binding.songCount.text = ""
                    updateEmptyState()
                    toast(
                        getString(
                            R.string.toast_scan_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    // =====================================================================
    // 过滤 / 渲染
    // =====================================================================

    private fun applyFilter(keyword: String) {
        val query = keyword.trim()
        filteredSongs = if (query.isEmpty()) {
            allSongs
        } else {
            allSongs.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                    song.artist.contains(query, ignoreCase = true) ||
                    song.album.contains(query, ignoreCase = true)
            }
        }
        adapter.submitList(filteredSongs)

        binding.songCount.text = when {
            allSongs.isEmpty() -> ""
            query.isEmpty() -> getString(R.string.song_count_format, allSongs.size)
            else -> getString(R.string.song_filtered_format, filteredSongs.size, allSongs.size)
        }
        updateEmptyState()

        val snapshot = musicService?.snapshot()
        adapter.setNowPlaying(snapshot?.song?.id, snapshot?.isPlaying == true)
    }

    private fun updateEmptyState() {
        val mode = when {
            !hasAudioPermission() -> EmptyMode.PERMISSION
            allSongs.isEmpty() -> EmptyMode.NO_MUSIC
            filteredSongs.isEmpty() -> EmptyMode.NO_SEARCH_RESULT
            else -> EmptyMode.HIDDEN
        }
        emptyMode = mode
        binding.emptyState.isVisible = mode != EmptyMode.HIDDEN
        binding.songList.isVisible = mode == EmptyMode.HIDDEN

        when (mode) {
            EmptyMode.PERMISSION -> {
                binding.emptyIcon.setImageResource(R.drawable.ic_headphones)
                binding.emptyTitle.setText(R.string.permission_title)
                binding.emptyMessage.setText(R.string.permission_message)
                binding.emptyAction.setText(R.string.action_grant)
            }

            EmptyMode.NO_MUSIC -> {
                binding.emptyIcon.setImageResource(R.drawable.ic_headphones)
                binding.emptyTitle.setText(R.string.empty_title)
                binding.emptyMessage.setText(R.string.empty_message)
                binding.emptyAction.setText(R.string.action_rescan)
            }

            EmptyMode.NO_SEARCH_RESULT -> {
                binding.emptyIcon.setImageResource(R.drawable.ic_search)
                binding.emptyTitle.setText(R.string.empty_search_title)
                binding.emptyMessage.setText(R.string.empty_search_message)
                binding.emptyAction.setText(R.string.action_clear_search)
            }

            EmptyMode.HIDDEN -> Unit
        }

        // 没有权限时不显示歌曲数量
        if (mode == EmptyMode.PERMISSION) binding.songCount.text = ""
    }

    private fun onEmptyActionClicked() {
        when (emptyMode) {
            EmptyMode.PERMISSION -> {
                val permanentlyDenied = permissionRequestCount > 0 &&
                    !shouldShowRequestPermissionRationale(audioPermission())
                if (permanentlyDenied) {
                    toast(getString(R.string.permission_permanently_denied))
                    openAppSettings()
                } else {
                    requestPermissionsIfNeeded()
                }
            }

            EmptyMode.NO_MUSIC -> loadSongs()

            EmptyMode.NO_SEARCH_RESULT -> {
                binding.searchInput.setText("")
                hideKeyboard()
            }

            EmptyMode.HIDDEN -> Unit
        }
    }

    /** 把服务的状态快照画到界面上（全部控件都由 Binder 提供的数据驱动） */
    private fun renderSnapshot(snapshot: PlaybackSnapshot, fromService: Boolean) {
        val song = snapshot.song
        binding.playerTitle.text = song?.title ?: getString(R.string.now_playing_idle)
        binding.playerArtist.text = song?.metaText ?: getString(R.string.now_playing_hint)

        binding.trackIndex.text = if (song != null && snapshot.index >= 0) {
            getString(R.string.track_index_format, snapshot.index + 1, snapshot.queueSize)
        } else {
            ""
        }

        val playing = snapshot.status == PlaybackStatus.PLAYING
        binding.btnPlay.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        binding.btnPlay.contentDescription =
            getString(if (playing) R.string.btn_pause else R.string.btn_play)

        binding.playState.isVisible = song != null && snapshot.status != PlaybackStatus.IDLE
        binding.playState.setText(
            when (snapshot.status) {
                PlaybackStatus.PLAYING -> R.string.label_now_playing
                PlaybackStatus.PAUSED -> R.string.label_paused
                PlaybackStatus.STOPPED -> R.string.label_stopped
                PlaybackStatus.IDLE -> R.string.label_stopped
            }
        )

        binding.durationText.text = if (snapshot.durationMs > 0) {
            Song.formatDuration(snapshot.durationMs)
        } else {
            getString(R.string.time_zero)
        }
        if (!isUserSeeking) {
            binding.seekBar.progress = snapshot.progressPermille
            binding.positionText.text = Song.formatDuration(snapshot.positionMs)
        }

        adapter.setNowPlaying(song?.id, playing)
        if (fromService && song != null && song.id != lastPlayedSongId) {
            lastPlayedSongId = song.id
            val listIndex = filteredSongs.indexOfFirst { it.id == song.id }
            if (listIndex >= 0) binding.songList.scrollToPosition(listIndex)
        }
    }

    private fun updateBindingState(connected: Boolean) {
        binding.statusText.setText(
            if (connected) R.string.service_bound else R.string.service_unbound
        )
        ViewCompat.setBackgroundTintList(
            binding.statusDot,
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (connected) R.color.status_online else R.color.status_offline
                )
            )
        )
    }

    // =====================================================================
    // 播放操作：全部转发给 Service
    // =====================================================================

    /**
     * 播放类操作统一入口。
     * 如果服务当前没有在播放（还没开始 / 已停止），先把服务以「启动服务」的方式拉起来，
     * 这样退出 Activity 之后音乐仍然能在后台继续播放。
     */
    private fun performPlaybackAction(action: (MusicPlayerService) -> Unit) {
        val service = musicService
        if (service == null) {
            toast(getString(R.string.toast_service_unavailable))
            return
        }
        val snapshot = service.snapshot()
        val needsStart = snapshot.queueSize > 0 &&
            (snapshot.status == PlaybackStatus.IDLE || snapshot.status == PlaybackStatus.STOPPED)
        if (needsStart) startServiceSafely()
        action(service)
    }

    private fun startServiceSafely() {
        try {
            startService(MusicPlayerService.intent(this))
        } catch (t: Throwable) {
            Log.w(TAG, "startService 失败，音乐可能无法在后台继续播放", t)
        }
    }

    private fun playSong(song: Song) {
        val service = musicService
        if (service == null) {
            toast(getString(R.string.toast_service_unavailable))
            return
        }
        val snapshot = service.snapshot()
        if (snapshot.queueSize == 0 || snapshot.status == PlaybackStatus.IDLE ||
            snapshot.status == PlaybackStatus.STOPPED
        ) {
            startServiceSafely()
        }
        if (!service.playSong(song)) {
            toast(getString(R.string.toast_play_failed))
        }
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private fun progressOf(positionMs: Long, durationMs: Long): Int =
        if (durationMs <= 0L) 0 else ((positionMs * 1000) / durationMs).toInt().coerceIn(0, 1000)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        runCatching { startActivity(intent) }
    }
}
