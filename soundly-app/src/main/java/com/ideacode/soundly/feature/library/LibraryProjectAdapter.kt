package com.ideacode.soundly.feature.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ideacode.soundly.databinding.ItemAudioProjectBinding
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import java.util.Locale

/** Small, presentation-only adapter for F26/F27. Project changes stay in the SDK gateway. */
class LibraryProjectAdapter(
    private val onProjectClick: (AudioProject) -> Unit
) : ListAdapter<AudioProject, LibraryProjectAdapter.ProjectViewHolder>(ProjectDiffCallback) {

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
            projectDetails.text = listOf(
                project.sourceType.name,
                project.original.format.name,
                formatDuration(project.original.durationMs),
                "${project.variants.size + 1}"
            ).joinToString(" · ")
            root.setOnClickListener { onProjectClick(project) }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs / 1_000L
        return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
    }

    private object ProjectDiffCallback : DiffUtil.ItemCallback<AudioProject>() {
        override fun areItemsTheSame(oldItem: AudioProject, newItem: AudioProject): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AudioProject, newItem: AudioProject): Boolean =
            oldItem == newItem
    }
}
