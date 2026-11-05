package io.embodit.kingsrow.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.kingsrow.domain.CrownSource
import io.embodit.kingsrow.domain.Layout
import io.embodit.kingsrow.domain.Phase
import io.embodit.kingsrow.domain.Piece
import io.embodit.kingsrow.domain.PlayState
import io.embodit.kingsrow.domain.Settings
import io.embodit.kingsrow.domain.Side
import io.embodit.kingsrow.domain.Sq
import io.embodit.kingsrow.domain.Timeline
import io.embodit.kingsrow.domain.V3
import io.embodit.kingsrow.platform.TAG
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Every entity of the board volume. Compose hands over [PlayState] through [apply]; all motion (moves, jumps,
 * captured pieces flying to the tray, the crowning piece, pulses) is computed per frame in [onFrame] from the
 * state's start times (BoardFrameSystem, never Compose).
 *
 * ```
 * root (volume centre)
 * └ sceneRoot (volume floor)
 *   ├ keyLight (warm directional)
 *   ├ tiltRoot (near bottom edge of the board, tipped 20° about X)
 *   │ └ boardRoot (board top centre)
 *   │   ├ slab, face (Canvas texture), 32 invisible square pads "sq:<i>"
 *   │   ├ pieces (holder -> disc0, disc1 (king), collider "pc:<i>"), mover, flyers, crown flyer
 *   │   ├ trays (floor + rims, stacks, spares, winner glow)
 *   │   └ marks: select ring, blue dots, orange rings / frames, red frame, white pulses, last move, hint, coach glow
 *   ├ seat card (AttachmentPanel, upright behind the far edge)
 *   └ two tray cards (AttachmentPanel, upright on the trays' front rims)
 * ```
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f

    /** One disc: body + top cap. */
    private inner class Disc(name: String, r: SceneResources) {
        val holder = Entity().apply { setName(name) }
        val body = ModelEntity(r.disc, r.ebony).apply { setName("$name:body"); transform().setPosition(Vector3(0f, Layout.PIECE_H / 2, 0f)) }
        val cap = ModelEntity(r.cap, r.capBlackMan).apply {
            setName("$name:cap")
            transform().setPosition(Vector3(0f, Layout.PIECE_H + 0.0003f, 0f))
            transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
        }
        private var look = -1

        init { holder.addChild(body); holder.addChild(cap) }

        fun look(side: Int, kingCap: Boolean) {
            val k = side * 2 + if (kingCap) 1 else 0
            if (k == look) return
            look = k
            val r = res ?: return
            body.components[ModelComponent::class.java]?.materials?.set(0, if (side == Side.BLACK) r.ebony else r.wine)
            cap.components[ModelComponent::class.java]?.materials?.set(0, when (k) { 0 -> r.capBlackMan; 1 -> r.capBlackKing; 2 -> r.capRedMan; else -> r.capRedKing })
        }
    }

    /** A man (one disc) or a king (two discs); [collider] is null for animation copies. */
    private inner class PieceView(name: String, r: SceneResources, withCollider: Boolean) {
        val holder = Entity().apply { setName(name) }
        val lower = Disc("$name:d0", r)
        val upper = Disc("$name:d1", r)
        val collider: Entity? = if (withCollider) Entity().apply {
            setName("pc:-1")
            components.set(CollisionComponent(collisionShape = listOf(r.manShape), physicsMaterial = r.physics))
            components.set(InteractableComponent())
        } else null
        private var king = false
        private var shapeKing = false

        init {
            holder.addChild(lower.holder)
            upper.holder.transform().setPosition(Vector3(0f, Layout.PIECE_H, 0f))
            upper.holder.enabled = false
            holder.addChild(upper.holder)
            collider?.let { c -> c.transform().setPosition(Vector3(0f, Layout.PIECE_H / 2, 0f)); holder.addChild(c) }
        }

        fun look(side: Int, isKing: Boolean) {
            lower.look(side, false)
            upper.look(side, true)
            if (isKing != king) { king = isKing; upper.holder.enabled = isKing }
            collider?.let { c ->
                if (isKing != shapeKing) {
                    shapeKing = isKing
                    val r = res ?: return@let
                    c.components.set(CollisionComponent(collisionShape = listOf(if (isKing) r.kingShape else r.manShape), physicsMaterial = r.physics))
                    c.transform().setPosition(Vector3(0f, if (isKing) Layout.PIECE_H else Layout.PIECE_H / 2, 0f))
                }
            }
        }

        fun square(sq: Int) { collider?.setName("pc:$sq") }
    }

    private val pieces = HashMap<Int, PieceView>()
    private val piecePool = ArrayList<PieceView>()
    private val placed = HashMap<Int, Int>() // square -> piece code drawn
    private var mover: PieceView? = null
    private val flyers = ArrayList<PieceView>()
    private var crownFlyer: Disc? = null
    private val trayDiscs = HashMap<Int, ArrayList<Disc>>()
    private val spareDiscs = HashMap<Int, ArrayList<Disc>>()
    private val trayGlow = HashMap<Int, ModelEntity>()

    private var selRing: ModelEntity? = null
    private var selRingInner: ModelEntity? = null
    private val dots = ArrayList<ModelEntity>()
    private val landRings = ArrayList<ModelEntity>()
    private val frames = ArrayList<Entity>()
    private var redFrame: Entity? = null
    private val pulses = ArrayList<ModelEntity>()
    private val lastMarks = ArrayList<ModelEntity>()
    private val hintRings = ArrayList<ModelEntity>()
    private val coachRings = ArrayList<ModelEntity>()
    private var crownGlow: ModelEntity? = null
    private var seat: Entity? = null
    private val trayCards = HashMap<Int, Entity>()

    // drag
    private var dragSq: Int? = null
    private var dragOffset = V3(0f, 0f, 0f)

    private var clock = 0f

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
        tiltRoot.transform().setPosition(vec(Layout.PIVOT))
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), Layout.THETA))
        boardRoot.transform().setPosition(vec(Layout.BOARD_OFFSET))
    }

    private fun vec(v: V3) = Vector3(v.x, v.y, v.z)
    private fun place(e: Entity, v: V3) = e.transform().setPosition(vec(v))

    /** The volume's half height from the SpatialView; the floor is at -half. */
    fun setRegion(halfY: Float) {
        if (kotlin.math.abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, -halfY, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    /** S00 stand-in: a plain slab and 24 discs, shown until the real board is built (design lifecycle). */
    fun showFallback(slab: MeshResource, disc: MeshResource, wood: Material, dark: Material) {
        if (built || fallback.isNotEmpty()) return
        val board = ModelEntity(slab, wood).apply { setName("fallbackBoard"); transform().setPosition(Vector3(0f, -Layout.THICK / 2, 0f)) }
        boardRoot.addChild(board)
        val list = arrayListOf<Entity>(board)
        for (sq in Sq.DARK) {
            val r = Sq.row(sq)
            if (r in 3..4) continue
            val e = ModelEntity(disc, dark).apply { setName("fallbackDisc"); place(this, Layout.square(sq, Layout.PIECE_H / 2)) }
            boardRoot.addChild(e)
            list += e
        }
        fallback = list
        Log.i(TAG, "SCENE fallback shown S00")
    }

    fun install(r: SceneResources, seatCard: Entity?, cardBlack: Entity?, cardRed: Entity?) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        boardRoot.addChild(ModelEntity(r.slab, r.oakDark).apply { setName("slab"); transform().setPosition(Vector3(0f, -Layout.THICK / 2 - 0.0004f, 0f)) })
        // the wood itself is pinchable (below the square pads and the pieces, which win the ray first)
        boardRoot.addChild(Entity().apply {
            setName("board")
            transform().setPosition(Vector3(0f, -Layout.THICK / 2 - 0.001f, 0f))
            components.set(CollisionComponent(collisionShape = listOf(r.slabShape), physicsMaterial = r.physics))
            components.set(InteractableComponent())
        })
        boardRoot.addChild(ModelEntity(r.face, r.faceMat).apply {
            setName("face")
            transform().setPosition(Vector3(0f, 0.0003f, 0f))
            transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat()))
        })
        // 32 dark-square pads (design input.rule: thin boxes 5.5 x 0.3 x 5.5 cm, not overlapping)
        for (sq in Sq.DARK) {
            boardRoot.addChild(Entity().apply {
                setName("sq:$sq")
                place(this, Layout.square(sq, 0.0015f))
                components.set(CollisionComponent(collisionShape = listOf(r.squareShape), physicsMaterial = r.physics))
                components.set(InteractableComponent())
            })
        }
        // trays
        for (side in listOf(Side.BLACK, Side.RED)) {
            val c = Layout.trayCentre(side)
            val tray = Entity().apply { setName("tray:$side") }
            fun bar(name: String, size: V3, at: V3, m: Material = r.oak) = ModelEntity(r.unitBox, m).apply {
                setName(name); transform().setScaleVector(vec(size)); place(this, at)
            }
            tray.addChild(bar("trayFloor:$side", V3(Layout.TRAY_W, 0.004f, Layout.TRAY_L), V3(c.x, Layout.TRAY_FLOOR - 0.002f, c.z), r.oakDark))
            val wall = 0.004f
            val rimY = Layout.TRAY_RIM / 2
            tray.addChild(bar("trayRimL:$side", V3(wall, Layout.TRAY_RIM, Layout.TRAY_L), V3(c.x - Layout.TRAY_W / 2 + wall / 2, rimY, c.z)))
            tray.addChild(bar("trayRimR:$side", V3(wall, Layout.TRAY_RIM, Layout.TRAY_L), V3(c.x + Layout.TRAY_W / 2 - wall / 2, rimY, c.z)))
            tray.addChild(bar("trayRimFront:$side", V3(Layout.TRAY_W, Layout.TRAY_RIM, wall), V3(c.x, rimY, c.z + Layout.TRAY_L / 2 - wall / 2)))
            // back rim 1 cm higher (design gameSpec.tray "后沿挡边 1 cm")
            tray.addChild(bar("trayRimBack:$side", V3(Layout.TRAY_W, Layout.TRAY_RIM + 0.01f, wall), V3(c.x, (Layout.TRAY_RIM + 0.01f) / 2, c.z - Layout.TRAY_L / 2 + wall / 2)))
            // divider between the spare cell and the stacks
            tray.addChild(bar("trayDivider:$side", V3(Layout.TRAY_W, Layout.TRAY_RIM * 0.6f, wall * 0.75f), V3(c.x, Layout.TRAY_RIM * 0.3f, Layout.SPARE_Z + 0.027f)))
            tray.addChild(Entity().apply {
                setName("trayHit:$side")
                place(this, V3(c.x, Layout.TRAY_RIM / 2, c.z))
                components.set(CollisionComponent(collisionShape = listOf(r.trayShape), physicsMaterial = r.physics))
                components.set(InteractableComponent())
            })
            val glow = ModelEntity(r.unitBox, r.amberSoft).apply {
                setName("trayGlow:$side"); transform().setScaleVector(Vector3(Layout.TRAY_W + 0.012f, 0.0008f, Layout.TRAY_L + 0.012f))
                place(this, V3(c.x, -0.0004f, c.z)); enabled = false
            }
            tray.addChild(glow)
            trayGlow[side] = glow
            boardRoot.addChild(tray)
            trayDiscs[side] = ArrayList()
            spareDiscs[side] = ArrayList()
        }
        // marks
        selRing = ModelEntity(r.ring, r.selBlack).apply { setName("selRing"); enabled = false }.also { boardRoot.addChild(it) }
        selRingInner = ModelEntity(r.ring, r.selWhite).apply { setName("selRingInner"); enabled = false; transform().setScaleVector(Vector3(0.88f, 1f, 0.88f)) }.also { boardRoot.addChild(it) }
        repeat(4) { i -> dots += ModelEntity(r.dot, r.blue).apply { setName("dot:$i"); enabled = false }.also { boardRoot.addChild(it) } }
        repeat(8) { i -> landRings += ModelEntity(r.ring, r.orange).apply { setName("land:$i"); enabled = false }.also { boardRoot.addChild(it) } }
        repeat(8) { i -> frames += frame(r, r.orange, "frame:$i").also { it.enabled = false; boardRoot.addChild(it) } }
        redFrame = frame(r, r.red, "redFrame").also { it.enabled = false; boardRoot.addChild(it) }
        repeat(12) { i -> pulses += ModelEntity(r.ring, r.white).apply { setName("pulse:$i"); enabled = false }.also { boardRoot.addChild(it) } }
        repeat(2) { i -> lastMarks += ModelEntity(r.unitBox, r.amberSoft).apply { setName("last:$i"); enabled = false; transform().setScaleVector(Vector3(0.058f, 0.0006f, 0.058f)) }.also { boardRoot.addChild(it) } }
        repeat(2) { i -> hintRings += ModelEntity(r.ring, r.amber).apply { setName("hint:$i"); enabled = false }.also { boardRoot.addChild(it) } }
        repeat(12) { i -> coachRings += ModelEntity(r.ring, r.coach).apply { setName("coach:$i"); enabled = false; transform().setScaleVector(Vector3(1.05f, 1f, 1.05f)) }.also { boardRoot.addChild(it) } }
        crownGlow = ModelEntity(r.ring, r.gold).apply { setName("crownGlow"); enabled = false }.also { boardRoot.addChild(it) }
        mover = PieceView("mover", r, false).also { it.holder.enabled = false; boardRoot.addChild(it.holder) }
        crownFlyer = Disc("crownFlyer", r).also { it.holder.enabled = false; boardRoot.addChild(it.holder) }
        // light: warm key light from the front right, above (B24: moderate, not blown out)
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            light.components.set(DirectionalLightComponent(Color4(1f, 0.93f, 0.84f, 1f), 0.9f, true))
            val q = Quat(Vector3(0f, 1f, 0f), (30 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-55 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            sceneRoot.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
        // cards
        seat = seatCard?.also { sceneRoot.addChild(it); place(it, Layout.seatCard()) }
        cardBlack?.let { sceneRoot.addChild(it); place(it, Layout.trayCard(Side.BLACK)); trayCards[Side.BLACK] = it }
        cardRed?.let { sceneRoot.addChild(it); place(it, Layout.trayCard(Side.RED)); trayCards[Side.RED] = it }

        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms seat=${Layout.seatCard()} trayB=${Layout.trayCard(Side.BLACK)}")
        onFrame(0f)
    }

    private fun frame(r: SceneResources, m: Material, name: String): Entity {
        val holder = Entity().apply { setName(name) }
        val s = 0.052f
        val t = 0.003f
        for ((i, at) in listOf(V3(0f, 0f, -s / 2), V3(0f, 0f, s / 2), V3(-s / 2, 0f, 0f), V3(s / 2, 0f, 0f)).withIndex()) {
            holder.addChild(ModelEntity(r.unitBox, m).apply {
                setName("$name:bar$i")
                transform().setScaleVector(if (i < 2) Vector3(s + t, 0.0012f, t) else Vector3(t, 0.0012f, s + t))
                place(this, at)
            })
        }
        return holder
    }

    fun apply(next: PlayState, s: Settings) {
        state = next
        settings = s
        if (built) onFrame(0f)
    }

    // ------------------------------------------------------------------ drag

    fun beginDrag(sq: Int) { dragSq = sq; dragOffset = V3(0f, 0f, 0f) }

    /** [scene] delta in metres (volume axes) -> board-local offset. */
    fun dragBy(dx: Float, dy: Float, dz: Float) {
        if (dragSq == null) return
        val d = Layout.rotX(V3(dx, dy, dz), -Layout.THETA)
        dragOffset = V3(dragOffset.x + d.x, 0f, dragOffset.z + d.z)
    }

    /** Ends the drag: the nearest dark square within 1.5 squares (D27), or null to spring back. */
    fun endDrag(): Int? {
        val sq = dragSq ?: return null
        val at = Layout.square(sq) + dragOffset
        dragSq = null
        dragOffset = V3(0f, 0f, 0f)
        return Layout.nearestSquare(at)
    }

    // ------------------------------------------------------------------ per frame

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val anim = st.anim
        val freeze = st.freeze
        fun elapsed(start: Long): Long = if (freeze != null) min(now - start, freeze) else now - start

        // ---- crowning: which square shows a man until the second piece lands
        val crown = st.crown
        val crownT = crown?.let { elapsed(it.startAt) } ?: 0L
        val crownLanded = crown == null || crown.reduced || crownT >= Timeline.CROWN_DELAY_MS + Timeline.CROWN_FLY_MS

        // ---- static pieces
        val base = anim?.before ?: st.display
        val hidden = HashSet<Int>()
        if (anim != null) { hidden += anim.from; hidden += anim.captured }
        val want = HashMap<Int, Int>()
        for (sq in Sq.DARK) {
            val p = base[sq]
            if (p != Piece.EMPTY && sq !in hidden) want[sq] = p
        }
        if (!crownLanded && crown != null) want[crown.square]?.let { want[crown.square] = Piece.man(Piece.side(it)) }
        for (sq in pieces.keys.filter { it !in want }) {
            pieces.remove(sq)?.let { it.holder.enabled = false; it.square(-1); piecePool += it }
            placed.remove(sq)
        }
        val lifted = st.selected.takeIf { st.phase == Phase.SELECTED || st.phase == Phase.CHAIN }
        for ((sq, p) in want) {
            val v = pieces.getOrPut(sq) {
                val pv = if (piecePool.isNotEmpty()) piecePool.removeAt(piecePool.lastIndex) else PieceView("piece", r, true).also { boardRoot.addChild(it.holder) }
                pv.holder.enabled = true
                pv.square(sq)
                placed.remove(sq)
                pv
            }
            if (placed[sq] != p) { v.look(Piece.side(p), Piece.isKing(p)); placed[sq] = p }
            val lift = when {
                sq == dragSq -> Timeline.LIFT_ANIM
                sq == lifted -> Layout.LIFT_SELECTED
                else -> 0f
            }
            val at = if (sq == dragSq) Layout.square(sq, lift) + dragOffset else Layout.square(sq, lift)
            place(v.holder, at)
        }

        // ---- the moving piece and the captured ones
        val mv = mover
        if (anim != null && mv != null) {
            val p = anim.before[anim.from]
            mv.look(anim.side, Piece.isKing(p))
            mv.holder.enabled = true
            place(mv.holder, moverAt(anim, elapsed(anim.startAt)))
        } else mv?.holder?.enabled = false

        // ---- trays
        val landedPhysical = HashMap<Int, Int>()
        if (anim != null) {
            val e = elapsed(anim.startAt)
            var physical = 0
            anim.captured.forEachIndexed { i, sq ->
                val piece = anim.before[sq]
                val n = if (Piece.isKing(piece)) 2 else 1
                val f = flyer(i, r)
                val lift = Timeline.captureLift(i, anim.reduced)
                val target = Layout.traySlot(anim.side, anim.traysBefore[anim.side].stack + physical)
                when {
                    anim.reduced && e < lift -> { f.holder.enabled = true; f.look(Piece.side(piece), Piece.isKing(piece)); place(f.holder, Layout.square(sq)) }
                    anim.reduced -> { f.holder.enabled = false; physical += n; landedPhysical[anim.side] = physical }
                    e < lift -> { f.holder.enabled = true; f.look(Piece.side(piece), Piece.isKing(piece)); place(f.holder, Layout.square(sq)) }
                    e < lift + Timeline.CAPTURE_FLY_MS -> {
                        val t = ((e - lift).toFloat() / Timeline.CAPTURE_FLY_MS).coerceIn(0f, 1f)
                        val from = Layout.square(sq, Timeline.CAPTURE_LIFT)
                        f.holder.enabled = true
                        f.look(Piece.side(piece), Piece.isKing(piece))
                        place(f.holder, Layout.arc(from, target - V3(0f, Layout.PIECE_H / 2, 0f), easeInOut(t), 0.06f))
                    }
                    else -> { f.holder.enabled = false; physical += n; landedPhysical[anim.side] = physical }
                }
            }
            for (i in anim.captured.size until flyers.size) flyers[i].holder.enabled = false
        } else flyers.forEach { it.holder.enabled = false }

        for (side in listOf(Side.BLACK, Side.RED)) {
            var stack = if (anim != null && anim.side == side) anim.traysBefore[side].stack + (landedPhysical[side] ?: 0) else if (anim != null) anim.traysBefore[side].stack else st.trays[side].stack
            var spares = if (anim != null) anim.traysBefore[side].spares else st.trays[side].spares
            if (crown != null && !crown.reduced && crownT < Timeline.CROWN_DELAY_MS) {
                if (crown.source == CrownSource.SPARE && crown.side == side) spares += 1
                if (crown.source == CrownSource.CAPTIVE && Side.other(crown.side) == side) stack += 1
            }
            val discs = trayDiscs[side]!!
            while (discs.size < stack) discs += Disc("tray$side:${discs.size}", r).also { boardRoot.addChild(it.holder) }
            discs.forEachIndexed { i, d ->
                if (i < stack) {
                    d.holder.enabled = true
                    d.look(Side.other(side), false)
                    place(d.holder, Layout.traySlot(side, i) - V3(0f, Layout.PIECE_H / 2, 0f))
                } else d.holder.enabled = false
            }
            val sp = spareDiscs[side]!!
            while (sp.size < 3) sp += Disc("spare$side:${sp.size}", r).also { boardRoot.addChild(it.holder) }
            sp.forEachIndexed { i, d ->
                if (i < spares) { d.holder.enabled = true; d.look(side, false); place(d.holder, Layout.spareSlot(side, i) - V3(0f, Layout.PIECE_H / 2, 0f)) } else d.holder.enabled = false
            }
            trayGlow[side]?.enabled = st.result?.winner == side
        }

        // ---- the crowning piece
        val cf = crownFlyer
        if (crown != null && cf != null && !crown.reduced && crownT < Timeline.CROWN_DELAY_MS + Timeline.CROWN_FLY_MS) {
            val start = when (crown.source) {
                CrownSource.SPARE -> Layout.spareSlot(crown.side, st.trays[crown.side].spares)
                CrownSource.CAPTIVE -> Layout.traySlot(Side.other(crown.side), st.trays[Side.other(crown.side)].stack)
                CrownSource.FADE -> Layout.spareSlot(crown.side, 0)
            } - V3(0f, Layout.PIECE_H / 2, 0f)
            val end = Layout.square(crown.square, Layout.PIECE_H)
            cf.look(crown.side, true)
            cf.holder.enabled = true
            if (crownT < Timeline.CROWN_DELAY_MS) {
                place(cf.holder, start)
                val k = if (crown.source == CrownSource.FADE) (crownT.toFloat() / Timeline.CROWN_DELAY_MS).coerceIn(0.05f, 1f) else 1f
                cf.holder.transform().setScaleVector(Vector3(k, k, k))
            } else {
                val t = ((crownT - Timeline.CROWN_DELAY_MS).toFloat() / Timeline.CROWN_FLY_MS).coerceIn(0f, 1f)
                cf.holder.transform().setScaleVector(Vector3(1f, 1f, 1f))
                place(cf.holder, Layout.arc(start, end, easeInOut(t), 0.08f))
            }
        } else cf?.holder?.enabled = false

        // crown glow: 400 ms rising after the landing, then a calm ring while crownedAt holds
        crownGlow?.let { g ->
            val sq = st.crownedAt
            if (sq != null && crownLanded && anim == null) {
                val glowT = if (crown != null && !crown.reduced) ((crownT - Timeline.CROWN_DELAY_MS - Timeline.CROWN_FLY_MS).toFloat() / Timeline.CROWN_GLOW_MS).coerceIn(0f, 1f) else 1f
                val k = 0.7f + 0.35f * glowT
                g.transform().setScaleVector(Vector3(k, 1f, k))
                place(g, Layout.square(sq, 2 * Layout.PIECE_H + 0.002f))
                g.enabled = true
            } else g.enabled = false
        }

        // ---- marks
        val quiet = anim == null && crown == null
        // selection ring
        val sel = st.selected.takeIf { quiet && (st.phase == Phase.SELECTED || st.phase == Phase.CHAIN) }
        selRing?.let { ring ->
            if (sel != null) {
                val side = Piece.side(st.display[sel])
                val chain = st.phase == Phase.CHAIN
                ring.components[ModelComponent::class.java]?.materials?.set(0, if (chain) r.green else if (side == Side.BLACK) r.selBlack else r.selRed)
                place(ring, Layout.square(sel, 0.0016f))
                ring.enabled = true
                selRingInner?.let { inner -> inner.enabled = !chain && side == Side.BLACK; place(inner, Layout.square(sel, 0.0018f)) }
            } else { ring.enabled = false; selRingInner?.enabled = false }
        }
        val slide = if (quiet) st.slideTargets.toList() else emptyList()
        dots.forEachIndexed { i, d -> val sq = slide.getOrNull(i); if (sq != null) { place(d, Layout.square(sq, 0.0012f)); d.enabled = true } else d.enabled = false }
        val lands = if (quiet) st.jumpTargets.toList() else emptyList()
        landRings.forEachIndexed { i, d -> val sq = lands.getOrNull(i); if (sq != null) { place(d, Layout.square(sq, 0.0014f)); d.enabled = true } else d.enabled = false }
        val framed = if (quiet) st.framed.toList() else emptyList()
        frames.forEachIndexed { i, f -> val sq = framed.getOrNull(i); if (sq != null) { place(f, Layout.square(sq, 0.0012f)); f.enabled = true } else f.enabled = false }

        // alert: the tapped square's red frame flashes for 300 ms (x2), the pieces that can jump pulse white twice
        val al = st.alert
        val at = al?.let { now - it.at } ?: Long.MAX_VALUE
        redFrame?.let { f ->
            if (al != null && al.kind != io.embodit.kingsrow.domain.AlertKind.BUSY && al.kind != io.embodit.kingsrow.domain.AlertKind.NO_DRAG && at < 600 && (at / 150) % 2 == 0L) { place(f, Layout.square(al.square, 0.002f)); f.enabled = true } else f.enabled = false
        }
        val pulseList = if (al != null && at < 1400) al.pulse.toList() else emptyList()
        pulses.forEachIndexed { i, p ->
            val sq = pulseList.getOrNull(i)
            if (sq != null) {
                val k = 1f + 0.18f * sin((at / 700f) * 2f * PI.toFloat()).let { max(it, 0f) }
                p.transform().setScaleVector(Vector3(k, 1f, k))
                place(p, Layout.square(sq, pieceTop(sq) + 0.002f))
                p.enabled = true
            } else p.enabled = false
        }

        // last move: faint amber from / to squares
        val lm = st.snap.lastMove.takeIf { quiet && st.phase != Phase.CHAIN }
        lastMarks.forEachIndexed { i, m ->
            val sq = if (lm == null) null else if (i == 0) lm.from else lm.to
            if (sq != null) { place(m, Layout.square(sq, 0.0004f)); m.enabled = true } else m.enabled = false
        }

        // hint: amber rings on the piece and its first landing (D19 toggle)
        val h = st.hint.takeIf { quiet && st.hintOn && st.humansTurn && st.phase in setOf(Phase.IDLE, Phase.SELECTED) }
        hintRings.forEachIndexed { i, ring ->
            val sq = if (h == null) null else if (i == 0) h.from else h.path[0]
            if (sq != null) {
                val k = 1.1f + 0.06f * sin(clock * 4f)
                ring.transform().setScaleVector(Vector3(k, 1f, k))
                place(ring, Layout.square(sq, if (i == 0) pieceTop(sq) + 0.003f else 0.002f))
                ring.enabled = true
            } else ring.enabled = false
        }

        // first-game coaching: the pieces that can move glow softly
        val coach = if (quiet && st.coachTurns > 0 && st.phase == Phase.IDLE) st.movable.toList() else emptyList()
        coachRings.forEachIndexed { i, ring ->
            val sq = coach.getOrNull(i)
            if (sq != null) {
                val k = 1.0f + 0.05f * sin(clock * 3f)
                ring.transform().setScaleVector(Vector3(k, 1f, k))
                place(ring, Layout.square(sq, 0.0013f))
                ring.enabled = true
            } else ring.enabled = false
        }
    }

    private fun pieceTop(sq: Int): Float = if (Piece.isKing(state.display[sq])) 2 * Layout.PIECE_H else Layout.PIECE_H

    private fun flyer(i: Int, r: SceneResources): PieceView {
        while (flyers.size <= i) flyers += PieceView("flyer${flyers.size}", r, false).also { it.holder.enabled = false; boardRoot.addChild(it.holder) }
        return flyers[i]
    }

    private fun easeInOut(t: Float): Float = t * t * (3f - 2f * t)

    /** Where the moving piece is [e] ms into its steps (board-local, disc bottom). */
    private fun moverAt(a: io.embodit.kingsrow.domain.MoveAnim, e: Long): V3 {
        val n = a.steps.size
        for (i in 0 until n) {
            val start = Timeline.stepStart(i, a.jumps, a.reduced)
            val dur = Timeline.stepDuration(a.jumps, a.reduced)
            val fromSq = if (i == 0) a.from else a.steps[i - 1]
            val toSq = a.steps[i]
            if (e < start) return Layout.square(fromSq)
            if (e < start + dur) {
                val t = ((e - start).toFloat() / dur).coerceIn(0f, 1f)
                if (a.reduced) return if (t < 0.5f) Layout.square(fromSq) else Layout.square(toSq)
                return Layout.arc(Layout.square(fromSq), Layout.square(toSq), easeInOut(t), if (a.jumps) Timeline.JUMP_ARC else Timeline.LIFT_ANIM)
            }
        }
        return Layout.square(a.steps.last())
    }

    /** Collider entity name -> square ("sq:<i>" pads and "pc:<i>" pieces). */
    fun squareOf(name: String?): Int? = when {
        name == null -> null
        name.startsWith("sq:") -> name.removePrefix("sq:").toIntOrNull()
        name.startsWith("pc:") -> name.removePrefix("pc:").toIntOrNull()?.takeIf { it >= 0 }
        else -> null
    }

    fun evidence(): String =
        "pieces=${pieces.size} trayB=${trayDiscs[Side.BLACK]?.count { it.holder.enabled }} trayR=${trayDiscs[Side.RED]?.count { it.holder.enabled }} " +
            "seat=${Layout.seatCard()} cardB=${Layout.trayCard(Side.BLACK)} cardR=${Layout.trayCard(Side.RED)} a1=${Layout.toScene(Layout.square(Sq.parse("a1")!!))} " +
            "h8=${Layout.toScene(Layout.square(Sq.parse("h8")!!))} halfY=$regionHalfY"

    fun dispose() {
        res?.close()
        res = null
    }
}
