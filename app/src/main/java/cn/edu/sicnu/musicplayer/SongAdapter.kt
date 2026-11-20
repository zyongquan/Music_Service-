package cn.edu.sicnu.musicplayer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.edu.sicnu.musicplayer.databinding.ItemSongBinding

/**
 * 歌曲列表适配器。
 *
 * 用 ListAdapter + DiffUtil 只刷新有变化的条目；
 * 正在播放的那一条会显示跳动图标、淡紫色卡片和加粗的标题。
 */
class SongAdapter(
    private val onSongClick: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.SongViewHolder>(DIFF_CALLBACK) {

    private var playingSongId: Long? = null
    private var isPlaying: Boolean = false

    /** 服务状态变化时调用：只刷新原来和现在的两行 */
    fun setNowPlaying(songId: Long?, playing: Boolean) {
        if (playingSongId == songId && isPlaying == playing) return
        val previousId = playingSongId
        playingSongId = songId
        isPlaying = playing

        if (previousId != null && previousId != songId) {
            currentList.indexOfFirst { it.id == previousId }
                .takeIf { it >= 0 }
                ?.let { notifyItemChanged(it) }
        }
        if (songId != null) {
            currentList.indexOfFirst { it.id == songId }
                .takeIf { it >= 0 }
                ?.let { notifyItemChanged(it) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val binding = ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class SongViewHolder(
        private val binding: ItemSongBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song, position: Int) {
            val context = binding.root.context
            val nowPlaying = song.id == playingSongId

            binding.indexText.text = String.format("%02d", position + 1)
            binding.playingIcon.isVisible = nowPlaying && isPlaying
            binding.indexText.isVisible = !(nowPlaying && isPlaying)

            binding.titleText.text = song.title
            binding.titleText.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (nowPlaying) R.color.brand_deep else R.color.text_primary
                )
            )
            binding.metaText.text = song.metaText
            binding.durationText.text = song.durationText

            binding.itemRoot.setCardBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (nowPlaying) R.color.playing_bg else R.color.card_bg
                )
            )
            binding.itemRoot.strokeColor = ContextCompat.getColor(
                context,
                if (nowPlaying) R.color.playing_stroke else R.color.card_stroke
            )
            binding.indexContainer.setBackgroundResource(
                if (nowPlaying) R.drawable.bg_index_circle_playing else R.drawable.bg_index_circle
            )
            binding.playingIcon.imageTintList = ContextCompat.getColorStateList(
                context,
                R.color.brand_accent
            )

            binding.root.contentDescription = context.getString(
                R.string.cd_song_format,
                position + 1,
                song.title,
                song.metaText
            )
            binding.itemRoot.setOnClickListener { onSongClick(song) }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean =
                oldItem == newItem
        }
    }
}
