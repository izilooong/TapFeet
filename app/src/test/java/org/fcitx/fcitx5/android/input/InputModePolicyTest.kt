/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down every branch of the old four `when (candidatesViewMode)` copies. The four variants
 * had subtly different fallbacks, so this is the guard against "changed one trigger, forgot the
 * other three".
 */
class InputModePolicyTest {

    private fun resolve(
        mode: FloatingCandidatesMode,
        trigger: InputModeTrigger,
        currentVirtual: Boolean = true,
        systemWantsVirtual: Boolean = false,
        isTouchTool: Boolean = false,
    ): Boolean {
        var askedSystem = 0
        val result = InputModePolicy.resolve(
            mode, trigger, currentVirtual,
            { askedSystem++; systemWantsVirtual },
            isTouchTool,
        )
        // Only SystemDefault may query the system; the other modes used to short-circuit.
        if (mode != FloatingCandidatesMode.SystemDefault) {
            check(askedSystem == 0) { "$mode/$trigger must not query the system" }
        }
        return result
    }

    @Test
    fun disabledIsAlwaysVirtualAndNeverAsksSystem() {
        InputModeTrigger.entries.forEach { trigger ->
            assertTrue("Disabled/$trigger", resolve(FloatingCandidatesMode.Disabled, trigger, false))
        }
    }

    @Test
    fun systemDefaultStartAndKeyDownFollowSystemExactly() {
        listOf(InputModeTrigger.StartInputView, InputModeTrigger.KeyDown).forEach { trigger ->
            assertFalse(
                "SystemDefault/$trigger false",
                resolve(FloatingCandidatesMode.SystemDefault, trigger, true, false),
            )
            assertTrue(
                "SystemDefault/$trigger true",
                resolve(FloatingCandidatesMode.SystemDefault, trigger, false, true),
            )
        }
    }

    @Test
    fun systemDefaultViewClickedAndToolTypeKeepAVisibleKeyboard() {
        listOf(InputModeTrigger.ViewClicked, InputModeTrigger.ToolTypeUpdate).forEach { trigger ->
            assertTrue(
                "SystemDefault/$trigger preserves visible",
                resolve(FloatingCandidatesMode.SystemDefault, trigger, true, false),
            )
            assertFalse(
                "SystemDefault/$trigger follows system when hidden",
                resolve(FloatingCandidatesMode.SystemDefault, trigger, false, false),
            )
        }
    }

    @Test
    fun inputDeviceStartPreservesCurrentMode() {
        assertTrue(resolve(FloatingCandidatesMode.InputDevice, InputModeTrigger.StartInputView, true))
        assertFalse(resolve(FloatingCandidatesMode.InputDevice, InputModeTrigger.StartInputView, false))
    }

    @Test
    fun inputDeviceKeyDownGoesPhysicalAndViewClickedGoesVirtual() {
        assertFalse(resolve(FloatingCandidatesMode.InputDevice, InputModeTrigger.KeyDown, true))
        assertTrue(resolve(FloatingCandidatesMode.InputDevice, InputModeTrigger.ViewClicked, false))
    }

    @Test
    fun inputDeviceToolTypeOnlyTouchSwitchesToVirtual() {
        val trigger = InputModeTrigger.ToolTypeUpdate
        assertTrue(
            "finger",
            resolve(FloatingCandidatesMode.InputDevice, trigger, false, isTouchTool = true),
        )
        assertFalse(
            "hardware tool preserves physical",
            resolve(FloatingCandidatesMode.InputDevice, trigger, false),
        )
        assertTrue(
            "already virtual stays virtual",
            resolve(FloatingCandidatesMode.InputDevice, trigger, true),
        )
    }
}
