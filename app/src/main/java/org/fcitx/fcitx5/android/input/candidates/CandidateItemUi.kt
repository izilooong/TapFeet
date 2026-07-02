/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter

class CandidateItemUi(override val ctx: Context, val theme: Theme) : Ui {

    private val index = textView {
        textSize = 10f // sp, half of candidate text size
        setTextColor(ColorUtils.blendARGB(theme.candidateLabelColor, theme.candidateCommentColor, 0.5f))
        includeFontPadding = false
        gravity = gravityCenter
    }

    private val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = 20f // sp
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    private val content = view(::LinearLayout) {
        orientation = LinearLayout.HORIZONTAL
        gravity = gravityCenter

        add(index, lParams(wrapContent, wrapContent))
        add(text, lParams(wrapContent, matchParent))
    }

    private val pressHighlight = pressHighlightDrawable(theme.keyPressHighlightColor)

    private val borderThickness: Int by lazy {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, ctx.resources.displayMetrics).toInt()
    }

    private val activeBackground by lazy {
        val border = ColorDrawable(theme.genericActiveForegroundColor)
        LayerDrawable(arrayOf(pressHighlight, border)).apply {
            setLayerGravity(1, Gravity.BOTTOM)
            setLayerHeight(1, borderThickness)
        }
    }

    override val root = view(::CustomGestureView) {
        background = pressHighlight

        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.BaseInputView.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false

        add(content, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    fun updateCandidate(candidate: CandidateWord, indexLabel: String = "", isActive: Boolean = false) {
        index.text = if (indexLabel.isNotBlank()) "$indexLabel " else ""
        text.text = candidate.textWithComment()
        if (isActive) {
            text.setTypeface(Typeface.DEFAULT_BOLD)
            root.background = activeBackground
        } else {
            text.setTypeface(Typeface.DEFAULT)
            root.background = pressHighlight
        }
    }
}
