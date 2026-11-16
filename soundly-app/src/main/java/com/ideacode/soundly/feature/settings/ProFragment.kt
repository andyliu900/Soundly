package com.ideacode.soundly.feature.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ideacode.soundly.R
import com.ideacode.soundly.databinding.FragmentProBinding

/** The page describes planned directions without starting a purchase flow. */
class ProFragment : Fragment(R.layout.fragment_pro) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        FragmentProBinding.bind(view).proBack.setOnClickListener { findNavController().navigateUp() }
    }
}
