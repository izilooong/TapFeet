/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 TapFeet Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import org.fcitx.fcitx5.android.input.shortcut.ShortcutAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the key-profile declaration point ([HardwareKeyProfile]).
 *
 * These pin down the deletion test: if a profile loses one of its six facts, or a consumer
 * reintroduces its own String fallback, one of these goes red instead of silently shipping a
 * device with the wrong keycaps / wrong shortcut chords.
 */
class HardwareKeyProfileTest {

    @Test
    fun idsAreUniqueAndMatchTheFacade() {
        val ids = HardwareKeyProfiles.ids()
        assertEquals(HardwareKeyProfile.entries.map { it.id }, ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyProfileCarriesAllFacts() {
        HardwareKeyProfile.entries.forEach { profile ->
            assertTrue("${profile.name} id", profile.id.isNotBlank())
            assertTrue("${profile.name} labelRes", profile.labelRes != 0)
            assertEquals("${profile.name} bindings", 11, profile.bindings.size)
            assertEquals("${profile.name} first pick", "space", profile.bindings.first())
        }
        assertEquals(
            "two profiles share a display name",
            HardwareKeyProfile.entries.size,
            HardwareKeyProfile.entries.map { it.labelRes }.toSet().size,
        )
    }

    @Test
    fun everyPersistedIdResolvesBackToTheSameProfile() {
        HardwareKeyProfile.entries.forEach { profile ->
            assertSame(profile, HardwareKeyProfile.from(profile.id))
        }
    }

    @Test
    fun unknownIdHasOneConsistentFallbackAcrossConsumers() {
        val fallback = HardwareKeyProfile.BLACKBERRY
        assertSame(fallback, HardwareKeyProfile.from("no_such_device"))
        assertEquals(fallback.labelRes, HardwareKeyProfiles.labelResFor("no_such_device"))
        // 历史事故：键位落 blackberry、动作键却落 Titan Fn/Sym。回落必须只发生一次且全局一致。
        assertEquals(
            HardwareKeyProfiles.shortcutValuesFor(fallback.id),
            HardwareKeyProfiles.shortcutValuesFor("no_such_device"),
        )
    }

    @Test
    fun shortcutPresetsCoverEveryAction() {
        HardwareKeyProfile.entries.forEach { profile ->
            assertEquals(
                profile.name,
                ShortcutAction.entries.size,
                HardwareKeyProfiles.shortcutValuesFor(profile.id).size,
            )
        }
        assertTrue(
            "Passport has no modifier keys",
            HardwareKeyProfiles.shortcutValuesFor(HardwareKeyProfile.PASSPORT.id)
                .values.all { it.isEmpty() },
        )
    }

    @Test
    fun pinnedAndSharedSymbolMapsAreExplicitFacts() {
        assertEquals(
            listOf(HardwareKeyProfile.PASSPORT),
            HardwareKeyProfile.entries.filter { it.customKeyboardPinned },
        )
        // 同硬件同键帽，共表是有意声明，不是漏配。
        assertEquals(
            HardwareKeyProfile.TITAN2_ELITE.symbolMap,
            HardwareKeyProfile.TITAN2_ELITE_MOD.symbolMap,
        )
        assertNotEquals(
            HardwareKeyProfile.TT2.symbolMap,
            HardwareKeyProfile.TITAN2_ELITE.symbolMap,
        )
    }
}
