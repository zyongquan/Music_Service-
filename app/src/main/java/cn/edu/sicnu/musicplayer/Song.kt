package cn.edu.sicnu.musicplayer

import android.net.Uri

/**
 * 一首歌（一条 MediaStore 音频记录）。
 *
 * 只保存界面与服务之间传递需要的字段，[uri] 是 ContentResolver 能直接打开的
 * `content://media/external/audio/media/<id>` 地址，MediaPlayer 用它来播放。
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri
) {
    /** 列表里显示的副标题：歌手 · 专辑 */
    val metaText: String
        get() = "$artist · $album"

    /** 时长文本，如 03:45 */
    val durationText: String
        get() = formatDuration(durationMs)

    companion object {
        /**
         * 把毫秒格式化成 mm:ss（超过 1 小时用 h:mm:ss）。
         * 纯函数，方便做 JVM 单元测试。
         */
        fun formatDuration(ms: Long): String {
            if (ms <= 0L) return "00:00"
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }
    }
}
