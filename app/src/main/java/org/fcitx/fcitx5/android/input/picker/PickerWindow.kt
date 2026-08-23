/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import androidx.core.content.ContextCompat
import androidx.transition.Transition
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.ImagePickerSwitchKey
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.wm.EssentialWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class PickerWindow(
    override val key: Key,
    private val data: List<Pair<PickerData.Category, Array<String>>>,
    private val density: PickerPageUi.Density,
    private val switchKey: KeyDef,
    private val popupPreview: Boolean = true,
    private val followKeyBorder: Boolean = true,
    private val policy: PickerPolicy = DefaultPickerPolicy()
) : InputWindow.ExtendedInputWindow<PickerWindow>(), EssentialWindow {

    enum class Key : EssentialWindow.Key {
        Symbol,
        Emoji,
        Emoticon
    }

    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    private val keyBorder by ThemeManager.prefs.keyBorder

    private lateinit var pickerLayout: PickerLayout
    private lateinit var pickerPagesAdapter: PickerPagesAdapter

    override fun enterAnimation(lastWindow: InputWindow): Transition? = null

    override fun exitAnimation(nextWindow: InputWindow): Transition? = null

    private val keyActionListener = KeyActionListener { it, source ->
        when (it) {
            is KeyAction.LayoutSwitchAction -> {
                // Switch to NumberKeyboard before attaching KeyboardWindow
                (windowManager.getEssentialWindow(KeyboardWindow) as KeyboardWindow)
                    .switchLayout(it.act)
                // The real switchLayout (detachCurrentLayout and attachLayout) in KeyboardWindow is postponed,
                // so we have to postpone attachWindow as well
                ContextCompat.getMainExecutor(context).execute {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            is KeyAction.FcitxKeyAction -> {
                // we want the behavior of CommitAction (commit the character as-is),
                // but don't want to include it in recently used list
                commonKeyActionListener.listener.onKeyAction(KeyAction.CommitAction(it.act), source)
            }

            else -> {
                if (it is KeyAction.CommitAction) {
                    pickerPagesAdapter.insertRecent(it.text)
                }
                commonKeyActionListener.listener.onKeyAction(it, source)
            }
        }
    }

    private val popupActionListener: PopupActionListener by lazy {
        PopupActionListener {
            when (it) {
                is PopupAction.PreviewAction -> {
                    if (!popupPreview) return@PopupActionListener
                }
                is PopupAction.ShowKeyboardAction -> {
                    // prevent ViewPager from consuming swipe gesture when popup keyboard shown
                    pickerLayout.pager.isUserInputEnabled = false
                }
                is PopupAction.DismissAction -> {
                    // restore ViewPager scrolling
                    pickerLayout.pager.isUserInputEnabled = true
                }
                else -> {}
            }
            popup.listener.onPopupAction(it)
        }
    }

    override fun onCreateView() = PickerLayout(
        context, theme, switchKey,
        commaKey = if (key == Key.Symbol)
            ImagePickerSwitchKey(R.drawable.ic_baseline_tag_faces_24, Key.Emoji)
        else PickerLayout.Keyboard.PunctuationKey(",")
    ).apply {
        pickerLayout = this
        val bordered = followKeyBorder && keyBorder
        pickerPagesAdapter = PickerPagesAdapter(
            theme, keyActionListener, popupActionListener, data,
            density, key.name, bordered, policy
        )
        tabsUi.apply {
            setTabs(pickerPagesAdapter.getCategoryList())
            setOnTabClickListener { i ->
                pager.setCurrentItem(pickerPagesAdapter.getRangeOfCategoryIndex(i).first, false)
            }
        }
        pager.apply {
            adapter = pickerPagesAdapter
            // show first symbol category by default, rather than recently used
            val range = pickerPagesAdapter.getRangeOfCategoryIndex(1)
            setCurrentItem(range.first, false)
            // update initial tab and page manually to avoid
            // "Adding or removing callbacks during dispatch to callbacks"
            tabsUi.activateTab(1)
            paginationUi.updatePageCount(range.run { last - first + 1 })
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                    val range = pickerPagesAdapter.getCategoryRangeOfPage(position)
                    paginationUi.updatePageCount(range.run { last - first + 1 })
                    paginationUi.updateScrollProgress(position - range.first, positionOffset)
                }

                override fun onPageSelected(position: Int) {
                    tabsUi.activateTab(pickerPagesAdapter.getCategoryIndexOfPage(position))
                    popup.dismissAll()
                }
            })
        }
    }

    override fun onCreateBarExtension() = pickerLayout.tabsUi.root

    override fun onAttached() {
        pickerLayout.embeddedKeyboard.also {
            pickerPagesAdapter.refreshIfNeeded()
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.keyActionListener = keyActionListener
        }
    }

    override fun onDetached() {
        popup.dismissAll()
        pickerLayout.embeddedKeyboard.keyActionListener = null
    }

    /** 当前页（含最近使用页 position 0）的符号列表，供物理键盘选符号与调试。 */
    fun currentPageSymbols(): List<String> =
        pickerPagesAdapter.pageItems(pickerLayout.pager.currentItem)

    /**
     * 与触摸点击完全一致的上屏通道：CommitAction → insertRecent + commitText。
     * 最近使用页（position 0）提交原样；分类页按 policy.transform 转换（如 Emoji 默认肤色）——
     * 与 PickerPageUi.setItems(items, policy) 的点击行为一致。
     */
    fun selectItem(item: String) {
        val committed = if (pickerLayout.pager.currentItem == 0) item else policy.transform(item)
        keyActionListener.onKeyAction(KeyAction.CommitAction(committed), KeyActionListener.Source.Keyboard)
    }

    /**
     * 物理键盘选符号：按物理键位 (row, col) 换算成键盘形网格线性序，与触摸点击完全一致上屏。
     *
     * 三个页签（Symbol / Emoji / Emoticon）与最近使用页统一使用键盘形网格，映射规则全页一致：
     * R0(Q..P) → 0..9、R1(A..L) → 10..18、R2(Z..M) → 19..25，
     * 与 PickerPageUi 的 keyViews 填充顺序逐位对齐。
     *
     * 每页正好 26 个符号（idx 0..25）对应 26 个物理字母键；多余符号由 PickerPagesAdapter 翻到下一页。
     * idx 越界（分类末页不满、最近使用页不满）→ 吞掉该键，避免面板打开时误打字。
     */
    fun selectByLetter(row: Int, col: Int): Boolean {
        val idx = when (row) {
            0 -> col        // R0: Q..P → 0..9
            1 -> 10 + col   // R1: A..L → 10..18
            2 -> 19 + col   // R2: Z..M → 19..25
            else -> return true
        }
        val item = currentPageSymbols().getOrNull(idx) ?: return true  // 越界也吞掉
        selectItem(item)
        return true
    }

    /**
     * 物理键翻页：复用候选分页快捷键（pageNextKey / pagePrevKey）。
     * direction < 0 上一页，> 0 下一页；到边界自动夹断。无多页时直接忽略。
     *
     * 用 [androidx.viewpager2.widget.ViewPager2.setCurrentItem] 的 smoothScroll(=true)，
     * 与手指左右滑动翻页是同一套线性滑动动画。
     */
    fun page(direction: Int) {
        if (direction == 0) return
        val pager = pickerLayout.pager
        val count = pager.adapter?.itemCount ?: 0
        if (count <= 1) return
        val target = (pager.currentItem + direction).coerceIn(0, count - 1)
        pager.setCurrentItem(target, true)
    }

    override val showTitle = false
}