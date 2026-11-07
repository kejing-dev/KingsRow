package io.embodit.kingsrow.ui.board

import com.pico.spatial.core.ecs.Entity
import io.embodit.kingsrow.ui.board.input.TargetResolver

/** What a pinch / trigger / eye-hand pinch can land on (board-input template, profile D50). */
sealed interface BoardTarget {
    /** A dark square: its collider parent `sq:<i>` or the visible marker `mark:<i>` on it. */
    data class Square(val sq: Int) : BoardTarget

    /** A piece: its hit root `pc:<square>` (the meshes `piece:…` hang below it and resolve up to it). */
    data class Piece(val sq: Int) : BoardTarget

    /** The wooden board or a tray: not a square — a pinch there cancels a selection (and reopens a closed panel). */
    data object Board : BoardTarget
}

/**
 * Entity names the scene sets (BoardScene): `sq:<i>` square collider parents with a visible `mark:<i>` marker child,
 * `pc:<sq>` piece hit roots (renamed when the piece moves) with `piece:…` disc meshes below, `board` the slab collider,
 * `trayHit:<side>` the tray colliders. Pure name logic lives in [byName] / [resolvePath] so the hit → square replay is
 * unit-tested without the engine.
 */
object BoardTargets : TargetResolver<BoardTarget> {
    override fun resolve(entity: Entity): BoardTarget? = byName(entity.getName())

    fun byName(n: String): BoardTarget? = when {
        n.startsWith("sq:") -> n.removePrefix("sq:").toIntOrNull()?.takeIf { it in 0..63 }?.let { BoardTarget.Square(it) }
        n.startsWith("mark:") -> n.removePrefix("mark:").toIntOrNull()?.takeIf { it in 0..63 }?.let { BoardTarget.Square(it) }
        n.startsWith("pc:") -> n.removePrefix("pc:").toIntOrNull()?.takeIf { it in 0..63 }?.let { BoardTarget.Piece(it) }
        n == "board" || n.startsWith("trayHit:") -> BoardTarget.Board
        else -> null
    }

    /** Same walk as the template's resolveUp, over names (hit entity first, then its parents). */
    fun resolvePath(path: List<String>, maxDepth: Int = 10): BoardTarget? = path.take(maxDepth + 1).firstNotNullOfOrNull { byName(it) }

    /** The square a target stands for, or null (board / tray / nothing → "tap nothing"). */
    fun squareOf(target: BoardTarget?): Int? = when (target) {
        is BoardTarget.Square -> target.sq
        is BoardTarget.Piece -> target.sq
        else -> null
    }
}
