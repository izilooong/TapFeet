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
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
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

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 1f
    private var pageCandidates: Array<org.fcitx.fcitx5.android.core.CandidateWord> = emptyArray()
    private var sourceTotal = -1
    private var candidatePagingMode = 0
    private var remoteHasPrev = false
    private var remoteHasNext = false
    private var localPageStart = 0
    private var localPageSize = Int.MAX_VALUE
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
                    if (remoteHasNext) {
                        // Sparse tail (e.g. only 1 candidate left): load the subsequent batch
                        // from the backend to fill a proper page instead of showing a lonely
                        // 1-item page.
                        candidatePagingMode = 1
                        fcitx.launchOnReady {
                            it.setCandidatePagingMode(1)
                            it.offsetCandidatePage(1)
                        }
                        updatePagingState()
                        return
                    }
                    // No more data behind: borrow candidates from the current page so the
                    // trailing page is no longer an orphan (e.g. [4,1] -> [3,2]).
                    val orphanThreshold = (effectiveLocalPageSize().coerceAtLeast(1) + 1) / 2
                    while (localPageSize > 1 &&
                            (pageCandidates.size - (localPageStart + localPageSize)) < orphanThreshold) {
                        localPageSize -= 1
                    }
                    localPageStart = min(localPageStart + localPageSize, lastLocalPageStart())
                    localPageSize = preferredLocalPageSize(localPageStart)
                    renderCurrentPage()
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
        return min(maxSpanCountPref.getValue().coerceAtLeast(1), remaining)
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
            localPageSize = childCount
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
                .apply { justifyContent = JustifyContent.CENTER }
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
                        if (fillStyle == AutoFillWidth) {
                            val maxSpanCount = maxSpanCountPref.getValue()
                            layoutMinWidth = w / maxSpanCount - dividerDrawable.intrinsicWidth
                        }
                        if (w != oldw && oldw > 0) {
                            pendingLocalPageSize = -1
                            localPageSize = preferredLocalPageSize(localPageStart)
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
        localPageSize = preferredLocalPageSize(0)
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            AutoFillWidth -> {
                layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
                layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = candidates.size < maxSpanCount
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
