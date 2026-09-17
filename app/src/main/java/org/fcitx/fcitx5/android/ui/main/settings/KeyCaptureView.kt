/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import androidx.annotation.StringRes
import androidx.core.widget.addTextChangedListener
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Key
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.HardwareChord
import org.fcitx.fcitx5.android.data.prefs.HardwareSpecialKeys
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
 * Stored value is a fcitx5 [Key] portableString (e.g. "Alt+space", "dollar", "Shift_L"), or the
 * name of a [HardwareSpecialKeys] pseudo key (e.g. "Sym", "NavBack", "NavFn") for physical function
 * keys that have no fcitx5 KeySym.
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
                // 真修饰键与 Fn/Sym 互斥：一个和弦只能有一个前缀，"Fn+Ctrl+e" 说不通。
                if (checked) setChordButtonsChecked("")
                updateKey()
            }
        }

        var checked: Boolean = false
            set(value) {
                field = value
                applyStyles()
            }

        fun applyStyles() = root.applyToggleStyle(checked)
    }

    /**
     * 伪修饰键按钮（Fn / Sym）。语义跟 Ctrl/Alt/Shift 一样是「给下一个键加前缀」，只是值不能是
     * [KeyState] —— Fn/Sym 没有 fcitx5 修饰位，只能写成字符串前缀（见 [HardwareChord]）。
     *
     * 这是**唯一**能做出 `Fn+字母` 的地方：物理按 Fn 只会上报一个裸伪键名（`NavFn`），
     * 「按住 Fn 再按字母」在捕获窗口里根本录不出来（录到的永远是后一个键）。
     */
    private inner class ChordButton(val name: String, @StringRes labelRes: Int) : Ui {
        override val ctx = this@KeyCaptureUi.ctx

        override val root = button {
            text = ctx.getString(labelRes)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setOnClickListener {
                setChordButtonsChecked(if (checked) "" else name)
                updateKey()
            }
        }

        var checked: Boolean = false
            set(value) {
                field = value
                applyStyles()
            }

        fun applyStyles() = root.applyToggleStyle(checked)
    }

    /** 勾选态的统一视觉 —— 两排按钮共用，别各写一份。 */
    private fun Button.applyToggleStyle(checked: Boolean) = apply {
        backgroundTintList = ctx.styledColorSL(
            if (checked) android.R.attr.colorAccent else android.R.attr.colorBackgroundFloating
        )
        setTextColor(
            ctx.styledColor(
                if (checked) android.R.attr.colorForegroundInverse else android.R.attr.colorForeground
            )
        )
    }

    private val modifierButtons = arrayOf(
        ModifierButton("Ctrl", KeyState.Ctrl),
        ModifierButton("Alt", KeyState.Alt),
        ModifierButton("Shift", KeyState.Shift)
    )

    private val chordButtons = arrayOf(
        ChordButton(HardwareChord.FN, R.string.hw_special_fn),
        ChordButton(HardwareChord.SYM, R.string.hw_special_sym)
    )

    /** 当前勾选的伪修饰键；"" = 没有。直接从按钮读，不另存一份字段（省掉一次同步机会）。 */
    private val chordModifier: String
        get() = chordButtons.firstOrNull { it.checked }?.name ?: ""

    /**
     * 勾选某个伪修饰键（"" = 全不勾），并维持与真修饰键的互斥。
     *
     * 刻意**不**在这里重算值：它有两个调用场景（点按钮 / 捕获到伪键），重算时机不同，
     * 由调用方显式决定调 [updateKey] 还是 [applyValue]。
     */
    private fun setChordButtonsChecked(name: String) {
        chordButtons.forEach { it.checked = it.name == name }
        if (name.isNotEmpty()) {
            modifierButtons.forEach { it.checked = false }
        }
    }

    private val input = editText {
        textSize = 16f
        inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_FORCE_ASCII
        privateImeOptions = FcitxInputMethodService.DeleteSurroundingFlag + "|" + FcitxInputMethodService.KeyCaptureFlag
        requestFocus()
        setOnKeyListener l@{ _, _, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@l false
            // Pseudo keys (SYM, back, recents, home, fn) have no fcitx5 KeySym — store their name.
            HardwareSpecialKeys.entryForKeyCode(event.keyCode)?.let { entry ->
                setKeyString(entry.name)
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
        // 伪修饰键排（Fn / Sym）：自己占一行 —— 塞进上一排会把输入框挤得没法用。
        chordButtons.forEachIndexed { i, btn ->
            add(btn.root, lParams(matchConstraints, wrapContent) {
                below(modifierButtons.first().root, vMargin)
                if (i == 0) startOfParent(hMargin) else after(chordButtons[i - 1].root)
            })
        }
        add(input, lParams(matchConstraints, wrapContent) {
            below(chordButtons.first().root, vMargin)
            startOfParent(hMargin)
            endOfParent(hMargin)
            bottomOfParent(vMargin)
        })
    }

    private var keySym = KeySym(0)

    /** 内层键串（不含和弦前缀）。存储串 = 前缀 + 它，见 [applyValue]。 */
    private var innerValue: String = ""

    private var currentValue: String = initialValue

    var lastKey: Key = Key.None
        private set

    init {
        // restore initial value
        // 先拆和弦前缀：内层键（"e" / "Sym"）之后照旧解析，前缀只是勾一下按钮。
        val (chord, inner) = HardwareChord.split(initialValue)
        if (chord.isNotEmpty()) {
            chordButtons.forEach { it.checked = it.name == chord }
        }
        val special = HardwareSpecialKeys.entryForName(inner)
        if (special != null) {
            keySym = KeySym(0)
            innerValue = special.name
        } else if (inner.isNotEmpty()) {
            val parsed = Key.parse(normalizeKeyString(inner))
            keySym = parsed.keySym
            innerValue = inner
            lastKey = parsed
            modifierButtons.forEach { btn ->
                btn.checked = parsed.keyStates.has(btn.modifier)
            }
        }
        applyValue()
    }

    private val keyStates
        get() = KeyStates(
            *modifierButtons
                .mapNotNull { it.takeIf { it.checked }?.modifier }
                .toTypedArray()
        )

    /**
     * 所有写值都收敛到这一处：内层键串 + 当前勾选的伪修饰键前缀 = 存储串。
     *
     * 以前每个写点都直接 `currentValue = key.portableString`，加上和弦后那种写法必然漏掉前缀 ——
     * 收敛成一个出口，就不会出现「界面勾着 Fn、存进去却没有 Fn」这类半截状态。
     */
    private fun applyValue() {
        // 内层键被清空（清除按钮 / 重置）时把前缀一并摘掉：否则界面留着「勾着 Fn、值却是空」
        // 的半截状态 —— 看着像绑定还在，实际什么都没存，又是一次「配置看着对、就是不动」。
        if (innerValue.isEmpty()) setChordButtonsChecked("")
        currentValue = HardwareChord.compose(chordModifier, innerValue)
        updateDisplay()
    }

    private fun setKey(key: Key) {
        lastKey = key
        keySym = key.keySym
        innerValue = key.portableString
        modifierButtons.forEach {
            it.checked = key.keyStates.has(it.modifier)
        }
        applyValue()
    }

    /** Set a [HardwareSpecialKeys] pseudo-key name (e.g. "Sym") that has no fcitx5 Key representation. */
    private fun setKeyString(s: String) {
        innerValue = s
        keySym = KeySym(0)
        lastKey = Key.None
        modifierButtons.forEach { it.checked = false }
        // 伪键自己不是一个修饰键，和弦前缀在这里没有意义（"Fn+NavFn" 是自指），一律摘掉。
        setChordButtonsChecked("")
        applyValue()
    }

    private fun updateKey() {
        val states = keyStates
        if (keySym.sym != 0) {
            innerValue = Key.create(keySym, states).portableString
            lastKey = Key.create(keySym, states)
        }
        // If keySym is 0 (a pseudo key such as "Sym"), modifier toggles are ignored
        applyValue()
    }

    private fun updateDisplay() {
        textView.text = formatKey(ctx, currentValue)
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
        fun formatKey(ctx: Context, keyString: String): String {
            if (keyString.isEmpty()) return ctx.getString(R.string.none)
            // 和弦（"Fn+e"）：前缀 + 内层键的本地化名。递归处理，内层可能是伪键（"Fn+Sym"）。
            // 前缀名直接用 HardwareChord 里的字面量（"Fn" / "Sym"）—— 它们本来就是键帽上的丝印，
            // 不需要翻译；sep 用 " + " 与 fcitx5 自己的组合键显示风格保持一致。
            val (modifier, inner) = HardwareChord.split(keyString)
            if (modifier.isNotEmpty()) return "$modifier + ${formatKey(ctx, inner)}"
            HardwareSpecialKeys.entryForName(keyString)?.let { return ctx.getString(it.labelRes) }
            val key = Key.parse(normalizeKeyString(keyString))
            return key.localizedString.ifEmpty { ctx.getString(R.string.none) }
        }
    }
}
