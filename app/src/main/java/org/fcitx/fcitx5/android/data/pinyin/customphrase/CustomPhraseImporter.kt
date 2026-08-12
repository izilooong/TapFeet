/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.customphrase

import org.fcitx.fcitx5.android.core.FcitxUtils
import org.fcitx.fcitx5.android.data.pinyin.PinyinLookup

data class ImportResult(
    val phrases: List<PinyinCustomPhrase>,
    val errors: List<String>,
    val duplicated: Int,
    val autoKeys: Set<String>
)

/**
 * Parses pasted/plain-text custom-phrase entries.
 *
 * Supported line formats (one per line, `#`/`;` start a comment, blank lines ignored):
 *   - `key=value`            explicit key, order defaults to 1 (enabled)
 *   - `key,order=value`      explicit key with order (negative order => disabled)
 *   - `中文词组`              no `=`: the pinyin key is auto-generated from the Han characters
 *
 * A value may be quoted (handled by [FcitxUtils.unescapeForValue]). When an explicit
 * key contains illegal characters, the key is derived from the value's pinyin as a
 * fallback. Exact `(key, value)` duplicates (relative to [existing]) are skipped.
 */
object CustomPhraseImporter {

    private val KEY_PATTERN = Regex("""^[A-Za-z]+$""")

    fun import(
        text: String,
        existing: List<PinyinCustomPhrase> = emptyList()
    ): ImportResult {
        val phrases = mutableListOf<PinyinCustomPhrase>()
        val errors = mutableListOf<String>()
        val autoKeys = mutableSetOf<String>()
        var duplicated = 0
        val seen = mutableSetOf<Pair<String, String>>().apply {
            existing.forEach { add(it.key to it.value) }
        }

        text.lineSequence().forEach { raw ->
            val line = raw
                .replace('，', ',')
                .replace('＝', '=')
                .replace('　', ' ')
                .trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) return@forEach
            val parsed = if (line.contains('=')) {
                parseExplicit(line, errors)
            } else {
                parseImplicit(line, errors)
            } ?: return@forEach
            val dupKey = parsed.key to parsed.value
            if (!seen.add(dupKey)) {
                duplicated++
                return@forEach
            }
            phrases.add(PinyinCustomPhrase(parsed.key, parsed.order, parsed.value))
            if (parsed.isAuto) autoKeys.add(parsed.key)
        }
        return ImportResult(phrases, errors, duplicated, autoKeys)
    }

    private data class Parsed(val key: String, val order: Int, val value: String, val isAuto: Boolean)

    private fun parseExplicit(line: String, errors: MutableList<String>): Parsed? {
        val eq = line.indexOf('=')
        val left = line.substring(0, eq).trim()
        val right = line.substring(eq + 1)
        val value = try {
            FcitxUtils.unescapeForValue(right)
        } catch (e: Exception) {
            errors.add(line)
            return null
        }
        val comma = left.indexOf(',')
        val keyRaw = if (comma >= 0) left.substring(0, comma).trim() else left.trim()
        val orderRaw = if (comma >= 0) left.substring(comma + 1).trim() else ""
        val order = if (orderRaw.isEmpty()) 1 else orderRaw.toIntOrNull()
        if (order == null || order == 0) {
            errors.add(line)
            return null
        }
        val keyValid = KEY_PATTERN.matches(keyRaw)
        val key = if (keyValid) {
            keyRaw.lowercase()
        } else {
            PinyinLookup.pinyinOf(value) ?: run {
                errors.add(line)
                return null
            }
        }
        return Parsed(key, order, value, isAuto = !keyValid)
    }

    private fun parseImplicit(line: String, errors: MutableList<String>): Parsed? {
        val value = line.trim()
        val key = PinyinLookup.pinyinOf(value) ?: run {
            errors.add(line)
            return null
        }
        if (!KEY_PATTERN.matches(key)) {
            errors.add(line)
            return null
        }
        return Parsed(key, 1, value, isAuto = true)
    }
}
