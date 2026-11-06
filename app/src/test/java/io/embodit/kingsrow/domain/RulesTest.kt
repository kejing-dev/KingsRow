package io.embodit.kingsrow.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun sq(s: String) = Sq.parse(s)!!

    @Test fun perftMatchesPublicTable() {
        // design mustPass 1: depth 1-8 against the public English-draughts perft table (depth 8 = 845931)
        val expected = longArrayOf(7, 49, 302, 1469, 7361, 36768, 179740, 845931)
        val p = Position.initial()
        for (d in 1..8) assertEquals("perft $d", expected[d - 1], Rules.perft(p, d))
    }

    @Test fun a1IsDarkAndNearLeftBelongsToBlack() {
        assertTrue(Sq.dark(sq("a1")))
        assertEquals(Piece.BLACK_MAN, Position.initial()[sq("a1")])
        assertEquals(Piece.RED_MAN, Position.initial()[sq("h8")])
        assertEquals(32, Sq.DARK.size)
    }

    @Test fun jumpIsForced() {
        val p = Position.setup("b:c3,g3;r:d4")
        val moves = Rules.legalMoves(p)
        assertTrue(moves.all { it.isCapture })
        assertEquals(listOf(sq("e5")), moves.single().path)
    }

    @Test fun chainMustContinueAndBranches() {
        val p = Position.setup("b:c3;r:d4,d6,f6")
        val moves = Rules.legalMoves(p)
        assertEquals(2, moves.size)
        assertTrue(moves.all { it.path.size == 2 && it.captured.size == 2 })
        assertEquals(setOf(sq("c7"), sq("g7")), moves.map { it.to }.toSet())
    }

    @Test fun menDoNotJumpBackwardsKingsDo() {
        val man = Position.setup("b:e5;r:d4")
        assertTrue(Rules.legalMoves(man).none { it.isCapture })
        val king = Position.setup("B:e5;r:d4")
        assertEquals(sq("c3"), Rules.legalMoves(king).single().to)
    }

    @Test fun crowningEndsTheTurn() {
        // black man jumps b6 to land on c7? use: black d6 jumps c7 -> b8 crowns, though b8 could jump on as a king
        val p = Position.setup("b:d6;r:c7,a7")
        val m = Rules.legalMoves(p).single()
        assertTrue(m.crowns)
        assertEquals(sq("b8"), m.to)
        assertEquals(1, m.captured.size)
        val after = Rules.apply(p, m)
        assertEquals(Piece.BLACK_KING, after[sq("b8")])
    }

    @Test fun capturedPieceCannotBeJumpedTwice() {
        val p = Position.setup("B:c3;r:d4,f4,d6,f6")
        for (m in Rules.legalMoves(p)) assertEquals(m.captured.size, m.captured.toSet().size)
        assertEquals(2, Rules.legalMoves(p).maxOf { it.captured.size })
    }

    @Test fun noMovesLoses() {
        val p = Position.setup("b:a1;r:b2,c3", Side.BLACK)
        val s = Snapshot(p)
        assertEquals(Result(Side.RED, EndReason.BLOCKED), Turns.result(s, listOf(s)))
        val empty = Snapshot(Position.setup("r:b2", Side.BLACK))
        assertEquals(EndReason.NO_PIECES, Turns.result(empty, listOf(empty))!!.reason)
    }

    @Test fun fortyMoveDrawAndRepetition() {
        val p = Position.setup("B:a1;R:h8")
        val s = Snapshot(p, noProgress = 80)
        assertEquals(EndReason.FORTY_MOVES, Turns.result(s, listOf(s))!!.reason)
        val s2 = Snapshot(p)
        assertEquals(EndReason.REPETITION, Turns.result(s2, listOf(s2, s2, s2))!!.reason)
        assertNull(Turns.result(s2, listOf(s2, s2)))
    }

    @Test fun traysCountCapturesAndCrownUsesSpareFirst() {
        val s = Snapshot(Position.setup("b:d6;r:c7,a7"))
        val m = Rules.legalMoves(s.pos).single()
        val after = Turns.play(s, m)
        assertEquals(1, after.tray(Side.BLACK).score)
        assertEquals(1, after.tray(Side.BLACK).stack)
        assertEquals(1, after.tray(Side.BLACK).spares)
        assertEquals(0, after.noProgress)
    }

    @Test fun crownSourceFallsBackToCaptiveThenFade() {
        assertEquals(CrownSource.SPARE, Turns.crownSource(listOf(Tray(spares = 1), Tray()), Side.BLACK))
        assertEquals(CrownSource.CAPTIVE, Turns.crownSource(listOf(Tray(spares = 0), Tray(stack = 2)), Side.BLACK))
        assertEquals(CrownSource.FADE, Turns.crownSource(listOf(Tray(spares = 0), Tray(stack = 0)), Side.BLACK))
    }

    @Test fun partialChainRemovesJumpedPieces() {
        val p = Position.setup("b:c3;r:d4,d6,f6")
        val part = Rules.applyPartial(p, sq("c3"), listOf(sq("e5")), listOf(sq("d4")))
        assertEquals(Piece.EMPTY, part[sq("d4")])
        assertEquals(Piece.BLACK_MAN, part[sq("e5")])
        assertEquals(Side.BLACK, part.toMove)
    }

    @Test fun codecRoundTrip() {
        var s = Snapshot.start()
        val hist = arrayListOf(s)
        repeat(6) { s = Turns.play(s, Rules.legalMoves(s.pos).first()); hist += s }
        val g = SavedGame(GameConfig(Side.RED, 4), hist, Chain(9, listOf(27), listOf(18)), null, 2, true)
        val back = SaveCodec.decode(SaveCodec.encode(g))
        assertNotNull(back)
        assertEquals(g, back)
        assertNull(SaveCodec.decode("garbage"))
    }

    @Test fun notationAndParse() {
        val m = Rules.legalMoves(Position.setup("b:c3;r:d4,f6")).single()
        assertEquals("c3xe5xg7", m.notation)
        assertEquals(sq("c3") to listOf(sq("e5"), sq("g7")), Move.parse("c3xe5xg7"))
        assertFalse(Position.initial().key.isEmpty())
    }
}
