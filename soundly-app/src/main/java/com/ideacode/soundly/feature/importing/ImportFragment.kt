package com.ideacode.soundly.feature.importing

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.databinding.FragmentImportBinding
import kotlinx.coroutines.launch

/** Common platform boundary for selecting and importing an audio document. */
class ImportFragment : Fragment(R.layout.fragment_import) {
    private val model: ImportViewModel by viewModels {
        object : AbstractSavedStateViewModelFactory(this, arguments) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
                val app = requireActivity().application as SoundlyApplication
                return ImportViewModel(handle, ImportJournal(app), app.appContainer.editingGateway) as T
            }
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) findNavController().navigateUp() else model.start(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentImportBinding.bind(view)
        val backGuard = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (model.state.value) {
                    ImportState.Working -> Unit
                    is ImportState.Interrupted -> if (model.chooseAgain()) findNavController().navigateUp()
                    else -> findNavController().navigateUp()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backGuard)
        binding.importPrimary.setOnClickListener {
            when (model.state.value) {
                ImportState.Select -> { model.markPickerLaunched(); picker.launch(arrayOf("audio/*")) }
                is ImportState.Interrupted, ImportState.JournalFailure -> if (model.chooseAgain()) { model.markPickerLaunched(); picker.launch(arrayOf("audio/*")) }
                is ImportState.Uncertain -> model.checkAgain()
                else -> Unit
            }
        }
        binding.importBack.setOnClickListener {
            if (model.state.value is ImportState.Interrupted && !model.chooseAgain()) return@setOnClickListener
            findNavController().navigateUp()
        }
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { state ->
                val working = state is ImportState.Working
                binding.importProgress.isVisible = working
                binding.importPrimary.isVisible = !working && state !is ImportState.Completed
                binding.importBack.isVisible = !working && state !is ImportState.Completed
                binding.importMessage.text = when (state) {
                    ImportState.Select -> getString(R.string.import_select_message)
                    ImportState.Working -> getString(R.string.import_working)
                    is ImportState.Completed -> ""
                    is ImportState.Interrupted -> state.error?.let {
                        getString((requireActivity().application as SoundlyApplication).appContainer.errorMessageMapper.messageRes(it)) + "\n\n" + getString(R.string.import_interrupted)
                    } ?: getString(R.string.import_interrupted)
                    is ImportState.Uncertain -> getString(R.string.import_uncertain)
                    ImportState.JournalFailure -> getString(R.string.import_journal_failed)
                }
                binding.importPrimary.setText(if (state is ImportState.Uncertain) R.string.import_check_again else R.string.import_select)
                if (state is ImportState.Completed && findNavController().currentDestination?.id == R.id.importFragment) {
                    findNavController().navigate(R.id.audioPreviewFragment, bundleOf("projectId" to state.projectId, "variantId" to state.variantId),
                        androidx.navigation.navOptions { popUpTo(R.id.importFragment) { inclusive = true } })
                    model.acknowledgeSuccess(state.operationId)
                } else if (state == ImportState.Select && !model.pickerLaunched) {
                    model.markPickerLaunched()
                    picker.launch(arrayOf("audio/*"))
                }
            }
        } }
    }
}
