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

    private val fillStyle by AppPrefs.getInstance().candidateBar.horizontalCandidateStyle
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
     * When the user advances into a tail page that would be too small (e.g. only 1 candidate
     * left locally) while the backend still has more data, we fetch the next slice first and
     * continue from this position once it lands. If the backend genuinely has no more,
     * [onSliceFetched] falls back to rendering the small tail page (single-candidate pages are
     * only acceptable when fcitx truly has no next page of data).
     */
    private var pendingOrphanTailStart: Int = -1
    private var pendingLocalPageSize = -1
    private var pagingStateListener: ((Boolean, Boolean) -> Unit)? = null

    /** Guards against concurrent slice fetches and double-appends. */
    private var prefetchInFlight = false

    /**
     * Bumped on every new query ([onCandidateUpdate]). Slice fetch responses are tagged with
     * the generation they were issued under; a mismatched response is stale (from the previous
     * query) and must be dropped without touching state.
     */
    private var queryGeneration = 0

    /**
     * Slice size for backend fetches. Matches the bulk batch granularity used by the C++
     * frontend (`updateCandidatesBulk` caps the initial batch at 16), so a full slice back
     * implies "probably more data behind" when the backend total is unknown.
     */
    private val prefetchSliceLimit = 16

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
            }
            // No remote-prev: bulk mode keeps every fetched slice in [pageCandidates], so
            // navigating backwards is always local.
        } else if (delta > 0) {
            if (hasLocalNext()) {
                val nextStart =
                        min(localPageStart + effectiveLocalPageSize(), lastLocalPageStart())
                if (isOrphanPage(nextStart) && remoteHasNext) {
                    // The tail page would be too small (e.g. a single leftover candidate) but
                    // the backend has more data: fetch the next slice first and continue from
                    // [nextStart] when it lands, so the user sees a full page. Only if the
                    // backend is truly exhausted do we fall back to the small tail.
                    fetchNextSlice(continueFromStart = nextStart)
                    updatePagingState()
                    return
                }
                localPageStart = nextStart
                localPageSize = preferredLocalPageSize(localPageStart)
                renderCurrentPage()
            } else if (remoteHasNext) {
                // Local history exhausted but the backend has more: pull the next slice and
                // continue from where the user wanted to go.
                fetchNextSlice(continueFromStart = localPageStart + effectiveLocalPageSize())
                updatePagingState()
            }
        }
    }

    /**
     * Fetch the next slice of candidates from the backend's bulk list via
     * [org.fcitx.fcitx5.android.core.FcitxAPI.getCandidates] and APPEND it to [pageCandidates].
     * Unlike the old paged-mode path (`setCandidatePagingMode(1)` + `offsetCandidatePage`), this:
     *  - never discards local leftovers (the {5,3,1} snap can leave 1-2 trailing candidates;
     *    appending keeps them so the next page shows a full count instead of a lonely tail);
     *  - keeps selection indices GLOBAL: [FcitxAPI.select] in bulk mode resolves through
     *    `candidateFromAll(idx)`, which matches our absolute indices into the appended array.
     */
    private fun fetchNextSlice(continueFromStart: Int = -1) {
        if (continueFromStart >= 0) pendingOrphanTailStart = continueFromStart
        if (prefetchInFlight) return
        prefetchInFlight = true
        val offset = pageCandidates.size
        val generation = queryGeneration
        fcitx.launchOnReady {
            val more = it.getCandidates(offset, prefetchSliceLimit)
            view.post { onSliceFetched(more, generation) }
        }
    }

    private fun onSliceFetched(
            more: Array<org.fcitx.fcitx5.android.core.CandidateWord>,
            generation: Int
    ) {
        // Stale slice from a previous query: drop it entirely (state was already reset by
        // onCandidateUpdate, and a new fetch may be in flight).
        if (generation != queryGeneration) return
        prefetchInFlight = false
        if (more.isEmpty()) {
            // Backend genuinely exhausted: a small tail page is acceptable here.
            remoteHasNext = false
            if (pendingOrphanTailStart >= 0) {
                val fallbackStart = pendingOrphanTailStart
                pendingOrphanTailStart = -1
                localPageStart = fallbackStart
                localPageSize = preferredLocalPageSize(fallbackStart)
                renderCurrentPage()
            } else {
                updatePagingState()
            }
            return
        }
        pageCandidates = pageCandidates + more
        // sourceTotal is authoritative when positive (bulk totalSize); otherwise infer from
        // whether the backend returned a full slice.
        remoteHasNext =
                if (sourceTotal > 0) pageCandidates.size < sourceTotal
                else more.size >= prefetchSliceLimit
        val continueFromStart = pendingOrphanTailStart
        pendingOrphanTailStart = -1
        if (continueFromStart >= 0) {
            localPageStart = min(continueFromStart, lastLocalPageStart())
            localPageSize = preferredLocalPageSize(localPageStart)
            renderCurrentPage()
        } else {
            updatePagingState()
            // Data arrived while the user was browsing: if they're already near the end of the
            // freshly-extended array, keep the pipeline warm for the next page.
            maybePrefetchNextSlice()
        }
    }

    /**
     * Preload the next slice while the user is still on the current page, so pressing "next"
     * shows a full page instantly instead of a partially-filled (or 1-item) page. Fires when
     * the number of not-yet-displayed local candidates drops to one page's worth or less.
     */
    private fun maybePrefetchNextSlice() {
        if (prefetchInFlight || !remoteHasNext) return
        val remaining = pageCandidates.size - (localPageStart + effectiveLocalPageSize())
        if (remaining > maxSpanCountPref.getValue().coerceAtLeast(1)) return
        fetchNextSlice()
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
        // 翻页（上一页/下一页/首屏重渲染都会走到这里）必须重新评估「按需填充」意图：
        // AutoFillWidth 重新打开二次 layout pass；Never/Always 关闭它
        // （Always 已靠 layoutFlexGrow=1f 直接铺满，无需二次 pass）。
        // 否则上一页填充后 secondLayoutPassDone 置 true、或「已满」时
        // secondLayoutPassNeeded 置 false，会让后续页永远不再触发填充。
        secondLayoutPassDone = false
        secondLayoutPassNeeded = fillStyle == AutoFillWidth
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
        // Preload the next backend slice in the background while the user reads this page,
        // so pressing "next" lands on a full page instead of a sparse tail.
        maybePrefetchNextSlice()
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
                        // Aggregate every laid-out candidate's width once — consumed by both the
                        // 5→3→1 overflow fallback below and the AutoFillWidth fill-on-demand pass.
                        var totalWidth = 0
                        for (i in 0 until cnt) totalWidth += getChildAt(i)!!.width
                        // Responsive 5→3→1 fallback: if the laid-out children overflow the
                        // candidate bar's width, the candidate words are too wide to fit at the
                        // current tier — snap down to the next smaller one (5→3 or 3→1) and
                        // re-render. Without this gate, layoutMinWidth would force every
                        // candidate to claim its 5-slice minimum and wide candidates would be
                        // silently clipped at the right edge instead of the bar showing fewer,
                        // properly-sized words.
                        if (cnt > 0 && view.width > 0 && localPageSize > 1) {
                            if (totalWidth > view.width) {
                                val nextSize = when {
                                    localPageSize > 3 -> 3
                                    else -> 1
                                }
                                if (nextSize < localPageSize) {
                                    // 先藏住这一帧过宽的候选，避免「先画出 5/4 个、再回退成 3 个」
                                    // 的闪一下；下一帧渲染到正确档位（3/1）后由下方稳定分支恢复可见。
                                    view.visibility = View.INVISIBLE
                                    applyLocalPageSize(nextSize)
                                    view.post { renderCurrentPage() }
                                    return
                                }
                            }
                        }
                        // 稳定帧（无溢出回退）：确保候选栏可见。上一帧若被上面藏住，
                        // 这一帧已渲染到正确档位，在此恢复，避免出现空白闪烁。
                        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
                        // Fill-on-demand (AutoFillWidth only): after the first natural-width
                        // pass, if the candidates don't already span the full bar, stretch them
                        // evenly to consume the leftover space. Replaces the old `cnt <
                        // adapter.candidates.size` check, which never fired under the single-row
                        // NOWRAP Flexbox (every child stays attached, so cnt == size). The
                        // secondLayoutPassDone guard prevents onLayoutCompleted re-entry loops.
                        if (secondLayoutPassNeeded && localPageSize > 1) {
                            if (totalWidth < view.width) {
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
        // total == -1 means the engine doesn't report a total; if the initial batch filled the
        // backend's bulk slice granularity, assume there's more behind it.
        remoteHasNext =
                if (total >= 0) total > candidates.size
                else candidates.size >= prefetchSliceLimit
        localPageStart = 0
        pendingLocalPageSize = -1
        pendingOrphanTailStart = -1
        prefetchInFlight = false
        queryGeneration++
        localPageSize = preferredLocalPageSize(0)
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            AutoFillWidth -> {
                // Keep each candidate at its natural text width on the first pass (minWidth=0,
                // flexGrow=0), but re-enable the "fill on demand" second layout pass: if the
                // laid-out candidates don't already span the full bar width, [onLayoutCompleted]
                // stretches them evenly to consume the leftover space. This is distinct from
                // [AlwaysFillWidth] (forces flexGrow=1f unconditionally) and [NeverFillWidth]
                // (never fills). Restored after 03c5d8b disabled it and collapsed AutoFillWidth
                // into NeverFillWidth.
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = true
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
        // No-op: this component pages through the backend's bulk candidate list via
        // getCandidates(offset, limit) slices (see fetchNextSlice), never via paged mode.
        // PagedCandidateEvents arrive here only when another component (e.g. the floating
        // CandidatesView) drives paged mode — letting them replace [pageCandidates] would
        // corrupt the appended local history and its global selection indices.
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
