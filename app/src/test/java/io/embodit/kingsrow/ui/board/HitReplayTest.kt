package io.embodit.kingsrow.ui.board

import io.embodit.kingsrow.domain.GameConfig
import io.embodit.kingsrow.domain.GameFlow
import io.embodit.kingsrow.domain.MemoryStore
import io.embodit.kingsrow.domain.Phase
import io.embodit.kingsrow.domain.Position
import io.embodit.kingsrow.domain.Settings
import io.embodit.kingsrow.domain.Side
import io.embodit.kingsrow.domain.Sq
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hit → square replay (build.md「命中目标 → 格子必须有回放单测」, 斗兽棋 v0.1.3 lesson): real hits arrive on whatever
 * node the ray / gaze met — the visible marker, the square collider, a disc mesh deep below a piece's hit root, the
 * board slab. Every path the scene can produce must resolve to the right square (or to "nothing" for board / tray),
 * and the resolved square must drive GameFlow exactly like the debug taps do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitReplayTest {
    private fun sq(s: String) = Sq.parse(s)!!

    @Test fun everyHitPathResolvesToItsSquare() {
        val c3 = sq("c3")
        // hit entity first, then its parents (the order the template's resolveUp walks)
        val cases = listOf(
            listOf("mark:$c3", "sq:$c3", "boardRoot") to c3,                 // visible square marker (eye-hand highlight)
            listOf("sq:$c3", "boardRoot") to c3,                              // square collider edge
            listOf("piece:d0:body", "piece:d0", "pc:$c3", "piece") to c3,     // lower disc mesh of a man
            listOf("piece:d1:cap", "piece:d1", "pc:$c3", "piece") to c3,      // king's upper cap
            listOf("pc:$c3", "piece") to c3,                                  // the piece hit root itself
        )
        for ((path, want) in cases) assertEquals(path.toString(), want, BoardTargets.squareOf(BoardTargets.resolvePath(path)))
        assertEquals(BoardTarget.Board, BoardTargets.resolvePath(listOf("board", "boardRoot")))
        assertEquals(BoardTarget.Board, BoardTargets.resolvePath(listOf("trayHit:0", "tray:0")))
        assertNull(BoardTargets.squareOf(BoardTargets.resolvePath(listOf("board"))))
        assertNull(BoardTargets.resolvePath(listOf("slab", "boardRoot", "tiltRoot")))
        assertNull(BoardTargets.byName("pc:-1")) // pooled piece views hidden off the board never resolve
    }

    @Test fun everyDarkSquareMarkerResolves() {
        for (s in Sq.DARK) assertEquals(s, BoardTargets.squareOf(BoardTargets.resolvePath(listOf("mark:$s", "sq:$s"))))
    }

    @Test fun replayedHitsDriveTheGame() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val flow = GameFlow(TestScope(d), d, { testScheduler.currentTime }, MemoryStore(), { Settings(firstRunDone = true, coachDone = true) }, {}, {}, {})
        flow.setup(Position.setup("b:c3,a1;r:h8"), GameConfig(Side.BLACK, 1))
        fun hit(path: List<String>) {
            val s = BoardTargets.squareOf(BoardTargets.resolvePath(path))
            if (s != null) flow.tap(s) else flow.tapNothing()
        }
        hit(listOf("piece:d0:cap", "piece:d0", "pc:${sq("c3")}", "piece"))  // eye-hand on the piece's cap
        assertEquals(Phase.SELECTED, flow.state.value.phase)
        assertEquals(sq("c3"), flow.state.value.selected)
        hit(listOf("board", "boardRoot"))                                   // a pinch on the wood: cancel
        assertEquals(Phase.IDLE, flow.state.value.phase)
        hit(listOf("pc:${sq("c3")}"))
        hit(listOf("mark:${sq("d4")}", "sq:${sq("d4")}"))                   // pinch on the target marker: the move
        assertEquals(Phase.ANIMATING, flow.state.value.phase)
    }
}
