package io.embodit.kingsrow.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class Sound { SELECT, SLIDE, JUMP, TRAY, CROWN, BUMP, WIN, LOSE }

/**
 * Turn sequencing, AI timing, undo, hint, persistence (Cloud Air GameFlowUseCase pattern). Results are committed
 * (and saved) before anything moves; the board only draws [PlayState] (anim / crown carry their start time), and
 * this class moves on after the timeline's duration by itself, so a board that never reports can't stall a game.
 * The board, the window and the debug receiver all call the same entry points (tap / drag / undo / ...).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit,
    private val onSound: (Sound) -> Unit,
    private val log: (String) -> Unit,
) {
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    private var history: List<Snapshot> = listOf(Snapshot.start())
    private var turnMoves: List<Move> = emptyList()
    private var aiJob: Job? = null
    private var hintJob: Job? = null
    private var stepJob: Job? = null
    private val sideJobs = ArrayList<Job>()
    private var pending: (() -> Unit)? = null
    private var aiPlies = 0
    var aiSeed: Int = 7
        set(v) { field = v; engine = Engine(Random(v)) }
    private var engine = Engine(Random(aiSeed))
    private val hintEngine = Engine(Random(3))
    var holdThinking = false
    var minThinkMs = 400L

    companion object {
        const val ALERT_MS = 3000L
        const val RESTORED_MS = 2000L
        const val COACH_TURNS = 3
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        val saved = store.load()
        if (saved != null && saved.result == null && saved.history.isNotEmpty()) {
            restore(saved)
            return
        }
        if (saved != null) store.clear()
        _state.value = PlayState(phase = if (settings().firstRunDone) Phase.SETUP else Phase.WELCOME)
        log("GAME start phase=${s.phase.stateId}")
    }

    private fun restore(g: SavedGame) {
        history = g.history.takeLast(120)
        val snap = history.last()
        turnMoves = Rules.legalMoves(snap.pos)
        _state.value = PlayState(
            phase = Phase.IDLE, config = g.config, snap = snap, display = snap.pos, trays = snap.trays,
            hintOn = g.hintOn, coachTurns = g.coachTurns, restoredUntil = clock() + RESTORED_MS,
        )
        val chain = g.chain
        if (chain != null && snap.pos.toMove == g.config.human && validChain(chain)) {
            log("GAME restored chain at ${Sq.name(chain.at)} S16 -> S07")
            enterChain(chain)
        } else {
            log("GAME restored ply=${snap.ply} S16")
            beginTurn()
        }
        scope.launch { delay(RESTORED_MS); _state.value = s.copy(restoredUntil = null) }
    }

    private fun validChain(c: Chain): Boolean = turnMoves.any { m -> m.from == c.from && m.path.size > c.steps.size && m.path.take(c.steps.size) == c.steps }

    fun welcomeDone() {
        onSettings(settings().copy(firstRunDone = true))
        if (s.phase == Phase.WELCOME) _state.value = s.copy(phase = Phase.SETUP)
        log("WELCOME done -> S02")
    }

    fun showWelcome() {
        cancelJobs()
        _state.value = s.copy(phase = Phase.WELCOME)
    }

    fun toMenu() {
        cancelJobs()
        if (s.result != null) store.clear()
        val keep = s.config != null && s.result == null
        _state.value = PlayState(phase = Phase.SETUP, savedAvailable = keep, config = if (keep) s.config else null,
            snap = if (keep) s.snap else Snapshot.start(), display = if (keep) s.snap.pos else Position.initial(),
            trays = if (keep) s.snap.trays else listOf(Tray(), Tray()))
        log("MENU S02 saved=$keep")
    }

    /** [继续上一盘] on the New page. */
    fun continueSaved(): Boolean {
        val g = store.load() ?: return false
        if (g.result != null) return false
        cancelJobs()
        restore(g)
        return true
    }

    fun newGame(pick: SidePick, level: Int) {
        cancelJobs()
        val human = when (pick) { SidePick.BLACK -> Side.BLACK; SidePick.RED -> Side.RED; SidePick.RANDOM -> if (Random.nextBoolean()) Side.BLACK else Side.RED }
        onSettings(settings().copy(side = pick, level = level.coerceIn(1, 5)))
        val cfg = GameConfig(human, level.coerceIn(1, 5))
        history = listOf(Snapshot.start())
        aiPlies = 0
        val coach = if (settings().coachDone) 0 else COACH_TURNS
        _state.value = PlayState(phase = Phase.IDLE, config = cfg, coachTurns = coach, hintOn = s.hintOn, freeze = s.freeze)
        log("GAME new human=${if (human == Side.BLACK) "black" else "red"} level=${cfg.level} coach=$coach")
        save()
        beginTurn()
    }

    fun again() {
        val cfg = s.config ?: return toMenu()
        newGame(if (cfg.human == Side.BLACK) SidePick.BLACK else SidePick.RED, cfg.level)
    }

    /** Debug / tests: replace the position (config kept, or AI level 2 with the human on Black). */
    fun setup(pos: Position, cfg: GameConfig? = null) {
        cancelJobs()
        val config = cfg ?: s.config ?: GameConfig(Side.BLACK, 2)
        history = listOf(Snapshot(pos))
        _state.value = PlayState(phase = Phase.IDLE, config = config, snap = history.last(), display = pos, trays = history.last().trays, hintOn = s.hintOn, freeze = s.freeze)
        log("SETUP ${pos.key}")
        save()
        beginTurn()
    }

    fun onBackground() {
        aiJob?.cancel(); aiJob = null
        hintJob?.cancel(); hintJob = null
        pending?.let { p -> stepJob?.cancel(); pending = null; p() }
        // The AI turn restarts on the way back (onForeground); everything is saved already.
        save()
        log("GAME background")
    }

    fun onForeground() {
        if (s.phase == Phase.THINKING && aiJob == null) startAi()
    }

    private fun cancelJobs() {
        aiJob?.cancel(); aiJob = null
        hintJob?.cancel(); hintJob = null
        stepJob?.cancel(); stepJob = null
        pending = null
        sideJobs.forEach { it.cancel() }; sideJobs.clear()
    }

    // ------------------------------------------------------------------ turns

    private fun beginTurn() {
        val snap = history.last()
        val cfg = s.config ?: return
        val result = Turns.result(snap, history)
        turnMoves = if (result == null) Rules.legalMoves(snap.pos) else emptyList()
        val base = s.copy(
            snap = snap, display = snap.pos, trays = snap.trays, selected = null, slideTargets = emptySet(), jumpTargets = emptySet(),
            framed = emptySet(), chain = null, anim = null, crown = null, hint = null, canUndo = canUndo(), confirm = null,
            movable = if (result == null && snap.pos.toMove == cfg.human) turnMoves.map { it.from }.toSet() else emptySet(),
        )
        if (result != null) {
            _state.value = base.copy(phase = Phase.RESULT, result = result)
            store.save(saved(result))
            onSound(if (result.winner == cfg.human) Sound.WIN else Sound.LOSE)
            log("RESULT winner=${result.winner} reason=${result.reason} ply=${snap.ply} S09")
            return
        }
        if (snap.pos.toMove == cfg.human) {
            _state.value = base.copy(phase = Phase.IDLE, result = null)
            log("TURN human ${turnMoves.size} moves${if (turnMoves.firstOrNull()?.isCapture == true) " (must jump)" else ""} S03")
            if (s.hintOn) computeHint()
        } else {
            _state.value = base.copy(phase = Phase.THINKING, result = null)
            startAi()
        }
    }

    private fun canUndo(): Boolean {
        val cfg = s.config ?: return false
        return (1 until history.size).any { history[it - 1].pos.toMove == cfg.human }
    }

    private fun startAi() {
        val cfg = s.config ?: return
        val snap = history.last()
        val level = Level.of(cfg.level)
        val opening = aiPlies < 3
        aiJob?.cancel()
        aiJob = scope.launch {
            val t0 = clock()
            log("AI think level=${cfg.level} S06")
            val move = withContext(engineDispatcher) { engine.choose(snap.pos, level, openingRandom = opening, cancelled = { !isActive }) }
            val wait = minThinkMs - (clock() - t0)
            if (wait > 0) delay(wait)
            while (holdThinking) delay(100)
            if (!isActive || history.last() !== snap) return@launch
            aiJob = null
            if (move == null) { beginTurn(); return@launch }
            aiPlies++
            log("AI move ${move.notation} depth=${engine.lastDepth} nodes=${engine.lastNodes} ms=${clock() - t0}")
            commit(move, fromChain = null)
        }
    }

    // ------------------------------------------------------------------ input

    /** A pinch / trigger on a dark square or a piece (both name the square). */
    fun tap(sq: Int) {
        val st = s
        if (st.confirm != null) return
        when (st.phase) {
            Phase.IDLE, Phase.SELECTED -> tapIdle(sq)
            Phase.CHAIN -> if (sq in st.jumpTargets) step(sq) else log("TAP ${Sq.name(sq)} ignored in chain")
            // not silent (D44「点了没反应是阻断」): while a move plays or the AI thinks, say so
            Phase.ANIMATING, Phase.THINKING, Phase.CROWNING -> { alert(AlertKind.BUSY, sq, emptySet()); log("TAP ${Sq.name(sq)} busy phase=${st.phase.stateId}") }
            else -> log("TAP ${Sq.name(sq)} ignored phase=${st.phase.stateId}")
        }
    }

    /** A pinch on nothing that belongs to the board (light square, slab, air): cancels a selection. */
    fun tapNothing() {
        if (s.phase == Phase.SELECTED) { deselect(); log("TAP nothing -> S03") }
    }

    private fun tapIdle(sq: Int) {
        val st = s
        val cfg = st.config ?: return
        if (!st.humansTurn) return
        val piece = st.snap.pos[sq]
        val sel = st.selected
        if (sel != null && (sq in st.slideTargets || sq in st.jumpTargets)) { step(sq); return }
        if (Piece.side(piece) == cfg.human) {
            if (sq == sel) { deselect(); onSound(Sound.SELECT); log("DESELECT ${Sq.name(sq)} S03"); return }
            val from = turnMoves.filter { it.from == sq }
            if (from.isEmpty()) {
                val jumpers = turnMoves.filter { it.isCapture }.map { it.from }.toSet()
                val kind = if (jumpers.isNotEmpty()) AlertKind.MUST_JUMP else AlertKind.STUCK
                alert(kind, sq, if (kind == AlertKind.MUST_JUMP) jumpers else emptySet())
                deselect()
                log("ALERT ${kind.name} at ${Sq.name(sq)} jumpers=${jumpers.joinToString { Sq.name(it) }} S08")
                return
            }
            select(sq, from)
            return
        }
        if (sel != null) {
            alert(AlertKind.ILLEGAL, sq, emptySet())
            log("ILLEGAL ${Sq.name(sq)} stay S04")
        } else if (piece != Piece.EMPTY) {
            alert(AlertKind.NOT_YOURS, sq, emptySet())
            log("NOT_YOURS ${Sq.name(sq)}")
        }
    }

    private fun select(sq: Int, from: List<Move>) {
        val jumps = from.first().isCapture
        _state.value = s.copy(
            phase = Phase.SELECTED, selected = sq,
            slideTargets = if (jumps) emptySet() else from.map { it.path[0] }.toSet(),
            jumpTargets = if (jumps) from.map { it.path[0] }.toSet() else emptySet(),
            framed = if (jumps) from.map { it.captured[0] }.toSet() else emptySet(),
            alert = null,
        )
        onSound(Sound.SELECT)
        log("SELECT ${Sq.name(sq)} targets=${(s.slideTargets + s.jumpTargets).joinToString { Sq.name(it) }} S04")
    }

    private fun deselect() {
        _state.value = s.copy(phase = Phase.IDLE, selected = null, slideTargets = emptySet(), jumpTargets = emptySet(), framed = emptySet())
    }

    private fun alert(kind: AlertKind, sq: Int, pulse: Set<Int>) {
        val a = Alert(kind, sq, pulse, clock())
        _state.value = s.copy(alert = a)
        onSound(Sound.BUMP)
        sideJobs += scope.launch { delay(ALERT_MS); if (s.alert === a) _state.value = s.copy(alert = null) }
    }

    /** Drag start on a piece: selects it like a tap (no drag in the first-game coaching). */
    fun dragStart(sq: Int): Boolean {
        if (s.coachTurns > 0) {
            // first game: taps only (design input.drag); a drag gets a sentence instead of nothing
            if (s.humansTurn && s.phase in setOf(Phase.IDLE, Phase.SELECTED)) alert(AlertKind.NO_DRAG, sq, emptySet())
            log("DRAG off during coaching")
            return false
        }
        if (s.phase !in setOf(Phase.IDLE, Phase.SELECTED) || !s.humansTurn) return false
        if (s.selected != sq) tapIdle(sq)
        return s.selected == sq
    }

    /** Drag released over [sq] (nearest dark square within 1.5 squares, null = spring back). */
    fun dragEnd(sq: Int?) {
        if (s.phase != Phase.SELECTED) return
        if (sq != null && (sq in s.slideTargets || sq in s.jumpTargets)) { log("DROP ${Sq.name(sq)}"); step(sq) }
        else { log("DROP back"); onSound(Sound.BUMP) }
    }

    /** The human picked [target]: extend the jump while there is only one way on, then animate. */
    private fun step(target: Int) {
        val st = s
        val turnStart = history.last()
        val base = st.chain ?: Chain(st.selected ?: return, emptyList(), emptyList())
        var prefix = base.steps + target
        var cands = turnMoves.filter { m -> m.from == base.from && m.path.size >= prefix.size && m.path.take(prefix.size) == prefix }
        if (cands.isEmpty()) return
        while (cands.none { it.path.size == prefix.size }) {
            val next = cands.map { it.path[prefix.size] }.distinct()
            if (next.size != 1) break
            prefix = prefix + next[0]
            cands = cands.filter { it.path.take(prefix.size) == prefix }
        }
        val full = cands.firstOrNull { it.path == prefix }
        if (full != null) {
            commit(full, fromChain = base)
            return
        }
        // A branch: animate to the fork, then S07.
        val captured = cands.first().captured.take(prefix.size)
        val newSteps = prefix.drop(base.steps.size)
        val newCaptured = captured.drop(base.captured.size)
        val chain = Chain(base.from, prefix, captured)
        val reduced = settings().reducedMotion
        val anim = MoveAnim(st.display, st.trays, turnStart.pos.toMove, base.at, newSteps, newCaptured, clock(), reduced)
        store.save(saved(null, chain))
        _state.value = st.copy(phase = Phase.ANIMATING, anim = anim, selected = null, slideTargets = emptySet(), jumpTargets = emptySet(),
            framed = emptySet(), alert = null, hint = null, chain = chain)
        log("CHAIN step ${newSteps.joinToString { Sq.name(it) }} fork S05")
        playSounds(anim)
        after(anim.total) { enterChain(chain) }
    }

    private fun enterChain(chain: Chain) {
        val turnStart = history.last()
        val cands = turnMoves.filter { m -> m.from == chain.from && m.path.size > chain.steps.size && m.path.take(chain.steps.size) == chain.steps }
        _state.value = s.copy(
            phase = Phase.CHAIN, chain = chain, anim = null,
            display = Rules.applyPartial(turnStart.pos, chain.from, chain.steps, chain.captured),
            trays = Turns.partialTrays(turnStart, chain.captured),
            jumpTargets = cands.map { it.path[chain.steps.size] }.toSet(),
            framed = cands.map { it.captured[chain.steps.size] }.toSet(),
            selected = chain.at, slideTargets = emptySet(), canUndo = true, movable = emptySet(),
        )
        log("CHAIN fork at ${Sq.name(chain.at)} options=${s.jumpTargets.joinToString { Sq.name(it) }} S07")
    }

    /** Commits a whole move (human or AI), saves, then animates the part not yet shown. */
    private fun commit(move: Move, fromChain: Chain?) {
        val st = s
        val turnStart = history.last()
        val side = turnStart.pos.toMove
        val crownSource = Turns.crownSource(Turns.partialTrays(turnStart, move.captured), side)
        val after = Turns.play(turnStart, move)
        history = (history + after).takeLast(120)
        val done = fromChain?.steps?.size ?: 0
        val reduced = settings().reducedMotion
        val anim = MoveAnim(st.display, st.trays, side, if (done == 0) move.from else move.path[done - 1], move.path.drop(done), move.captured.drop(done), clock(), reduced)
        store.save(saved(null))
        _state.value = st.copy(phase = Phase.ANIMATING, snap = after, anim = anim, selected = null, slideTargets = emptySet(), jumpTargets = emptySet(),
            framed = emptySet(), chain = null, alert = null, hint = null, movable = emptySet(), canUndo = false)
        log("MOVE ${move.notation}${if (move.crowns) " crowns" else ""} S05")
        playSounds(anim)
        after(anim.total) { finishMove(move, side, crownSource) }
    }

    private fun finishMove(move: Move, side: Int, source: CrownSource) {
        val snap = history.last()
        _state.value = s.copy(display = snap.pos, trays = snap.trays, anim = null)
        if (move.crowns) {
            val reduced = settings().reducedMotion
            val c = CrownAnim(move.to, side, source, clock(), reduced)
            _state.value = s.copy(phase = Phase.CROWNING, crown = c, crownedAt = move.to)
            log("CROWN ${Sq.name(move.to)} source=${source.name} S17")
            sideJobs += scope.launch { delay(if (reduced) 0 else Timeline.CROWN_DELAY_MS + Timeline.CROWN_FLY_MS); onSound(Sound.CROWN) }
            after(Timeline.crownTotal(reduced)) {
                _state.value = s.copy(crown = null)
                endTurn(side)
                sideJobs += scope.launch { delay(1600); _state.value = s.copy(crownedAt = null) }
            }
        } else endTurn(side)
    }

    private fun endTurn(side: Int) {
        val cfg = s.config
        if (cfg != null && side == cfg.human && s.coachTurns > 0) {
            val left = s.coachTurns - 1
            _state.value = s.copy(coachTurns = left)
            if (left == 0) onSettings(settings().copy(coachDone = true))
        }
        save()
        beginTurn()
    }

    /** Runs [then] after [ms] (waits while a debug freeze holds the timeline); onBackground runs it at once. */
    private fun after(ms: Long, then: () -> Unit) {
        stepJob?.cancel()
        pending = then
        stepJob = scope.launch {
            delay(ms)
            while (s.freeze != null) delay(50)
            if (pending === then) { pending = null; then() }
        }
    }

    private fun playSounds(a: MoveAnim) {
        for (i in a.steps.indices) {
            // the sound plays on the frame the piece lands (D51), not when it lifts off
            val at = Timeline.stepStart(i, a.jumps, a.reduced) + Timeline.stepDuration(a.jumps, a.reduced)
            sideJobs += scope.launch { delay(at); onSound(if (a.jumps) Sound.JUMP else Sound.SLIDE) }
            if (a.jumps) sideJobs += scope.launch { delay(Timeline.captureLift(i, a.reduced) + if (a.reduced) 0 else Timeline.CAPTURE_FLY_MS); onSound(Sound.TRAY) }
        }
        sideJobs.removeAll { !it.isActive }
    }

    // ------------------------------------------------------------------ buttons

    /** [悔一步]: back a whole round (the AI's reply too); in a chain it's [退回本回合]. */
    fun undo() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.CHAIN) { backTurn(); return }
        if (st.phase !in setOf(Phase.IDLE, Phase.SELECTED, Phase.THINKING, Phase.RESULT)) return
        if (!canUndo()) return
        cancelJobs()
        var h = history
        while (h.size > 1) {
            val prev = h[h.size - 2]
            h = h.dropLast(1)
            if (prev.pos.toMove == cfg.human) break
        }
        history = h
        aiPlies = maxOf(0, aiPlies - 1)
        _state.value = s.copy(result = null, alert = null)
        log("UNDO -> ply ${history.last().ply}")
        save()
        beginTurn()
    }

    /** [退回本回合]: the jump sequence in flight goes back to the start of the turn. */
    fun backTurn() {
        if (s.phase != Phase.CHAIN) return
        log("BACKTURN S07 -> S03")
        _state.value = s.copy(chain = null)
        save()
        beginTurn()
    }

    fun setHint(on: Boolean) {
        _state.value = s.copy(hintOn = on, hint = if (on) s.hint else null)
        log("HINT ${if (on) "on" else "off"}")
        if (on && s.humansTurn && s.phase in setOf(Phase.IDLE, Phase.SELECTED)) computeHint() else hintJob?.cancel()
        save()
    }

    private fun computeHint() {
        val snap = history.last()
        hintJob?.cancel()
        hintJob = scope.launch {
            val m = withContext(engineDispatcher) { hintEngine.choose(snap.pos, Level.HINT, cancelled = { !isActive }) }
            if (isActive && history.last() === snap && s.hintOn && s.humansTurn) {
                _state.value = s.copy(hint = m)
                log("HINT ${m?.notation}")
            }
        }
    }

    fun requestConfirm(kind: ConfirmKind) {
        _state.value = s.copy(confirm = kind)
        log("CONFIRM ${kind.name} S13")
    }

    fun cancelConfirm() { _state.value = s.copy(confirm = null) }

    fun resign() {
        val cfg = s.config ?: return
        cancelJobs()
        val r = Result(cfg.ai, EndReason.RESIGN)
        _state.value = s.copy(phase = Phase.RESULT, result = r, confirm = null, anim = null, crown = null, chain = null,
            selected = null, slideTargets = emptySet(), jumpTargets = emptySet(), framed = emptySet(), display = history.last().pos, trays = history.last().trays)
        store.save(saved(r))
        onSound(Sound.LOSE)
        log("RESIGN S09")
    }

    fun restart() {
        _state.value = s.copy(confirm = null)
        again()
    }

    fun settingsChanged() { _state.value = s.copy() }

    // ------------------------------------------------------------------ debug

    fun freeze(ms: Long?) {
        _state.value = s.copy(freeze = ms)
        log("FREEZE $ms")
    }

    fun dump(): String {
        val st = s
        return "phase=${st.phase.stateId} toMove=${st.toMove} human=${st.config?.human} level=${st.config?.level} ply=${st.snap.ply} " +
            "black=${st.display.count(Side.BLACK)} red=${st.display.count(Side.RED)} trays=${st.trays} noProgress=${st.snap.noProgress} " +
            "sel=${st.selected?.let(Sq::name)} targets=${(st.slideTargets + st.jumpTargets).joinToString(",") { Sq.name(it) }} " +
            "chain=${st.chain?.steps?.joinToString(",") { Sq.name(it) }} result=${st.result} coach=${st.coachTurns} hint=${st.hint?.notation} key=${st.display.key}"
    }

    // ------------------------------------------------------------------ save

    private fun saved(result: Result?, chain: Chain? = null): SavedGame =
        SavedGame(s.config ?: GameConfig(Side.BLACK, 1), history, chain, result, s.coachTurns, s.hintOn)

    private fun save() {
        if (s.config == null) return
        store.save(saved(s.result, s.chain.takeIf { s.phase == Phase.CHAIN }))
    }
}
