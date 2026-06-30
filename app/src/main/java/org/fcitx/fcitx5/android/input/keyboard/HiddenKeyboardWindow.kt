/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow

class HiddenKeyboardWindow : InputWindow.SimpleInputWindow<HiddenKeyboardWindow>(), EssentialWindow {

    companion object : EssentialWindow.Key

    override val key: EssentialWindow.Key
        get() = HiddenKeyboardWindow

    override fun onCreateView(): View = View(context)

    override fun onAttached() {}

    override fun onDetached() {}
}