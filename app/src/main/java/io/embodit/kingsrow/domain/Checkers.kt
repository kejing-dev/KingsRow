package io.embodit.kingsrow.domain

/**
 * English / American checkers (design gameSpec.rules). Pure Kotlin, no Android.
 *
 * Squares are 0..63 = r * 8 + c, c = 0..7 left to right (files a..h), r = 0..7 from the FAR edge (rank 8) to the
 * NEAR edge (rank 1), exactly the design's squareCenter (c, r). Only dark squares are used: (c + r) odd, which makes
 * a1 (c 0, r 7) dark. Black sits on the near three rows and moves first; black men move toward r = 0 and crown
 * there, red men move toward r = 7.
 */
object Sq {
    fun of(c: Int, r: Int): Int = r * 8 + c
    fun col(i: Int): Int = i % 8
    fun row(i: Int): Int = i / 8
    fun dark(c: Int, r: Int): Boolean = (c + r) % 2 == 1
    fun dark(i: Int): Boolean = dark(col(i), row(i))
    fun inside(c: Int, r: Int): Boolean = c in 0..7 && r in 0..7

    /** Algebraic name, a1 = near left. */
    fun name(i: Int): String = "${'a' + col(i)}${8 - row(i)}"

    fun parse(s: String): Int? {
        val t = s.trim().lowercase()
        if (t.length != 2) return null
        val c = t[0] - 'a'
        val rank = t[1] - '0'
        if (c !in 0..7 || rank !in 1..8) return null
        return of(c, 8 - rank)
    }

    val DARK: List<Int> = (0 until 64).filter { dark(it) }
}

object Piece {
    const val EMPTY = 0
    const val BLACK_MAN = 1
    const val BLACK_KING = 2
    const val RED_MAN = 3
    const val RED_KING = 4

    fun side(p: Int): Int = when (p) { BLACK_MAN, BLACK_KING -> Side.BLACK; RED_MAN, RED_KING -> Side.RED; else -> -1 }
    fun isKing(p: Int): Boolean = p == BLACK_KING || p == RED_KING
    fun isMan(p: Int): Boolean = p == BLACK_MAN || p == RED_MAN
    fun man(side: Int): Int = if (side == Side.BLACK) BLACK_MAN else RED_MAN
    fun king(side: Int): Int = if (side == Side.BLACK) BLACK_KING else RED_KING
    fun char(p: Int): Char = when (p) { BLACK_MAN -> 'b'; BLACK_KING -> 'B'; RED_MAN -> 'r'; RED_KING -> 'R'; else -> '.' }
    fun fromChar(ch: Char): Int = when (ch) { 'b' -> BLACK_MAN; 'B' -> BLACK_KING; 'r' -> RED_MAN; 'R' -> RED_KING; else -> EMPTY }
}

object Side {
    const val BLACK = 0
    const val RED = 1
    fun other(s: Int): Int = 1 - s
}

/** An immutable position: 64 cells (light squares always empty) + side to move. */
class Position(private val cells: IntArray, val toMove: Int) {
    init { require(cells.size == 64) }

    operator fun get(i: Int): Int = cells[i]
    fun copyCells(): IntArray = cells.copyOf()

    fun count(side: Int): Int = cells.count { Piece.side(it) == side }
    fun kings(side: Int): Int = cells.count { Piece.side(it) == side && Piece.isKing(it) }
    fun men(side: Int): Int = cells.count { Piece.side(it) == side && Piece.isMan(it) }
    fun squaresOf(side: Int): List<Int> = Sq.DARK.filter { Piece.side(cells[it]) == side }

    /** Repetition key: the board plus the side to move. */
    val key: String by lazy { String(CharArray(32) { Piece.char(cells[Sq.DARK[it]]) }) + toMove }

    fun with(toMove: Int): Position = Position(cells, toMove)

    override fun equals(other: Any?): Boolean = other is Position && other.toMove == toMove && other.cells.contentEquals(cells)
    override fun hashCode(): Int = cells.contentHashCode() * 31 + toMove
    override fun toString(): String = key

    companion object {
        fun initial(): Position {
            val c = IntArray(64)
            for (i in Sq.DARK) {
                val r = Sq.row(i)
                if (r <= 2) c[i] = Piece.RED_MAN
                if (r >= 5) c[i] = Piece.BLACK_MAN
            }
            return Position(c, Side.BLACK)
        }

        fun empty(toMove: Int = Side.BLACK): Position = Position(IntArray(64), toMove)

        /** From [key] (32 dark-square chars + side digit). */
        fun fromKey(key: String): Position? {
            if (key.length != 33) return null
            val c = IntArray(64)
            for (k in 0 until 32) c[Sq.DARK[k]] = Piece.fromChar(key[k])
            val side = key[32].digitToIntOrNull()?.takeIf { it in 0..1 } ?: return null
            return Position(c, side)
        }

        /** Debug / test setup: "b:c3,e3;B:h8;r:d4;R:a1" + side. */
        fun setup(spec: String, toMove: Int = Side.BLACK): Position {
            val c = IntArray(64)
            for (group in spec.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                val (kind, squares) = group.split(":", limit = 2).let { it[0].trim() to it.getOrElse(1) { "" } }
                val piece = Piece.fromChar(kind.firstOrNull() ?: '.')
                for (s in squares.split(",")) Sq.parse(s)?.takeIf { Sq.dark(it) }?.let { c[it] = piece }
            }
            return Position(c, toMove)
        }
    }
}

/**
 * One full turn: [from] then every landing square in [path]; [captured] are the jumped squares in order (empty for
 * a slide). [crowns]: a man ends on the far row (a crowning ends the turn even if more jumps were possible).
 */
data class Move(val from: Int, val path: List<Int>, val captured: List<Int>, val crowns: Boolean) {
    val to: Int get() = path.last()
    val isCapture: Boolean get() = captured.isNotEmpty()

    /** "c3-d4" or "c3xe5xg7". */
    val notation: String get() = Sq.name(from) + path.joinToString("") { (if (isCapture) "x" else "-") + Sq.name(it) }

    companion object {
        fun parse(s: String): Pair<Int, List<Int>>? {
            val t = s.trim().lowercase()
            val parts = t.split('x', '-').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val sq = parts.map { Sq.parse(it) ?: return null }
            return sq.first() to sq.drop(1)
        }
    }
}

object Rules {
    /** Crowning row for a side's men. */
    fun crownRow(side: Int): Int = if (side == Side.BLACK) 0 else 7

    private val KING_DIRS = listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
    private val BLACK_DIRS = listOf(-1 to -1, 1 to -1)
    private val RED_DIRS = listOf(-1 to 1, 1 to 1)

    fun dirs(piece: Int): List<Pair<Int, Int>> = when (piece) {
        Piece.BLACK_MAN -> BLACK_DIRS
        Piece.RED_MAN -> RED_DIRS
        Piece.BLACK_KING, Piece.RED_KING -> KING_DIRS
        else -> emptyList()
    }

    /** Every legal move of the side to move; jumps are forced (design: 必吃 fixed on in v1). */
    fun legalMoves(pos: Position): List<Move> {
        val caps = captures(pos)
        if (caps.isNotEmpty()) return caps
        return slides(pos)
    }

    fun legalMovesFrom(pos: Position, sq: Int): List<Move> = legalMoves(pos).filter { it.from == sq }

    fun captures(pos: Position): List<Move> {
        val out = ArrayList<Move>()
        val side = pos.toMove
        for (sq in Sq.DARK) {
            val p = pos[sq]
            if (Piece.side(p) != side) continue
            capturesFrom(pos, sq, out)
        }
        return out
    }

    fun hasCapture(pos: Position): Boolean {
        val side = pos.toMove
        for (sq in Sq.DARK) {
            val p = pos[sq]
            if (Piece.side(p) != side) continue
            val c = Sq.col(sq); val r = Sq.row(sq)
            for ((dc, dr) in dirs(p)) {
                val mc = c + dc; val mr = r + dr; val lc = c + 2 * dc; val lr = r + 2 * dr
                if (!Sq.inside(lc, lr)) continue
                if (Piece.side(pos[Sq.of(mc, mr)]) == Side.other(side) && pos[Sq.of(lc, lr)] == Piece.EMPTY) return true
            }
        }
        return false
    }

    private fun capturesFrom(pos: Position, from: Int, out: MutableList<Move>) {
        val cells = pos.copyCells()
        val piece = cells[from]
        cells[from] = Piece.EMPTY // the jumper leaves its square (it may land back on it after a king loop)
        jump(cells, piece, from, from, ArrayList(), ArrayList(), out)
    }

    /** Captured pieces stay on the board until the turn ends: they block landings and can't be jumped twice. */
    private fun jump(cells: IntArray, piece: Int, from: Int, at: Int, path: ArrayList<Int>, captured: ArrayList<Int>, out: MutableList<Move>) {
        val side = Piece.side(piece)
        val c = Sq.col(at); val r = Sq.row(at)
        var any = false
        for ((dc, dr) in dirs(piece)) {
            val mc = c + dc; val mr = r + dr; val lc = c + 2 * dc; val lr = r + 2 * dr
            if (!Sq.inside(lc, lr)) continue
            val mid = Sq.of(mc, mr); val land = Sq.of(lc, lr)
            if (Piece.side(cells[mid]) != Side.other(side) || mid in captured || cells[land] != Piece.EMPTY) continue
            any = true
            path.add(land); captured.add(mid)
            if (Piece.isMan(piece) && lr == crownRow(side)) {
                out.add(Move(from, path.toList(), captured.toList(), crowns = true))
            } else {
                jump(cells, piece, from, land, path, captured, out)
            }
            path.removeAt(path.lastIndex); captured.removeAt(captured.lastIndex)
        }
        if (!any && path.isNotEmpty()) out.add(Move(from, path.toList(), captured.toList(), crowns = false))
    }

    fun slides(pos: Position): List<Move> {
        val out = ArrayList<Move>()
        val side = pos.toMove
        for (sq in Sq.DARK) {
            val p = pos[sq]
            if (Piece.side(p) != side) continue
            val c = Sq.col(sq); val r = Sq.row(sq)
            for ((dc, dr) in dirs(p)) {
                val tc = c + dc; val tr = r + dr
                if (!Sq.inside(tc, tr)) continue
                val to = Sq.of(tc, tr)
                if (pos[to] != Piece.EMPTY) continue
                out.add(Move(sq, listOf(to), emptyList(), Piece.isMan(p) && tr == crownRow(side)))
            }
        }
        return out
    }

    /** Can the piece on [sq] move at all (ignoring the forced-jump rule)? */
    fun canMoveIgnoringForce(pos: Position, sq: Int): Boolean {
        val p = pos[sq]
        val c = Sq.col(sq); val r = Sq.row(sq)
        for ((dc, dr) in dirs(p)) {
            val tc = c + dc; val tr = r + dr
            if (Sq.inside(tc, tr) && pos[Sq.of(tc, tr)] == Piece.EMPTY) return true
        }
        return false
    }

    fun apply(pos: Position, move: Move): Position {
        val cells = pos.copyCells()
        var piece = cells[move.from]
        cells[move.from] = Piece.EMPTY
        for (x in move.captured) cells[x] = Piece.EMPTY
        if (move.crowns) piece = Piece.king(Piece.side(piece))
        cells[move.to] = piece
        return Position(cells, Side.other(pos.toMove))
    }

    /**
     * The board part-way through a jump sequence: the jumper stands on the last square of [steps], the pieces it
     * jumped are gone (they are already in the tray). The side to move does not change.
     */
    fun applyPartial(pos: Position, from: Int, steps: List<Int>, captured: List<Int>): Position {
        if (steps.isEmpty()) return pos
        val cells = pos.copyCells()
        val piece = cells[from]
        cells[from] = Piece.EMPTY
        for (x in captured) cells[x] = Piece.EMPTY
        cells[steps.last()] = piece
        return Position(cells, pos.toMove)
    }

    /** perft: number of leaf move sequences of [depth] plies (design acceptance: 7 / 49 / 302 / 1469 / 7361 ...). */
    fun perft(pos: Position, depth: Int): Long {
        if (depth == 0) return 1
        val moves = legalMoves(pos)
        if (depth == 1) return moves.size.toLong()
        var n = 0L
        for (m in moves) n += perft(apply(pos, m), depth - 1)
        return n
    }
}
