/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

/**
 * Normalize a fcitx5 [Key] portableString so that common "side-qualified" modifier typos are
 * accepted.
 *
 * fcitx5's native `Key::parse` only recognizes generic modifier prefixes followed by `+`
 * (`"Alt+"`, `"Control+"`, `"Shift+"`, `"Super+"`, `"Hyper+"`, plus the uppercase `"ALT_"` /
 * `"CTRL_"` / `"SHIFT_"` forms). It does NOT understand keysym-style side qualifiers such as
 * `"Alt_L+grave"` or `"Ctrl_L+c"` — those are treated as a single (unknown) keysym name and parse
 * to `Key.None` (sym = 0), which then shows as "none" in the UI and never triggers.
 *
 * This helper rewrites the side-qualified combo form into the generic modifier form, e.g.
 * `"Alt_L+grave"` -> `"Alt+grave"`, `"Ctrl_L+c"` -> `"Control+c"`. Plain keys (e.g. a bare
 * `"Alt_L"` with no trailing `+`) are left untouched, since those are valid standalone key
 * bindings (the left/right Alt *key*, not a modifier combo).
 */
fun normalizeKeyString(keyString: String): String {
    if (keyString.isEmpty() || keyString == "Sym") return keyString
    val sideQualified = Regex("(?i)(control|ctrl|alt|shift|super|hyper)_(?:l|r)\\+")
    return sideQualified.replace(keyString) { m ->
        when (m.groupValues[1].lowercase()) {
            "control", "ctrl" -> "Control+"
            "alt" -> "Alt+"
            "shift" -> "Shift+"
            "super" -> "Super+"
            "hyper" -> "Hyper+"
            else -> m.value
        }
    }
}
