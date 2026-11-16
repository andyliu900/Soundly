package com.ideacode.soundly.feature.enhancement

import com.ideacode.soundly.data.sdk.EditingGateway
import com.ideacode.soundly.domain.mainflow.EnhancementMode

/** A single, explicit source and product mode for both enhancement entry points. */
class StartEnhancementUseCase(private val gateway: EditingGateway) {
    suspend operator fun invoke(projectId: String, variantId: String, mode: EnhancementMode) =
        gateway.startEnhancement(projectId, variantId, mode.preset)
}
