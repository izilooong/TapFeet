/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.keyboard.ImageKeyView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener.Source
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.bottomToTopOf
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToBottomOf
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent

class PickerPageUi(
    override val ctx: Context,
    theme: Theme,
    density: Density,
    bordered: Boolean = false
) : Ui {

    /**
     * 所有页签统一渲染为物理键盘形状：第一行 10 键、第二行 9 键、第三行 7 字母键 + 退格（右下角），
     * 共 26 个与物理字母键一一对应的键位（见 HardwarePickerLetterMap）。
     * 每页正好 26 个符号，多余的符号由 PickerPagesAdapter 按 26 一页翻页显示，不占用额外按钮。
     */
    enum class Density(
        val pageSize: Int,
        val textSize: Float,
        val autoScale: Boolean
    ) {
        // symbol: 26 个物理键位（每页正好 26，多余翻页）
        High(26, 19f, false),

        // emoji: 26 个物理键位（每页正好 26，多余翻页）
        Medium(26, 23.7f, false),

        // emoticon: 26 个物理键位（颜文字较长，按键宽自动缩放）
        Low(26, 19f, autoScale = true)
    }

    private val popupOnKeyPress by AppPrefs.getInstance().keyboard.popupOnKeyPress

    var keyActionListener: KeyActionListener? = null
    var popupActionListener: PopupActionListener? = null

    private val keyAppearance = Appearance.Text(
        displayText = "",
        textSize = density.textSize,
        variant = Variant.Normal,
        border = if (bordered) Border.On else Border.Off
    )

    private val keyViews = Array(density.pageSize) {
        TextKeyView(ctx, theme, keyAppearance).apply {
            if (density.autoScale) {
                mainText.apply {
                    scaleMode = AutoScaleTextView.Mode.Proportional
                    setPadding(hMargin, vMargin, hMargin, vMargin)
                }
            }
        }
    }

    private val backspaceAppearance = Appearance.Image(
        src = R.drawable.ic_baseline_backspace_24,
        variant = Variant.Alternative,
        border = if (bordered) Border.On else Border.Off,
        viewId = R.id.button_backspace
    )

    private val backspaceKey by lazy {
        val backspaceAction = SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))
        val action: (View) -> Unit = {
            keyActionListener?.onKeyAction(backspaceAction, Source.Keyboard)
        }
        val listener = View.OnClickListener { action.invoke(it) }
        ImageKeyView(ctx, theme, backspaceAppearance).apply {
            setOnClickListener(listener)
            repeatEnabled = true
            onRepeatListener = action
        }
    }

    override val root = constraintLayout {
        // 物理键盘形状（所有页签统一）：R0=10、R1=9、R2=7 字母 + 退格（右下角）。
        // keyViews[0..25] 按物理键位线性序连续排布（R0:0-9, R1:10-18, R2:19-25），
        // 与 PickerWindow.selectByLetter 的线性序及 PickerPagesAdapter 的页内顺序逐位对齐。
        // 每页正好 26 个键位（= 26 字母键），多余符号由 PickerPagesAdapter 翻页；符号填充顺序 keyViews[i] = items[i] 不变。
        val rows = listOf(
            keyViews.sliceArray(0..9),
            keyViews.sliceArray(10..18),
            keyViews.sliceArray(19..25)
        )
        rows.forEachIndexed { r, rowKeys ->
            rowKeys.forEachIndexed { c, kv ->
                add(kv, lParams {
                    if (r == 0) topOfParent() else topToBottomOf(rows[r - 1][0])
                    if (r == rows.lastIndex) bottomOfParent() else bottomToTopOf(rows[r + 1][0])
                    if (c == 0) leftOfParent() else leftToRightOf(rowKeys[c - 1])
                    if (c == rowKeys.lastIndex) {
                        // 末行最后一格（R2 第 7 个字母）让出右侧给退格键
                        if (r == rows.lastIndex) rightToLeftOf(backspaceKey) else rightOfParent()
                    } else {
                        rightToLeftOf(rowKeys[c + 1])
                    }
                    // 统一键宽（1/10 屏宽）+ 整行 PACKED 链，配合下方 bias 实现行缩进
                    matchConstraintPercentWidth = 0.1f
                    horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED
                })
            }
        }
        // 行缩进完全对齐主键盘 TextKeyboard.Layout（见 BaseKeyboard init 的行内 PACKED 链）：
        //   R0 = 10 × 0.1                       → 铺满，Q 从 0%
        //   R1 = 9 × 0.1 = 0.9，居中             → A 从 5%
        //   R2 = Caps(0.15) + 7 × 0.1 + BackSpace(0.15) → Z 从 15%，退格 85%~100%
        rows[1][0].updateLayoutParams<ConstraintLayout.LayoutParams> {
            // 链宽 0.9，剩余 0.1 平分 → A 落在 5%，与主键盘第二行一致
            horizontalBias = 0.5f
        }
        rows[2][0].updateLayoutParams<ConstraintLayout.LayoutParams> {
            // 链右端锚在退格键左侧（85%），链宽 0.7 → bias = 1 使字母右对齐，Z 落在 15%，
            // 恰好让出主键盘 CapsKey 的 0.15 宽度位
            horizontalBias = 1f
        }
        add(backspaceKey, lParams {
            topToBottomOf(rows[1][0])
            bottomOfParent()
            rightOfParent()
            // 与主键盘 BackspaceKey 同宽（0.15）：85%~100%
            matchConstraintPercentWidth = 0.15f
        })
        layoutParams = ViewGroup.LayoutParams(matchParent, matchParent)
    }

    fun setItems(items: List<String>) {
        keyViews.forEachIndexed { i, keyView ->
            keyView.apply {
                if (i >= items.size) {
                    isEnabled = false
                    mainText.text = ""
                    setOnClickListener(null)
                } else {
                    isEnabled = true
                    mainText.text = items[i]
                    setOnClickListener { onItemClick(items[i]) }
                }
                swipeEnabled = false
                onGestureListener = null
                setOnLongClickListener(null)
            }
        }
    }

    fun setItems(items: List<String>, policy: PickerPolicy) {
        keyViews.forEachIndexed { i, keyView ->
            keyView.apply {
                if (i >= items.size) {
                    isEnabled = false
                    mainText.text = ""
                    setOnClickListener(null)
                    setOnLongClickListener(null)
                    swipeEnabled = false
                    onGestureListener = null
                } else {
                    isEnabled = true
                    val item = items[i]
                    val transformed = policy.transform(item)
                    mainText.text = transformed
                    setOnClickListener {
                        onItemClick(transformed)
                    }
                    setOnLongClickListener longClick@{ view ->
                        if (view !is KeyView) return@longClick false
                        val popup = policy.popup(item) ?: return@longClick false
                        onItemLongClick(view, popup)
                        false
                    }
                    swipeEnabled = true
                    onGestureListener = OnGestureListener { view, event ->
                        view as KeyView
                        when (event.type) {
                            CustomGestureView.GestureType.Down -> {
                                onPopupShow(view, item)
                                // never "consume" the gesture on touch down
                                false
                            }
                            CustomGestureView.GestureType.Move -> {
                                onPopupChangeFocus(view.id, event.x, event.y)
                            }
                            CustomGestureView.GestureType.Up -> {
                                onPopupTrigger(view.id).also {
                                    onPopupAction(PopupAction.DismissAction(view.id))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onItemClick(item: String) {
        keyActionListener?.onKeyAction(CommitAction(item), Source.Keyboard)
    }

    private fun onPopupAction(action: PopupAction) {
        popupActionListener?.onPopupAction(action)
    }

    private fun onItemLongClick(view: KeyView, popup: KeyDef.Popup.Keyboard) {
        if (!popupOnKeyPress) {
            // in case "popup on keypress" is disabled, popup keyboard need to know
            // the actual bounds on press. see [^1] as well
            view.updateBounds()
        }
        onPopupAction(PopupAction.ShowKeyboardAction(view.id, popup, view.bounds))
    }

    private fun onPopupShow(view: KeyView, item: String) {
        if (!popupOnKeyPress) return
        // [^1]: bounds is first calculated in KeyView's onLayout(), it
        // not in screen viewport at the time of layout.
        // e.g. it's inside the next page of ViewPager
        // so update bounds when it's pressed
        view.updateBounds()
        onPopupAction(PopupAction.PreviewAction(view.id, item, view.bounds))
        return
    }

    private fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean {
        val changeFocusAction = PopupAction.ChangeFocusAction(viewId, x, y)
        onPopupAction(changeFocusAction)
        return changeFocusAction.outResult
    }

    private fun onPopupTrigger(viewId: Int): Boolean {
        val triggerAction = PopupAction.TriggerAction(viewId)
        onPopupAction(triggerAction)
        val action = triggerAction.outAction as? FcitxKeyAction ?: return false
        onItemClick(action.act)
        onPopupAction(PopupAction.DismissAction(viewId))
        return true
    }

    companion object {
    }
}
