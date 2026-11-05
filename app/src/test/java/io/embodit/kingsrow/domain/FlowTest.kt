package io.embodit.kingsrow.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private fun sq(s: String) = Sq.parse(s)!!

    private class Rig(scope: TestScope) {
        var settings = Settings(firstRunDone = true, coachDone = true)
        val store = MemoryStore()
        val sounds = ArrayList<Sound>()
        val flow: GameFlow

        init {
            val d = StandardTestDispatcher(scope.testScheduler)
            flow = GameFlow(TestScope(d), d, { scope.testScheduler.currentTime }, store, { settings }, { settings = it }, { sounds += it }, { })
        }
    }

    @Test fun startGoesToWelcomeThenSetup() = runTest {
        val r = Rig(this)
        r.settings = Settings()
        r.flow.start()
        assertEquals(Phase.WELCOME, r.flow.state.value.phase)
        r.flow.welcomeDone()
        assertEquals(Phase.SETUP, r.flow.state.value.phase)
        assertTrue(r.settings.firstRunDone)
    }

    @Test fun humanMoveThenAiReplies() = runTest {
        val r = Rig(this)
        r.flow.newGame(SidePick.BLACK, 1)
        r.flow.tap(sq("c3"))
        assertEquals(Phase.SELECTED, r.flow.state.value.phase)
        assertEquals(setOf(sq("b4"), sq("d4")), r.flow.state.value.slideTargets)
        r.flow.tap(sq("d4"))
        assertEquals(Phase.ANIMATING, r.flow.state.value.phase)
        assertNotNull(r.store.game) // committed and saved before the animation
        advanceUntilIdle()
        val st = r.flow.state.value
        assertEquals(Phase.IDLE, st.phase)
        assertEquals(2, st.snap.ply)
        assertTrue(st.canUndo)
        r.flow.undo()
        advanceUntilIdle()
        assertEquals(0, r.flow.state.value.snap.ply)
    }

    @Test fun mustJumpAlertPulsesTheJumpers() = runTest {
        val r = Rig(this)
        r.flow.setup(Position.setup("b:c3,g3,a1;r:d4,h8"), GameConfig(Side.BLACK, 1))
        r.flow.tap(sq("g3"))
        val a = r.flow.state.value.alert
        assertEquals(AlertKind.MUST_JUMP, a!!.kind)
        assertEquals(setOf(sq("c3")), a.pulse)
        assertEquals(Phase.IDLE, r.flow.state.value.phase)
    }

    @Test fun forkStopsInChainThenFinishes() = runTest {
        val r = Rig(this)
        r.flow.setup(Position.setup("b:c3,a1;r:d4,d6,f6,h8"), GameConfig(Side.BLACK, 1))
        r.flow.tap(sq("c3")); r.flow.tap(sq("e5"))
        advanceUntilIdle()
        var st = r.flow.state.value
        assertEquals(Phase.CHAIN, st.phase)
        assertEquals(setOf(sq("c7"), sq("g7")), st.jumpTargets)
        assertEquals(1, st.trays[Side.BLACK].score)
        assertNotNull(r.store.game!!.chain)
        r.flow.tap(sq("g7"))
        advanceUntilIdle()
        st = r.flow.state.value
        assertEquals(2, st.snap.tray(Side.BLACK).score)
        assertTrue(st.snap.ply >= 1)
    }

    @Test fun backTurnFromChain() = runTest {
        val r = Rig(this)
        r.flow.setup(Position.setup("b:c3,a1;r:d4,d6,f6,h8"), GameConfig(Side.BLACK, 1))
        r.flow.tap(sq("c3")); r.flow.tap(sq("e5"))
        advanceUntilIdle()
        r.flow.undo()
        val st = r.flow.state.value
        assertEquals(Phase.IDLE, st.phase)
        assertEquals(Piece.BLACK_MAN, st.display[sq("c3")])
        assertEquals(Piece.RED_MAN, st.display[sq("d4")])
        assertNull(st.chain)
    }

    @Test fun uniqueChainAutoContinuesAndWins() = runTest {
        val r = Rig(this)
        r.flow.setup(Position.setup("b:c3;r:d4,f6"), GameConfig(Side.BLACK, 1))
        r.flow.tap(sq("c3")); r.flow.tap(sq("e5"))
        assertEquals(listOf(sq("e5"), sq("g7")), r.flow.state.value.anim!!.steps)
        advanceUntilIdle()
        val st = r.flow.state.value
        assertEquals(Phase.RESULT, st.phase)
        assertEquals(Result(Side.BLACK, EndReason.NO_PIECES), st.result)
        assertTrue(Sound.WIN in r.sounds)
    }

    @Test fun crowningAnimatesThenEndsTurn() = runTest {
        val r = Rig(this)
        r.flow.setup(Position.setup("b:c7,a1;r:h8,a5"), GameConfig(Side.BLACK, 1))
        r.flow.tap(sq("c7")); r.flow.tap(sq("d8"))
        advanceTimeByMs(this, 300)
        assertEquals(Phase.CROWNING, r.flow.state.value.phase)
        assertEquals(CrownSource.SPARE, r.flow.state.value.crown!!.source)
        advanceUntilIdle()
        assertEquals(Piece.BLACK_KING, r.flow.state.value.snap.pos[sq("d8")])
        assertTrue(Sound.CROWN in r.sounds)
    }

    @Test fun restoreMidChain() = runTest {
        val r = Rig(this)
        r.flow.setup(Position.setup("b:c3,a1;r:d4,d6,f6,h8"), GameConfig(Side.BLACK, 1))
        r.flow.tap(sq("c3")); r.flow.tap(sq("e5"))
        advanceUntilIdle()
        val r2 = Rig(this)
        r2.store.game = r.store.game
        r2.flow.start()
        assertEquals(Phase.CHAIN, r2.flow.state.value.phase)
        assertNotNull(r2.flow.state.value.restoredUntil)
    }

    @Test fun coachingCountsDownAndBlocksDrag() = runTest {
        val r = Rig(this)
        r.settings = r.settings.copy(coachDone = false)
        r.flow.newGame(SidePick.BLACK, 1)
        assertEquals(GameFlow.COACH_TURNS, r.flow.state.value.coachTurns)
        assertTrue(r.flow.state.value.movable.isNotEmpty())
        assertTrue(!r.flow.dragStart(sq("c3")))
    }

    private fun advanceTimeByMs(scope: TestScope, ms: Long) = scope.testScheduler.advanceTimeBy(ms).also { scope.testScheduler.runCurrent() }
}
