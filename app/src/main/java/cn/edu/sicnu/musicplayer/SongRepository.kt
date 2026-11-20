package cn.edu.sicnu.musicplayer

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log

/**
 * 用 ContentResolver 查询 MediaStore，拿到本机音乐列表。
 *
 * 实验内容 (1)：使用 contentResolver 获得音乐文件列表。
 * 查询的是 `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`，只取 IS_MUSIC != 0
 * 的记录，排序按歌曲名；每条记录的播放地址用 ContentUris 拼成 content:// URI，
 * 这样在 Android 10+ 的分区存储下也能正常播放。
 */
object SongRepository {

    private const val TAG = "SongRepository"

    private val PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DISPLAY_NAME
    )

    /** 只查询音乐，过滤掉铃声、通知音、录音等 */
    private const val SELECTION = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

    private const val SORT_ORDER = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

    /**
     * 同步查询音乐列表。调用方需要在子线程执行（见 MainActivity.loadSongs）。
     *
     * @throws SecurityException 没有读取音频权限时由系统抛出
     */
    fun query(context: Context): List<Song> {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val unknownArtist = context.getString(R.string.unknown_artist)
        val unknownAlbum = context.getString(R.string.unknown_album)

        val songs = mutableListOf<Song>()
        resolver.query(collection, PROJECTION, SELECTION, null, SORT_ORDER)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val displayName = cursor.getString(nameColumn).orEmpty()
                val title = cursor.getString(titleColumn)
                    ?.takeIf { it.isNotBlank() }
                    ?: displayName.substringBeforeLast('.').ifBlank { "未命名歌曲" }
                val artist = normalize(cursor.getString(artistColumn), unknownArtist)
                val album = normalize(cursor.getString(albumColumn), unknownAlbum)
                val duration = cursor.getLong(durationColumn)

                songs += Song(
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    uri = ContentUris.withAppendedId(collection, id)
                )
            }
        }
        Log.i(TAG, "从 MediaStore 读取到 ${songs.size} 首音乐")
        return songs
    }

    /** MediaStore 里没有标签时会是 null 或 "<unknown>" */
    private fun normalize(value: String?, fallback: String): String {
        val text = value?.trim().orEmpty()
        return if (text.isEmpty() || text == MediaStore.UNKNOWN_STRING) fallback else text
    }
}
