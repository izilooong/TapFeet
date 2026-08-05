/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.WindowInsets
import android.widget.TextView
import org.fcitx.fcitx5.android.input.candidates.HardwareShortcutResolver
import androidx.annotation.Size
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.floating.PagedCandidatesUi
import org.fcitx.fcitx5.android.input.preedit.PreeditUi
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.padding
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class CandidatesView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val ctx = context.withTheme(R.style.Theme_InputViewTheme)

    private val candidatesPrefs = AppPrefs.getInstance().candidates
    private val orientation by candidatesPrefs.orientation
    private val windowMinWidth by candidatesPrefs.windowMinWidth
    private val windowPadding by candidatesPrefs.windowPadding
    private val windowRadius by candidatesPrefs.windowRadius
    private val windowShadow by candidatesPrefs.windowShadow
    private val showPreedit by candidatesPrefs.showPreedit
    private val fontSize by candidatesPrefs.fontSize
    private val itemPaddingVertical by candidatesPrefs.itemPaddingVertical
    private val itemPaddingHorizontal by candidatesPrefs.itemPaddingHorizontal

    private var inputPanel = FcitxEvent.InputPanelEvent.Data()
    private var paged = FcitxEvent.PagedCandidateEvent.Data.Empty

    /**
     * horizontal, bottom, top
     */
    private val anchorPosition = floatArrayOf(0f, 0f, 0f)
    private val parentSize = floatArrayOf(0f, 0f)

    private var shouldUpdatePosition = false

    /**
     * layout update may or may not cause [CandidatesView]'s size [onSizeChanged],
     * in either case, we should reposition it
     */
    private val layoutListener = OnGlobalLayoutListener {
        shouldUpdatePosition = true
    }

    /**
     * [CandidatesView]'s position is calculated based on it's size,
     * so we need to recalculate the position after layout,
     * and before any actual drawing to avoid flicker
     */
    private val preDrawListener = OnPreDrawListener {
        if (shouldUpdatePosition) {
            updatePosition()
        }
        true
    }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(this)

    private val setupTextView: TextView.() -> Unit = {
        textSize = fontSize.toFloat()
        val v = dp(itemPaddingVertical)
        val h = dp(itemPaddingHorizontal)
        setPadding(h, v, h, v)
    }

    // The preedit hint sits in the top-left corner of the floating window as a small, padding-free
    // label — visually distinct from the candidate items (which use the normal font size + padding).
    private val setupPreeditTextView: TextView.() -> Unit = {
        textSize = 13f
        setPadding(0, 0, 0, 0)
    }

    private val preeditUi = PreeditUi(ctx, theme, setupPreeditTextView)

    // Default candidate row height: one line of text at candidates.fontSize plus the vertical
    // item padding. The paging-buttons column is sized to this height, so the two stacked buttons
    // split a default row evenly (half a row each).
    private val candidateRowHeightPx: Int by lazy {
        val fontHeight = TextView(ctx).apply { textSize = fontSize.toFloat() }
            .paint.fontMetricsInt.let { it.bottom - it.top }
        fontHeight + dp(itemPaddingVertical) * 2
    }

    private val candidatesUi = PagedCandidatesUi(
        ctx, theme, setupTextView,
        onCandidateClick = { index -> fcitx.launchOnReady { it.select(index) } },
        onCandidateAction = { index, text, view -> showCandidateActionMenu(index, text, view) },
        onPrevPage = { fcitx.launchOnReady { it.offsetCandidatePage(-1) } },
        onNextPage = { fcitx.launchOnReady { it.offsetCandidatePage(1) } }
    )

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        handleFcitxEvent(FcitxEvent.InputPanelEvent(inputPanelData))
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.InputPanelEvent -> {
                inputPanel = it.data
                updateUi()
            }
            is FcitxEvent.PagedCandidateEvent -> {
                paged = it.data
                updateUi()
            }
            else -> {}
        }
    }

    private fun evaluateVisibility(): Boolean {
        // When the preedit is not drawn in this window (showPreedit == false), it must not keep
        // the floating window alive on its own — the composing letters are shown in the target
        // text box instead. Only the candidate list / auxiliary text justify showing the window.
        val preeditVisible = showPreedit && inputPanel.preedit.isNotEmpty()
        return preeditVisible ||
                paged.candidates.isNotEmpty() ||
                inputPanel.auxUp.isNotEmpty() ||
                inputPanel.auxDown.isNotEmpty()
    }

    private fun updateUi() {
        preeditUi.update(inputPanel)
        preeditUi.root.visibility = if (showPreedit && preeditUi.visible) VISIBLE else GONE
        candidatesUi.update(paged, orientation)
        if (evaluateVisibility()) {
            visibility = VISIBLE
        } else {
            // RecyclerView won't update its items when ancestor view is GONE
            visibility = INVISIBLE
        }
    }

    private var bottomInsets = 0

    private fun updatePosition() {
        if (visibility != VISIBLE) {
            // skip unnecessary updates
            return
        }
        val (parentWidth, parentHeight) = parentSize
        if (parentWidth <= 0 || parentHeight <= 0) {
            // panic, bail
            translationX = 0f
            translationY = 0f
            return
        }
        val (horizontal, bottom, top) = anchorPosition
        val w: Int = width
        val h: Int = height
        val selfWidth = w.toFloat()
        val selfHeight = h.toFloat()
        val tX: Float = if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            val rtlOffset = parentWidth - horizontal
            if (rtlOffset + selfWidth > parentWidth) selfWidth - parentWidth else -rtlOffset
        } else {
            if (horizontal + selfWidth > parentWidth) parentWidth - selfWidth else horizontal
        }
        val bottomLimit = parentHeight - bottomInsets
        val bottomSpace = bottomLimit - bottom
        // move CandidatesView above cursor anchor, only when
        val tY: Float = if (
            bottom + selfHeight > bottomLimit   // bottom space is not enough
            && top > bottomSpace                // top space is larger than bottom
        ) top - selfHeight else bottom
        translationX = tX
        translationY = tY
        // update touchEventReceiverWindow's position after CandidatesView's
        touchEventReceiverWindow.showAt(tX.roundToInt(), tY.roundToInt(), w, h)
        shouldUpdatePosition = false
    }

    fun updateCursorAnchor(@Size(4) anchor: FloatArray, @Size(2) parent: FloatArray) {
        val (horizontal, bottom, _, top) = anchor
        val (parentWidth, parentHeight) = parent
        anchorPosition[0] = horizontal
        anchorPosition[1] = bottom
        anchorPosition[2] = top
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        updatePosition()
    }

    /**
     * Anchor candidates view to bottom-left corner, takes navbar bottom insets into consideration.
     * Should only be used when [CursorAnchorInfo][android.view.inputmethod.CursorAnchorInfo] is invalid
     */
    fun updateCursorAnchor(@Size(2) parent: FloatArray) {
        val (parentWidth, parentHeight) = parent
        val bottom = parentHeight - bottomInsets
        anchorPosition[0] = 0f
        anchorPosition[1] = bottom
        anchorPosition[2] = bottom
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        updatePosition()
    }

    /**
     * Handle a physical-keyboard candidate shortcut while this floating window is the active
     * candidate surface (physical-keyboard mode). Selection is resolved **strictly by sequence
     * number** ([HardwareShortcutResolver.resolveShortcutPositionBySequence]) because the floating
     * window renders candidates in plain index order (candidate1 → 0, candidate2 → 1, …), unlike
     * the horizontal bar which uses arrangement-aware first-pick / BlackBerry slots. The final pick
     * is committed via [org.fcitx.fcitx5.android.core.FcitxAPI.select] — exactly what a touch tap does.
     *
     * Global actions (toggle IME / input-method picker) and the symbol-picker toggle are still
     * handled by [org.fcitx.fcitx5.android.input.InputView] (they are view-independent); this
     * method only deals with candidate selection and paging.
     */
    fun handleHardwareCandidateShortcut(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (!isShowingCandidates()) return false

        // Paging: previous / next candidate page (layout-independent).
        val paging = HardwareShortcutResolver.resolvePaging(event)
        if (paging != null) {
            pageCandidates(paging)
            return true
        }

        val count = visibleCandidateCount()
        if (count <= 0) return false

        // Selection is purely by sequence number: candidate N → visible position N-1.
        val position = HardwareShortcutResolver.resolveShortcutPositionBySequence(event, count)
            ?: return false
        return selectAtVisiblePosition(position)
    }

    internal fun isShowingCandidates(): Boolean =
        visibility == VISIBLE && paged.candidates.isNotEmpty()

    private fun visibleCandidateCount(): Int = paged.candidates.size

    private fun selectAtVisiblePosition(position: Int): Boolean {
        if (position !in 0 until paged.candidates.size) return false
        fcitx.launchOnReady { it.select(position) }
        return true
    }

    private fun pageCandidates(direction: Int) {
        fcitx.launchOnReady { it.offsetCandidatePage(direction) }
    }

    init {
        // invisible by default
        visibility = INVISIBLE

        val shadowPx = dp(windowShadow).toFloat()
        val shadowDyPx = shadowPx * 0.6f
        // The self-painted shadow insets the fill asymmetrically: the fill is pushed in by
        // shadowPx on the top/left/right and by shadowPx + shadowDy on the bottom (the drop
        // shadow falls downward). Grow the padding by exactly those insets PLUS the normal
        // windowPadding, so children keep a uniform windowPadding of breathing room *inside*
        // the painted fill* on every side — otherwise the bottom row would hug the fill edge.
        val sideDp = windowPadding + windowShadow
        val bottomDp = windowPadding + windowShadow + (windowShadow * 0.6f).toInt()
        setPadding(dp(sideDp), dp(sideDp), dp(sideDp), dp(bottomDp))
        minWidth = dp(windowMinWidth) + (shadowPx * 2).toInt()
        // Self-contained drop shadow: painted by CandidateWindowShadowDrawable so it shows on
        // every API level and theme (elevation shadows are unreliable inside an IME window and
        // tinted outline colours are only honoured on API 28+). Size stays user-configurable via
        // candidates.windowShadow.
        background = CandidateWindowShadowDrawable(
            fillColor = theme.backgroundColor,
            radius = dp(windowRadius).toFloat(),
            shadowColor = 0x40000000,
            shadowRadius = shadowPx,
            shadowDy = shadowPx * 0.6f
        )
        // Keep the content from bleeding past the rounded fill; the drawable already insets the
        // fill by the shadow amount, so children never overflow the corners.
        clipToOutline = false
        // Force a software layer so setShadowLayer() renders on all Android versions (pre-28
        // ignores it under hardware acceleration). Cost is negligible for a small floating window.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        add(preeditUi.root, lParams(wrapContent, wrapContent) {
            topOfParent()
            startOfParent()
        })
        // Paging buttons: stacked vertically (prev on top, next on bottom), always pinned to the
        // right side of the window. Total height = one default candidate row, each button gets
        // half of it (enforced by layout weights inside PaginationUi).
        val pagination = candidatesUi.paginationUi.root
        add(pagination, lParams(candidateRowHeightPx, candidateRowHeightPx) {
            below(preeditUi.root)
            bottomOfParent()
            endOfParent()
        })
        add(candidatesUi.root, lParams(matchConstraints, wrapContent) {
            matchConstraintMinWidth = wrapContent
            horizontalBias = 0.5f
            below(preeditUi.root)
            startOfParent()
            before(pagination)
            bottomOfParent()
        })

        isFocusable = false
        layoutParams = ViewGroup.LayoutParams(wrapContent, wrapContent)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            bottomInsets = getNavBarBottomInset(insets)
        }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun setVisibility(visibility: Int) {
        if (visibility != VISIBLE) {
            touchEventReceiverWindow.dismiss()
        }
        super.setVisibility(visibility)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        touchEventReceiverWindow.dismiss()
        super.onDetachedFromWindow()
    }
}
