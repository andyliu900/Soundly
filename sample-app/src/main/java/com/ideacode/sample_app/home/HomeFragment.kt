package com.ideacode.sample_app.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.ideacode.sample_app.R
import com.ideacode.sample_app.history.HistoryFragment
import com.ideacode.soundly_model.audio.dsp.PresetOption
import com.ideacode.soundly_model.audio.processor.ProcessingStage
import timber.log.Timber
import java.io.File

class HomeFragment : Fragment(R.layout.fragment_home) {

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var spinnerPreset: Spinner

    private var selectAudioUri: Uri? = null
    private var processedWavFilePath: String? = null

    private lateinit var pickAudioLauncher: ActivityResultLauncher<Array<String>>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pickAudioLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                Timber.i("pickAudio uri: $uri")

                if (uri != null) {
                    onAudioSelected(uri)
                    selectAudioUri = uri
                } else {
                    Toast.makeText(context, "未选择音频文件", Toast.LENGTH_SHORT).show()
                }
            }

        spinnerPreset = view.findViewById<Spinner>(R.id.spinner_preset)
        val btnPickAudio = view.findViewById<Button>(R.id.btn_pick_audio)
        val processBar = view.findViewById<ProgressBar>(R.id.process_bar)
        val messageTv = view.findViewById<TextView>(R.id.process_message)
        val btnCancelProcess =
            view.findViewById<Button>(R.id.btn_cancel_process)

        val layoutAudioCompare =
            view.findViewById<LinearLayout>(R.id.layout_audio_compare)
        val btnPlayOriginAudio =
            view.findViewById<Button>(R.id.btn_play_origin_audio)
        val btnPlayProcessedAudio =
            view.findViewById<Button>(R.id.btn_play_processed_audio)

        val btnGotoHistory = view.findViewById<Button>(R.id.btn_goto_history)

        btnPickAudio.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        btnCancelProcess.setOnClickListener {
            viewModel.cancelProcessing()
        }

        btnPlayOriginAudio.setOnClickListener {
            if (selectAudioUri != null) {
                viewModel.playOriginalAudio(selectAudioUri!!)
                Timber.i("selectAudioUri = $selectAudioUri")
            } else {
                Toast.makeText(
                    context, "未选择音频文件，无法播放",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        btnPlayProcessedAudio.setOnClickListener {
            if (!TextUtils.isEmpty(processedWavFilePath)) {
                val wavFile = File(processedWavFilePath!!)
                viewModel.playProcessedAudio(wavFile)
                Timber.i("wavFile = ${wavFile.absolutePath}")
            } else {
                Toast.makeText(
                    context, "未完成音频文件处理，无法播放处理后的音频",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        setupPresetSpinner()

        btnGotoHistory.setOnClickListener {
            navigateToHistory()
        }

        viewLifecycleOwner.lifecycleScope.launchWhenCreated {
            viewModel.uiState.collect { state ->
                processBar.visibility =
                    if (state.isProcessing && state.stage == ProcessingStage.DSP_PROCESSING) View.VISIBLE else View.GONE

                btnCancelProcess.visibility =
                    if (state.isProcessing) View.VISIBLE else View.GONE

                processBar.progress = state.process
                messageTv.text = state.message

                if (state.stage == ProcessingStage.DSP_PROCESSING) {
                    Timber.i("stage: ${state.stage}  process:${state.process}  message: ${state.message}")
                } else {
                    Timber.i("stage: ${state.stage}  message: ${state.message}")

                    if (state.stage == ProcessingStage.COMPLETED) {
                        processedWavFilePath = state.wavFilePath
                        Timber.i("processed WavFilePath = $processedWavFilePath")
                    }
                }

                // 原始音频、处理后音频对比
                layoutAudioCompare.visibility =
                    if (state.stage == ProcessingStage.COMPLETED) View.VISIBLE else View.GONE

            }
        }
    }

    private fun setupPresetSpinner() {
        val options = PresetOption.values().toList()

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            options.map { it.title }
        ).apply {
            setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }

        spinnerPreset.adapter = adapter

        spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                viewModel.setPresetOption(options[position])
                Timber.i("spinnerPreset onItemSelected option = ${options[position].title}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                Timber.i("什么策略也没选择")
            }
        }
    }

    private fun onAudioSelected(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Timber.e("PersistableUriPermission failed: ${e.message}")
        }

        viewModel.onAudioSelected(uri)
    }

    private fun navigateToHistory() {
        val fragment = HistoryFragment().apply {
//            arguments = Bundle().apply {
//                putString("processedFilePath", outputPath)
//            }
        }

        parentFragmentManager.beginTransaction().replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

}