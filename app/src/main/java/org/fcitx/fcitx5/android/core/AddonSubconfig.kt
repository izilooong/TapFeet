/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

suspend fun FcitxAPI.reloadPinyinDict() = setAddonSubConfig("pinyin", "dictmanager")

suspend fun FcitxAPI.getPunctuationConfig(lang: String) =
    getAddonSubConfig("punctuation", "punctuationmap/$lang")

suspend fun FcitxAPI.savePunctuationConfig(lang: String = "zh_CN", config: RawConfig) =
    setAddonSubConfig("punctuation", "punctuationmap/$lang", config)

suspend fun FcitxAPI.reloadQuickPhrase() = setAddonSubConfig("quickphrase", "editor")

suspend fun FcitxAPI.reloadPinyinCustomPhrase() = setAddonSubConfig("pinyin", "customphrase")

/**
 * A single entry in the pinyin user dictionary (an auto-learned word).
 */
data class PinyinUserDictEntry(val pinyin: String, val word: String)

/**
 * Enumerate all auto-learned words in the pinyin user dict.
 * The C++ side wraps the list in a `PinyinUserDictConfig` (a Configuration)
 * with one entry per word under the `entries` option, each holding `pinyin`
 * and `word` leaves; mergeConfigDesc serializes it under `cfg`.
 */
suspend fun FcitxAPI.enumeratePinyinUserDict(): List<PinyinUserDictEntry> {
    val root = getAddonSubConfig("pinyin", "userdict")
    val cfg = root.findByName("cfg") ?: return emptyList()
    // C++ wraps the list in an `entries` option; each item holds pinyin/word leaves.
    val entries = cfg.findByName("entries")?.subItems ?: return emptyList()
    return entries.mapNotNull { item ->
        val pinyin = item.findByName("pinyin")?.value
        val word = item.findByName("word")?.value
        if (pinyin != null && word != null) PinyinUserDictEntry(pinyin, word) else null
    }
}

/**
 * Remove a single auto-learned word from the pinyin user dict.
 * The C++ side reads `pinyin`/`word` leaves and removes + saves immediately.
 */
suspend fun FcitxAPI.removePinyinUserDictWord(pinyin: String, word: String) {
    val config = RawConfig("", subItems = arrayOf(
        RawConfig("pinyin", pinyin),
        RawConfig("word", word)
    ))
    setAddonSubConfig("pinyin", "userdict", config)
}

/**
 * Clear the entire pinyin user dict (all auto-learned words).
 * The C++ side clears the UserDict trie and saves immediately.
 */
suspend fun FcitxAPI.clearPinyinUserDict() {
    setAddonSubConfig("pinyin", "clearuserdict")
}
