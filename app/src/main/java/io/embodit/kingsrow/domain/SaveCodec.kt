package io.embodit.kingsrow.domain

/** The whole game on disk (design dataModel.game): every snapshot (undo + repetition), the chain in flight, the result. */
data class SavedGame(
    val config: GameConfig,
    val history: List<Snapshot>,
    val chain: Chain? = null,
    val result: Result? = null,
    val coachTurns: Int = 0,
    val hintOn: Boolean = false,
)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

class MemoryStore : GameStore {
    var game: SavedGame? = null
    override fun load(): SavedGame? = game
    override fun save(game: SavedGame) { this.game = game }
    override fun clear() { game = null }
}

/** A plain line format (no org.json, so the JVM unit tests run it for real). */
object SaveCodec {
    fun encode(g: SavedGame): String = buildString {
        appendLine("kingsrow 1")
        appendLine("config ${g.config.human} ${g.config.level}")
        appendLine("coach ${g.coachTurns}")
        appendLine("hint ${if (g.hintOn) 1 else 0}")
        g.history.forEach { appendLine("snap ${snap(it)}") }
        g.chain?.let { appendLine("chain ${it.from};${it.steps.joinToString(".")};${it.captured.joinToString(".")}") }
        g.result?.let { appendLine("result ${it.winner} ${it.reason.name}") }
    }

    fun decode(text: String): SavedGame? = runCatching {
        val lines = text.lines().filter { it.isNotBlank() }
        require(lines.first() == "kingsrow 1")
        var config: GameConfig? = null
        var coach = 0
        var hint = false
        val history = ArrayList<Snapshot>()
        var chain: Chain? = null
        var result: Result? = null
        for (l in lines.drop(1)) {
            val (tag, rest) = l.split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (tag) {
                "config" -> rest.split(" ").let { config = GameConfig(it[0].toInt(), it[1].toInt().coerceIn(1, 5)) }
                "coach" -> coach = rest.toInt()
                "hint" -> hint = rest == "1"
                "snap" -> history += unsnap(rest)
                "chain" -> rest.split(";").let { p -> chain = Chain(p[0].toInt(), ints(p.getOrElse(1) { "" }), ints(p.getOrElse(2) { "" })) }
                "result" -> rest.split(" ").let { result = Result(it[0].toInt(), EndReason.valueOf(it[1])) }
            }
        }
        require(history.isNotEmpty())
        SavedGame(config!!, history, chain, result, coach, hint)
    }.getOrNull()

    private fun ints(s: String): List<Int> = s.split(".").filter { it.isNotEmpty() }.map { it.toInt() }

    private fun snap(s: Snapshot): String =
        listOf(
            s.pos.key,
            s.trays.joinToString(",") { "${it.score}.${it.stack}.${it.spares}" },
            s.noProgress.toString(),
            s.ply.toString(),
            s.lastMove?.let { m -> "${m.from}>${m.path.joinToString(".")}>${m.captured.joinToString(".")}>${if (m.crowns) 1 else 0}" } ?: "-",
        ).joinToString("|")

    private fun unsnap(t: String): Snapshot {
        val p = t.split("|")
        val pos = Position.fromKey(p[0]) ?: error("bad position")
        val trays = p[1].split(",").map { x -> x.split(".").let { Tray(it[0].toInt(), it[1].toInt(), it[2].toInt()) } }
        require(trays.size == 2)
        val move = p[4].takeIf { it != "-" }?.split(">")?.let { m -> Move(m[0].toInt(), ints(m[1]), ints(m[2]), m[3] == "1") }
        return Snapshot(pos, trays, p[2].toInt(), p[3].toInt(), move)
    }
}
