package com.ideacode.soundly.common.ui

import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.ideacode.soundly.R

/** Updates the title only while this destination is current. */
fun Fragment.setTopAppBarTitle(@IdRes destinationId: Int, @StringRes title: Int) {
    if (findNavController().currentDestination?.id == destinationId) {
        requireActivity().findViewById<MaterialToolbar>(R.id.main_top_app_bar).setTitle(title)
    }
}

/** Adds page actions while the fragment is resumed; they are removed with its view. */
fun Fragment.addTopAppBarMenuProvider(provider: MenuProvider) {
    requireActivity().findViewById<MaterialToolbar>(R.id.main_top_app_bar)
        .addMenuProvider(provider, viewLifecycleOwner, Lifecycle.State.RESUMED)
}
