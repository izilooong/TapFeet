/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.modified.MyPreferenceFragment

abstract class PaddingPreferenceFragment : MyPreferenceFragment() {

    @CallSuper
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        val density = resources.displayMetrics.density
        // Match the card margins so a pressed row's ripple never spills past the corners,
        // and lift the first card off the screen top a touch.
        val pad = (CardGroupDecoration.CARD_MARGIN_X_DP * density).toInt()
        // Top clearance: a headerless first group (list starts straight on the rows) would
        // otherwise sit flush against the screen top. clipToPadding=false keeps a card able
        // to rise to the screen edge on scroll, so the clearance only shows at rest.
        val topPad = (CardGroupDecoration.CARD_TOP_PAD_DP * density).toInt()
        val bottomPad = (CardGroupDecoration.CARD_BOTTOM_PAD_DP * density).toInt()
        listView.setPadding(pad, topPad, pad, bottomPad)
        // The page behind the cards is a deeper tone than the cards themselves, so the cards
        // read as raised surfaces (iOS / WeChat look).
        listView.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.settings_page_bg)
        )
        // clipToPadding=false so a card can rise to a flat top edge at the screen top on scroll
        // (clipped at the list bounds), instead of being cut short by a padding rectangle.
        listView.clipToPadding = false
        // Bottom padding = card clearance + nav-bar inset, stacked. This replaces
        // applyNavBarInsetsBottomPadding, which would clobber the clearance with the inset.
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, windowInsets ->
            val nav = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(pad, topPad, pad, bottomPad + nav.bottom)
            windowInsets
        }
        listView.addItemDecoration(CardGroupDecoration(requireContext()))
    }
}
