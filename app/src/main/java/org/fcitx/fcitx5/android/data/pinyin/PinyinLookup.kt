/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.core.data.DataManager
import timber.log.Timber
import java.io.File
import java.util.HashMap

/**
 * Converts Han characters to their tone-less full pinyin (全拼) using the same
 * `py_table.mb` data file that fcitx5's pinyinhelper module ships with.
 *
 * The table is read once and cached. No native code is involved, so this works
 * without any NDK rebuild. The byte layout mirrors `pinyinlookup.cpp`:
 *
 *     uint8_t wordLen;
 *     char    word[wordLen];   // exactly one UTF-8 codepoint
 *     uint8_t count;           // number of readings
 *     int8_t  py[count][3];    // {consonantIndex, vocalIndex, tone}
 *
 * Only the first (most common) reading of each character is used. Polyphones
 * therefore get their primary pronunciation — callers that need a different key
 * can always supply an explicit `key=词组` line instead.
 */
object PinyinLookup {

    private val TABLE_FILE =
        File(DataManager.dataDir, "usr/share/fcitx5/pinyinhelper/py_table.mb")

    // Mirrors pinyinlookup.cpp `konsonants_table` (index 0 == zero initial).
    private val KONSONANTS = arrayOf(
        "", "b", "c", "ch", "d", "f", "g", "h", "j", "k", "l", "m", "n",
        "ng", "p", "q", "r", "s", "sh", "t", "w", "x", "y", "z", "zh"
    )

    // Mirrors pinyinlookup.cpp `vokals_table` tone-0 (tone-less) column.
    private val VOKALS_TONE0 = arrayOf(
        "", "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er",
        "i", "ia", "ian", "iang", "iao", "ie", "in", "ing", "iong", "iu",
        "m", "n", "ng", "o", "ong", "ou", "u", "ua", "uai", "uan", "uang",
        "ue", "ueng", "ui", "un", "uo", "ü", "üan", "üe", "ün"
    )

    // After j/q/x/y the umlaut loses its dots (ju/qu/xu/yu); elsewhere ü -> v (nv/lv).
    private val U_VOWEL_INITIALS = setOf("j", "q", "x", "y")

    // Basic CJK Unified Ideographs block. Good enough for everyday phrases;
    // extension blocks are intentionally ignored.
    private const val CJK_START = 0x4E00
    private const val CJK_END = 0x9FFF

    private val table: Map<Int, String> by lazy { loadTable() }

    fun isAvailable(): Boolean = TABLE_FILE.exists()

    private fun Byte.u() = toInt() and 0xFF

    @Synchronized
    private fun loadTable(): Map<Int, String> {
        val result = HashMap<Int, String>(VOKALS_TONE0.size * 1024)
        if (!isAvailable()) return result
        try {
            val bytes = TABLE_FILE.readBytes()
            var i = 0
            while (i < bytes.size) {
                val wordLen = bytes[i].u()
                i++
                if (wordLen == 0) break
                if (i + wordLen > bytes.size) break
                val word = String(bytes, i, wordLen, Charsets.UTF_8)
                i += wordLen
                val cp = word.codePointAt(0)
                if (i >= bytes.size) break
                val count = bytes[i].u()
                i++
                if (count == 0) continue
                if (i + count * 3 > bytes.size) break
                // Use the first (most common) reading.
                val consonant = bytes[i].u()
                val vocal = bytes[i + 1].u()
                i += count * 3
                val c = KONSONANTS.getOrNull(consonant) ?: ""
                val vRaw = VOKALS_TONE0.getOrNull(vocal) ?: ""
                if (c.isEmpty() && vRaw.isEmpty()) continue
                val v = if ('ü' in vRaw) {
                    if (c in U_VOWEL_INITIALS) vRaw.replace('ü', 'u')
                    else vRaw.replace('ü', 'v')
                } else vRaw
                val py = c + v
                if (py.isNotEmpty()) result[cp] = py
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load pinyin table: $TABLE_FILE")
        }
        return result
    }

    /** Tone-less full pinyin of a single Han character, or null if unknown. */
    fun lookup(codepoint: Int): String? = table[codepoint]

    /**
     * Converts a piece of text to its tone-less full-pinyin key.
     * Han characters are transliterated; any non-Han character is skipped.
     * Returns null when there is no Han character to convert, or when any Han
     * character has no reading in the table (caller should then ask for an
     * explicit key).
     */
    fun pinyinOf(text: String): String? {
        if (!isAvailable()) return null
        val sb = StringBuilder()
        for (cp in text.codePoints().toArray()) {
            if (cp < CJK_START || cp > CJK_END) continue
            val py = table[cp] ?: return null
            sb.append(py)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }
}
