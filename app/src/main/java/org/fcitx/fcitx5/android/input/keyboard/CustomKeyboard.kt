/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.CustomKeyConfig
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.imageResource
import androidx.core.view.updateLayoutParams

/** [CustomKey] 的默认宽度（无功能键场景的历史默认；常驻行版由 [CustomKeyboard.Layout] 动态平分） */
private const val CUSTOM_KEY_WIDTH = 0.1f

/**
 * 常驻行左/右两个功能键的宽度：左端虚拟 Shift 比普通自定义键略宽（0.11f），右端隐藏状态栏
 * 只有普通键的一半（0.05f）。剩余宽度由实际的自定义键（最多 10 个）动态平分
 * （见 [CustomKeyboard.Layout]），保证 ConstraintLayout percent chain 精确为 1.0f。
 */
private const val CUSTOM_SHIFT_KEY_WIDTH = 0.11f
private const val HIDE_STATUSBAR_KEY_WIDTH = 0.05f

/**
 * 「自定义一行键盘」：一行 10 个可配置键（高度只占一行，见 [KeyboardWindow] 的单行高度切换）。
 *
 * 每个键的字符列表来自 [AppPrefs.customKeyboard]（第一项 = 单击输入的默认字符，
 * 其余项长按弹出选择）。实例在 [KeyboardWindow.switchLayout] 切换到本键盘时按需重建，
 * 因此配置保存后下次切换即生效。
 *
 * 返回主键盘靠**快速下滑手势**：按住后 300ms 内下滑超过 24dp 即触发；长按弹字符选择（按住停留
 * 超过阈值）不受影响。
 */
@SuppressLint("ViewConstructor")
class CustomKeyboard(
    context: Context,
    theme: Theme,
) : BaseKeyboard(context, theme, Layout(AppPrefs.getInstance().customKeyboard.keys.getValue())) {

    private val swipeBackDistancePx = dp(24)
    private val swipeBackTimeThresholdMs = 300L
    private var gestureDownY = 0f
    private var gestureDownTime = 0L

    init {
        // 单行键盘：唯一一行必须充满整个键盘窗口高度。否则 wrap_content 行在 ConstraintLayout
        // 中会因内部 matchParent 子（appearanceView）无法撑开而塌缩为 0，导致所有键不可见。
        (getChildAt(0) as? ConstraintLayout)?.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = 0 // MATCH_CONSTRAINT，配合行的 topOfParent/bottomOfParent 撑满父高
        }
    }

    companion object {
        const val Name = "Custom"

        /**
         * 一行 = 左端虚拟 Shift + N 个自定义键 + 右端隐藏状态栏键（高度由 [KeyboardWindow] 压到单行）。
         * 自定义键最多 10 个（配置里多余的忽略）；剩余宽度（1.0 - 0.11 - 0.05 = 0.84）由实际
         * 键数平分，一个键都没有时功能键撑满整行（percentWidth 经 ConstraintLayout chain 归一化）。
         */
        fun Layout(keys: List<CustomKeyConfig>): List<List<KeyDef>> {
            val customKeys = keys.take(10)
            val customWidth = if (customKeys.isEmpty()) 0f
            else (1f - CUSTOM_SHIFT_KEY_WIDTH - HIDE_STATUSBAR_KEY_WIDTH) / customKeys.size
            return listOf(
                listOf(CustomShiftKey()) +
                    customKeys.map { CustomKey(it, customWidth) } +
                    listOf(HideStatusBarKey())
            )
        }

        /**
         * 单码点走 fcitx sendKey（字符上屏铁律）；多码点字符串无法用单个 key 表达，
         * 走 [KeyAction.CommitAction]（项目约定的例外场景）。
         */
        fun actionFor(text: String): KeyAction =
            if (text.codePointCount(0, text.length) == 1) KeyAction.FcitxKeyAction(text)
            else KeyAction.CommitAction(text)
    }

    /**
     * 拦截"快速下滑"手势切回主键盘：按住 300ms 内下滑超过 24dp 才拦截；
     * 长按（停留超时）或轻微滑动放行给键（长按弹字符选择不受影响）。
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownY = ev.y
                gestureDownTime = ev.eventTime
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.eventTime - gestureDownTime < swipeBackTimeThresholdMs &&
                    ev.y - gestureDownY > swipeBackDistancePx
                ) {
                    onAction(KeyAction.LayoutSwitchAction(TextKeyboard.Name))
                    return true
                }
                return false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    // ===== 虚拟 Shift（左端功能键）—— 单击 = 下一键大写，双击 = 锁定/解锁 =====
    //
    // ⚠️ 状态真源在 service（FcitxInputMethodService.shiftOnce / capsLatched），键盘层是薄客户端：
    // Passport 的字母输入走**物理键盘**，虚拟 Shift 的一次性大写必须能作用到物理键路径
    // （withInjectedModifiers 注入 META_SHIFT_ON），键盘层私有状态够不着那条路——一版就栽在这。
    //  - 单击 → [onVirtualShiftTap]（service.pressVirtualShift：置/清 once，Lock 态解锁）；
    //  - 双击 → [onVirtualShiftLock]（service.toggleCapsLatch，物理长按 Shift 同一套 latch）；
    //  - 图标三态由 service 广播驱动：onShiftOnceChanged / onCapsLatched（InputView →
    //    KeyboardWindow → 本键盘）；
    //  - 常驻行**虚拟键**的大写转换仍在本层（onAction 拦截 + states），消费后回调
    //    [onShiftOnceConsumed] 清 service 的 once。物理键路径由 withInjectedModifiers 消费。

    /** 单击回调（service.pressVirtualShift），由 [KeyboardWindow] 注入。 */
    var onVirtualShiftTap: (() -> Unit)? = null

    /** 双击锁定/解锁回调（service.toggleCapsLatch），由 [KeyboardWindow] 注入。 */
    var onVirtualShiftLock: (() -> Unit)? = null

    /** 虚拟键消费完一次性大写后的清零回调（service.consumeVirtualShiftOnce），由 KeyboardWindow 注入。 */
    var onShiftOnceConsumed: (() -> Unit)? = null

    /** caps latch 广播（物理 Shift 长按锁定 / KawaiiBar 按钮同步）。 */
    fun onCapsLatched(latched: Boolean) {
        capsLatched = latched
        updateShiftIcon()
    }

    /** 「下一键大写」一次性状态广播（service 真源驱动图标）。 */
    fun onShiftOnceChanged(once: Boolean) {
        shiftOnce = once
        updateShiftIcon()
    }

    private var shiftOnce = false
    private var capsLatched = false

    private fun updateShiftIcon() {
        (findViewById<ImageKeyView>(R.id.button_custom_shift))?.img?.imageResource = when {
            capsLatched -> R.drawable.ic_capslock_lock
            shiftOnce -> R.drawable.ic_capslock_once
            else -> R.drawable.ic_capslock_none
        }
    }

    /**
     * 键盘层处理（Shift 两动作不外发）+ 常驻行**虚拟键**的大写转换：
     * once 态字母键 uppercase 并**补 Shift states**（fcitx 只认带状态的键），消费后清 once；
     * Lock 态补 CapsLock。物理键路径的大写在 service（withInjectedModifiers），不经过这里。
     */
    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        when (action) {
            KeyAction.CustomShiftAction -> {
                onVirtualShiftTap?.invoke()
                return
            }
            KeyAction.CustomShiftLockAction -> {
                onVirtualShiftLock?.invoke()
                return
            }
            else -> {}
        }
        val transformed = if (action is KeyAction.FcitxKeyAction && action.act.length == 1 &&
            action.act[0].isLetter() && source == KeyActionListener.Source.Keyboard
        ) {
            when {
                capsLatched -> action.copy(
                    act = action.act.uppercase(),
                    states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                )
                shiftOnce -> action.copy(
                    act = action.act.uppercase(),
                    states = KeyStates(KeyState.Virtual, KeyState.Shift)
                ).also { onShiftOnceConsumed?.invoke() }
                else -> action
            }
        } else {
            action
        }
        super.onAction(transformed, source)
    }
}

/**
 * 单个自定义键：键帽与主键盘数字键一致——大字显示主字符（[CustomKeyConfig.chars] 首项，
 * 即单击默认字符），右上角小字显示次字符（第二项）；单击输入主字符，长按弹出**除主字符外的
 * 其余字符**（次字符及其后的字符），即长按选择列表不重复出现主字符。
 *
 * 仅有一项时退化为普通 [Appearance.Text]（无次字符小字）；仅一项时长按无候选可弹，仅保留按压预览。
 */
class CustomKey(
    config: CustomKeyConfig,
    percentWidth: Float = CUSTOM_KEY_WIDTH
) : KeyDef(
    appearance = run {
        val chars = config.chars.filter { it.isNotBlank() }
        val main = chars.firstOrNull() ?: ""
        val alt = chars.getOrNull(1) ?: ""
        if (alt.isNotBlank())
            Appearance.AltText(
                displayText = main,
                altText = alt,
                textSize = 16f,
                percentWidth = percentWidth,
                variant = Appearance.Variant.Alternative
            )
        else
            Appearance.Text(
                displayText = main,
                textSize = 16f,
                percentWidth = percentWidth,
                variant = Appearance.Variant.Alternative
            )
    },
    behaviors = config.chars.filter { it.isNotBlank() }.firstOrNull()
        ?.let { setOf(Behavior.Press(CustomKeyboard.actionFor(it))) } ?: emptySet(),
    popup = config.chars.filter { it.isNotBlank() }.let { chars ->
        if (chars.isEmpty()) null
        else {
            val popups = mutableListOf<Popup>(Popup.Preview(chars.first()))
            chars.drop(1).takeIf { it.isNotEmpty() }
                ?.let { popups.add(Popup.Keyboard.Explicit(it.toTypedArray())) }
            popups.toTypedArray()
        }
    }
)

/**
 * 常驻行左端「虚拟 Shift」：单击 = 下一键大写（caps once），双击 = 锁定/解锁大写（caps latch，
 * service 侧 hardwareKeyDispatch.capsLatched）。图标三态（none/once/lock）由
 * [CustomKeyboard.updateShiftIcon] 随状态切换。
 */
class CustomShiftKey : KeyDef(
    appearance = Appearance.Image(
        src = R.drawable.ic_capslock_none,
        viewId = R.id.button_custom_shift,
        percentWidth = CUSTOM_SHIFT_KEY_WIDTH,
        variant = Appearance.Variant.Alternative
    ),
    setOf(
        Behavior.Press(KeyAction.CustomShiftAction),
        Behavior.DoubleTap(KeyAction.CustomShiftLockAction)
    )
)

/**
 * 常驻行右端「隐藏状态栏」：单击翻转 `candidateBar.hideStatusBar`（与「候选栏-顶栏-隐藏状态栏」
 * 设置项及 ToggleStatusBar 物理快捷键完全同一开关），KawaiiBar 的可见性监听自动收起/展开顶栏。
 * 宽度只有普通自定义键的一半——它是个开关，不是输入键。
 */
class HideStatusBarKey : KeyDef(
    appearance = Appearance.Image(
        src = R.drawable.ic_baseline_expand_less_24,
        viewId = R.id.button_hide_statusbar,
        percentWidth = HIDE_STATUSBAR_KEY_WIDTH,
        variant = Appearance.Variant.Alternative
    ),
    setOf(
        Behavior.Press(KeyAction.HideStatusBarAction)
    )
)
