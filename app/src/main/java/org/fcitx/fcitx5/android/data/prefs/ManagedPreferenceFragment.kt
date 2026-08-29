/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.common.createSettingsTabBar

abstract class ManagedPreferenceFragment(private val preferenceProvider: ManagedPreferenceProvider) :
    PaddingPreferenceFragment() {

    private val evaluator = ManagedPreferenceVisibilityEvaluator(preferenceProvider) {
        lifecycleScope.launch {
            it.forEach { (key, enable) ->
                findPreference<Preference>(key)?.isEnabled = enable
            }
        }
    }

    open fun onPreferenceUiCreated(screen: PreferenceScreen) {}

    private var tabLayout: TabLayout? = null
    private var screens: List<Pair<String, PreferenceScreen>> = emptyList()
    private var selectedTab = 0

    private companion object {
        const val KEY_SELECTED_TAB = "managed_selected_tab"
    }

    @CallSuper
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        evaluator.evaluateVisibility()
        val ctx = preferenceManager.context
        val rootScreen = preferenceManager.createPreferenceScreen(ctx).also { screen ->
            preferenceProvider.createUi(screen)
            onPreferenceUiCreated(screen)
        }
        screens = buildTabs(ctx, rootScreen)
        selectedTab = (savedInstanceState?.getInt(KEY_SELECTED_TAB) ?: 0).coerceIn(0, screens.lastIndex)
        preferenceScreen = screens[selectedTab].second
    }

    /**
     * The underlying [ManagedPreferenceCategory.createUi] renders each declared `category(...)` as a
     * top-level [PreferenceCategory] inside a single screen. Turn those categories into tabs so the
     * page is no longer one long flat list. Each category's children are moved into its own screen;
     * any items not inside a category stay on the root screen as a "General" tab. A single-category
     * (or category-less) page degrades to one tab and the [TabLayout] is hidden.
     */
    private fun buildTabs(ctx: android.content.Context, rootScreen: PreferenceScreen): List<Pair<String, PreferenceScreen>> {
        val categories = ArrayList<PreferenceCategory>()
        for (i in 0 until rootScreen.preferenceCount) {
            val p = rootScreen.getPreference(i)
            if (p is PreferenceCategory) categories.add(p)
        }
        if (categories.isEmpty()) return listOf("" to rootScreen)
        val tabs = mutableListOf<Pair<String, PreferenceScreen>>()
        for (cat in categories) {
            val tabScreen = preferenceManager.createPreferenceScreen(ctx)
            val children = ArrayList<Preference>()
            for (i in 0 until cat.preferenceCount) children.add(cat.getPreference(i))
            for (pref in children) {
                cat.removePreference(pref)
                tabScreen.addPreference(pref)
            }
            val title = cat.title?.toString().orEmpty().ifEmpty { ctx.getString(R.string.general) }
            tabs.add(title to tabScreen)
            rootScreen.removePreference(cat)
        }
        if (rootScreen.preferenceCount > 0) {
            tabs.add(0, ctx.getString(R.string.general) to rootScreen)
        }
        return tabs
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        tabLayout = createSettingsTabBar(requireContext())
        (root as? ViewGroup)?.addView(tabLayout, 0)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabLayout()
    }

    private fun setupTabLayout() {
        val tl = tabLayout ?: return
        if (screens.size <= 1) {
            tl.visibility = View.GONE
            return
        }
        tl.visibility = View.VISIBLE
        tl.removeAllTabs()
        screens.forEach { (title, _) -> tl.addTab(tl.newTab().setText(title)) }
        tl.selectTab(tl.getTabAt(selectedTab))
        tl.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val pos = tab?.position ?: return
                selectedTab = pos
                preferenceScreen = screens[pos].second
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTab)
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AppPrefs.getInstance().syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }

    override fun onDestroy() {
        evaluator.destroy()
        super.onDestroy()
    }
}