/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.core.widget.addTextChangedListener
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.normalizeKeyString
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.styledColor
import splitties.resources.styledColorSL
import splitties.resources.styledDrawable
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.button
import splitties.views.dsl.core.editText
import splitties.views.dsl.core.imageButton
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageDrawable

/**
 * Key capture Ui for hardware keyboard shortcuts.
 *
 * Uses the same fcitx5 Key/KeySym/KeyStates system as [KeyPreferenceUi] (the global
 * key preference), so symbol keys like `$` are identified by their unicode character
 * rather than an unreliable Android keyCode.
 *
 * Stored value is a fcitx5 [Key] portableString (e.g. "Alt+space", "dollar", "Shift_L").
 * The BlackBerry SYM key (which has no fcitx5 KeySym) is stored as the special string "Sym".
 */
class KeyCaptureUi(override val ctx: Context, initialValue: String) : Ui {

    private val textView = textView {
        gravity = gravityCenter
    }

    private inner class ModifierButton(label: String, val modifier: KeyState) : Ui {
        override val ctx = this@KeyCaptureUi.ctx

        override val root = button {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                checked = !checked
                updateKey()
            }
        }

        var checked: Boolean = false
            set(value) {
                field = value
                applyStyles()
            }

        fun applyStyles() = root.apply {
            backgroundTintList = ctx.styledColorSL(
                if (checked) android.R.attr.colorAccent else android.R.attr.colorBackgroundFloating
            )
            setTextColor(
                ctx.styledColor(
                    if (checked) android.R.attr.colorForegroundInverse else android.R.attr.colorForeground
                )
            )
        }
    }

    private val modifierButtons = arrayOf(
        ModifierButton("Ctrl", KeyState.Ctrl),
        ModifierButton("Alt", KeyState.Alt),
        ModifierButton("Shift", KeyState.Shift)
    )

    private val input = editText {
        textSize = 16f
        inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_FORCE_ASCII
        privateImeOptions = FcitxInputMethodService.DeleteSurroundingFlag + "|" + FcitxInputMethodService.KeyCaptureFlag
        requestFocus()
        setOnKeyListener l@{ _, _, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@l false
            // SYM key has no fcitx5 KeySym — store as special string
            if (event.keyCode == KeyEvent.KEYCODE_SYM || event.keyCode == KeyEvent.KEYCODE_PICTSYMBOLS) {
                setKeyString("Sym")
                return@l true
            }
            val sym = KeySym.fromKeyEvent(event) ?: return@l false
            // For modifier keys (Shift_L, Alt_R, etc.), strip their own modifier state
            // so that pressing Shift_L stores "Shift_L" rather than "Shift+Shift_L"
            val states = if (isModifierKeySym(sym.sym)) KeyStates.Empty else rawModifierStates(event)
            setKey(Key.create(sym, states))
            return@l true
        }
        addTextChangedListener l@{
            val text = it?.toString() ?: return@l
            if (text.isEmpty()) return@l
            val parsed = Key.parse(normalizeKeyString(text))
            if (parsed.sym != 0) {
                setKey(Key.create(parsed.keySym, keyStates))
            }
        }
    }

    private val clearButton = imageButton {
        background = styledDrawable(android.R.attr.actionBarItemBackground)
        imageDrawable = drawable(R.drawable.ic_baseline_delete_24)!!.apply {
            setTint(styledColor(android.R.attr.colorControlNormal))
        }
        setOnClickListener {
            setKey(Key.None)
        }
    }

    override val root = constraintLayout {
        val vMargin = dp(18)
        val hMargin = dp(24)
        add(textView, lParams(matchConstraints, wrapContent) {
            topOfParent(vMargin)
            startOfParent(hMargin)
            before(clearButton)
            above(modifierButtons.first().root)
        })
        val iconSize = dp(48)
        add(clearButton, lParams(iconSize, iconSize) {
            below(textView)
            above(textView)
            endOfParent(hMargin)
        })
        // modifier buttons row
        modifierButtons.forEachIndexed { i, btn ->
            add(btn.root, lParams(matchConstraints, wrapContent) {
                below(textView, vMargin)
                if (i == 0) startOfParent(hMargin) else after(modifierButtons[i - 1].root)
            })
        }
        add(input, lParams(matchConstraints, wrapContent) {
            below(textView, vMargin)
            after(modifierButtons.last().root)
            endOfParent(hMargin)
            bottomOfParent(vMargin)
        })
    }

    private var keySym = KeySym(0)

    private var currentValue: String = initialValue

    var lastKey: Key = Key.None
        private set

    init {
        // restore initial value
        if (initialValue == "Sym") {
            keySym = KeySym(0)
            currentValue = "Sym"
        } else if (initialValue.isNotEmpty()) {
            val parsed = Key.parse(initialValue)
            keySym = parsed.keySym
            currentValue = initialValue
            lastKey = parsed
            modifierButtons.forEach { btn ->
                btn.checked = parsed.keyStates.has(btn.modifier)
            }
        }
        updateDisplay()
    }

    private val keyStates
        get() = KeyStates(
            *modifierButtons
                .mapNotNull { it.takeIf { it.checked }?.modifier }
                .toTypedArray()
        )

    private fun setKey(key: Key) {
        lastKey = key
        keySym = key.keySym
        currentValue = key.portableString
        modifierButtons.forEach {
            it.checked = key.keyStates.has(it.modifier)
        }
        updateDisplay()
    }

    /** Set a special key string (e.g. "Sym") that has no fcitx5 Key representation. */
    private fun setKeyString(s: String) {
        currentValue = s
        keySym = KeySym(0)
        lastKey = Key.None
        modifierButtons.forEach { it.checked = false }
        updateDisplay()
    }

    private fun updateKey() {
        val states = keyStates
        if (keySym.sym != 0) {
            currentValue = Key.create(keySym, states).portableString
            lastKey = Key.create(keySym, states)
        }
        // If keySym is 0 (e.g. "Sym" special key), modifier toggles are ignored
        updateDisplay()
    }

    private fun updateDisplay() {
        textView.text = when {
            currentValue.isEmpty() -> ctx.getString(R.string.none)
            currentValue == "Sym" -> "Sym"
            else -> {
                val key = Key.parse(currentValue)
                key.localizedString.ifEmpty { ctx.getString(R.string.none) }
            }
        }
    }

    fun getValue(): String = currentValue

    companion object {
        /** fcitx5 modifier keysym range: Shift_L (0xffe1) through Hyper_R (0xffee). */
        private fun isModifierKeySym(sym: Int): Boolean = sym in 0xffe1..0xffee

        /**
         * Extract the modifier state directly from the event's pressed modifiers, WITHOUT the
         * number/symbol-key stripping that [KeyStates.fromKeyEvent] applies. Used when capturing a
         * combo (e.g. `Alt+grave` / `Alt+$`) so the held modifier is preserved in the stored string
         * and can be matched exactly later.
         */
        private fun rawModifierStates(event: KeyEvent): KeyStates {
            var s = KeyState.NoState.state
            if (event.isAltPressed) s = s or KeyState.Alt.state
            if (event.isCtrlPressed) s = s or KeyState.Ctrl.state
            if (event.isShiftPressed) s = s or KeyState.Shift.state
            if (event.isMetaPressed) s = s or KeyState.Meta.state
            return KeyStates(s and KeyState.SimpleMask.state)
        }

        /** Format a stored key string for display in preference summary. */
        fun formatKey(keyString: String): String {
            if (keyString.isEmpty()) return "(none)"
            if (keyString == "Sym") return "Sym"
            val key = Key.parse(normalizeKeyString(keyString))
            return key.localizedString.ifEmpty { "(none)" }
        }
    }
}
