package com.ideacode.sample_app.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ideacode.sample_app.R
import com.ideacode.soundly_model.domain.AudioHistory
import timber.log.Timber

class HistoryAdapter(
    private val listener: HistoryItemListener
): ListAdapter<AudioHistory, HistoryAdapter.VH>(DiffCallback) {

    companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<AudioHistory>() {
            override fun areItemsTheSame(
                oldItem: AudioHistory,
                newItem: AudioHistory
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: AudioHistory,
                newItem: AudioHistory
            ): Boolean {
                return oldItem == newItem
            }

        }
    }

    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(
        holder: VH,
        position: Int
    ) {
        Timber.i("adapter position: $position  item: ${getItem(position)}")

        holder.bind(getItem(position), listener)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val historyId = itemView.findViewById<TextView>(R.id.history_id)
        private val originalFilePath = itemView.findViewById<TextView>(R.id.original_filepath)
        private val processedFilePath = itemView.findViewById<TextView>(R.id.processed_filepath)
        private val presetOption = itemView.findViewById<TextView>(R.id.preset_option)
        private val btnPlayOriginal = itemView.findViewById<Button>(R.id.btn_play_original)
        private val btnPlayProcessed = itemView.findViewById<Button>(R.id.btn_play_processed)
        private val btnHistoryDelete = itemView.findViewById<Button>(R.id.btn_history_delete)

        fun bind(history: AudioHistory, listener: HistoryItemListener) {
            historyId.text = "id: ${history.id}"
            originalFilePath.text = "原始音频路径: ${history.original.filePath}"

            if (history.variants.isNotEmpty()) {
                processedFilePath.text = "处理后音频文件路径: ${history.variants[0].filePath}"
                presetOption.text = "音频处理策略: ${history.variants[0].presetOption.title}"
            }

            btnPlayOriginal.setOnClickListener {
                listener.onPlayOriginal(history)
            }

            btnPlayProcessed.setOnClickListener {
                if (history.variants.isNotEmpty()) {
                    val audioVariant = history.variants[0]
                    listener.onPlayVariant(audioVariant)
                }
            }

            btnHistoryDelete.setOnClickListener {
                listener.onDelete(history)
            }
        }
    }
}