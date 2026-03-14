package com.ideacode.sample_app.history

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ideacode.sample_app.R
import com.ideacode.soundly_sdk.api.Soundly
import timber.log.Timber
import kotlin.getValue
import androidx.core.net.toUri
import com.ideacode.soundly_sdk.api.model.domain.AudioHistory
import com.ideacode.soundly_sdk.api.model.domain.AudioVariant
import java.io.File

class HistoryFragment: Fragment(R.layout.fragment_history) {
    
    private val viewModel: HistoryViewModel by viewModels() {
        HistoryViewModelFactory(
            repository = Soundly.container().historyRepository
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_history)
        val adapter = HistoryAdapter(listener = object :
            HistoryItemListener {
            override fun onPlayOriginal(history: AudioHistory) {
                if (history.original.isOriginal) {
                    val filePath = history.original.filePath
                    val uri = filePath.toUri()
                    viewModel.playOriginalAudio(uri)
                    Timber.i("original filePath: $filePath")
                }
            }

            override fun onPlayVariant(audioVariant: AudioVariant) {
                val localFilePath = audioVariant.filePath
                val wavFile = File(localFilePath)
                if (wavFile.exists()) {
                    viewModel.playProcessedAudio(wavFile)
                    Timber.i("wavFile: ${wavFile.absolutePath}")
                }
            }

            override fun onDelete(history: AudioHistory) {
                confirmDelete(history)
            }

        })

        recycler.layoutManager = LinearLayoutManager(requireContext())
        val dividerItemDecoration = DividerItemDecoration(
            requireContext(),
            LinearLayoutManager.VERTICAL   // 或 HORIZONTAL
        )
        recycler.addItemDecoration(dividerItemDecoration)
        recycler.adapter = adapter

        lifecycleScope.launchWhenCreated {
            viewModel.history.collect {
                Timber.i("it: $it")
                adapter.submitList(it)
            }
        }

        viewModel.loadHistory()
    }

    private fun confirmDelete(history: AudioHistory) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除记录")
            .setMessage("确定删除该记录？")
            .setPositiveButton("删除") { _, _ ->
                Timber.i("confirmDelete historyId: ${history.id}")
                viewModel.deleteHistory(history.id)
            }
            .setNeutralButton("取消", null)
            .show()
    }
    
}