/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.Preference.SummaryProvider
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceGroup
import androidx.preference.isEmpty
import arrow.core.getOrElse
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.LongClickPreference
import org.fcitx.fcitx5.android.utils.buildDocumentsProviderIntent
import org.fcitx.fcitx5.android.utils.buildPrimaryStorageIntent
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigBool
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigCustom
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnum
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigEnumList
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigExternal
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigInt
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigKey
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigList
import org.fcitx.fcitx5.android.utils.config.ConfigDescriptor.ConfigString
import org.fcitx.fcitx5.android.utils.config.ConfigType
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.parcelableArray
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

object PreferenceScreenFactory {

    private val hideKeyConfig by AppPrefs.getInstance().advanced.hideKeyConfig

    fun create(
        preferenceManager: PreferenceManager,
        fragmentManager: FragmentManager,
        raw: RawConfig,
        save: () -> Unit
    ): PreferenceScreen {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        val cfg = raw["cfg"]
        val desc = raw["desc"]
        val store = FcitxRawConfigStore(cfg)
        // TODO: needs some error handling
        val topLevelDesc = ConfigDescriptor.parseTopLevel(desc).getOrElse { throw it }
        screen.title = topLevelDesc.name
        topLevelDesc.values.forEach {
            general(context, fragmentManager, cfg.findByName(it.name), screen, it, store, save)
        }
        return screen
    }

    /**
     * A single tab grouping config items. [title] is the tab label, [screen] holds the
     * preferences rendered for that group (one top-level fcitx5 `[Section]`, or the
     * "General" bucket for top-level scalar items not belonging to any section).
     */
    data class ConfigTab(val title: String, val screen: PreferenceScreen)

    /**
     * Like [create], but splits the top-level config into multiple [ConfigTab]s so the
     * caller can present them with a tab switcher instead of one long flat list.
     *
     * - Each top-level [ConfigCustom] (a fcitx5 `[Section]`) becomes its own tab; its
     *   section title is expressed by the tab, so we do NOT render the outer
     *   [PreferenceCategory] (that would duplicate the title).
     * - Top-level scalar items (not inside any section) are collected into a "General" tab.
     * - Sections whose items are all hidden (e.g. by [hideKeyConfig]) are skipped.
     * - A single result means no meaningful grouping: callers should hide the tab UI.
     */
    fun createTabbed(
        preferenceManager: PreferenceManager,
        fragmentManager: FragmentManager,
        raw: RawConfig,
        save: () -> Unit
    ): List<ConfigTab> {
        val context = preferenceManager.context
        val cfg = raw["cfg"]
        val desc = raw["desc"]
        val store = FcitxRawConfigStore(cfg)
        // TODO: needs some error handling
        val topLevelDesc = ConfigDescriptor.parseTopLevel(desc).getOrElse { throw it }
        val tabs = mutableListOf<ConfigTab>()
        val generalScreen = preferenceManager.createPreferenceScreen(context)
        topLevelDesc.values.forEach { item ->
            if (item is ConfigCustom) {
                val sectionCfg = cfg.findByName(item.name)
                val subStore = FcitxRawConfigStore(sectionCfg ?: RawConfig())
                val values = item.customTypeDef?.values
                if (!values.isNullOrEmpty()) {
                    val tabScreen = preferenceManager.createPreferenceScreen(context)
                    values.forEach { child ->
                        general(
                            context, fragmentManager, sectionCfg?.findByName(child.name),
                            tabScreen, child, subStore, save
                        )
                    }
                    if (!tabScreen.isEmpty()) {
                        tabs.add(ConfigTab(item.description ?: item.name, tabScreen))
                    }
                }
            } else {
                general(context, fragmentManager, cfg.findByName(item.name), generalScreen, item, store, save)
            }
        }
        if (!generalScreen.isEmpty()) {
            tabs.add(0, ConfigTab(context.getString(R.string.general), generalScreen))
        }
        return tabs
    }

    /**
     * One config source (either an IM config or a same-named addon config) belonging to a
     * [DictionarySource]. The bound [raw] holds the source's *full* config (`cfg` + `desc`), and
     * [save] writes the full config back. The rendering layer only shows dictionary-related
     * entries, but saving never drops unrelated settings.
     */
    data class DictionaryEntry(
        val raw: RawConfig,
        val sourceId: String,
        val addonId: String,
        val save: suspend (FcitxAPI) -> Unit
    )

    /**
     * One input method. Aggregates every dictionary-related config source ([entries]) that the IM
     * provides — typically its IM config and its same-named addon config. Each entry keeps its own
     * full config and is written back independently.
     */
    data class DictionarySource(
        val sourceId: String,
        val title: String,
        val entries: List<DictionaryEntry>
    )

    /** Scalar option names (by source unique id) that are dictionary-related. */
    private val DICT_ALLOWLIST: Map<String, Set<String>> = mapOf(
        "pinyin" to setOf(
            "CloudPinyinEnabled", "CloudPinyinIndex", "CloudPinyinAnimation",
            "KeepCloudPinyinPlaceHolder"
        ),
        "table" to setOf(
            "Learning", "ModifyDictionaryKey"
        ),
        "chewing" to setOf("AddPhraseForward"),
        "anthy" to setOf("learnOnManualCommit", "learnOnAutoCommit", "AddWord", "DictAdmin")
    )

    /**
     * External (navigation-link) option types that are dictionary-related, mapped to the set of
     * source ids (addon unique names) under which they may appear. A table-global or pinyin-global
     * external (e.g. "码表全局选项" / TableGlobal) is owned by the *addon*, not by any individual
     * input method, so it must only render inside the addon's own tab — never inside wubi / ziranma
     * / shuangpin tabs, whose IM configs merely reference the same global external.
     */
    private val DICT_EXTERNAL_SOURCES: Map<ConfigExternal.ETy, Set<String>> = mapOf(
        ConfigExternal.ETy.PinyinDict to setOf("pinyin"),         // pinyin DictManager -> PinyinDict page
        ConfigExternal.ETy.PinyinCustomPhrase to setOf("pinyin"), // pinyin CustomPhrase -> Custom Phrase page
        ConfigExternal.ETy.QuickPhrase to setOf("pinyin"),        // pinyin / quickphrase editor -> Quick Phrase page
        ConfigExternal.ETy.TableGlobal to setOf("table"),        // table global config
        ConfigExternal.ETy.AndroidTable to setOf("table"),       // table manage input methods
        ConfigExternal.ETy.RimeUserDataDir to setOf("rime")      // rime user data dir
    )

    /**
     * Aggregate dictionary-related options from multiple fcitx config sources (addon configs and
     * input method configs) into one tabbed [PreferenceScreen] list. Each source becomes a tab;
     * within a tab only options listed in [DICT_ALLOWLIST] / [DICT_EXTERNAL_SOURCES] are rendered,
     * everything else stays untouched in the full config. The bound [FcitxRawConfigStore] always
     * wraps the source's *full* cfg, so saving writes back the complete config.
     */
    fun createDictionaryTabs(
        sources: List<DictionarySource>,
        preferenceManager: PreferenceManager,
        fragmentManager: FragmentManager,
        save: () -> Unit
    ): List<ConfigTab> {
        val context = preferenceManager.context
        val tabs = mutableListOf<ConfigTab>()
        for (source in sources) {
            val screen = preferenceManager.createPreferenceScreen(context)
            val seen = mutableSetOf<String>()
            for (entry in source.entries) {
                val cfg = entry.raw.findByName("cfg") ?: continue
                val desc = entry.raw.findByName("desc") ?: continue
                val store = FcitxRawConfigStore(cfg)
                val topLevelDesc = ConfigDescriptor.parseTopLevel(desc).getOrElse { continue }
                renderDictItems(
                    context, fragmentManager, cfg, screen,
                    topLevelDesc.values, store, entry.sourceId, entry.addonId, save, seen
                )
            }
            if (source.sourceId == "pinyin") {
                // User dictionary (self-learned words) — enumerated + swipe-to-delete,
                // distinct from the pinyin library / custom phrase pages.
                screen.addPreference(Preference(context).apply {
                    setTitle(R.string.user_dict)
                    setOnPreferenceClickListener {
                        fragmentManager.primaryNavigationFragment
                            ?.let { it.navigateWithAnim(SettingsRoute.PinyinUserDict) }
                        true
                    }
                })
            }
            if (!screen.isEmpty()) {
                tabs.add(ConfigTab(source.title, screen))
            }
        }
        return tabs
    }

    private fun renderDictItems(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig,
        screen: PreferenceGroup,
        descriptors: List<ConfigDescriptor<*, *>>,
        store: PreferenceDataStore,
        sourceId: String,
        addonId: String,
        save: () -> Unit,
        seen: MutableSet<String>
    ) {
        descriptors.forEach { d ->
            // Skip an option name already rendered by another config source in this same tab
            // (e.g. pinyin's IM config and its addon config both carry the dictionary options).
            if (d.name in seen) return@forEach
            when {
                d is ConfigExternal -> {
                    if (DICT_EXTERNAL_SOURCES[d.knownType]?.contains(sourceId) == true) {
                        seen.add(d.name)
                        general(context, fragmentManager, cfg.findByName(d.name), screen, d, store, save)
                    }
                }
                d is ConfigCustom -> {
                    val children = d.customTypeDef?.values ?: emptyList()
                    if (children.any { isDictRelated(sourceId, addonId, it) }) {
                        seen.add(d.name)
                        val subCfg = cfg.findByName(d.name) ?: RawConfig()
                        val subStore = FcitxRawConfigStore(subCfg)
                        val cat = PreferenceCategory(context).apply {
                            key = d.name
                            title = d.description ?: d.name
                            isSingleLineTitle = false
                            isIconSpaceReserved = false
                        }
                        screen.addPreference(cat)
                        renderDictItems(
                            context, fragmentManager, subCfg, cat,
                            children, subStore, sourceId, addonId, save, seen
                        )
                    }
                }
                else -> {
                    if (isDictRelated(sourceId, addonId, d)) {
                        seen.add(d.name)
                        general(context, fragmentManager, cfg.findByName(d.name), screen, d, store, save)
                    }
                }
            }
        }
    }

    private fun isDictRelated(sourceId: String, addonId: String, d: ConfigDescriptor<*, *>): Boolean {
        // Look up the allowlist by the precise source id first; if that source has no dedicated
        // entry (e.g. a concrete table IM like wubi / ziranma whose config reuses the table addon's
        // schema), fall back to its backing addon id so per-IM dictionary options (e.g. Learning)
        // still surface in that IM's own tab.
        val allowlist = DICT_ALLOWLIST[sourceId] ?: DICT_ALLOWLIST[addonId] ?: emptySet()
        return when (d) {
            is ConfigExternal -> DICT_EXTERNAL_SOURCES[d.knownType]?.contains(sourceId) == true
            else -> d.name in allowlist
        }
    }

    private fun general(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig?,
        screen: PreferenceGroup,
        descriptor: ConfigDescriptor<*, *>,
        store: PreferenceDataStore,
        save: () -> Unit
    ) {

        // Hide key related configs
        if (hideKeyConfig && ConfigType.pretty(descriptor.ty).contains("Key")) {
            return
        }

        if (descriptor is ConfigCustom) {
            custom(context, fragmentManager, cfg, screen, descriptor, save)
            return
        }

        fun stubPreference() = Preference(context).apply {
            summary =
                "${context.getString(R.string.unimplemented_type)} '${ConfigType.pretty(descriptor.ty)}'"
        }

        fun <T : Any> navigate(route: T): Boolean {
            return try {
                fragmentManager.primaryNavigationFragment!!.navigateWithAnim(route)
                true
            } catch (e: Exception) {
                Timber.w("Unable to navigate(route=$route): $e")
                false
            }
        }

        fun pinyinDictionary() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.PinyinDict(""))
            }
        }

        fun punctuationEditor(title: String, lang: String?) = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.Punctuation(title, lang))
            }
        }

        fun quickPhraseEditor() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.QuickPhraseList)
            }
        }

        fun tableInputMethod() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.TableInputMethods)
            }
        }

        fun pinyinCustomPhrase() = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(SettingsRoute.PinyinCustomPhrase)
            }
        }

        fun rimeUserDataDir(title: String): Preference = LongClickPreference(context).apply {
            setOnPreferenceClickListener {
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setMessage(R.string.open_rime_user_data_dir)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        try {
                            context.startActivity(buildDocumentsProviderIntent())
                        } catch (e: Exception) {
                            context.toast(e)
                        }
                    }
                    .show()
                true
            }

            // make it a hidden option, because of compatibility issues
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setOnPreferenceLongClickListener {
                    try {
                        context.startActivity(buildPrimaryStorageIntent("data/rime"))
                    } catch (e: Exception) {
                        context.toast(e)
                    }
                }
            }
        }

        fun listPreference(subtype: ConfigType<*>): Preference = object : Preference(context) {
            override fun onClick() {
                navigate(SettingsRoute.ListConfig(cfg ?: RawConfig(), descriptor))
                fragmentManager.setFragmentResultListener(
                    descriptor.name,
                    fragmentManager.primaryNavigationFragment!!
                ) { _, v ->
                    cfg?.subItems = v.parcelableArray(descriptor.name)
                    if (callChangeListener(null)) {
                        notifyChanged()
                    }
                }
            }
        }.apply {
            if (subtype == ConfigType.TyKey) {
                summaryProvider = SummaryProvider<Preference> {
                    val keys = cfg?.subItems?.joinToString("\n") {
                        Key.parse(it.value).localizedString
                    }
                    if (keys.isNullOrEmpty()) context.getString(R.string.none) else keys
                }
            }
        }

        fun addonConfigPreference(addon: String) = Preference(context).apply {
            setOnPreferenceClickListener {
                navigate(
                    SettingsRoute.AddonConfig(descriptor.description ?: descriptor.name, addon)
                )
            }
        }

        when (descriptor) {
            is ConfigBool -> MySwitchPreference(context).apply {
                summary = descriptor.tooltip
                setDefaultValue(descriptor.defaultValue)
            }
            is ConfigEnum -> ListPreference(context).apply {
                entries = (descriptor.entriesI18n ?: descriptor.entries).toTypedArray()
                entryValues = descriptor.entries.toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                setDefaultValue(descriptor.defaultValue)
            }
            is ConfigEnumList -> listPreference(ConfigType.TyEnum)
            is ConfigExternal -> when (descriptor.knownType) {
                ConfigExternal.ETy.PinyinDict -> pinyinDictionary()
                ConfigExternal.ETy.Punctuation -> punctuationEditor(
                    descriptor.description ?: descriptor.name,
                    // fcitx://config/addon/punctuation/punctuationmap/zh_CN
                    descriptor.uri?.substringAfterLast('/')
                )
                ConfigExternal.ETy.QuickPhrase -> quickPhraseEditor()
                ConfigExternal.ETy.Chttrans -> addonConfigPreference("chttrans")
                ConfigExternal.ETy.TableGlobal -> addonConfigPreference("table")
                ConfigExternal.ETy.AndroidTable -> tableInputMethod()
                ConfigExternal.ETy.PinyinCustomPhrase -> pinyinCustomPhrase()
                ConfigExternal.ETy.RimeUserDataDir -> rimeUserDataDir(
                    descriptor.description ?: descriptor.name
                )
                else -> stubPreference()
            }
            is ConfigInt -> {
                val min = descriptor.intMin
                val max = descriptor.intMax
                if (min != null && max != null && max - min <= 100) {
                    DialogSeekBarPreference(context).apply {
                        summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
                        descriptor.defaultValue?.let { setDefaultValue(it) }
                        this.min = min
                        this.max = max
                    }
                } else {
                    EditTextIntPreference(context).apply {
                        summaryProvider = EditTextIntPreference.SimpleSummaryProvider
                        descriptor.defaultValue?.let { setDefaultValue(it) }
                        min?.let { this.min = it }
                        max?.let { this.max = it }
                    }
                }
            }
            is ConfigKey -> FcitxKeyPreference(context).apply {
                summaryProvider = FcitxKeyPreference.SimpleSummaryProvider
                descriptor.defaultValue?.let { setDefaultValue(it) }
            }
            is ConfigList -> if (descriptor.ty.subtype in ListFragment.supportedSubtypes)
                listPreference(descriptor.ty.subtype)
            else
                stubPreference()
            is ConfigString -> EditTextPreference(context).apply {
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                setDefaultValue(descriptor.defaultValue)
            }
            is ConfigCustom -> throw IllegalAccessException("Impossible!")
        }.apply {
            key = descriptor.name
            title = descriptor.description ?: descriptor.name
            isSingleLineTitle = false
            isIconSpaceReserved = false
            preferenceDataStore = store
            if (this is DialogPreference) {
                dialogTitle = title
                dialogMessage = descriptor.tooltip
            }
            setOnPreferenceChangeListener { _, _ ->
                // setOnPreferenceChangeListener runs before preferenceDataStore was updated,
                // post to save() to make sure store has been updated (hopefully)
                ContextCompat.getMainExecutor(context).execute {
                    save()
                }
                true
            }
            screen.addPreference(this)
        }
    }

    private fun custom(
        context: Context,
        fragmentManager: FragmentManager,
        cfg: RawConfig?,
        screen: PreferenceGroup,
        descriptor: ConfigCustom,
        save: () -> Unit
    ) {
        val subStore = FcitxRawConfigStore(cfg ?: RawConfig())
        val subPref = PreferenceCategory(context).apply {
            key = descriptor.name
            title = descriptor.description ?: descriptor.name
            isSingleLineTitle = false
            isIconSpaceReserved = false
        }
        screen.addPreference(subPref)
        descriptor.customTypeDef?.values?.forEach {
            general(context, fragmentManager, cfg?.findByName(it.name), screen, it, subStore, save)
        }
    }

}