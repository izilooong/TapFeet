/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.animation.ObjectAnimator
import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.Keep
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxEvent.PagedCandidateEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

class HorizontalCandidateComponent :
        UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()
    private val service: FcitxInputMethodService by manager.inputMethodService()

    private var pendingFlyText: String? = null
    private var pendingFlyX = 0f
    private var pendingFlyY = 0f

    fun prepareFlyAnimation(text: String, sourceView: View) {
        pendingFlyText = text
        val loc = intArrayOf(0, 0)
        sourceView.getLocationOnScreen(loc)
        pendingFlyX = loc[0].toFloat() + sourceView.width / 2f
        pendingFlyY = loc[1].toFloat() + sourceView.height / 2f
    }

    fun prepareFlyAnimationForLocalNumber(number: Int) {
        val candidate = candidateForLocalNumber(number) ?: return
        val position = adapter.selectionIndexForDisplayNumber(number) ?: return
        val viewHolder =
                view.findViewHolderForAdapterPosition(position) as? CandidateViewHolder ?: return
        prepareFlyAnimation(candidate.text, viewHolder.ui.text)
    }

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                    expandedCandidateGridSpanCount
            else expandedCandidateGridSpanCountLandscape
        }
    }

    // Hold the ManagedPreference<String> directly (no `by` delegate) so we can call
    // registerOnChangeListener on it. With `by`, the property would be the unwrapped String
    // and the listener-registration method would not resolve.
    private val candidateArrangementModePref = AppPrefs.getInstance().candidateBar.arrangementMode

    @Keep
    private val onCandidateArrangementModeChangeListener =
        ManagedPreference.OnChangeListener<CandidateArrangementMode> { _, newValue ->
            val mode = newValue.toDisplayMode()
            if (adapter.displayMode != mode) {
                adapter.displayMode = mode
                // Re-render the current page so the new ordering (居中展开 vs 线性) takes effect
                // immediately. The adapter reuses the same `candidates` data, so no Fcitx event
                // is needed; we just rebuild the display order and notify.
                if (pageCandidates.isNotEmpty()) {
                    renderCurrentPage()
                }
            }
        }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 1f
    private var pageCandidates: Array<org.fcitx.fcitx5.android.core.CandidateWord> = emptyArray()
    private var sourceTotal = -1
    private var candidatePagingMode = 0
    private var remoteHasPrev = false
    private var remoteHasNext = false
    private var localPageStart = 0
    private var localPageSize = Int.MAX_VALUE
    /**
     * When the user advances into an orphan tail page (e.g. 4 candidates → page 1 of 3 leaves a
     * lonely 1-item tail), we always trigger a remote page fetch first to try to fill a normal
     * {5, 3, 1} page from the next backend batch. If the backend genuinely has no more
     * candidates ([onPagedCandidateUpdate] returns an empty list), we fall back to rendering
     * this small tail page. This field records the `nextStart` to fall back to so the fallback
     * path knows where to resume locally.
     */
    private var pendingOrphanTailStart: Int = -1
    private var pendingLocalPageSize = -1
    private var pagingStateListener: ((Boolean, Boolean) -> Unit)? = null

    /**
     * (for [HorizontalCandidateMode.AutoFillWidth] only) Second layout pass is needed when: [^1]
     * total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them In that
     * case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    // Since expanded candidate window is created once the expand button was clicked,
    // we need to replay the last offset
    private val _expandedCandidateOffset =
            MutableSharedFlow<Int>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    fun setPagingStateListener(listener: (Boolean, Boolean) -> Unit) {
        pagingStateListener = listener
        listener(hasPrevPage(), hasNextPage())
    }

    fun page(delta: Int) {
        if (delta < 0) {
            if (hasLocalPrev()) {
                localPageStart = max(0, localPageStart - effectiveLocalPageSize())
                localPageSize = preferredLocalPageSize(localPageStart)
                renderCurrentPage()
            } else if (remoteHasPrev) {
                // Remote paging in bulk mode would keep returning the same leading slice.
                // Switch to paged mode first so the backend advances by page.
                candidatePagingMode = 1
                fcitx.launchOnReady {
                    it.setCandidatePagingMode(1)
                    it.offsetCandidatePage(-1)
                }
            }
        } else if (delta > 0) {
            if (hasLocalNext()) {
                val nextStart =
                        min(localPageStart + effectiveLocalPageSize(), lastLocalPageStart())
                if (isOrphanPage(nextStart)) {
                    // Sparse tail (e.g. only 1 candidate left): always try to load the next
                    // backend batch first so we can fill a normal {5, 3, 1} page instead of
                    // showing a lonely 1-item page — even when [remoteHasNext] is currently
                    // false (the flag can be stale; the fcitx backend may still have more
                    // candidates than the initial batch reported). If the backend genuinely
                    // has no more, [onPagedCandidateUpdate] will see an empty response and
                    // fall back to rendering the small local tail via [pendingOrphanTailStart].
                    pendingOrphanTailStart = nextStart
                    candidatePagingMode = 1
                    fcitx.launchOnReady {
                        it.setCandidatePagingMode(1)
                        it.offsetCandidatePage(1)
                    }
                    updatePagingState()
                    return
                }
                localPageStart = nextStart
                localPageSize = preferredLocalPageSize(localPageStart)
                renderCurrentPage()
            } else if (remoteHasNext) {
                // Remote paging in bulk mode would keep returning the same leading slice.
                // Switch to paged mode first so the backend advances by page.
                candidatePagingMode = 1
                fcitx.launchOnReady {
                    it.setCandidatePagingMode(1)
                    it.offsetCandidatePage(1)
                }
                updatePagingState()
            }
        }
    }

    fun selectionIndexForLocalNumber(number: Int): Int? {
        val normalized = if (number == 0) 10 else number
        if (normalized <= 0) return null
        return adapter.selectionIndexForDisplayNumber(normalized)
    }

    fun selectionIndexAtVisiblePosition(position: Int): Int? {
        if (position < 0) return null
        return adapter.selectionIndexAtDisplayPosition(position)
    }

    fun currentCandidatePagingMode(): Int = candidatePagingMode

    fun visibleCandidateCount(): Int = adapter.itemCount

    fun candidateForLocalNumber(number: Int): org.fcitx.fcitx5.android.core.CandidateWord? {
        val index = selectionIndexForLocalNumber(number) ?: return null
        return pageCandidates.getOrNull(index)
    }

    fun candidateAtVisiblePosition(position: Int): org.fcitx.fcitx5.android.core.CandidateWord? {
        val index = selectionIndexAtVisiblePosition(position) ?: return null
        return pageCandidates.getOrNull(index)
    }

    fun isOnLocalSubPage(): Boolean = localPageStart > 0

    private fun effectiveLocalPageSize(): Int {
        return localPageSize.coerceAtLeast(1).coerceAtMost(pageCandidates.size.coerceAtLeast(1))
    }

    private fun preferredLocalPageSize(start: Int): Int {
        val remaining = pageCandidates.size - start
        if (remaining <= 0) return 0
        val raw = min(maxSpanCountPref.getValue().coerceAtLeast(1), remaining)
        // Snap to {5, 3, 1} so the local page never shows 2 or 4 candidates. 4 and 2 break the
        // visual rhythm of the 巨硬/普通 layout (居中展开的间距假设) and also force the Flexbox
        // 5-slice layoutMinWidth to compress candidates into non-round widths; 5/3/1 are the only
        // tiers that look correct and trigger the width-based overflow fallback cleanly (5→3,
        // 3→1) in onLayoutCompleted.
        //
        // Note: snap is computed from `raw` (= min(maxSpan, remaining)), NOT from `remaining` —
        // a 2-candidate query must show 1, not 3; a 4-candidate query must show 3, not 5. If we
        // returned 3 when only 2 candidates exist, renderCurrentPage's slice would be capped at
        // pageCandidates.size and the user would still see 2 (the bug this snap was added to fix).
        //
        // Pagination still walks every candidate: 4 candidates → page 1 shows 3, page 2 shows 1;
        // 7 candidates with maxSpan=5 → 5+1+1 (3 pages). All candidates remain reachable, just
        // possibly with one extra tap.
        return when {
            raw <= 2 -> 1
            raw <= 4 -> 3
            else -> 5
        }
    }

    /**
     * Apply a new [localPageSize] and keep [layoutMinWidth] in lockstep with it, so the Flexbox
     * layout manager distributes the bar width evenly across the visible candidates at every tier
     * (5/3/1). Without this, shrinking the page size to 3 would still leave each candidate at the
     * 5-slice minimum, so the overflow check in [layoutManager]'s `onLayoutCompleted` would never
     * see a clean 3-across layout and would collapse straight to 1.
     */
    private fun applyLocalPageSize(size: Int) {
        localPageSize = size
        // AutoFillWidth no longer forces a 1/size layoutMinWidth — candidates stay at their
        // natural text width regardless of the page-size tier (see onCandidateUpdate).
        if (fillStyle == AlwaysFillWidth) {
            layoutMinWidth = 0
        }
    }

    private fun lastLocalPageStart(): Int {
        val pageSize = effectiveLocalPageSize()
        val count = pageCandidates.size
        if (count <= pageSize) return 0
        return ((count - 1) / pageSize) * pageSize
    }

    /**
     * Whether a page starting at [start] would display fewer than half of the per-page capacity
     * (an "orphan" tail, e.g. the lonely 1-item last page when words are wide).
     */
    private fun isOrphanPage(start: Int): Boolean {
        val remaining = pageCandidates.size - start
        if (remaining <= 0) return false
        val capacity = effectiveLocalPageSize().coerceAtLeast(1)
        val size = min(maxSpanCountPref.getValue().coerceAtLeast(1), remaining)
        return size < (capacity + 1) / 2
    }

    private fun hasLocalPrev(): Boolean = localPageStart > 0

    private fun hasLocalNext(): Boolean {
        return pageCandidates.isNotEmpty() &&
                localPageStart + effectiveLocalPageSize() < pageCandidates.size
    }

    private fun hasPrevPage(): Boolean = hasLocalPrev() || remoteHasPrev

    private fun hasNextPage(): Boolean = hasLocalNext() || remoteHasNext

    private fun updatePagingState() {
        pagingStateListener?.invoke(hasPrevPage(), hasNextPage())
    }

    private fun renderCurrentPage() {
        val pageSize = effectiveLocalPageSize()
        localPageStart = localPageStart.coerceIn(0, lastLocalPageStart())
        val end = min(localPageStart + pageSize, pageCandidates.size)
        val slice =
                if (pageCandidates.isEmpty()) emptyArray()
                else pageCandidates.copyOfRange(localPageStart, end)
        adapter.updateCandidates(slice, sourceTotal, localPageStart)
        updatePagingState()
        if (slice.isEmpty()) {
            refreshExpanded(0)
        }
    }

    private fun refreshExpanded(childCount: Int) {
        _expandedCandidateOffset.tryEmit(childCount)
        bar.expandButtonStateMachine.push(
                ExpandedCandidatesUpdated,
                ExpandedCandidatesEmpty to (adapter.total == childCount)
        )
        if (childCount in 2 until pageCandidates.size && childCount != localPageSize) {
            scheduleLocalPageResize(childCount)
            return
        }
        updatePagingState()
    }

    private fun scheduleLocalPageResize(childCount: Int) {
        if (pendingLocalPageSize == childCount) return
        pendingLocalPageSize = childCount
        view.post {
            if (pendingLocalPageSize != childCount) return@post
            pendingLocalPageSize = -1
            if (localPageSize == childCount) return@post
            applyLocalPageSize(childCount)
            renderCurrentPage()
        }
    }

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = layoutMinWidth
                    flexGrow = layoutFlexGrow
                    // flexShrink must be 0 so wide candidates keep their natural text width and
                    // actually overflow the bar — otherwise the Flexbox layout manager silently
                    // shrinks each item down to minWidth and clips the text, which prevents the
                    // 5→3→1 overflow fallback in onLayoutCompleted from ever triggering.
                    flexShrink = 0f
                }
                holder.itemView.setOnClickListener {
                    prepareFlyAnimation(holder.candidate.text, holder.ui.text)
                    fcitx.launchOnReady { it.select(holder.idx) }
                }
                holder.itemView.setOnLongClickListener {
                    inputView.showCandidateActionMenu(
                            holder.idx,
                            holder.candidate.text,
                            holder.ui.root
                    )
                    true
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    val layoutManager: FlexboxLayoutManager by lazy {
        object : FlexboxLayoutManager(context) {
                    override fun canScrollVertically() = false
                    override fun canScrollHorizontally() = false
                    override fun onLayoutCompleted(state: RecyclerView.State) {
                        super.onLayoutCompleted(state)
                        val cnt = this.childCount
                        // Responsive 5→3→1 fallback: if the laid-out children overflow the
                        // candidate bar's width, the candidate words are too wide to fit at the
                        // current tier — snap down to the next smaller one (5→3 or 3→1) and
                        // re-render. Without this gate, layoutMinWidth would force every
                        // candidate to claim its 5-slice minimum and wide candidates would be
                        // silently clipped at the right edge instead of the bar showing fewer,
                        // properly-sized words.
                        if (cnt > 0 && view.width > 0 && localPageSize > 1) {
                            var totalWidth = 0
                            for (i in 0 until cnt) totalWidth += getChildAt(i)!!.width
                            if (totalWidth > view.width) {
                                val nextSize = when {
                                    localPageSize > 3 -> 3
                                    else -> 1
                                }
                                if (nextSize < localPageSize) {
                                    applyLocalPageSize(nextSize)
                                    view.post { renderCurrentPage() }
                                    return
                                }
                            }
                        }
                        if (secondLayoutPassNeeded) {
                            if (cnt < adapter.candidates.size) {
                                // [^2] RecyclerView can't display all candidates
                                // update LayoutParams in onLayoutCompleted would trigger another
                                // onLayoutCompleted, skip the second one to avoid infinite loop
                                if (secondLayoutPassDone) return
                                secondLayoutPassDone = true
                                for (i in 0 until cnt) {
                                    getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                        flexGrow = 1f
                                    }
                                }
                            } else {
                                secondLayoutPassNeeded = false
                            }
                        }
                        refreshExpanded(cnt)
                    }
                    // no need to override `generate{,Default}LayoutParams`, because
                    // HorizontalCandidateViewAdapter
                    // guarantees ViewHolder's layoutParams to be
                    // `FlexboxLayoutManager.LayoutParams`
                }
                .apply {
                    justifyContent = JustifyContent.CENTER
                    // Force a single row: without NOWRAP (the default is WRAP), 5 wide candidates
                    // would silently wrap to 3+2 or 2+3, producing a visible "2" / "3" stack
                    // instead of triggering the 5→3→1 overflow fallback in onLayoutCompleted.
                    flexWrap = FlexWrap.NOWRAP
                }
    }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    override val view by lazy {
        object : RecyclerView(context) {
                    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                        super.onSizeChanged(w, h, oldw, oldh)
                        // Resize the page first so layoutMinWidth reflects the actual tier (5/3/1)
                        // we are about to render, not the pref's max span count.
                        if (w != oldw && oldw > 0) {
                            pendingLocalPageSize = -1
                            localPageSize = preferredLocalPageSize(localPageStart)
                        }
                        if (fillStyle == AutoFillWidth) {
                            // AutoFillWidth no longer sets a 1/size layoutMinWidth (see
                            // onCandidateUpdate) — keep minWidth at 0 so candidates stay at their
                            // natural text width after a width change.
                            layoutMinWidth = 0
                        }
                        if (w != oldw && oldw > 0) {
                            renderCurrentPage()
                        }
                    }
                }
                .apply {
                    id = R.id.candidate_view
                    itemAnimator = null
                    adapter = this@HorizontalCandidateComponent.adapter
                    layoutManager = this@HorizontalCandidateComponent.layoutManager
                    addItemDecoration(FlexboxVerticalDecoration(dividerDrawable))
                }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        // Align the adapter's display mode with the persisted preference at startup, so a mode
        // change made in a previous session (or in the settings screen while the IME is alive)
        // is honoured on the very next candidate update — no flicker of the old layout first.
        adapter.displayMode = candidateArrangementModePref.getValue().toDisplayMode()
        candidateArrangementModePref.registerOnChangeListener(onCandidateArrangementModeChangeListener)
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        val candidates = data.candidates
        val total = data.total
        pageCandidates = candidates
        sourceTotal = total
        candidatePagingMode = 0
        remoteHasPrev = false
        remoteHasNext = total > candidates.size
        localPageStart = 0
        pendingLocalPageSize = -1
        pendingOrphanTailStart = -1
        localPageSize = preferredLocalPageSize(0)
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            AutoFillWidth -> {
                // Don't force-fill each candidate cell to view.width / maxSpanCount. The user
                // wants each candidate at its natural text width, so:
                //   - minWidth=0 lets short candidates take just their text width (no padding-out
                //     to a 1/N slot).
                //   - flexGrow=0 stops Flexbox from stretching wide candidates into the leftover
                //     bar space.
                // Combined with the {5, 3, 1} snap in [preferredLocalPageSize] and the
                // onLayoutCompleted overflow fallback (5→3→1), the visible count now reflects
                // how many candidates actually fit at their natural width — instead of always
                // squeezing 5 short candidates into a 5-slot layout or stretching 3 short
                // candidates into a 3-slot layout.
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
                secondLayoutPassDone = false
            }
            AlwaysFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }
        renderCurrentPage()
        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshExpanded(0)
        }
    }

    override fun onPagedCandidateUpdate(data: PagedCandidateEvent.Data) {
        // If we triggered this fetch as an orphan-tail fallback (see [page] delta>0 branch)
        // and the backend genuinely has no more candidates (empty response), fall back to
        // rendering the small local tail page so the user still sees the leftover candidate
        // instead of a blank bar.
        if (data.candidates.isEmpty() && pendingOrphanTailStart >= 0) {
            val fallbackStart = pendingOrphanTailStart
            pendingOrphanTailStart = -1
            localPageStart = fallbackStart
            localPageSize = preferredLocalPageSize(fallbackStart)
            renderCurrentPage()
            return
        }
        pendingOrphanTailStart = -1
        pageCandidates = data.candidates
        sourceTotal = data.candidates.size + if (data.hasPrev || data.hasNext) 1 else 0
        candidatePagingMode = 1
        remoteHasPrev = data.hasPrev
        remoteHasNext = data.hasNext
        localPageStart = 0
        pendingLocalPageSize = -1
        localPageSize = preferredLocalPageSize(0)
        renderCurrentPage()
        if (data.candidates.isEmpty()) {
            refreshExpanded(0)
        }
    }

    override fun onCommitText(text: String) {
        if (!AppPrefs.getInstance().candidateBar.showCandidateFlyAnimation.getValue()) return
        val flyText = pendingFlyText ?: return
        if (flyText != text) return
        showCandidateFlyAnimation(pendingFlyX, pendingFlyY, text)
        pendingFlyText = null
    }

    private fun showCandidateFlyAnimation(startX: Float, startY: Float, text: String) {
        val flyView =
                TextView(context).apply {
                    this.text = text
                    textSize = 20f
                    setTextColor(theme.candidateTextColor)
                    isSingleLine = true
                    alpha = 0f
                    elevation = 1000f
                }

        val parentView = service.contentView as ViewGroup
        parentView.addView(
                flyView,
                ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        )

        flyView.post {
            val parentLoc = intArrayOf(0, 0)
            parentView.getLocationOnScreen(parentLoc)

            val localStartX = startX - parentLoc[0]
            val localStartY = startY - parentLoc[1]

            flyView.x = localStartX - flyView.width / 2f
            flyView.y = localStartY - flyView.height / 2f

            flyView.alpha = 1f

            val targetY = 300f - parentLoc[1]
            val flyDistance = startY - 300f

            ObjectAnimator.ofFloat(
                            flyView,
                            View.TRANSLATION_Y,
                            flyView.translationY,
                            flyView.translationY - flyDistance
                    )
                    .apply {
                        duration = 600
                        start()
                    }

            ObjectAnimator.ofFloat(flyView, View.ALPHA, 1f, 1f, 1f, 1f, 0.8f, 0f).apply {
                duration = 600
                start()
            }
        }

        flyView.postDelayed({ parentView.removeView(flyView) }, 700)
    }
}
