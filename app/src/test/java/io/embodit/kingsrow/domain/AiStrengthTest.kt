package io.embodit.kingsrow.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Design mustPass 2 / 3 (acceptance.mustPass): five AI levels get monotonically stronger (higher vs lower, 20 games,
 * win rate >= 70 %, fixed seeds) and the starter level is beatable (starter vs a random-legal-move script, 50 games,
 * the random side wins 40-60 %).
 *
 * Determinism: the shipped levels 3-5 stop on a clock (300 / 800 / 1600 ms), which a fixed-seed test can't reproduce,
 * so the ladder uses each level's depth with no clock (3 = depth 4, 4 = depth 6, 5 = depth 10: about the depth the master
 * level reaches in its first second on the emulator, log "AI move … depth=9 … ms=822"). Colours alternate; the first
 * three AI plies of each game pick at random among moves within 20 of the best (the shipped opening rule), seeded.
 * Results are printed as "LADDER" / "STARTER" lines (copied into apps/kings-row/build/mustpass.md).
 */
class AiStrengthTest {
    private fun testLevel(id: Int): Level = when (id) {
        1, 2 -> Level.of(id)
        3 -> Level.of(3).copy(timeMs = 0)
        4 -> Level.of(4).copy(timeMs = 0)
        else -> Level.of(5).copy(depth = 10, timeMs = 0)
    }

    /** Plays one game; returns the winner side or -1 for a draw. */
    private fun play(black: (Position, Int) -> Move, red: (Position, Int) -> Move): Int {
        var s = Snapshot.start()
        val hist = arrayListOf(s)
        var result: Result? = null
        while (result == null && s.ply < 400) {
            val m = if (s.pos.toMove == Side.BLACK) black(s.pos, s.ply) else red(s.pos, s.ply)
            s = Turns.play(s, m); hist += s
            result = Turns.result(s, hist)
        }
        return result?.winner ?: -1
    }

    private fun ai(level: Level, seed: Int): (Position, Int) -> Move {
        val e = Engine(Random(seed))
        return { p, ply -> e.choose(p, level, openingRandom = ply < 6)!! }
    }

    private fun randomMover(seed: Int): (Position, Int) -> Move {
        val r = Random(seed)
        return { p, _ -> Rules.legalMoves(p).let { it[r.nextInt(it.size)] } }
    }

    @Test fun ladderHigherBeatsLower() {
        val report = StringBuilder()
        var ok = true
        for (hi in 2..5) {
            val lo = hi - 1
            var wins = 0; var draws = 0; var losses = 0
            repeat(20) { g ->
                val hiBlack = g % 2 == 0
                val a = ai(testLevel(hi), 1000 * hi + g)
                val b = ai(testLevel(lo), 2000 * hi + g)
                val w = if (hiBlack) play(a, b) else play(b, a)
                val hiSide = if (hiBlack) Side.BLACK else Side.RED
                when (w) { hiSide -> wins++; -1 -> draws++; else -> losses++ }
            }
            val rate = wins / 20.0
            report.append("LADDER L$hi vs L$lo: win $wins draw $draws loss $losses -> ${"%.0f".format(rate * 100)} %\n")
            if (rate < 0.70) ok = false
        }
        println(report)
        assertTrue(report.toString(), ok)
    }

    @Test fun starterIsBeatableByRandom() {
        var randomWins = 0; var draws = 0
        repeat(50) { g ->
            val starter = ai(Level.of(1), 300 + g)
            val rnd = randomMover(700 + g)
            val starterBlack = g % 2 == 0
            val w = if (starterBlack) play(starter, rnd) else play(rnd, starter)
            val rndSide = if (starterBlack) Side.RED else Side.BLACK
            if (w == rndSide) randomWins++ else if (w == -1) draws++
        }
        val rate = randomWins / 50.0
        println("STARTER random side wins $randomWins / 50 (draws $draws) -> ${"%.0f".format(rate * 100)} %")
        assertTrue("random side win rate $rate", rate in 0.40..0.60)
    }
}
