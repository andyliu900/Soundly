package com.ideacode.soundly.debug

import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.applyEnhancementAppearance
import com.ideacode.soundly.common.ui.applySoundlyInsets
import com.ideacode.soundly.databinding.FragmentH6EnhancementResultBinding
import com.ideacode.soundly.feature.enhancement.EnhancementResultRenderer
import com.ideacode.soundly.feature.enhancement.ResultState
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState

/** Debug sample data only: production layout/renderer, no SDK playback or persisted projects. */
class EnhancementResultPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.enhancement_result_complete)
            setTitleTextAppearance(context, R.style.TextAppearance_Soundly_TopAppBar)
            setNavigationIcon(R.drawable.ic_top_app_bar_back)
            setNavigationContentDescription(R.string.prepare_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
        val b = FragmentH6EnhancementResultBinding.inflate(layoutInflater, root, false)
        root.addView(b.root, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.applySoundlyInsets(consume = true)
        applyEnhancementAppearance(root, toolbar, true)
        val original = AudioVariant("", PresetOption.RAW, true, 44100, 1, 1_726_000, id = "original", format = AudioFormat.M4A)
        val enhanced = original.copy(id = "enhanced", presetOption = PresetOption.AI, isOriginal = false, format = AudioFormat.WAV)
        val project = AudioProject("preview", "产品周会", AudioSourceType.RECORDED, RecordingScene.MEETING, original, variants = listOf(enhanced), createdAt = 0, updatedAt = 0)
        val samples = listOf(.05f,.12f,.22f,.12f,.24f,.44f,.18f,.2f,.25f,.4f,.6f,.25f,.2f,.4f,.15f,.22f,.6f,.4f,.25f,.2f,.12f,.5f,.18f,.15f,.3f,.1f,.05f)
        var state = ResultState(loading = false, project = project, source = original, enhanced = enhanced,
            selectedId = if (intent.getBooleanExtra("original", false)) original.id else enhanced.id,
            waveform = samples, playback = if (intent.getBooleanExtra("original", false)) PlaybackState.Playing(208_000, original.durationMs) else PlaybackState.Paused(0, original.durationMs))
        val renderer = EnhancementResultRenderer(b)
        renderer.render(state)
        b.versionToggle.setOnCheckedChangeListener { _, id ->
            state = state.copy(selectedId = if (id == R.id.version_original) original.id else enhanced.id)
            renderer.render(state)
        }
    }
}
