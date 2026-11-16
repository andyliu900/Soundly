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
import com.ideacode.soundly.databinding.FragmentAudioPreviewBinding
import com.ideacode.soundly.feature.preview.AudioPreviewRenderer
import com.ideacode.soundly.feature.preview.PreviewState
import com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption
import com.ideacode.soundly_sdk.api.model.domain.*
import kotlin.math.abs
import kotlin.math.sin

/** Debug-only fixture: production view and renderer with example project and waveform data. */
class AudioPreviewFixtureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = if (intent.getBooleanExtra("night", false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.preview_complete)
            setTitleTextAppearance(context, R.style.TextAppearance_Soundly_TopAppBar)
            setNavigationIcon(R.drawable.ic_top_app_bar_back)
            setNavigationContentDescription(R.string.prepare_back)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.preview_actions)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
        val b = FragmentAudioPreviewBinding.inflate(layoutInflater, root, false)
        root.addView(b.root, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.applySoundlyInsets(consume = true)
        applyEnhancementAppearance(root, toolbar, false, compactToolbar = true)
        val now = System.currentTimeMillis()
        val original = AudioVariant("", PresetOption.RAW, true, 44100, 1, 1_726_000, id = "original", format = AudioFormat.M4A, fileSizeBytes = 44_879_053)
        val markers = listOf(AudioMarker("one", "fixture", 135_000, "产品预算", now), AudioMarker("two", "fixture", 1_120_000, "用户反馈", now))
        val project = AudioProject("fixture", "产品周会", AudioSourceType.RECORDED, RecordingScene.MEETING, original, markers = markers, createdAt = now, updatedAt = now)
        val samples = (0 until 120).map { i -> (.1f + .65f * abs(sin(i * .23f) * sin(i * .058f))).coerceIn(0f, 1f) }
        AudioPreviewRenderer(b) {}.render(PreviewState(loading = false, project = project, variant = original, waveform = samples))
    }
}
