package com.ideacode.soundly.debug

import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.applyEnhancementAppearance
import com.ideacode.soundly.common.ui.applySoundlyInsets
import com.ideacode.soundly.databinding.FragmentH6EnhancementProgressBinding
import com.ideacode.soundly.feature.enhancement.EnhancementProgressRenderer
import com.ideacode.soundly.feature.enhancement.ProgressState
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.AudioProject
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import com.ideacode.soundly_sdk.api.model.domain.RecordingScene
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementStage
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTask
import com.ideacode.soundly_sdk.api.model.enhancement.EnhancementTaskState

/** Static visual fixture, debug only. It renders production views without starting an SDK task. */
class EnhancementProgressPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.enhancement_progress_title)
            setTitleTextAppearance(context, R.style.TextAppearance_Soundly_TopAppBar)
            setNavigationIcon(R.drawable.ic_top_app_bar_back)
            setNavigationContentDescription(R.string.prepare_back)
            setNavigationOnClickListener { finish() }
        }
        val toolbarHeight = obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.actionBarSize)).let {
            val height = it.getDimensionPixelSize(0, (56 * resources.displayMetrics.density).toInt())
            it.recycle()
            height
        }
        root.addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toolbarHeight))
        val b = FragmentH6EnhancementProgressBinding.inflate(layoutInflater, root, false)
        root.addView(b.root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.applySoundlyInsets(consume = true)
        applyEnhancementAppearance(root, toolbar, true)
        val percent = intent.getIntExtra("percent", 68).coerceIn(0, 100)
        val task = EnhancementTask("visual-fixture", "fixture-project", "fixture-source", "fixture-output", PresetOption.AI, 0)
        EnhancementProgressRenderer.render(b, ProgressState(
            task = EnhancementTaskState.Running(task, EnhancementStage.PROCESSING, percent, 0),
            loading = false, projectId = task.projectId, sourceId = task.sourceVariantId
        ), animate = false)
        val variant = AudioVariant("", PresetOption.RAW, true, 44100, 1, 1_726_000, id = task.sourceVariantId)
        val project = AudioProject(task.projectId, "产品周会", AudioSourceType.RECORDED, RecordingScene.MEETING, variant, createdAt = 0, updatedAt = 0)
        EnhancementProgressRenderer.renderSource(b, project, variant)
    }
}
