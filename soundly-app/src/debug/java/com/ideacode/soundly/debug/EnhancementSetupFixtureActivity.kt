package com.ideacode.soundly.debug

import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.appbar.MaterialToolbar
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.applyEnhancementAppearance
import com.ideacode.soundly.common.ui.applySoundlyInsets
import com.ideacode.soundly.databinding.FragmentH6EnhancementSetupBinding
import com.ideacode.soundly.domain.mainflow.EnhancementMode
import com.ideacode.soundly.feature.enhancement.EnhancementSetupRenderer
import com.ideacode.soundly.feature.enhancement.EnhancementStartState
import com.ideacode.soundly.feature.preview.PreviewState
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import com.ideacode.soundly_sdk.api.model.playback.PlaybackState
import kotlin.math.abs
import kotlin.math.sin

/** Debug-only visual fixture; production layout and renderer, with sample data and no SDK task. */
class EnhancementSetupFixtureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (intent.getBooleanExtra("night", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.setup_title)
            setTitleTextAppearance(context, R.style.TextAppearance_Soundly_TopAppBar)
            setNavigationIcon(R.drawable.ic_top_app_bar_back)
            setNavigationContentDescription(R.string.prepare_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
        val b = FragmentH6EnhancementSetupBinding.inflate(layoutInflater, root, false)
        root.addView(b.root, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.applySoundlyInsets(consume = true)
        applyEnhancementAppearance(root, toolbar, false, compactToolbar = true)
        val original = AudioVariant("", PresetOption.RAW, true, 44100, 1, 1_726_000, id = "fixture-original", format = AudioFormat.M4A)
        val project = AudioProject("fixture", "产品周会", AudioSourceType.RECORDED, RecordingScene.MEETING, original, createdAt = 0, updatedAt = 0)
        val samples = (0 until 40).map { i -> (.18f + .75f * abs(sin(i * .35f))).coerceIn(0f, 1f) }
        var start = EnhancementStartState(selected = EnhancementMode.AUTO)
        var source = PreviewState(loading = false, project = project, variant = original, waveform = samples)
        lateinit var renderer: EnhancementSetupRenderer
        renderer = EnhancementSetupRenderer(b, onSelect = { mode -> start = start.copy(selected = mode); renderer.render(start, source) })
        b.setupPlay.setOnClickListener {
            source = source.copy(playback = if (source.playback is PlaybackState.Playing) PlaybackState.Paused(0, original.durationMs) else PlaybackState.Playing(0, original.durationMs))
            renderer.render(start, source)
        }
        renderer.render(start, source)
    }
}
