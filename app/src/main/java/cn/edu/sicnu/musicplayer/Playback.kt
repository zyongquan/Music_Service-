package cn.edu.sicnu.musicplayer

/** 播放器状态 */
enum class PlaybackStatus {
    /** 还没有选择歌曲 */
    IDLE,

    /** 正在播放 */
    PLAYING,

    /** 已暂停 */
    PAUSED,

    /** 已停止（回到这首歌的开头） */
    STOPPED
}

/**
 * 服务当前播放状态的快照。
 *
 * Activity 绑定服务后，既可以直接调用 [MusicPlayerService.snapshot] 主动拉取，
 * 也可以注册 [PlaybackListener] 等待服务推送（换歌、暂停、拖动进度等）。
 */
data class PlaybackSnapshot(
    val song: Song? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val index: Int = -1,
    val queueSize: Int = 0
) {
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING

    /** 进度百分比，用于 SeekBar（0..1000） */
    val progressPermille: Int
        get() = if (durationMs <= 0L) 0 else ((positionMs * 1000) / durationMs).toInt().coerceIn(0, 1000)
}

/**
 * 播放状态回调。所有回调都会被切回主线程，Activity 里可以直接更新控件。
 */
interface PlaybackListener {

    /** 换歌、播放/暂停/停止时回调 */
    fun onPlaybackChanged(snapshot: PlaybackSnapshot)

    /** 播放过程中每 500ms 回调一次（在服务里的子线程计算，主线程派发） */
    fun onProgress(positionMs: Long, durationMs: Long)

    /** 播放出错 */
    fun onPlaybackError(message: String)
}
