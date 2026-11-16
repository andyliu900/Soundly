package com.ideacode.soundly.feature.home

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.databinding.ItemHomeRecentBinding
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import java.util.Date

internal class HomeRecentAdapter(private val onClick: (String) -> Unit) :
    ListAdapter<AudioProject, HomeRecentAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemHomeRecentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemHomeRecentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(project: AudioProject) = with(binding) {
            val context = root.context
            val enhanced = project.variants.any { it.variantType == VariantType.ENHANCED }
            val label = when {
                enhanced -> R.string.recent_enhanced
                project.sourceType == AudioSourceType.IMPORTED -> R.string.recent_imported
                else -> R.string.recent_original
            }
            val date = Date(project.createdAt)
            val timestamp = DateFormat.getDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
            recentTitle.text = project.title
            recentMetadata.text = listOf(context.getString(label), timestamp,
                context.getString(R.string.recent_variants, project.variants.size + 1)).joinToString(" · ")
            recentDuration.text = DisplayFormat.durationMillis(project.original.durationMs)
            root.setOnClickListener { onClick(project.id) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AudioProject>() {
            override fun areItemsTheSame(oldItem: AudioProject, newItem: AudioProject) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: AudioProject, newItem: AudioProject) = oldItem == newItem
        }
    }
}
