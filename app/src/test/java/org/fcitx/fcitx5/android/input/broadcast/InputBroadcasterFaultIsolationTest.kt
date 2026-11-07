/*
 * Regression test for the frozen-candidate-bar bug.
 *
 * Root cause (locked rule: all users are on physical keyboards, the candidate bar is
 * HorizontalCandidateComponent fed by InputBroadcaster): when any single
 * InputBroadcastReceiver threw inside the old `receivers.forEach { it.xxx(data) }`, the
 * exception aborted the forEach and starved every *sibling* receiver of that event — i.e.
 * the candidate bar (HorizontalCandidateComponent) stopped refreshing under high-frequency
 * typing while typing/selecting kept working, and only an IME restart (which re-arms the
 * collector on attach) temporarily restored it.
 *
 * The fix wraps each receiver dispatch in runCatching (see InputBroadcaster.dispatch). This
 * test asserts the two invariants the fix must hold:
 *   1. a throwing receiver never propagates out of onCandidateUpdate();
 *   2. sibling receivers still receive the event (so the candidate bar keeps updating).
 *
 * Note: removing the runCatching in dispatch makes this test fail (the exception escapes),
 * so it guards the fix. Timber needs a tree planted or its e() throws AssertionError in a
 * bare JVM test — we plant a silent tree in setUp().
 */
package org.fcitx.fcitx5.android.input.broadcast

import org.fcitx.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

class InputBroadcasterFaultIsolationTest {

    private lateinit var broadcaster: InputBroadcaster

    @Before
    fun setUp() {
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) = Unit
        })
        broadcaster = InputBroadcaster()
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `one receiver throwing does not starve siblings or propagate`() {
        val goodCalls = AtomicInteger(0)
        val badCalls = AtomicInteger(0)
        var receivedTotal: Int? = null

        val good = object : InputBroadcastReceiver {
            override fun onCandidateUpdate(data: CandidateListEvent.Data) {
                goodCalls.incrementAndGet()
                receivedTotal = data.total
            }
        }
        val bad = object : InputBroadcastReceiver {
            override fun onCandidateUpdate(data: CandidateListEvent.Data) {
                badCalls.incrementAndGet()
                throw RuntimeException("simulated render crash in candidate bar")
            }
        }
        broadcaster.addReceiver(good)
        broadcaster.addReceiver(bad)

        val data = CandidateListEvent.Data(total = 5, candidates = emptyArray())

        // Must not throw even though `bad` throws on every invocation.
        broadcaster.onCandidateUpdate(data)

        // Good receiver still got the event (candidate bar would keep updating).
        assertEquals(1, goodCalls.get())
        assertEquals(data.total, receivedTotal)
        // Bad one was still attempted (so the failure is logged, not silently swallowed-then-skipped).
        assertEquals(1, badCalls.get())
    }

    @Test
    fun `sibling receivers keep receiving under sustained bad-receiver failures`() {
        val goodCalls = AtomicInteger(0)
        val bad = object : InputBroadcastReceiver {
            override fun onCandidateUpdate(data: CandidateListEvent.Data) {
                throw RuntimeException("always throws")
            }
        }
        val good = object : InputBroadcastReceiver {
            override fun onCandidateUpdate(data: CandidateListEvent.Data) {
                goodCalls.incrementAndGet()
            }
        }
        broadcaster.addReceiver(bad)
        broadcaster.addReceiver(good)

        val data = CandidateListEvent.Data(total = 1, candidates = emptyArray())
        // 1000 high-frequency candidate updates; bad receiver throws every single time.
        repeat(1000) { broadcaster.onCandidateUpdate(data) }

        // The candidate bar (good receiver) must receive all 1000 events despite the
        // always-throwing sibling — this is exactly the "frozen bar under heavy typing" guard.
        assertEquals(1000, goodCalls.get())
    }

    @Test
    fun `removed receiver stops receiving events`() {
        val calls = AtomicInteger(0)
        val receiver = object : InputBroadcastReceiver {
            override fun onCandidateUpdate(data: CandidateListEvent.Data) {
                calls.incrementAndGet()
            }
        }
        val data = CandidateListEvent.Data(total = 1, candidates = emptyArray())
        broadcaster.addReceiver(receiver)
        broadcaster.onCandidateUpdate(data)
        broadcaster.removeReceiver(receiver)
        broadcaster.onCandidateUpdate(data)
        assertEquals(1, calls.get())
    }

    @Test
    fun `siblings keep receiving after the throwing receiver is removed`() {
        val goodCalls = AtomicInteger(0)
        val bad = object : InputBroadcastReceiver {
            override fun onCandidateUpdate(data: CandidateListEvent.Data) {
                throw RuntimeException("always throws")
            }
        }
        val good = object : InputBroadcastReceiver {
            override fun onCandidateUpdate(data: CandidateListEvent.Data) {
                goodCalls.incrementAndGet()
            }
        }
        val data = CandidateListEvent.Data(total = 1, candidates = emptyArray())
        broadcaster.addReceiver(bad)
        broadcaster.addReceiver(good)
        broadcaster.onCandidateUpdate(data)
        broadcaster.removeReceiver(bad)
        repeat(10) { broadcaster.onCandidateUpdate(data) }
        assertEquals(11, goodCalls.get())
    }
}
