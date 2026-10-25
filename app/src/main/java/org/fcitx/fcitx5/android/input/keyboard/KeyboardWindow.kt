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
import org.fcitx.fcitx5.android.data.prefs.HardwareKeyProfiles
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

    /**
     * 总开关变化时立即对齐显示态：
     *  - 关闭：若正显示自定义键盘则切回主键盘（并重置 Sym 记忆态）。Passport 下此时
     *    customKeyboardPinned() 已为 false，这次 TextKeyboard 切换不会再被改道回自定义 —— 固定
     *    的逃生口就是关这个开关；
     *  - 打开（仅 Passport 固定显示下）：若正显示主键盘则立即改道自定义键盘，不等下次输入会话。
     */
    @Keep
    private val customKeyboardEnabledListener =
        ManagedPreference.OnChangeListener<Boolean> { _, enabled ->
            if (!enabled && currentKeyboardName == CustomKeyboard.Name) {
                symMode = SymMode.NONE
                switchLayoutSync(TextKeyboard.Name, remember = false)
            } else if (enabled && HardwareKeyProfiles.customKeyboardPinned() &&
                currentKeyboardName == TextKeyboard.Name
            ) {
                switchLayoutSync(CustomKeyboard.Name, remember = false)
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

    /**
     * 键盘预设切换 → Passport 固定行**立即**生效/失效：
     *  - 切到 Passport：若正显示主键盘（TextKeyboard），当场改道自定义键盘（applyProfile 已把
     *    总开关播成开，pinned 为 true）；
     *  - 切走：若正显示自定义键盘，当场退回主键盘——此时它已不是常驻行，留着是残留。
     * 不等下一次 onStartInput（那只在切换输入框时才来）——用户在设置页切完预设回到输入框，
     * 看到的就该是新布局。
     */
    @Keep
    private val keyProfileListener =
        ManagedPreference.OnChangeListener<String> { _, _ ->
            when {
                HardwareKeyProfiles.customKeyboardPinned() &&
                    currentKeyboardName == TextKeyboard.Name ->
                    switchLayoutSync(CustomKeyboard.Name, remember = false)
                !HardwareKeyProfiles.customKeyboardPinned() &&
                    currentKeyboardName == CustomKeyboard.Name -> {
                    // 关掉的不是预设也可能走到这（keyProfile 没变、enabled 没变的情况不会
                    // fireChange），但 pinned 由 PASSPORT+enabled 合成，到这里必然是解除了
                    // 固定（切走预设，或总开关被关）。退回主键盘。
                    symMode = SymMode.NONE
                    switchLayoutSync(TextKeyboard.Name, remember = false)
                }
            }
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
        // 监听键盘预设：Passport 固定行立即生效/失效（切预设不等下一次输入会话）
        AppPrefs.getInstance().hardwareKeyboard.keyProfile.registerOnChangeListener(keyProfileListener)
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

    private fun doSwitchLayout(requested: String, remember: Boolean) {
        // Passport 固定行：该机的 29 键物理键盘没有任何符号/数字，自定义一行键盘以**常驻行**
        // 固定在状态栏（KawaiiBar）下方 —— 主键盘布局入口全部改道自定义键盘。这是「替换
        // 常驻行内容」，不是新增一行；总开关关闭即解除固定退回普通主键盘（customKeyboardPinned
        // 的第二个条件）。见 [HardwareKeyProfiles.customKeyboardPinned]。
        val target =
            if (requested == TextKeyboard.Name && HardwareKeyProfiles.customKeyboardPinned())
                CustomKeyboard.Name else requested
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
                // 用户配置在构造时读取，每次切换重建即拿到最新配置。
                // 虚拟 Shift 的状态真源在 service（物理字母键也要吃 once 态）：单击/双击/消费
                // 三个回调全部导向 service；图标随 service 广播 + 此处注入的初值同步。
                keyboards[CustomKeyboard.Name] = CustomKeyboard(context, theme).apply {
                    onVirtualShiftTap = { service.pressVirtualShift() }
                    onVirtualShiftLock = { service.toggleCapsLatch() }
                    onShiftOnceConsumed = { service.consumeVirtualShiftOnce() }
                    onCapsLatched(service.isCapsLatched())
                    onShiftOnceChanged(service.isVirtualShiftOnce())
                }
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

    /**
     * caps latch 广播（service → InputView 转发）：常驻行正显示自定义键盘时，同步其虚拟
     * Shift 图标（三态）。当前显示其他布局时忽略——下次切换到自定义键盘时构造注入初值。
     */
    internal fun onCapsLatched(latched: Boolean) {
        (currentKeyboard as? CustomKeyboard)?.onCapsLatched(latched)
    }

    /**
     * 虚拟 Shift「下一键大写」一次性状态广播：常驻行 Shift 图标随 service 的 shiftOnce
     * 真源切换（物理键消费、虚拟键消费、再次单击取消，都经此同步）。
     */
    internal fun onShiftOnceChanged(once: Boolean) {
        (currentKeyboard as? CustomKeyboard)?.onShiftOnceChanged(once)
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