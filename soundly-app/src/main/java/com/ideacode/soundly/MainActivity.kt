package com.ideacode.soundly

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.common.ui.applyEnhancementAppearance
import com.ideacode.soundly.common.ui.applySoundlyInsets
import com.ideacode.soundly.databinding.ActivityMainBinding
import com.ideacode.soundly_sdk.api.model.recording.RecordingState

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val navController get() =
        (supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mainBottomNavigation.isItemActiveIndicatorEnabled = false
        binding.root.applySoundlyInsets(consume = true) {
            navController.currentDestination?.id != R.id.startupFragment
        }
        binding.mainBottomNavigation.setOnItemSelectedListener { item ->
            val current = navController.currentDestination?.id
            if (current == item.itemId) return@setOnItemSelectedListener true
            if (item.itemId !in topLevelDestinations || current !in topLevelDestinations) {
                return@setOnItemSelectedListener false
            }
            if (item.itemId == R.id.homeFragment && navController.popBackStack(R.id.homeFragment, false)) {
                return@setOnItemSelectedListener true
            }
            val options = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setPopUpTo(R.id.homeFragment, false)
                .build()
            navController.navigate(item.itemId, null, options)
            true
        }
        binding.mainTopAppBar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isTopLevel = destination.id in topLevelDestinations
            binding.mainTopAppBar.isVisible = !isTopLevel && destination.id !in fullScreenDestinations
            binding.mainTopAppBar.title = destination.label
            if (destination.id != R.id.startupFragment) {
                val enhancementPage = destination.id in setOf(R.id.enhancementProgressFragment, R.id.enhancementResultFragment)
                applyEnhancementAppearance(binding.root, binding.mainTopAppBar, enhancementPage,
                    compactToolbar = enhancementPage || destination.id == R.id.audioPreviewFragment || destination.id == R.id.enhancementSetupFragment || destination.id == R.id.exportFragment)
            }
            binding.mainBottomNavigation.isVisible = isTopLevel
            if (isTopLevel && binding.mainBottomNavigation.selectedItemId != destination.id) {
                binding.mainBottomNavigation.selectedItemId = destination.id
            }
            ViewCompat.requestApplyInsets(binding.root)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            returnToActiveRecording()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        returnToActiveRecording()
    }

    private fun returnToActiveRecording() {
        val current = navController.currentDestination?.id
        if (current == R.id.startupFragment || current == R.id.recordingFragment) return
        val container = (application as SoundlyApplication).appContainer
        if (container.soundlySdkProvider.get() !is AppResult.Success) return
        val active = when (container.recordingGateway.state.value) {
            is RecordingState.Recording, is RecordingState.Paused,
            is RecordingState.Interrupted, is RecordingState.Finalizing -> true
            else -> false
        }
        if (!active || supportFragmentManager.isStateSaved) return
        if (!navController.popBackStack(R.id.recordingFragment, false)) {
            navController.navigate(R.id.recordingFragment, null, NavOptions.Builder().setLaunchSingleTop(true).build())
        }
    }

    override fun onSupportNavigateUp(): Boolean = navController.navigateUp() || super.onSupportNavigateUp()

    private companion object {
        val fullScreenDestinations = setOf(R.id.startupFragment, R.id.onboardingFragment)
        val topLevelDestinations = setOf(R.id.homeFragment, R.id.libraryFragment, R.id.settingsFragment)
    }
}
