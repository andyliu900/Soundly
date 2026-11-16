package com.ideacode.soundly.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.ideacode.soundly.R
import com.ideacode.soundly.databinding.ItemRecordingSceneBinding
import com.ideacode.soundly.databinding.SheetRecordingSceneBinding
import com.ideacode.soundly.domain.mainflow.RecordingScenario
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene

internal object SceneLabels {
    fun name(scene: RecordingScene): Int = when (scene) {
        RecordingScene.AUTO -> R.string.scene_auto
        RecordingScene.MEETING -> R.string.scene_meeting
        RecordingScene.CLASSROOM -> R.string.scene_classroom
        RecordingScene.INTERVIEW -> R.string.scene_interview
        RecordingScene.OTHER -> R.string.scene_voice_memo
        RecordingScene.PODCAST -> R.string.scene_podcast
        RecordingScene.OUTDOOR -> R.string.scene_outdoor
    }
    fun description(scene: RecordingScene): Int = when (scene) {
        RecordingScene.AUTO -> R.string.scene_auto_description
        RecordingScene.MEETING -> R.string.scene_meeting_description
        RecordingScene.CLASSROOM -> R.string.scene_classroom_description
        RecordingScene.INTERVIEW -> R.string.scene_interview_description
        else -> R.string.scene_voice_memo_description
    }
}

class RecordingSceneSheet : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = SheetRecordingSceneBinding.inflate(inflater, container, false)
        val selected = requireArguments().getString(ARG_SELECTED)
        binding.sceneClose.setOnClickListener { dismiss() }
        RecordingScenario.entries.forEach { scenario ->
            val row = ItemRecordingSceneBinding.inflate(inflater, binding.sceneOptions, false)
            row.sceneIcon.setImageResource(when (scenario) {
                RecordingScenario.AUTO -> R.drawable.ic_soundly_wave
                RecordingScenario.MEETING, RecordingScenario.INTERVIEW -> R.drawable.ic_people
                RecordingScenario.CLASSROOM -> R.drawable.ic_book
                RecordingScenario.VOICE_MEMO -> R.drawable.ic_microphone
            })
            row.sceneName.setText(SceneLabels.name(scenario.sdkScene))
            row.sceneDescription.setText(SceneLabels.description(scenario.sdkScene))
            row.sceneRadio.isChecked = scenario.sdkScene.name == selected
            row.sceneRow.isSelected = row.sceneRadio.isChecked
            row.sceneRow.contentDescription = getString(SceneLabels.name(scenario.sdkScene)) + "，" +
                getString(SceneLabels.description(scenario.sdkScene)) + if (row.sceneRadio.isChecked) "，" + getString(R.string.scene_selected) else ""
            row.sceneRow.setOnClickListener {
                parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(RESULT_SCENE to scenario.sdkScene.name))
                dismiss()
            }
            binding.sceneOptions.addView(row.root)
        }
        return binding.root
    }

    companion object {
        const val TAG = "recording_scene_sheet"
        const val RESULT_KEY = "recording_scene_result"
        const val RESULT_SCENE = "scene"
        private const val ARG_SELECTED = "selected"
        fun create(scene: RecordingScene) = RecordingSceneSheet().apply { arguments = bundleOf(ARG_SELECTED to scene.name) }
    }
}
