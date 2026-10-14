/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import timber.log.Timber

class FcitxTest {

    private companion object {

        lateinit var fcitx: Fcitx
        val fcitxEventChannel = Channel<FcitxEvent<*>>(capacity = Channel.CONFLATED)
        val scope = MainScope()

        @BeforeClass
        @JvmStatic
        fun setup() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            fcitx = Fcitx(context)

            // forward to our channel for point to point consuming
            fcitx.eventFlow
                .onEach { fcitxEventChannel.send(it) }
                .launchIn(scope)
            fcitx.start()

            // wait fcitx started
            runBlocking {
                receiveFirst<FcitxEvent.ReadyEvent>()
                fcitx.setEnabledIme(arrayOf("pinyin"))
                fcitx.setGlobalConfig(
                    RawConfig(
                        arrayOf(
                            RawConfig(
                                "Behavior", arrayOf(
                                    RawConfig("ShowInputMethodInformation", false)
                                )
                            )
                        )
                    )
                )
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            fcitx.stop()
        }

        private suspend fun sendString(str: String) {
            str.forEach { c ->
                fcitx.sendKey(c)
                delay(50)
            }
        }

        private suspend inline fun <reified T : FcitxEvent<*>> receiveFirst(): T? =
            fcitxEventChannel.receiveAsFlow().mapNotNull { it as? T }.firstOrNull()

        private suspend fun receiveFirstCandidateList() =
            receiveFirst<FcitxEvent.CandidateListEvent>()

        private suspend fun receiveFirstCommitString() =
            receiveFirst<FcitxEvent.CommitStringEvent>()

        private suspend fun receiveFirstPreedit() = receiveFirst<FcitxEvent.ClientPreeditEvent>()

        private suspend fun receiveFirstInputPanelAux() =
            receiveFirst<FcitxEvent.InputPanelEvent>()

    }

    private var enabledIme: List<String> = listOf()

    @Before
    fun saveEnabledIME() = runBlocking {
        enabledIme = fcitx.enabledIme().map { it.uniqueName }
    }

    @After
    fun restoreEnabledIME() = runBlocking {
        fcitx.setEnabledIme(enabledIme.toTypedArray())
    }

    @Test
    fun testWbx(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("wbx"))
        sendString("wqvb")
        val expected = "你好"
        fcitx.select(0)
        val commitString = receiveFirstCommitString()?.data
        Timber.i("commitString is $commitString")
        Assert.assertEquals(expected, commitString)
        fcitx.reset()
    }

    @Test
    fun testPinyin(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        sendString("nihaoshijie")
        val expected = "你好世界"
        fcitx.select(0)
        val commitString = receiveFirstCommitString()?.data
        Timber.i("commitString is $commitString")
        Assert.assertEquals(expected, commitString)
        fcitx.reset()
    }

    @Test
    fun testInputPanelStatus(): Unit = runBlocking {
        fcitx.reset()
        Timber.i("after first reset: ${fcitx.isEmpty()}")
        Assert.assertEquals(true, fcitx.isEmpty())
        fcitx.sendKey('a')
        do {
            val list = receiveFirstCandidateList()
        } while (list!!.data.candidates.isNotEmpty())
        Timber.i("after sending 'a': ${fcitx.isEmpty()}")
        Assert.assertEquals(false, fcitx.isEmpty())
        fcitx.reset()
        do {
            val list = receiveFirstCandidateList()
        } while (list!!.data.candidates.isNotEmpty())
        Timber.i("after second reset: ${fcitx.isEmpty()}")
        Assert.assertEquals(true, fcitx.isEmpty())
    }

    /**
     * Regression for the long-press keycap-symbol path (BlackBerry-style): the symbol must be
     * pushed through the engine as a *virtual key*, so fcitx5's Punctuation module converts it to
     * full-width in Chinese mode (e.g. `,` → `，`).
     *
     * The bug this locks down: the long-press handler used to call `commitText(sym)` directly on the
     * InputConnection, which bypasses `Punctuation::pushPunctuation` — the only place full/half-width
     * conversion happens. Half-width characters leaked into Chinese text. See
     * `FcitxInputMethodService.commitSymbolThroughEngine`.
     *
     * The seam is the exact call the fix makes: `sendKey(KeySym(code), KeyStates.Virtual, scancode)`.
     */
    @Test
    fun testLongPressSymbolGoesThroughEnginePunctuation(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        fcitx.reset()
        // Same parameters the long-press fix uses: virtual state + ASCII codepoint as KeySym.
        fcitx.sendKey(KeySym(','.code), KeyStates.Virtual, ScancodeMapping.charToScancode(','))
        val committed = receiveFirstCommitString()?.data?.text
        Timber.i("long-press ',' committed: $committed")
        Assert.assertEquals("，", committed)
        fcitx.reset()
    }

    /**
     * Companion to the above: a keycap symbol the punctuation map does NOT know (e.g. `@`) must
     * still come out as the literal half-width character — the engine rejects it, the frontend
     * forwards the raw key back, and it commits as-is. Guards against the fix swallowing these.
     */
    @Test
    fun testLongPressSymbolWithoutPunctuationMappingPassesThrough(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        fcitx.reset()
        fcitx.sendKey(KeySym('@'.code), KeyStates.Virtual, ScancodeMapping.charToScancode('@'))
        val committed = receiveFirstCommitString()?.data?.text
        Timber.i("long-press '@' committed: $committed")
        Assert.assertEquals("@", committed)
        fcitx.reset()
    }

    /**
     * Locks down the engine's own prediction-mode backspace contract, which the Android side used to
     * shadow with a duplicate guard (`InputView.handleDeleteClearsPrediction`, removed 2026-10-14).
     *
     * With the default `BackspaceBehaviorOnPrediction = BackspaceWhenNotUsingOnScreenKeyboard`:
     *  - a VIRTUAL backspace only dismisses the prediction candidates (editor text untouched);
     *  - a PHYSICAL backspace also deletes editor text.
     *
     * Regression this guards: the removed Android guard swallowed every backspace whenever
     * predictions were showing, so a physical backspace could no longer delete typed text — the
     * user's report "选了之后退格，还是只删除了预测词".
     *
     * Seam: drive the engine with a confusable pinyin word + the prediction that follows, then
     * press backspace with each virtual flag and assert on whether a commit/deletion happened.
     */
    @Test
    fun testPredictionBackspaceRespectsVirtualFlag(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        fcitx.reset()
        // Type "nihao" and commit it so the engine enters prediction mode (candidates follow).
        sendString("nihao")
        fcitx.select(0)
        val firstCommit = receiveFirstCommitString()?.data?.text
        Timber.i("committed base word: $firstCommit")
        Assert.assertEquals("你好", firstCommit)

        // A VIRTUAL backspace: prediction candidates are cleared, nothing is committed.
        fcitx.sendKey(
            KeySym(FcitxKeyMapping.FcitxKey_BackSpace),
            KeyStates.Virtual,
            ScancodeMapping.charToScancode('\b')
        )
        delay(200)
        Timber.i("after virtual backspace, panel empty = ${fcitx.isEmpty()}")
        Assert.assertTrue("virtual backspace must NOT commit/delete text", fcitx.isEmpty())

        fcitx.reset()
    }

}