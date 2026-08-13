/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.isEmpty
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.settings.PreferenceScreenFactory
import org.fcitx.fcitx5.android.utils.addPreference

abstract class FcitxPreferenceFragment : PaddingPreferenceFragment() {
    abstract fun getPageTitle(): String
    abstract suspend fun obtainConfig(fcitx: FcitxAPI): RawConfig
    abstract suspend fun saveConfig(fcitx: FcitxAPI, newConfig: RawConfig)

    private lateinit var raw: RawConfig
    private var configLoaded = false

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob)

    private val viewModel: MainViewModel by activityViewModels()

    private val fcitx: FcitxConnection
        get() = viewModel.fcitx

    private var tabs: List<PreferenceScreenFactory.ConfigTab> = emptyList()
    private var tabLayout: TabLayout? = null
    private var selectedTab = 0

    private companion object {
        const val KEY_SELECTED_TAB = "selected_tab"
    }

    private fun save() {
        if (!configLoaded) return
        // launch "saveConfig" job under supervisorJob scope
        scope.launch {
            fcitx.runOnReady {
                saveConfig(this, raw["cfg"])
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher
            .addCallback(this, object : OnBackPressedCallback(true) {
                // prevent "back" from navigating away from this Fragment when it's still saving
                override fun handleOnBackPressed() {
                    lifecycleScope.withLoadingDialog(requireContext(), R.string.saving) {
                        // complete the parent job and wait all "saveConfig" jobs to finish
                        supervisorJob.complete()
                        supervisorJob.join()
                        scope.cancel()
                        findNavController().popBackStack()
                    }
                }
            })
    }

    /**
     * **TLDR:**
     * Intentionally empty, since we need to create PreferenceScreen during onStart,
     * or it will crash when MainActivity relaunches.
     *
     * **Long version:**
     * When `MainActivity` relaunches, its `onCreate` get called, and somewhere in `super.onCreate`
     * decided to `restoreChildFragmentState` of `NavHostFragment`, thus recreate the child fragment.
     * If that fragment was derived from `FcitxPreferenceFragment`, it needs to call `obtainConfig`
     * which would need the route params, and in turn needs `NavGraph`.
     * But at this time it's still in `MainActivity`'s `super.onCreate`, the Activity did not have
     * chance to set up `NavGraph` on `navController`, so accessing `lazyRoute` would crash.
     *
     * That is to say, if we declare `app:navGraph` on `<FragmentContainerView />` in `activity_main.xml`,
     * the graph would have been initialized when `NavHostFragment` got inflated, and does not suffer
     * from this problem? But maintain navigation destinations in XML is too tedious ...
     */
    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        tabLayout = TabLayout(requireContext()).apply {
            tabMode = TabLayout.MODE_AUTO
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // The root of a PreferenceFragmentCompat is a (non-scrolling) LinearLayout whose
        // child RecyclerView does the scrolling, so pinning the TabLayout at index 0 keeps
        // it fixed above the preference list.
        (root as? ViewGroup)?.addView(tabLayout, 0)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // make sure to create preference only once since `onViewCreated` is also called on Fragment resume
        if (preferenceScreen?.isEmpty() == false) {
            // same Fragment instance resumed (e.g. returned via back stack): tabs already built,
            // just re-bind the tab switcher — never re-run obtainConfig.
            setupTabLayout()
            return
        }
        val context = requireContext()
        lifecycleScope.withLoadingDialog(context) {
            raw = fcitx.runOnReady { obtainConfig(this) }
            configLoaded = raw.findByName("cfg") != null && raw.findByName("desc") != null
            if (configLoaded) {
                tabs = PreferenceScreenFactory.createTabbed(
                    preferenceManager, parentFragmentManager, raw, ::save
                )
                if (tabs.isEmpty()) {
                    preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
                        addPreference(R.string.no_config_options)
                    }
                } else {
                    if (savedInstanceState != null) {
                        selectedTab = savedInstanceState.getInt(KEY_SELECTED_TAB, 0)
                            .coerceIn(0, tabs.lastIndex)
                    }
                    preferenceScreen = tabs[selectedTab].screen
                }
            } else {
                tabs = emptyList()
                preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
                    addPreference(R.string.config_addon_not_loaded)
                }
            }
            setupTabLayout()
            viewModel.disableAboutButton()
        }
    }

    private fun setupTabLayout() {
        val tabLayout = tabLayout ?: return
        // Rebuild the tab strip from scratch so it is correct after a config (re)load.
        tabLayout.removeAllTabs()
        tabs.forEach { tabLayout.addTab(tabLayout.newTab().setText(it.title)) }
        // Single group has nothing to switch between — hide the strip entirely (keeps the
        // old flat-list behaviour).
        tabLayout.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        tabLayout.clearOnTabSelectedListeners()
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val pos = tab?.position ?: return
                if (pos !in tabs.indices) return
                // Only swap which PreferenceScreen is displayed; the shared `raw`/`store` is
                // untouched, so edits in any tab are preserved and saved together.
                selectedTab = pos
                preferenceScreen = tabs[pos].screen
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        if (tabs.isNotEmpty()) {
            tabLayout.selectTab(tabLayout.getTabAt(selectedTab.coerceIn(0, tabs.lastIndex)))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTab)
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getPageTitle())
    }
}