/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.shouldClearPredictionOnDelete
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the prediction-clear-on-delete boundary in InputView
 * (shouldClearPredictionOnDelete). The full InputView is a heavy Android View, so we test the
 * extracted pure predicate directly. See InputView.handleDeleteClearsPrediction.
 */
class DeleteClearsPredictionTest {

    // android.view.KeyEvent constants (not importable in this plain-JVM test source set):
    //   ACTION_DOWN = 0, KEYCODE_DEL = 67
    private val down = 0
    private val del = 67

    @Test
    fun `returns true only when all guard conditions hold`() {
        assertTrue(
            shouldClearPredictionOnDelete(
                keyAction = down,
                keyCode = del,
                repeatCount = 0,
                isPreeditEmpty = true,
                isCandidateUiShowing = true,
                candidateCount = 3,
            )
        )
    }

    @Test
    fun `ignores non-down action`() {
        assertFalse(
            shouldClearPredictionOnDelete(down + 1, del, 0, true, true, 3)
        )
    }

    @Test
    fun `ignores non-delete key`() {
        assertFalse(
            shouldClearPredictionOnDelete(down, del + 1, 0, true, true, 3)
        )
    }

    @Test
    fun `ignores key repeat so long-press delete keeps deleting editor text`() {
        assertFalse(
            shouldClearPredictionOnDelete(down, del, 1, true, true, 3)
        )
    }

    @Test
    fun `ignores when preedit is not empty mid-composition`() {
        assertFalse(
            shouldClearPredictionOnDelete(down, del, 0, false, true, 3)
        )
    }

    @Test
    fun `ignores when candidate UI is hidden`() {
        assertFalse(
            shouldClearPredictionOnDelete(down, del, 0, true, false, 3)
        )
    }

    @Test
    fun `ignores when there are no candidates`() {
        assertFalse(
            shouldClearPredictionOnDelete(down, del, 0, true, true, 0)
        )
    }
}
