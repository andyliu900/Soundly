package com.ideacode.soundly.debug

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.UiStatus
import com.ideacode.soundly.common.ui.applySoundlyInsets
import com.ideacode.soundly.common.ui.audio.AudioPlayerUiState
import com.ideacode.soundly.databinding.ActivityUiGalleryBinding
import kotlin.math.abs
import kotlin.math.sin

/** Manual UI inspection surface; this class is compiled into debug builds only. */
class UiGalleryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityUiGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySoundlyInsets()
        var state = AudioPlayerUiState(durationMs = 120_000, positionMs = 42_000, enabled = true)
        binding.galleryWaveform.setSamples((0 until 120).map { i -> (0.15 + 0.7 * abs(sin(i * 0.39))).toFloat() })
        fun renderPlayer() {
            binding.galleryPlayer.render(state)
            binding.galleryWaveform.setProgress(state.positionMs.toFloat() / state.durationMs)
        }
        binding.galleryPlayer.onToggle = { state = state.copy(playing = !state.playing); renderPlayer() }
        binding.galleryPlayer.onSeek = { state = state.copy(positionMs = it); renderPlayer() }
        binding.galleryLoading.setOnClickListener { binding.galleryStatus.render(UiStatus.Loading(getString(R.string.gallery_loading))) }
        binding.galleryError.setOnClickListener { binding.galleryStatus.render(UiStatus.Error(getString(R.string.gallery_error), true)) }
        binding.galleryStatus.onRetry = { binding.galleryStatus.render(UiStatus.Loading(getString(R.string.gallery_loading))) }
        binding.galleryTheme.setOnClickListener {
            val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            delegate.localNightMode = if (isNight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        }
        binding.galleryDialog.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Soundly_Dialog)
                .setMessage(R.string.gallery_dialog_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .show()
        }
        binding.gallerySheet.setOnClickListener {
            val dialog = BottomSheetDialog(this, R.style.ThemeOverlay_Soundly_BottomSheet)
            val content = LayoutInflater.from(this).inflate(R.layout.view_gallery_sheet, null)
            content.findViewById<android.view.View>(R.id.gallery_sheet_close).setOnClickListener { dialog.dismiss() }
            dialog.setContentView(content)
            dialog.show()
        }
        renderPlayer()
    }
}
