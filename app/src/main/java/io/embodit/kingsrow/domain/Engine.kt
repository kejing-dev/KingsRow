package io.embodit.kingsrow.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** The five AI levels (design gameSpec.ai.levels). */
data class Level(val id: Int, val depth: Int, val timeMs: Long, val randomTop: Int) {
    companion object {
        val ALL = listOf(
            Level(1, depth = 1, timeMs = 0, randomTop = 5),    // 入门 刚学会走
            Level(2, depth = 2, timeMs = 0, randomTop = 3),    // 初级 邻居大爷
            Level(3, depth = 4, timeMs = 300, randomTop = 1),  // 中级 棋摊常客
            Level(4, depth = 6, timeMs = 800, randomTop = 1),  // 高级 县里冠军
            Level(5, depth = 12, timeMs = 1600, randomTop = 1), // 大师 不眨眼的 (time first; design target <= 1.6 s average)
        )
        fun of(id: Int): Level = ALL[(id - 1).coerceIn(0, 4)]
        /** The hint uses the medium level (design gameSpec.hint). */
        val HINT = Level(3, depth = 4, timeMs = 300, randomTop = 1)
    }
}

/**
 * Negamax alpha-beta with iterative deepening, a capture extension (jumps are forced, so a position with a jump
 * pending is searched on until it is quiet), history ordering and a time limit (Xiangqi LocalEngine pattern).
 * [cancelled] is polled so the flow can stop a search on undo / new game / background.
 */
class Engine(private val random: Random = Random(7)) {
    private val history = HashMap<Long, Int>()
    private var deadline = Long.MAX_VALUE
    private var nodes = 0L
    private var stop = false
    private var cancelled: () -> Boolean = { false }
    var lastDepth = 0; private set
    var lastNodes = 0L; private set

    class Stop : RuntimeException(null, null, false, false)

    /**
     * Picks a move for the side to move. [openingRandom]: within the first plies the pick is random among moves
     * scoring within 20 of the best (design gameSpec.ai.opening).
     */
    fun choose(pos: Position, level: Level, openingRandom: Boolean = false, now: () -> Long = System::currentTimeMillis, cancelled: () -> Boolean = { false }): Move? {
        val moves = Rules.legalMoves(pos)
        if (moves.isEmpty()) return null
        if (moves.size == 1) return moves[0]
        this.cancelled = cancelled
        val scored = rootScores(pos, moves, level, now)
        val sorted = scored.sortedByDescending { it.second }
        val best = sorted.first().second
        val pool = when {
            level.randomTop > 1 -> sorted.take(level.randomTop)
            openingRandom -> sorted.filter { best - it.second <= 20 }
            else -> sorted.take(1)
        }
        return pool[random.nextInt(pool.size)].first
    }

    /** Scores every root move at the deepest fully finished iteration. */
    fun rootScores(pos: Position, moves: List<Move>, level: Level, now: () -> Long = System::currentTimeMillis): List<Pair<Move, Int>> {
        history.clear()
        nodes = 0
        stop = false
        val start = now()
        deadline = if (level.timeMs > 0) start + level.timeMs else Long.MAX_VALUE
        var result: List<Pair<Move, Int>> = moves.map { it to 0 }
        var order = moves
        lastDepth = 0
        for (d in 1..level.depth) {
            val scores = ArrayList<Pair<Move, Int>>()
            try {
                for (m in order) {
                    val s = -search(Rules.apply(pos, m), d - 1, -INF, INF, 1, now)
                    scores.add(m to s)
                }
            } catch (e: Stop) {
                break
            }
            result = scores
            lastDepth = d
            order = scores.sortedByDescending { it.second }.map { it.first }
            if (level.timeMs > 0 && now() - start > level.timeMs / 2) break // the next iteration would not finish
            if (scores.any { abs(it.second) > WIN - 200 }) break
        }
        lastNodes = nodes
        return result
    }

    private fun search(pos: Position, depth: Int, alphaIn: Int, beta: Int, ply: Int, now: () -> Long): Int {
        nodes++
        if ((nodes and 1023L) == 0L && (now() > deadline || cancelled())) throw Stop()
        val moves = Rules.legalMoves(pos)
        if (moves.isEmpty()) return -WIN + ply
        val forcing = moves[0].isCapture
        if (depth <= 0 && !forcing) return evaluate(pos)
        if (ply > 40) return evaluate(pos)
        var alpha = alphaIn
        val ordered = if (moves.size > 1) moves.sortedByDescending { history[key(it)] ?: 0 } else moves
        var best = -INF
        for (m in ordered) {
            val s = -search(Rules.apply(pos, m), if (forcing && depth <= 0) 0 else depth - 1, -beta, -alpha, ply + 1, now)
            if (s > best) best = s
            if (s > alpha) alpha = s
            if (alpha >= beta) {
                if (!m.isCapture) history[key(m)] = (history[key(m)] ?: 0) + depth * depth + 1
                break
            }
        }
        return best
    }

    private fun key(m: Move): Long = (m.from.toLong() shl 8) or m.to.toLong()

    companion object {
        const val INF = 1_000_000
        const val WIN = 100_000

        private val CENTER = setOf(Sq.of(3, 2), Sq.of(5, 2), Sq.of(2, 3), Sq.of(4, 3), Sq.of(3, 4), Sq.of(5, 4), Sq.of(2, 5), Sq.of(4, 5))

        /** Static evaluation from the side to move (design gameSpec.ai.eval). */
        fun evaluate(pos: Position): Int {
            val me = pos.toMove
            return sideScore(pos, me) - sideScore(pos, Side.other(me)) + chase(pos, me)
        }

        private fun sideScore(pos: Position, side: Int): Int {
            var s = 0
            val oppMen = pos.men(Side.other(side)) > 0
            val back = if (side == Side.BLACK) 7 else 0
            for (sq in Sq.DARK) {
                val p = pos[sq]
                if (Piece.side(p) != side) continue
                val r = Sq.row(sq)
                if (Piece.isMan(p)) {
                    s += 100
                    s += 3 * (if (side == Side.BLACK) 7 - r else r)
                    if (r == back && oppMen) s += 8
                } else {
                    s += 160
                    if (!Rules.canMoveIgnoringForce(pos, sq)) s -= 20
                }
                if (sq in CENTER) s += 3
                // mobility: free forward squares
                val c = Sq.col(sq)
                for ((dc, dr) in Rules.dirs(p)) {
                    val tc = c + dc; val tr = r + dr
                    if (Sq.inside(tc, tr) && pos[Sq.of(tc, tr)] == Piece.EMPTY) s += 2
                }
            }
            return s
        }

        /** Endgame (<= 4 pieces each, kings on board): the side ahead wants its kings close to the enemy. */
        private fun chase(pos: Position, me: Int): Int {
            val mine = pos.count(me); val theirs = pos.count(Side.other(me))
            if (mine > 4 || theirs > 4 || mine == theirs) return 0
            val leader = if (mine > theirs) me else Side.other(me)
            val kings = pos.squaresOf(leader).filter { Piece.isKing(pos[it]) }
            if (kings.isEmpty()) return 0
            val targets = pos.squaresOf(Side.other(leader))
            if (targets.isEmpty()) return 0
            var total = 0
            for (k in kings) {
                var dmin = 99
                for (t in targets) dmin = min(dmin, max(abs(Sq.col(k) - Sq.col(t)), abs(Sq.row(k) - Sq.row(t))))
                total += (8 - dmin) * 3
            }
            return if (leader == me) total else -total
        }
    }
}
