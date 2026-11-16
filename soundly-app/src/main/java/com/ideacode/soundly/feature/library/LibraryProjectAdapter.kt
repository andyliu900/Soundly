package com.ideacode.soundly.feature.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ideacode.soundly.databinding.ItemAudioProjectBinding
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.R
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.VariantType
import android.text.format.DateUtils
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat

/** Small, presentation-only adapter for F26/F27. Project changes stay in the SDK gateway. */
class LibraryProjectAdapter(
    private val onPlay: (AudioProject) -> Unit,
    private val onProjectClick: (AudioProject) -> Unit
) : ListAdapter<AudioProject, LibraryProjectAdapter.ProjectViewHolder>(ProjectDiffCallback) {
    var playingProjectId: String? = null
        set(value) { if (field != value) { field = value; notifyDataSetChanged() } }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProjectViewHolder =
        ProjectViewHolder(
            ItemAudioProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ProjectViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProjectViewHolder(
        private val binding: ItemAudioProjectBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(project: AudioProject) = with(binding) {
            projectTitle.text = project.title
            val context = root.context
            val enhanced = project.variants.count { it.variantType == VariantType.ENHANCED }
            val imported = project.sourceType == AudioSourceType.IMPORTED
            projectBadge.setText(if (enhanced > 0) R.string.s2_filter_enhanced else if (imported) R.string.s2_filter_imported else R.string.s2_filter_recorded)
            projectBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, if (enhanced > 0) R.color.library_icon_enhanced_bg else R.color.library_icon_bg))
            projectBadge.setTextColor(ContextCompat.getColor(context, if (enhanced > 0) R.color.library_badge_enhanced_text else R.color.library_badge_text))
            projectDate.text = DateUtils.getRelativeTimeSpanString(project.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            projectDuration.text = DisplayFormat.durationMillis(project.original.durationMs)
            projectDetails.text = when {
                enhanced > 0 -> context.getString(R.string.library_enhanced_versions, enhanced)
                imported -> context.getString(R.string.library_imported_unenhanced, project.original.format.name)
                else -> context.getString(R.string.library_unenhanced)
            }
            root.setOnClickListener { onProjectClick(project) }
            val playing = playingProjectId == project.id
            libraryItemPlay.setImageResource(if (playing) R.drawable.ic_h4_pause else R.drawable.ic_soundly_wave)
            libraryItemPlay.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, if (enhanced > 0) R.color.library_icon_enhanced_bg else R.color.library_icon_bg))
            libraryItemPlay.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, if (enhanced > 0) R.color.library_icon_enhanced else R.color.preview_primary))
            libraryItemPlay.contentDescription = context.getString(if (playing) R.string.s2_library_pause else R.string.s2_library_play)
            libraryItemPlay.setOnClickListener { onPlay(project) }
        }
    }


    private object ProjectDiffCallback : DiffUtil.ItemCallback<AudioProject>() {
        override fun areItemsTheSame(oldItem: AudioProject, newItem: AudioProject): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AudioProject, newItem: AudioProject): Boolean =
            oldItem == newItem
    }
}
