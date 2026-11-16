package com.ideacode.soundly.debug

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.ideacode.soundly.MainActivity
import com.ideacode.soundly.R
import com.ideacode.soundly.common.ui.addTopAppBarMenuProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopAppBarInstrumentedTest {
    @Test
    fun barStaysFixedAndPageActionsAreRemovedOnNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val nav = (activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment).navController
                nav.navigate(R.id.homeFragment)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.main_top_app_bar).visibility)
                nav.navigate(R.id.prepareRecordingFragment)
            }
            instrumentation.waitForIdleSync()
            var originalTop = 0
            var actionInvoked = false
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment
                val fragment = host.childFragmentManager.primaryNavigationFragment!!
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.main_top_app_bar)
                assertEquals(View.VISIBLE, toolbar.visibility)
                assertEquals(activity.getString(R.string.prepare_recording_title), toolbar.title)
                val root = activity.findViewById<View>(R.id.activity_root)
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(root)).getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                assertTrue("Toolbar must remain below the status bar", root.paddingTop >= insets.top)
                assertTrue("Content must remain above the navigation bar", root.paddingBottom >= insets.bottom)
                assertTrue("Toolbar must stay in the inset content area", toolbar.top >= root.paddingTop)
                originalTop = toolbar.top
                val scroll = fragment.requireView() as NestedScrollView
                scroll.getChildAt(0).minimumHeight = scroll.height + 600
                fragment.addTopAppBarMenuProvider(object : MenuProvider {
                    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                        menu.add(0, 12345, 0, "Test action").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    }
                    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                        actionInvoked = menuItem.itemId == 12345
                        return actionInvoked
                    }
                })
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment
                val scroll = host.childFragmentManager.primaryNavigationFragment!!.requireView() as NestedScrollView
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.main_top_app_bar)
                scroll.scrollTo(0, 500)
                assertTrue(scroll.scrollY > 0)
                assertEquals(originalTop, toolbar.top)
                assertTrue(toolbar.menu.performIdentifierAction(12345, 0))
                assertTrue(actionInvoked)
                host.navController.navigate(R.id.recordingSettingsFragment)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.main_top_app_bar)
                assertNull(toolbar.menu.findItem(12345))
            }
            androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.withContentDescription(R.string.prepare_back)
            ).perform(androidx.test.espresso.action.ViewActions.click())
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.app_nav_host) as NavHostFragment
                assertEquals(R.id.prepareRecordingFragment, host.navController.currentDestination?.id)
            }
        }
    }
}
