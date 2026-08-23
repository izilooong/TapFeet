/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.transition.Slide
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.CustomKeyConfig
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindow : InputWindow.SimpleInputWindow<KeyboardWindow>(), EssentialWindow,
    InputBroadcastReceiver {

    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val popup: PopupComponent by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = KeyboardWindow

    override fun enterAnimation(lastWindow: InputWindow) = Slide().apply {
        slideEdge = Gravity.BOTTOM
    }.takeIf {
        // disable animation switching between picker
        lastWindow !is PickerWindow
    }

    override fun exitAnimation(nextWindow: InputWindow) =
        super.exitAnimation(nextWindow).takeIf {
            // disable animation switching between picker
            nextWindow !is PickerWindow
        }

    private lateinit var keyboardView: FrameLayout

    private val keyboards: HashMap<String, BaseKeyboard> by lazy {
        hashMapOf(
            TextKeyboard.Name to TextKeyboard(context, theme),
            NumberKeyboard.Name to NumberKeyboard(context, theme)
        )
    }
    private var currentKeyboardName = ""
    private var lastSymbolType: String by AppPrefs.getInstance().internal.lastSymbolLayout

    private val currentKeyboard: BaseKeyboard? get() = keyboards[currentKeyboardName]

    /** 当前布局名（TextKeyboard.Name / NumberKeyboard.Name / CustomKeyboard.Name 等），供上层判断状态 */
    val currentLayoutName: String get() = currentKeyboardName

    /**
     * 记忆上一次 Sym 三态（自定义/符号/隐藏），用于重新进入输入状态时恢复，
     * 避免被 [onStartInput] 强制重置回主键盘。进程内有效（同一会话）。
     */
    internal enum class SymMode { NONE, CUSTOM, SYMBOL }
    internal var symMode: SymMode = SymMode.NONE

    /** 符号选择器窗口引用，由 InputView 注入，供 [onStartInput] 恢复符号态 */
    internal var symbolPickerWindow: InputWindow? = null

    /**
     * 自定义一行键盘是否激活（激活时窗口高度需压到单行，见 InputView.keyboardWindowHeightPx）。
     * 附加窗口 attach 判断：KeyboardWindow detach（如切到符号选择器）后布局名残留不算激活，
     * 避免高度误判为单行。
     */
    val isCustomKeyboardActive: Boolean
        get() = currentKeyboardName == CustomKeyboard.Name && windowManager.isAttached(this)

    /** 布局切换通知（InputView 用它刷新键盘窗口高度与触摸区域） */
    var onLayoutSwitched: (() -> Unit)? = null

    /** 自定义键盘配置保存后自动重建（当前正显示自定义键盘时立即生效） */
    @Keep
    private val customKeyboardKeysListener =
        ManagedPreference.OnChangeListener<List<CustomKeyConfig>> { _, _ ->
            if (currentKeyboardName == CustomKeyboard.Name) {
                doSwitchLayout(CustomKeyboard.Name, remember = false)
            }
        }

    /** 总开关关闭时：若正显示自定义键盘则立即切回主键盘（并重置 Sym 记忆态） */
    @Keep
    private val customKeyboardEnabledListener =
        ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
            if (!enabled && currentKeyboardName == CustomKeyboard.Name) {
                symMode = SymMode.NONE
                switchLayoutSync(TextKeyboard.Name, remember = false)
            }
        }

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            switchLayout(it.act)
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        popup.listener
    }

    // This will be called EXACTLY ONCE
    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        // 首帧布局跟随 [currentKeyboardName]（由 onStartInput / switchLayoutSync 预置），
        // 避免「先全高主键盘、后单行自定义」的闪烁
        attachLayout(currentKeyboardName.ifEmpty { TextKeyboard.Name })
        // 监听自定义键盘配置变化：保存后若正在显示自定义键盘，立即重建生效
        AppPrefs.getInstance().customKeyboard.keys.registerOnChangeListener(customKeyboardKeysListener)
        // 监听总开关：关闭时若正显示自定义键盘，立即切回主键盘
        AppPrefs.getInstance().customKeyboard.enabled.registerOnChangeListener(customKeyboardEnabledListener)
        return keyboardView
    }

    private fun detachCurrentLayout() {
        currentKeyboard?.also {
            it.onDetach()
            keyboardView.removeView(it)
            it.keyActionListener = null
            it.popupActionListener = null
        }
    }

    private fun attachLayout(target: String) {
        currentKeyboardName = target
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            keyboardView.apply { add(it, lParams(matchParent, matchParent)) }
            it.onAttach()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.onInputMethodUpdate(fcitx.runImmediately { inputMethodEntryCached })
        }
    }

    fun switchLayout(to: String, remember: Boolean = true) {
        val target = to.ifEmpty { lastSymbolType }
        ContextCompat.getMainExecutor(service).execute { doSwitchLayout(target, remember) }
    }

    /**
     * 同步版 [switchLayout]：在主线程直接执行布局切换（不 post 到 MainExecutor）。
     * 供上层在窗口 attach / 显示之前预置布局，避免「先全高键盘、后单行」的闪烁——
     * 配合 [onCreateView] 以 [currentKeyboardName] 作为首帧布局，attach 出来即单行。
     * 仅可在主线程调用（键盘事件、输入状态切换均为主线程）。
     */
    internal fun switchLayoutSync(to: String, remember: Boolean = true) {
        doSwitchLayout(to.ifEmpty { lastSymbolType }, remember)
    }

    private fun doSwitchLayout(target: String, remember: Boolean) {
        // 总开关兜底：关闭后任何入口（Sym 循环 / 状态栏⑩ / 符号键盘⑩ / 恢复上次态）都打不开自定义键盘
        if (target == CustomKeyboard.Name && !AppPrefs.getInstance().customKeyboard.enabled.getValue()) {
            if (symMode == SymMode.CUSTOM) symMode = SymMode.NONE
            return
        }
        if (keyboards.containsKey(target) || target == CustomKeyboard.Name) {
            // 自定义键盘不进 lastSymbolType，保证 ?123 始终回符号选择器
            if (remember && target != TextKeyboard.Name && target != CustomKeyboard.Name) {
                lastSymbolType = target
            }
            // 自定义键盘每次进入都重建（构造时读配置），同布局 toggle（收起再开）也要拿到最新配置
            if (target == currentKeyboardName && target != CustomKeyboard.Name) return
            // 切换布局时清掉可能残留的长按弹出层
            popup.dismissAll()
            detachCurrentLayout()
            if (target == CustomKeyboard.Name) {
                // 用户配置在构造时读取，每次切换重建即拿到最新配置
                keyboards[CustomKeyboard.Name] = CustomKeyboard(context, theme)
            }
            attachLayout(target)
            // 记录当前 Sym 三态：自定义键盘 → CUSTOM；主键盘 / 数字键盘 → NONE
            symMode = if (target == CustomKeyboard.Name) SymMode.CUSTOM else SymMode.NONE
            if (windowManager.isAttached(this)) {
                notifyBarLayoutChanged()
            }
        } else {
            if (remember) {
                lastSymbolType = PickerWindow.Key.Symbol.name
            }
            windowManager.attachWindow(PickerWindow.Key.Symbol)
        }
    }

    /** 标记符号选择器激活（符号态不走 switchLayout，需单独记录） */
    internal fun markSymbolPickerActive() {
        symMode = SymMode.SYMBOL
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        when (symMode) {
            // 上次停在自定义一行键盘 → 重新进入时恢复它（不再被强制重置回主键盘）。
            // 同步切换，确保首帧即单行，避免恢复时闪一下全高键盘。
            SymMode.CUSTOM -> switchLayoutSync(CustomKeyboard.Name, remember = false)
            // 上次停在符号选择器 → 重新进入时恢复符号键盘
            SymMode.SYMBOL -> {
                val sp = symbolPickerWindow
                if (sp != null && !windowManager.isAttached(sp)) {
                    windowManager.attachWindow(sp)
                }
            }
            // 主键盘 / 隐藏态 / 未打开过 → 按字段类型（数字/电话→数字键盘，否则主键盘），保持原行为
            SymMode.NONE -> {
                val targetLayout = when (info.inputType and InputType.TYPE_MASK_CLASS) {
                    InputType.TYPE_CLASS_NUMBER -> NumberKeyboard.Name
                    InputType.TYPE_CLASS_PHONE -> NumberKeyboard.Name
                    else -> TextKeyboard.Name
                }
                switchLayout(targetLayout, remember = false)
            }
        }
    }

    override fun onImeUpdate(ime: InputMethodEntry) {
        currentKeyboard?.onInputMethodUpdate(ime)
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        currentKeyboard?.onPunctuationUpdate(mapping)
    }

    override fun onReturnKeyDrawableUpdate(resourceId: Int) {
        currentKeyboard?.onReturnDrawableUpdate(resourceId)
    }

    override fun onAttached() {
        currentKeyboard?.let {
            it.keyActionListener = keyActionListener
            it.popupActionListener = popupActionListener
            it.onAttach()
        }
        notifyBarLayoutChanged()
    }

    override fun onDetached() {
        currentKeyboard?.let {
            it.onDetach()
            it.keyActionListener = null
            it.popupActionListener = null
        }
        popup.dismissAll()
    }

    // Call this when
    // 1) the keyboard window was newly attached
    // 2) currently keyboard window is attached and switchLayout was used
    private fun notifyBarLayoutChanged() {
        bar.onKeyboardLayoutSwitched(currentKeyboardName == NumberKeyboard.Name)
        onLayoutSwitched?.invoke()
    }
}