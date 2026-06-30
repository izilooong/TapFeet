/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui

import android.content.Context
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add

class CandidateUi(override val ctx: Context, theme: Theme, private val horizontalView: View) : Ui {

    val prevPageButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_prev_24, theme).apply {
        id = R.id.prev_candidate_btn
        visibility = View.INVISIBLE
    }

    val nextPageButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_next_24, theme).apply {
        id = R.id.next_candidate_btn
        visibility = View.INVISIBLE
    }

    val keyboardToggleButton = ToolButton(ctx, R.drawable.ic_baseline_keyboard_24, theme).apply {
        id = R.id.toggle_keyboard_btn
    }

    val expandButton = ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, theme).apply {
        id = R.id.expand_candidate_btn
        visibility = View.INVISIBLE
    }

    override val root = ctx.constraintLayout {
        add(prevPageButton, lParams(dp(40)) {
            centerVertically()
            startOfParent()
        })
        add(nextPageButton, lParams(dp(40)) {
            centerVertically()
            endOfParent()
        })
        add(expandButton, lParams(dp(40)) {
            centerVertically()
            before(nextPageButton)
        })
        add(keyboardToggleButton, lParams(dp(40)) {
            centerVertically()
            before(expandButton)
        })
        add(horizontalView, lParams {
            centerVertically()
            after(prevPageButton)
            before(keyboardToggleButton)
        })
    }
}
