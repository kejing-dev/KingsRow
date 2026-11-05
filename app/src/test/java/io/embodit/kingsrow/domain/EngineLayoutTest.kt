package io.embodit.kingsrow.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class EngineLayoutTest {
    private fun sq(s: String) = Sq.parse(s)!!

    @Test fun engineTakesTheWinningDoubleJump() {
        // design verifiedExample ① 送一个，吃两个: after red takes, black jumps two and red is out.
        val p = Position.setup("b:c3;r:d4,f6")
        val m = Engine().choose(p, Level.of(3))
        assertEquals("c3xe5xg7", m!!.notation)
    }

    @Test fun everyLevelReturnsALegalMove() {
        val p = Position.initial()
        for (l in 1..5) {
            val m = Engine(Random(l)).choose(p, Level.of(l))
            assertNotNull(m)
            assertTrue(m in Rules.legalMoves(p))
        }
    }

    @Test fun strongBeatsRandomMover() {
        // level 3 vs random legal moves, 6 games: the engine must not lose
        val rnd = Random(5)
        var engineWins = 0
        repeat(6) { g ->
            var s = Snapshot.start()
            val hist = arrayListOf(s)
            val engineSide = g % 2
            val e = Engine(Random(g))
            var result: Result? = null
            while (result == null && s.ply < 300) {
                val moves = Rules.legalMoves(s.pos)
                val m = if (s.pos.toMove == engineSide) e.choose(s.pos, Level(3, 4, 0, 1))!! else moves[rnd.nextInt(moves.size)]
                s = Turns.play(s, m); hist += s
                result = Turns.result(s, hist)
            }
            if (result?.winner == engineSide) engineWins++
            assertTrue("game $g lost to random: $result", result == null || result.winner != 1 - engineSide)
        }
        assertTrue("engine won $engineWins/6", engineWins >= 4)
    }

    @Test fun masterRespectsTimeLimit() {
        val t0 = System.currentTimeMillis()
        Engine().choose(Position.initial(), Level.of(5))
        assertTrue(System.currentTimeMillis() - t0 < 2500)
    }

    @Test fun boardGeometry() {
        // near edge on the floor at z = +0.28, far edge raised by 20°
        val nearBottom = Layout.toScene(V3(0f, -Layout.THICK, Layout.HALF))
        assertEquals(0f, nearBottom.y, 1e-4f)
        assertEquals(Layout.HALF, nearBottom.z, 1e-4f)
        val farTop = Layout.toScene(V3(0f, 0f, -Layout.HALF))
        assertEquals(0.215f, farTop.y, 0.005f)
        // a1 near-left, h8 far-right
        val a1 = Layout.toScene(Layout.square(sq("a1")))
        val h8 = Layout.toScene(Layout.square(sq("h8")))
        assertTrue(a1.x < 0 && a1.z > 0 && h8.x > 0 && h8.z < 0 && h8.y > a1.y)
        // round trip
        val p = V3(0.1f, 0.02f, -0.07f)
        val back = Layout.toLocal(Layout.toScene(p))
        assertTrue(abs(back.x - p.x) + abs(back.y - p.y) + abs(back.z - p.z) < 1e-4f)
    }

    @Test fun everythingFitsTheVolume() {
        val pts = listOf(V3(-Layout.HALF, 0f, -Layout.HALF), V3(Layout.HALF, 0f, -Layout.HALF), V3(-Layout.HALF, -Layout.THICK, Layout.HALF)).map(Layout::toScene) +
            listOf(Side.BLACK, Side.RED).flatMap { s -> listOf(Layout.toScene(Layout.trayCentre(s) + V3(Layout.TRAY_W / 2, 0.06f, -Layout.TRAY_L / 2))) } +
            Layout.corners(Layout.seatCard(), Layout.SEAT_W, Layout.SEAT_H) +
            Layout.toScene(Layout.square(sq("b8"), 2 * Layout.PIECE_H + Timeline.LIFT_ANIM + Timeline.JUMP_ARC))
        for (p in pts) {
            assertTrue("x $p", abs(p.x) <= Layout.VOLUME_W / 2)
            assertTrue("y $p", p.y in -0.001f..Layout.VOLUME_H)
            assertTrue("z $p", abs(p.z) <= Layout.VOLUME_D / 2)
        }
    }

    @Test fun d35CardsAreNotHidden() {
        val problems = Layout.occlusionProblems()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun nearestSquareSnapsWithinOneAndAHalf() {
        val c = Layout.square(sq("d4"))
        assertEquals(sq("d4"), Layout.nearestSquare(c + V3(0.02f, 0f, 0.01f)))
        assertEquals(null, Layout.nearestSquare(V3(0.5f, 0f, 0f)))
    }

    @Test fun levelGateOpensWhenLevelOrWithoutHead() {
        val g = LevelGate()
        g.ask("start", 0)
        assertEquals(null, g.step(10, 30f))
        assertEquals(null, g.step(100, 2f))
        assertEquals("start", g.step(450, 1f))
        g.ask("x", 1000)
        assertEquals(null, g.step(1000, null))
        assertEquals("x/nohead", g.step(2600, null))
    }

    @Test fun timelineTotals() {
        assertEquals(220L, Timeline.moveTotal(1, false, false))
        assertEquals(460L + 145L + 240L, Timeline.moveTotal(2, true, false))
        assertEquals(300L, Timeline.moveTotal(2, true, true))
    }
}

class WindowPolicyTest {
    @Test fun firstOpenAndReopenBothBesideBoard() {
        org.junit.Assert.assertEquals(WindowAnchor.RIGHT_OF_BOARD, WindowPolicy.anchor(reopen = false))
        org.junit.Assert.assertEquals(WindowAnchor.RIGHT_OF_BOARD, WindowPolicy.anchor(reopen = true)) // D44 corrected: reopen beside the board, never Placement.none()
    }

    @Test fun emulatorEyesAreChecked() {
        org.junit.Assert.assertTrue(Layout.eyes().any { it.first.startsWith("emu") })
    }
}
