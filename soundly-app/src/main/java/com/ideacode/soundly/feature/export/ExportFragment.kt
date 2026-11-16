package com.ideacode.soundly.feature.export

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.ui.DisplayFormat
import com.ideacode.soundly.common.ui.AudioVersionLabels
import com.ideacode.soundly.databinding.FragmentExportBinding
import com.ideacode.soundly.feature.home.SceneLabels
import com.ideacode.soundly_sdk.api.model.domain.AudioFormat
import com.ideacode.soundly_sdk.api.model.domain.AudioSourceType
import com.ideacode.soundly_sdk.api.model.export.ExportStage
import kotlinx.coroutines.launch
import java.util.Locale

class ExportFragment : Fragment(R.layout.fragment_export) {
    private val container get() = (requireActivity().application as SoundlyApplication).appContainer
    private val model: ExportViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T =
                ExportViewModel(handle, container.projectGateway, container.settingsStorageGateway, container.editingGateway,
                    AndroidExportedFileVerifier(requireContext()),
                    AndroidExportJournal(requireContext(), handle.get<String>("projectId").orEmpty(), handle.get<String>("variantId").orEmpty())) as T
        }
    }
    private val legacyPermissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = legacyPermissions.all { item -> ContextCompat.checkSelfPermission(requireContext(), item) == PackageManager.PERMISSION_GRANTED }
        model.permissionResult(granted)
    }
    private var rendering = false
    private var wasSuccess = false
    private var displayedFormats: List<AudioFormat> = emptyList()
    private val formatIds = mutableMapOf<AudioFormat, Int>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = FragmentExportBinding.bind(view)
        displayedFormats = emptyList(); formatIds.clear(); wasSuccess = false
        val guard = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val state = model.uiState.value
                if (!state.busy && state.result != null) model.leaveSuccess(toLibrary = false)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, guard)
        b.exportName.doOnTextChanged { text, _, _, _ -> if (!rendering) model.updateName(text?.toString().orEmpty()) }
        b.exportFormats.setOnCheckedChangeListener { _, id -> if (!rendering) formatIds.entries.firstOrNull { it.value == id }?.key?.let(model::selectFormat) }
        b.exportDownloads.setOnClickListener {
            b.exportName.clearFocus()
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(b.exportName.windowToken, 0)
            val needs = Build.VERSION.SDK_INT <= 28 && legacyPermissions.any { item -> ContextCompat.checkSelfPermission(requireContext(), item) != PackageManager.PERMISSION_GRANTED }
            model.save(needs)
        }
        b.exportShare.setOnClickListener { model.share() }
        b.exportSuccessContent.successShare.setOnClickListener { model.share() }
        b.exportSuccessContent.successUri.setOnClickListener {
            val result = model.uiState.value.result ?: return@setOnClickListener
            val uri = Uri.parse(result.destinationUri)
            val mime = when (result.format) {
                AudioFormat.WAV -> "audio/wav"
                AudioFormat.M4A -> "audio/mp4"
                AudioFormat.AAC -> "audio/aac"
                AudioFormat.MP3 -> "audio/mpeg"
                AudioFormat.UNKNOWN -> "audio/*"
            }
            val opened = runCatching {
                if (uri.scheme == "content") startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                else startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
            }.isSuccess
            if (!opened) runCatching { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                .onFailure { android.widget.Toast.makeText(requireContext(), R.string.error_operation_failed, android.widget.Toast.LENGTH_SHORT).show() }
        }
        b.exportSuccessContent.successLibrary.setOnClickListener {
            model.leaveSuccess(toLibrary = true)
        }
        b.exportOpenDownloads.setOnClickListener {
            runCatching { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        }
        b.exportPermissionSettings.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))) }
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.uiState.collect { state -> guard.isEnabled = state.busy || state.result != null; render(b, state) }
        } }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.effects.collect { entries ->
                val entry = entries.firstOrNull() ?: return@collect
                if (findNavController().currentDestination?.id != R.id.exportFragment) return@collect
                when (val effect = entry.value) {
                    ExportEffect.RequestStoragePermission -> {
                        model.acknowledge(entry.id)
                        permission.launch(legacyPermissions)
                    }
                    is ExportEffect.Leave -> {
                        model.acknowledge(entry.id)
                        val navigated = runCatching {
                            if (effect.toLibrary) {
                                findNavController().navigate(R.id.libraryFragment, null,
                                    NavOptions.Builder().setPopUpTo(R.id.homeFragment, false).setLaunchSingleTop(true).build())
                                true
                            } else findNavController().navigateUp()
                        }.getOrDefault(false)
                        if (!navigated) model.leaveNavigationFailed()
                    }
                    is ExportEffect.OpenShare -> {
                        model.acknowledge(entry.id, shareHandled = true)
                        try {
                            if (requireContext().packageManager.queryIntentActivities(effect.intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) model.shareLaunchFailed()
                            else startActivity(Intent.createChooser(effect.intent, getString(R.string.export_share)))
                        }
                        catch (_: ActivityNotFoundException) { model.shareLaunchFailed() }
                        catch (_: SecurityException) { model.shareLaunchFailed() }
                    }
                }
            }
        } }
    }

    private fun render(b: FragmentExportBinding, s: ExportScreenState) {
        val loading = s.loading || s.restoring
        val success = s.result != null && !s.restoring
        b.exportLoading.isVisible = loading
        b.exportMissing.isVisible = !loading && s.missing && !success
        b.exportConfig.isVisible = !loading && !s.missing && !success
        b.exportSuccessContent.root.isVisible = !loading && success
        if (success && !wasSuccess) b.exportRoot.post { b.exportRoot.scrollTo(0, 0) }
        wasSuccess = success
        requireActivity().findViewById<MaterialToolbar>(R.id.main_top_app_bar)?.title = getString(if (success) R.string.h7_saved_title else R.string.export_title)
        val project = s.project
        val variant = s.variant
        b.exportProjectName.text = project?.title.orEmpty()
        b.exportOriginalKept.setText(if (project?.sourceType == AudioSourceType.IMPORTED) R.string.i5_original_kept else R.string.h7_original_kept)
        b.exportScene.isVisible = project?.scene != null
        project?.scene?.let { b.exportScene.setText(SceneLabels.name(it)) }
        b.exportSourceVersion.text = AudioVersionLabels.type(requireContext(), project, variant)
        rendering = true
        try {
            if (b.exportName.text.toString() != s.name) { b.exportName.setText(s.name); b.exportName.setSelection(s.name.length) }
            if (displayedFormats != s.formats) {
                b.exportFormats.removeAllViews(); formatIds.clear()
                s.formats.forEach { format ->
                    val button = RadioButton(requireContext()).apply {
                        id = View.generateViewId(); text = format.name; minHeight = (48 * resources.displayMetrics.density).toInt()
                        setTextColor(ContextCompat.getColor(context, R.color.preview_text))
                        buttonTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.preview_primary))
                    }
                    formatIds[format] = button.id; b.exportFormats.addView(button)
                }
                displayedFormats = s.formats
            }
            s.format?.let { formatIds[it]?.let(b.exportFormats::check) }
        } finally { rendering = false }
        b.exportName.isEnabled = !s.busy
        for (i in 0 until b.exportFormats.childCount) b.exportFormats.getChildAt(i).isEnabled = !s.busy
        b.exportNameError.isVisible = s.nameError
        b.exportExtension.text = s.format?.let { ".${it.name.lowercase(Locale.ROOT)}" }.orEmpty()
        b.exportFormatNote.isVisible = s.formats.isEmpty() || s.preferenceUnavailable
        b.exportFormatNote.setText(if (s.formats.isEmpty()) R.string.h7_format_unsupported else R.string.h7_preference_fallback)
        b.exportDownloads.isEnabled = !s.busy && !s.exportBlocked && s.format != null && variant != null
        b.exportDownloads.alpha = if (b.exportDownloads.isEnabled) 1f else .5f
        b.exportShare.isEnabled = !s.busy && variant != null
        b.exportProgress.isVisible = s.exporting
        b.exportProgress.isIndeterminate = s.exporting && s.progress == null
        s.progress?.let { b.exportProgress.progress = it.percent.coerceIn(0, 100) }
        b.exportProgressText.isVisible = s.exporting || s.sharing
        b.exportProgressText.text = when {
            s.sharing -> getString(R.string.h7_sharing)
            s.progress?.stage == ExportStage.ENCODING -> getString(R.string.h7_encoding, s.progress.percent)
            s.progress?.stage == ExportStage.WRITING -> getString(R.string.h7_writing, s.progress.percent)
            else -> getString(R.string.h7_preparing)
        }
        b.exportProgress.contentDescription = b.exportProgressText.text
        val message = when {
            s.error?.code == "permission_denied" -> getString(R.string.h7_permission_error)
            s.error != null -> getString(container.errorMessageMapper.messageRes(s.error))
            s.interrupted -> getString(R.string.h7_interrupted)
            s.savedFileMissing -> getString(R.string.h7_result_missing)
            else -> null
        }
        b.exportError.isVisible = message != null
        b.exportError.text = if (s.interrupted && s.error != null && message != null)
            getString(R.string.i5_export_error_uncertain, message, getString(R.string.h7_interrupted)) else message
        b.exportOpenDownloads.isVisible = s.interrupted || s.savedFileMissing
        b.exportPermissionSettings.isVisible = s.error?.code == "permission_denied" && Build.VERSION.SDK_INT <= 28
        val detail = variant?.let { getString(R.string.h7_share_detail, it.format.name) }.orEmpty()
        b.exportShareDetail.text = detail
        val result = s.result ?: return
        val successBinding = b.exportSuccessContent
        successBinding.successName.text = result.displayName
        successBinding.successVersion.text = AudioVersionLabels.type(requireContext(), project, variant)
        successBinding.successDuration.isVisible = variant != null
        successBinding.successDuration.text = variant?.let { getString(R.string.h7_labeled_value, getString(R.string.h7_duration), DisplayFormat.durationMillis(it.durationMs)) }.orEmpty()
        successBinding.successFormat.text = getString(R.string.h7_labeled_value, getString(R.string.h7_format), result.format.name)
        successBinding.successSize.text = getString(R.string.h7_labeled_value, getString(R.string.h7_size), DisplayFormat.byteSize(result.fileSizeBytes))
        successBinding.successShareDetail.text = detail
        successBinding.successShare.setText(if (s.sharing) R.string.h7_sharing else R.string.h7_share)
        successBinding.successShare.isEnabled = !s.busy && variant != null && !s.missing
        successBinding.successLibrary.isEnabled = !s.busy
        successBinding.successError.isVisible = s.error != null
        successBinding.successError.text = s.error?.let { getString(container.errorMessageMapper.messageRes(it)) }
    }
}
