/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
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
        // 定死背景:设置项不多、也不回收(view 创建后背景就固定在它身上,滚动只 detach/attach,
        // background 永远跟着 view,不会"丢失")。所以背景在 view 首次 attach 时算好圆角设一次即可,
        // 不再每帧 canvas 重画(大哥:只要初始化时定死就可以了)。
        // 把 scrap cache 开大,避免 view 被回收进 pool 导致 background 被复用串掉。
        listView.setItemViewCacheSize(512)
        listView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    val adapter = listView.adapter as? PreferenceGroupAdapter ?: return
                    val pos = listView.getChildAdapterPosition(view)
                    if (pos == RecyclerView.NO_POSITION) return
                    if (adapter.getItem(pos) is PreferenceCategory) {
                        // 标题行不设卡片背景
                        if (view.background != null) view.background = null
                        view.foreground = null
                        return
                    }
                    val isFirst = pos == 0 || adapter.getItem(pos - 1) is PreferenceCategory
                    val isLast =
                        pos == adapter.itemCount - 1 || adapter.getItem(pos + 1) is PreferenceCategory
                    val bg = CardGroupDecoration.shapeFor(requireContext(), isFirst, isLast)
                    view.background = bg
                    // 按压高亮裁剪到圆角,不溢出卡片
                    view.foreground =
                        RippleDrawable(CardGroupDecoration.rippleColor(requireContext()), null, bg)
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
    }
}
