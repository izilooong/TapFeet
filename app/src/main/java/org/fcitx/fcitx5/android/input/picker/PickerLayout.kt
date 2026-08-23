/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.*
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.view
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class PickerLayout(
    context: Context,
    theme: Theme,
    switchKey: KeyDef,
    commaKey: KeyDef = Keyboard.PunctuationKey(",")
) : ConstraintLayout(context) {

    class Keyboard(context: Context, theme: Theme, switchKey: KeyDef, commaKey: KeyDef) : BaseKeyboard(
        context, theme,
        listOf(
            buildList {
                add(LayoutSwitchKey("ABC", TextKeyboard.Name))
                add(commaKey)
                add(switchKey)
                // 总开关（AppPrefs.CustomKeyboard.enabled）关闭时不放⑩键；SpaceKey(0f) 自动吸收余宽，底排无空隙
                if (AppPrefs.getInstance().customKeyboard.enabled.getValue()) {
                    add(LayoutSwitchKey("⑩", CustomKeyboard.Name, percentWidth = 0.1f))
                }
                add(SpaceKey())
                add(PunctuationKey("."))
                add(ReturnKey())
            }
        )
    ) {

        class PunctuationKey(val symbol: String) : KeyDef(
            Appearance.Text(
                displayText = symbol,
                textSize = 23f,
                percentWidth = 0.1f,
                variant = Appearance.Variant.Alternative
            ),
            setOf(
                Behavior.Press(KeyAction.FcitxKeyAction(symbol))
            )
        )

        val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

        override fun onReturnDrawableUpdate(returnDrawable: Int) {
            `return`.img.imageResource = returnDrawable
        }
    }

    private val switchKeyDef = switchKey
    private val commaKeyDef = commaKey
    private val themeDef = theme

    var embeddedKeyboard = Keyboard(context, themeDef, switchKeyDef, commaKeyDef)

    val pager = view(::ViewPager2) { }

    val tabsUi = PickerTabsUi(context, theme)

    val paginationUi = PickerPaginationUi(context, theme)

    init {
        add(pager, lParams {
            topOfParent()
            centerHorizontally()
            above(embeddedKeyboard)
        })
        add(embeddedKeyboard, lParams {
            below(pager)
            centerHorizontally()
            bottomOfParent()
            matchConstraintPercentHeight = 0.25f
        })
        add(paginationUi.root, lParams(matchConstraints, dp(2)) {
            centerHorizontally()
            below(pager, dp(-1))
        })
    }

    /** 自定义键盘总开关变化时重建底排键盘（⑩ 键随 enabled 出现/隐藏），并重指 pager 的 above 约束 */
    fun rebuildEmbeddedKeyboard() {
        val old = embeddedKeyboard
        removeView(old)
        embeddedKeyboard = Keyboard(context, themeDef, switchKeyDef, commaKeyDef)
        add(embeddedKeyboard, lParams {
            below(pager)
            centerHorizontally()
            bottomOfParent()
            matchConstraintPercentHeight = 0.25f
        })
        pager.updateLayoutParams<ConstraintLayout.LayoutParams> { above(embeddedKeyboard) }
    }
}