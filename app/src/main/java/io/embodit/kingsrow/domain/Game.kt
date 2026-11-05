package io.embodit.kingsrow.domain

/** What one side's tray holds: [score] = pieces it captured (the "吃了 N" card), [stack] = physical pieces of the
 *  other colour lying in it (a captured king is two pieces), [spares] = its own two spare pieces for crowning. */
data class Tray(val score: Int = 0, val stack: Int = 0, val spares: Int = 2)

enum class CrownSource { SPARE, CAPTIVE, FADE }

/**
 * A stable point of the game (between turns), kept per ply for undo and repetition. [noProgress] counts plies
 * without a capture or a crowning (80 plies = 40 moves each -> draw).
 */
data class Snapshot(
    val pos: Position,
    val trays: List<Tray> = listOf(Tray(), Tray()),
    val noProgress: Int = 0,
    val ply: Int = 0,
    val lastMove: Move? = null,
) {
    fun tray(side: Int): Tray = trays[side]

    companion object {
        fun start(): Snapshot = Snapshot(Position.initial())
    }
}

enum class EndReason { NO_PIECES, BLOCKED, RESIGN, FORTY_MOVES, REPETITION }

/** [winner] = Side.BLACK / Side.RED, or -1 for a draw. */
data class Result(val winner: Int, val reason: EndReason)

data class GameConfig(val human: Int, val level: Int) {
    val ai: Int get() = Side.other(human)
}

enum class SidePick { BLACK, RED, RANDOM }

enum class Language { SYSTEM, ZH, EN }

data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    val firstRunDone: Boolean = false,
    val coachDone: Boolean = false,
    val side: SidePick = SidePick.BLACK,
    val level: Int = 1,
)

/** Turn bookkeeping on top of [Rules]: applies a full move to a snapshot, updates trays, counters and crowning. */
object Turns {
    const val DRAW_PLIES = 80
    const val SHOW_COUNT_FROM = 60

    /** The piece used to crown [side]: its own spares first, then a captive of its colour in the other tray. */
    fun crownSource(trays: List<Tray>, side: Int): CrownSource = when {
        trays[side].spares > 0 -> CrownSource.SPARE
        trays[Side.other(side)].stack > 0 -> CrownSource.CAPTIVE
        else -> CrownSource.FADE
    }

    fun play(s: Snapshot, move: Move): Snapshot {
        val side = s.pos.toMove
        val next = Rules.apply(s.pos, move)
        val trays = s.trays.toMutableList()
        var t = trays[side]
        val physical = move.captured.fold(0) { acc, x -> acc + if (Piece.isKing(s.pos[x])) 2 else 1 }
        t = t.copy(score = t.score + move.captured.size, stack = t.stack + physical)
        trays[side] = t
        if (move.crowns) {
            when (crownSource(trays, side)) {
                CrownSource.SPARE -> trays[side] = trays[side].copy(spares = trays[side].spares - 1)
                CrownSource.CAPTIVE -> trays[Side.other(side)] = trays[Side.other(side)].copy(stack = trays[Side.other(side)].stack - 1)
                CrownSource.FADE -> Unit
            }
        }
        val progress = move.isCapture || move.crowns
        return Snapshot(next, trays, if (progress) 0 else s.noProgress + 1, s.ply + 1, move)
    }

    /** Trays part-way through a jump sequence: the captured pieces already sit in the jumper's tray. */
    fun partialTrays(s: Snapshot, captured: List<Int>): List<Tray> {
        val side = s.pos.toMove
        val physical = captured.fold(0) { acc, x -> acc + if (Piece.isKing(s.pos[x])) 2 else 1 }
        return s.trays.toMutableList().also { it[side] = it[side].copy(score = it[side].score + captured.size, stack = it[side].stack + physical) }
    }

    /** End of game after a finished turn, [history] includes [s] as its last element. */
    fun result(s: Snapshot, history: List<Snapshot>): Result? {
        val side = s.pos.toMove
        if (s.pos.count(side) == 0) return Result(Side.other(side), EndReason.NO_PIECES)
        if (Rules.legalMoves(s.pos).isEmpty()) return Result(Side.other(side), EndReason.BLOCKED)
        if (s.noProgress >= DRAW_PLIES) return Result(-1, EndReason.FORTY_MOVES)
        val key = s.pos.key
        if (history.count { it.pos.key == key } >= 3) return Result(-1, EndReason.REPETITION)
        return null
    }
}

/**
 * Animation timing shared by the flow (when to move on) and the scene (where things are): design animation.*.
 * A step list starts at [from]; jumps take 290 ms with a 170 ms rest between jumps, slides 220 ms; the captured
 * piece lifts when the jumper is over it (half way) and flies 240 ms into the tray. Reduced motion: 150 ms per step.
 */
object Timeline {
    const val SLIDE_MS = 220L
    const val JUMP_MS = 290L
    const val REST_MS = 170L
    const val CAPTURE_FLY_MS = 240L
    const val REDUCED_STEP_MS = 150L
    const val CROWN_DELAY_MS = 100L
    const val CROWN_FLY_MS = 300L
    const val CROWN_GLOW_MS = 400L
    const val LIFT_ANIM = 0.03f
    const val JUMP_ARC = 0.05f
    const val CAPTURE_LIFT = 0.02f

    fun stepStart(i: Int, jumps: Boolean, reduced: Boolean): Long = when {
        reduced -> i * REDUCED_STEP_MS
        jumps -> i * (JUMP_MS + REST_MS)
        else -> i * SLIDE_MS
    }

    fun stepDuration(jumps: Boolean, reduced: Boolean): Long = when {
        reduced -> REDUCED_STEP_MS
        jumps -> JUMP_MS
        else -> SLIDE_MS
    }

    fun captureLift(i: Int, reduced: Boolean): Long = if (reduced) stepStart(i, true, true) + REDUCED_STEP_MS else stepStart(i, true, false) + JUMP_MS / 2

    fun moveTotal(steps: Int, jumps: Boolean, reduced: Boolean): Long {
        if (steps == 0) return 0
        if (reduced) return steps * REDUCED_STEP_MS
        val last = stepStart(steps - 1, jumps, false)
        return if (jumps) maxOf(last + JUMP_MS, captureLift(steps - 1, false) + CAPTURE_FLY_MS) else last + SLIDE_MS
    }

    fun crownTotal(reduced: Boolean): Long = if (reduced) REDUCED_STEP_MS else CROWN_DELAY_MS + CROWN_FLY_MS + CROWN_GLOW_MS
}

/** A step sequence being animated: the piece on [from] in [before] walks [steps]; [captured] fly to the tray. */
data class MoveAnim(
    val before: Position,
    val traysBefore: List<Tray>,
    val side: Int,
    val from: Int,
    val steps: List<Int>,
    val captured: List<Int>,
    val startAt: Long,
    val reduced: Boolean,
) {
    val jumps: Boolean get() = captured.isNotEmpty()
    val total: Long get() = Timeline.moveTotal(steps.size, jumps, reduced)
}

data class CrownAnim(val square: Int, val side: Int, val source: CrownSource, val startAt: Long, val reduced: Boolean)

/** A jump sequence the human has started and must finish (S07 when it branches). */
data class Chain(val from: Int, val steps: List<Int>, val captured: List<Int>) {
    val at: Int get() = steps.lastOrNull() ?: from
}

enum class AlertKind { MUST_JUMP, STUCK, ILLEGAL, NOT_YOURS, NO_DRAG, BUSY }

data class Alert(val kind: AlertKind, val square: Int, val pulse: Set<Int>, val at: Long)

enum class ConfirmKind { RESIGN_OR_RESTART, EXIT }

/** Everything the board, the seat card and the window draw (one source, D38). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val config: GameConfig? = null,
    val snap: Snapshot = Snapshot.start(),
    val display: Position = snap.pos,
    val trays: List<Tray> = snap.trays,
    val selected: Int? = null,
    val slideTargets: Set<Int> = emptySet(),
    val jumpTargets: Set<Int> = emptySet(),
    val framed: Set<Int> = emptySet(),
    val chain: Chain? = null,
    val anim: MoveAnim? = null,
    val crown: CrownAnim? = null,
    val crownedAt: Int? = null,
    val alert: Alert? = null,
    val hintOn: Boolean = false,
    val hint: Move? = null,
    val result: Result? = null,
    val confirm: ConfirmKind? = null,
    val coachTurns: Int = 0,
    val restoredUntil: Long? = null,
    val canUndo: Boolean = false,
    val savedAvailable: Boolean = false,
    val movable: Set<Int> = emptySet(),
    val freeze: Long? = null,
) {
    val humansTurn: Boolean get() = config != null && result == null && snap.pos.toMove == config.human
    val toMove: Int get() = snap.pos.toMove
    val noProgressMoves: Int get() = snap.noProgress / 2
}

enum class Phase(val stateId: String) {
    LOADING("S00"), WELCOME("S01"), SETUP("S02"), IDLE("S03"), SELECTED("S04"), ANIMATING("S05"), THINKING("S06"),
    CHAIN("S07"), CROWNING("S17"), RESULT("S09"),
}
