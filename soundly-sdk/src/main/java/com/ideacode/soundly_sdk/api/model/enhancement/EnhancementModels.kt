package com.ideacode.soundly_sdk.api.model.enhancement

import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.capability.EnhancementResult
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption

/** Immutable input snapshot retained for cancellation diagnostics and retries. */
data class EnhancementRequest(
    val projectId: String,
    val sourceVariantId: String,
    val preset: PresetOption
)

/**
 * Stable identity for one enhancement attempt.
 *
 * A retry always creates a new task and output variant. [retryOfTaskId] links
 * the new attempt to the terminal task that it replaces without overwriting it.
 */
data class EnhancementTask(
    val taskId: String,
    val projectId: String,
    val sourceVariantId: String,
    val variantId: String,
    val preset: PresetOption,
    val createdAt: Long,
    val retryOfTaskId: String? = null
) {
    val request: EnhancementRequest
        get() = EnhancementRequest(projectId, sourceVariantId, preset)
}

/** Public enhancement pipeline. Terminal values never transition again. */
enum class EnhancementStage {
    PREPARING,
    LOADING,
    ANALYZING,
    RECOMMENDING,
    PROCESSING,
    WRITING,
    SAVING,
    COMPLETED,
    CANCELLED,
    FAILED
}

sealed interface EnhancementTaskState {
    val task: EnhancementTask
    val stage: EnhancementStage
    val updatedAt: Long

    data class Running(
        override val task: EnhancementTask,
        override val stage: EnhancementStage,
        /** Overall task progress, monotonically increasing from 0 through 100. */
        val progressPercent: Int,
        override val updatedAt: Long
    ) : EnhancementTaskState {
        init {
            require(stage in RUNNING_STAGES) { "Running state requires a running stage" }
            require(progressPercent in 0..100) { "Progress must be between 0 and 100" }
        }
    }

    data class Completed(
        override val task: EnhancementTask,
        val result: EnhancementResult,
        override val updatedAt: Long
    ) : EnhancementTaskState {
        override val stage: EnhancementStage = EnhancementStage.COMPLETED
    }

    data class Cancelled(
        override val task: EnhancementTask,
        val cancelledAtStage: EnhancementStage,
        override val updatedAt: Long
    ) : EnhancementTaskState {
        override val stage: EnhancementStage = EnhancementStage.CANCELLED
    }

    data class Failed(
        override val task: EnhancementTask,
        val error: SoundlyError,
        val failedAtStage: EnhancementStage,
        override val updatedAt: Long
    ) : EnhancementTaskState {
        override val stage: EnhancementStage = EnhancementStage.FAILED
        val retryable: Boolean
            get() = error.retryable
    }

    companion object {
        private val RUNNING_STAGES = setOf(
            EnhancementStage.PREPARING,
            EnhancementStage.LOADING,
            EnhancementStage.ANALYZING,
            EnhancementStage.RECOMMENDING,
            EnhancementStage.PROCESSING,
            EnhancementStage.WRITING,
            EnhancementStage.SAVING
        )
    }
}
