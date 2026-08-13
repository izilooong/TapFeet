/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
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
import org.fcitx.fcitx5.android.utils.addPreference

/**
 * Top-level "Dictionaries" page: one tab per input method, listing only its dictionary-related
 * options. The two addon-level global dictionaries (pinyin / table) get their own standalone tabs,
 * because their options (cloud pinyin, table-global options, …) are shared across every input method
 * backed by that addon and must not appear under each individual IM tab. Everything else reuses the
 * existing descriptor→Preference rendering engine via [PreferenceScreenFactory.createDictionaryTabs];
 * each source keeps its own full config and is written back independently, so editing one never
 * touches the others.
 */
class DictionaryFragment : PaddingPreferenceFragment() {

    private val sources = mutableListOf<PreferenceScreenFactory.DictionarySource>()
    private var loaded = false

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
        if (!loaded) return
        // launch "save" job under supervisorJob scope
        scope.launch {
            fcitx.runOnReady {
                sources.forEach { source -> source.entries.forEach { it.save(this) } }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher
            .addCallback(this, object : OnBackPressedCallback(true) {
                // prevent "back" from navigating away while the save is still in flight
                override fun handleOnBackPressed() {
                    lifecycleScope.withLoadingDialog(requireContext(), R.string.saving) {
                        supervisorJob.complete()
                        supervisorJob.join()
                        scope.cancel()
                        findNavController().popBackStack()
                    }
                }
            })
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
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
        // pin the TabLayout above the preference list (root is a non-scrolling LinearLayout)
        (root as? ViewGroup)?.addView(tabLayout, 0)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // same Fragment instance resumed (e.g. returned via back stack): tabs already built,
        // just re-bind the tab switcher — never re-run obtainConfig.
        if (preferenceScreen?.isEmpty() == false) {
            setupTabLayout()
            return
        }
        val context = requireContext()
        lifecycleScope.withLoadingDialog(context) {
            fcitx.runOnReady { loadSources(this) }
            loaded = sources.isNotEmpty()
            if (loaded) {
                tabs = PreferenceScreenFactory.createDictionaryTabs(
                    sources, preferenceManager, parentFragmentManager, ::save
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

    private suspend fun loadSources(api: FcitxAPI) {
        // The pinyin and table addons own *global* dictionary options (cloud pinyin, table-global
        // options, …) that are shared by every input method backed by them. These get their own
        // standalone tabs, loaded from the addon config only — never per-IM, so they don't repeat
        // under wubi / ziranma / … . getAddonConfig is gated on the live addon list because calling
        // it for a non-existent addon crashes the native fcitx core (runCatching cannot catch it).
        val addonList = api.addons()
        val addonNames = addonList.map { it.uniqueName }.toSet()

        suspend fun addAddonDictTab(addonId: String) {
            if (addonId !in addonNames) return
            val raw = runCatching { api.getAddonConfig(addonId) }.getOrNull() ?: return
            val entries = listOf(
                PreferenceScreenFactory.DictionaryEntry(raw, addonId, addonId) { a ->
                    a.setAddonConfig(addonId, raw["cfg"])
                }
            )
            val title = addonList.firstOrNull { it.uniqueName == addonId }?.name ?: addonId
            sources.add(PreferenceScreenFactory.DictionarySource(addonId, title, entries))
        }
        addAddonDictTab("pinyin")
        addAddonDictTab("table")

        // Per-IM tabs: each loads only its own IM config (no addon global options). The "primary" IM
        // of an addon (uniqueName == addon, e.g. the pinyin IM) is skipped because its dictionary
        // options already live in the addon tab above; child IMs (shuangpin / wubi / ziranma / …)
        // keep their own tabs and only show their own IM-level dictionary entries.
        val globalAddons = setOf("pinyin", "table")
        for (im in api.availableIme()) {
            if (im.addon in globalAddons && im.uniqueName == im.addon) continue
            val raw = runCatching { api.getImConfig(im.uniqueName) }.getOrNull() ?: continue
            val entries = listOf(
                PreferenceScreenFactory.DictionaryEntry(raw, im.uniqueName, im.addon) { a ->
                    a.setImConfig(im.uniqueName, raw["cfg"])
                }
            )
            sources.add(PreferenceScreenFactory.DictionarySource(im.uniqueName, im.name, entries))
        }
    }

    private fun setupTabLayout() {
        val tabLayout = tabLayout ?: return
        // Rebuild the tab strip from scratch so it is correct after a config (re)load.
        tabLayout.removeAllTabs()
        tabs.forEach { tabLayout.addTab(tabLayout.newTab().setText(it.title)) }
        // Single group has nothing to switch between — hide the strip entirely.
        tabLayout.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        tabLayout.clearOnTabSelectedListeners()
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val pos = tab?.position ?: return
                if (pos !in tabs.indices) return
                // Only swap which PreferenceScreen is displayed; edits are preserved across tabs
                // because each tab's screen shares the same underlying source cfg.
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
        viewModel.setToolbarTitle(getString(R.string.dictionary))
    }
}
