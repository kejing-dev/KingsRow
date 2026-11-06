package io.embodit.kingsrow.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    override fun toString(): String = "(%.3f, %.3f, %.3f)".format(x, y, z)
}

/**
 * Every length of the board volume (design spatialLayout, metres). Scene space: origin on the volume floor under
 * the volume centre, +X right, +Y up, +Z toward the player. The board tips [TILT_DEG] about its near bottom edge
 * (which rests on the floor at z = +0.28); board-local space has its origin at the board's top centre, row 0 at
 * the far edge (z < 0). Trays are tipped with the board; the seat card and tray cards stand upright.
 */
object Layout {
    const val VOLUME_W = 0.80f
    const val VOLUME_H = 0.44f
    const val VOLUME_D = 0.62f

    const val PITCH = 0.06f
    const val BOARD = 0.56f
    const val THICK = 0.025f
    const val HALF = BOARD / 2
    const val TILT_DEG = 20f
    val THETA: Float = (TILT_DEG * PI / 180).toFloat()

    const val PIECE_R = 0.023f
    const val PIECE_H = 0.010f
    const val LIFT_SELECTED = 0.008f

    const val TRAY_X = 0.33f
    const val TRAY_Z = 0.14f
    const val TRAY_W = 0.07f
    const val TRAY_L = 0.20f
    const val TRAY_RIM = 0.012f
    const val TRAY_FLOOR = 0.002f
    val TRAY_SLOT_Z = floatArrayOf(0.115f, 0.16f, 0.205f)
    const val SPARE_Z = 0.065f
    const val STACK_MAX = 4

    // v0.1.1: the system places the volume about 2.1 m away (emulator default, see emulatorEyes), so the cards are
    // sized for that distance (D44): 6 cm tall seat card, 3.2 cm tray cards.
    const val SEAT_W = 0.30f
    const val SEAT_H = 0.06f
    const val SEAT_GAP_UP = 0.04f
    const val SEAT_GAP_BACK = 0.04f
    const val TRAY_CARD_W = 0.10f
    const val TRAY_CARD_H = 0.032f

    /** Near bottom edge of the board, the tilt pivot (scene). */
    val PIVOT = V3(0f, 0f, HALF)

    /** boardRoot origin relative to the pivot, before the tilt. */
    val BOARD_OFFSET = V3(0f, THICK, -HALF)

    fun rotX(v: V3, a: Float = THETA): V3 = V3(v.x, v.y * cos(a) - v.z * sin(a), v.y * sin(a) + v.z * cos(a))

    /** Board-local -> scene. */
    fun toScene(l: V3): V3 = PIVOT + rotX(l + BOARD_OFFSET)

    /** Scene -> board-local. */
    fun toLocal(s: V3): V3 = rotX(s - PIVOT, -THETA) - BOARD_OFFSET

    /** Centre of square [sq] on the board top, [lift] above it (board-local). */
    fun square(sq: Int, lift: Float = 0f): V3 = V3((Sq.col(sq) - 3.5f) * PITCH, lift, (Sq.row(sq) - 3.5f) * PITCH)

    /** The dark square nearest a board-local point, or null when farther than [maxSquares] pitches. */
    fun nearestSquare(l: V3, maxSquares: Float = 1.5f): Int? {
        var best: Int? = null
        var bd = Float.MAX_VALUE
        for (sq in Sq.DARK) {
            val c = square(sq)
            val d = (c.x - l.x) * (c.x - l.x) + (c.z - l.z) * (c.z - l.z)
            if (d < bd) { bd = d; best = sq }
        }
        return best?.takeIf { kotlin.math.sqrt(bd) <= maxSquares * PITCH }
    }

    /** Black (near side at yaw 0) keeps its tray on the right, Red on the left (design spatialLayout.tray). */
    fun trayX(side: Int): Float = if (side == Side.BLACK) TRAY_X else -TRAY_X

    fun trayCentre(side: Int): V3 = V3(trayX(side), 0f, TRAY_Z)

    /** Where the i-th physical piece of a tray's stack lies (board-local, centre of the disc). */
    fun traySlot(side: Int, i: Int): V3 {
        val slot = if (i < 3 * STACK_MAX) i / STACK_MAX else (i - 3 * STACK_MAX) % 3
        val level = if (i < 3 * STACK_MAX) i % STACK_MAX else STACK_MAX + (i - 3 * STACK_MAX) / 3
        return V3(trayX(side), TRAY_FLOOR + PIECE_H * (level + 0.5f), TRAY_SLOT_Z[slot])
    }

    /** The spare cell of [side]'s tray, [i] = 0 bottom. */
    fun spareSlot(side: Int, i: Int): V3 = V3(trayX(side), TRAY_FLOOR + PIECE_H * (i + 0.5f), SPARE_Z)

    /** Seat card centre (scene): 4 cm above and 4 cm behind the far top edge. */
    fun seatCard(): V3 {
        val farTop = toScene(V3(0f, 0f, -HALF))
        return V3(0f, farTop.y + SEAT_GAP_UP + SEAT_H / 2, farTop.z - SEAT_GAP_BACK)
    }

    /** Tray count card centre (scene), standing on the tray's front rim. */
    fun trayCard(side: Int): V3 {
        val front = toScene(V3(trayX(side), TRAY_RIM, TRAY_Z + TRAY_L / 2))
        return V3(front.x, front.y + TRAY_CARD_H / 2 + 0.004f, front.z + 0.004f)
    }

    /** An arc between two board-local points with apex [height] above the higher end. */
    fun arc(a: V3, b: V3, t: Float, height: Float): V3 {
        val p = a.lerp(b, t)
        return V3(p.x, p.y + height * 4f * t * (1f - t), p.z)
    }

    // ---------------------------------------------------------- D35 occlusion check (design spatialLayout.occlusionCheck)

    /** Eye positions (scene): seated / standing, front and ±30° around the volume centre. */
    fun eyes(): List<Pair<String, V3>> {
        val out = ArrayList<Pair<String, V3>>()
        for ((pose, h) in listOf("sit" to 0.45f, "stand" to 0.75f)) for (deg in listOf(0, -30, 30)) {
            val a = deg * PI.toFloat() / 180f
            out += "$pose${if (deg == 0) "" else if (deg < 0) "L30" else "R30"}" to V3(0.85f * sin(a), h, 0.85f * cos(a))
        }
        out += emulatorEyes()
        return out
    }

    /**
     * Where the emulator's default camera actually sees the volume from (D44: design eyes are only a lower bound).
     * Measured on first-screen.png (emulator-5560, v0.1.0): near edge 290 px wide, far edge 232 px -> near edge about
     * 2.1 m away; the 20° board shows 0.24 m of apparent height -> the line of sight drops about 6° -> eye about
     * 0.25 m above the volume floor. Plus the same point turned ±30° around the volume.
     */
    fun emulatorEyes(): List<Pair<String, V3>> {
        val d = HALF + 2.1f
        return listOf(0, -30, 30).map { deg ->
            val a = deg * PI.toFloat() / 180f
            "emu${if (deg == 0) "" else if (deg < 0) "L30" else "R30"}" to V3(d * sin(a), 0.25f, d * cos(a))
        }
    }

    data class Box(val min: V3, val max: V3, val local: Boolean)

    /** Model boxes: the board slab and the far-row pieces at their highest (king 2 cm + lifted 3 cm), board-local. */
    fun modelBoxes(): List<Pair<String, Box>> = listOf(
        "boardSlab" to Box(V3(-HALF, -THICK, -HALF), V3(HALF, 0f, HALF), true),
        "piecesFarRow" to Box(V3(-3.5f * PITCH - PIECE_R, 0f, -3.5f * PITCH - PIECE_R), V3(3.5f * PITCH + PIECE_R, 2 * PIECE_H + Timeline.LIFT_ANIM, -3.5f * PITCH + PIECE_R), true),
        "piecesAll" to Box(V3(-3.5f * PITCH - PIECE_R, 0f, -3.5f * PITCH - PIECE_R), V3(3.5f * PITCH + PIECE_R, 2 * PIECE_H + LIFT_SELECTED, 3.5f * PITCH + PIECE_R), true),
    ) + listOf(Side.BLACK, Side.RED).map { s ->
        "tray$s" to Box(V3(trayX(s) - TRAY_W / 2, 0f, TRAY_Z - TRAY_L / 2), V3(trayX(s) + TRAY_W / 2, TRAY_RIM + PIECE_H * 5, TRAY_Z + TRAY_L / 2), true)
    }

    /** The four corners of an upright card facing +Z (scene). */
    fun corners(centre: V3, w: Float, h: Float): List<V3> =
        listOf(V3(-w / 2, -h / 2, 0f), V3(w / 2, -h / 2, 0f), V3(w / 2, h / 2, 0f), V3(-w / 2, h / 2, 0f)).map { centre + it }

    fun inside(p: V3, b: Box): Boolean {
        val q = if (b.local) toLocal(p) else p
        return q.x > b.min.x && q.x < b.max.x && q.y > b.min.y && q.y < b.max.y && q.z > b.min.z && q.z < b.max.z
    }

    /** Segment (scene) vs box (slab test in the box's frame). */
    fun segmentHits(a: V3, b: V3, box: Box): Boolean {
        val p = if (box.local) toLocal(a) else a
        val q = if (box.local) toLocal(b) else b
        var t0 = 0f; var t1 = 1f
        val d = q - p
        val axes = listOf(Triple(p.x, d.x, box.min.x to box.max.x), Triple(p.y, d.y, box.min.y to box.max.y), Triple(p.z, d.z, box.min.z to box.max.z))
        for ((o, dir, range) in axes) {
            if (abs(dir) < 1e-9f) {
                if (o <= range.first || o >= range.second) return false
            } else {
                var ta = (range.first - o) / dir
                var tb = (range.second - o) / dir
                if (ta > tb) { val t = ta; ta = tb; tb = t }
                t0 = max(t0, ta); t1 = min(t1, tb)
                if (t0 >= t1) return false
            }
        }
        return true
    }

    /** Cards that carry content: seat card + both tray cards. */
    fun cards(): List<Triple<String, V3, Pair<Float, Float>>> = listOf(
        Triple("seat", seatCard(), SEAT_W to SEAT_H),
        Triple("trayBlack", trayCard(Side.BLACK), TRAY_CARD_W to TRAY_CARD_H),
        Triple("trayRed", trayCard(Side.RED), TRAY_CARD_W to TRAY_CARD_H),
    )

    private fun ownTray(card: String): String? = when (card) { "trayBlack" -> "tray${Side.BLACK}"; "trayRed" -> "tray${Side.RED}"; else -> null }

    /** Returns every (card, eye, corner, box) problem; empty = D35 holds. */
    fun occlusionProblems(): List<String> {
        val out = ArrayList<String>()
        val boxes = modelBoxes()
        for ((name, c, size) in cards()) {
            val cs = corners(c, size.first, size.second)
            for ((bn, b) in boxes) {
                // a tray card stands on its own tray's front rim (that box includes the stack's headroom)
                if (bn == ownTray(name)) continue
                cs.forEachIndexed { i, p -> if (inside(p, b)) out += "$name corner$i inside $bn" }
            }
            for ((en, eye) in eyes()) cs.forEachIndexed { i, p ->
                for ((bn, b) in boxes) {
                    // the card's own tray is the thing it is attached to: its rim is under the card, not between the eye and it
                    if (bn == ownTray(name)) continue
                    if (segmentHits(eye, p, b)) out += "$name corner$i from $en crosses $bn"
                }
            }
        }
        return out
    }
}
