package com.ideacode.soundly.common.ui

import android.content.Context
import com.ideacode.soundly.R
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.VariantType

/** Consistent, user-facing names for versions and their immediate source. */
object AudioVersionLabels {
    fun versions(project: AudioProject): List<AudioVariant> =
        listOf(project.original) + project.variants.sortedWith(compareBy<AudioVariant> { it.createdAt }.thenBy { it.id })

    fun type(context: Context, project: AudioProject?, variant: AudioVariant?): String = context.getString(when (variant?.variantType) {
        VariantType.ORIGINAL -> if (project?.sourceType == AudioSourceType.RECORDED) R.string.h7_source_original else R.string.i3_original
        VariantType.TRIMMED -> R.string.i3_trimmed
        VariantType.ENHANCED -> R.string.h7_source_enhanced
        null -> R.string.i5_source_unavailable
    })

    fun details(context: Context, project: AudioProject, variant: AudioVariant): String {
        val versions = versions(project)
        fun name(item: AudioVariant): String {
            val base = type(context, project, item)
            if (item.variantType == VariantType.ORIGINAL) return base
            val siblings = versions.filter { it.variantType == item.variantType }
            val ordinal = siblings.indexOfFirst { it.id == item.id } + 1
            return if (ordinal > 0) context.getString(R.string.i5_numbered_version, base, ordinal) else base
        }
        val source = variant.sourceVariantId?.let { id -> versions.firstOrNull { it.id == id } }
        val title = source?.let { context.getString(R.string.i5_version_from, name(variant), name(it)) } ?: name(variant)
        return context.getString(R.string.i5_version_details, title, variant.format.name, DisplayFormat.durationMillis(variant.durationMs))
    }
}
