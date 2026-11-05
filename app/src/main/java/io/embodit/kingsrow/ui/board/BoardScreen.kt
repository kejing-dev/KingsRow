package io.embodit.kingsrow.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.kingsrow.R
import io.embodit.kingsrow.domain.AlertKind
import io.embodit.kingsrow.domain.Layout
import io.embodit.kingsrow.domain.Phase
import io.embodit.kingsrow.domain.PlayState
import io.embodit.kingsrow.domain.Side
import io.embodit.kingsrow.platform.Graph
import io.embodit.kingsrow.platform.TAG
import io.embodit.kingsrow.ui.Containers
import io.embodit.kingsrow.ui.theme.KingsColors
import io.embodit.kingsrow.ui.theme.KingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the board's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
}

private const val SEAT = "seatCard"
private const val CARD_BLACK = "trayCardBlack"
private const val CARD_RED = "trayCardRed"

/**
 * The board volume (default container). Opens the control window beside it through the level gate, shows the board
 * and routes a pinch / trigger on a square or a piece (tap) and a drag of a piece to [GameFlow]. Eye-hand and
 * controller both arrive here through the system pointer; the head direction is never used to pick (D1).
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val converter = LocalPhysicalLengthConverter.current
    val scene = remember { BoardScene() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    fun pxToM(px: Float): Float = with(converter) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }

    // Background / foreground (design container.lifecycle): stop the AI search and settle the move in flight when
    // the board volume stops; restart the AI turn when it comes back. Every move is on disk already.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> graph.flow.onBackground()
                Lifecycle.Event.ON_START -> graph.flow.onForeground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(navigator) {
        graph.window.attach(
            open = { navigator.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) },
            close = { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) },
            closeBoard = { navigator.closeWindowContainer() },
        )
        graph.window.request("start")
        onDispose {
            graph.window.detach()
            runCatching { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
        }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectSpatialTapGesture(context, TargetEntity.any()) { tap ->
                    val name = tap.targetEntity?.getName()
                    Log.i(TAG, "TAP target=$name kind=${tap.interactionKind}")
                    // S10: with the control window closed, a pinch anywhere on the board brings it back in front;
                    // the same pinch still counts on the board (critic suggestion 3)
                    if (graph.window.closed) graph.window.reopen("pinch")
                    val sq = scene.squareOf(name)
                    if (sq != null) graph.flow.tap(sq) else graph.flow.tapNothing()
                }
            }
            .pointerInput(Unit) {
                detectSpatialDragGesture(
                    context = context,
                    targetedToEntity = TargetEntity.any(),
                    onDragStart = { _, v ->
                        val sq = scene.squareOf(v.targetEntity?.getName())
                        Log.i(TAG, "DRAG start target=${v.targetEntity?.getName()}")
                        if (sq != null && graph.flow.dragStart(sq)) scene.beginDrag(sq)
                    },
                    onDragEnd = { val to = scene.endDrag(); graph.flow.dragEnd(to) },
                    onDragCancel = { scene.endDrag(); graph.flow.dragEnd(null) },
                ) { v -> scene.dragBy(pxToM(v.dragAmount.x), -pxToM(v.dragAmount.y), pxToM(v.dragAmount.z)) }
            },
        attachments = {
            AttachmentPanel(SEAT) { KingsTheme { SeatCard(state, graph, converter.lengthToDp(Layout.SEAT_W, LengthUnit.Meters), converter.lengthToDp(Layout.SEAT_H, LengthUnit.Meters)) } }
            AttachmentPanel(CARD_BLACK) { KingsTheme { TrayCard(state, Side.BLACK, converter.lengthToDp(Layout.TRAY_CARD_W, LengthUnit.Meters), converter.lengthToDp(Layout.TRAY_CARD_H, LengthUnit.Meters)) } }
            AttachmentPanel(CARD_RED) { KingsTheme { TrayCard(state, Side.RED, converter.lengthToDp(Layout.TRAY_CARD_W, LengthUnit.Meters), converter.lengthToDp(Layout.TRAY_CARD_H, LengthUnit.Meters)) } }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            // S00: a plain slab and 24 discs first, then the real board.
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle "首帧兜底形状 平板 + 24 个圆柱")
                    val slab = MeshResource.createBox(Vector3(Layout.BOARD, Layout.THICK, Layout.BOARD), cornerRadius = 0.006f)
                    // gate:allow geometry S00 loading stand-in discs
                    val disc = MeshResource.createCylinder(radius = Layout.PIECE_R, height = Layout.PIECE_H)
                    val wood = PhysicallyBasedMaterial.create().apply { setBaseColor(KingsColors.Oak.let { Color4(it.red, it.green, it.blue, 1f) }); setRoughness(0.7f) }
                    val dark = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.2f, 0.17f, 0.15f, 1f)); setRoughness(0.5f) }
                    listOf(slab, disc, wood, dark)
                }.getOrNull()
            }
            quick?.let { scene.showFallback(it[0] as MeshResource, it[1] as MeshResource, it[2] as PhysicallyBasedMaterial, it[3] as PhysicallyBasedMaterial) }
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            scene.install(resources, attachments.entity(SEAT), attachments.entity(CARD_BLACK), attachments.entity(CARD_RED))
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, settings)
        },
    )
}

/** Seat card behind the far edge: whose turn / thinking / chain / crowned / result; same state as the window (D38). */
@Composable
private fun SeatCard(state: PlayState, graph: Graph, w: Dp, h: Dp) {
    val window = graph.window
    val text = when {
        window.levelHint -> stringResource(R.string.level_hint)
        window.closed -> stringResource(R.string.seat_panel_closed)
        else -> seatText(state)
    }
    val dot = if (state.config != null && state.result == null && state.phase != Phase.SETUP && !window.closed && !window.levelHint) (state.crown?.side ?: state.anim?.side ?: state.toMove) else null
    CardBox(w, h) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = h * 0.25f)) {
            if (dot != null) {
                Box(Modifier.size(h * 0.42f).clip(CircleShape).background(if (dot == Side.BLACK) KingsColors.Ebony else KingsColors.Wine).border(h * 0.04f, Color.White, CircleShape))
                Spacer(Modifier.width(h * 0.22f))
            }
            val avail = w.value - h.value * 0.5f - (if (dot != null) h.value * 0.64f else 0f)
            val units = text.sumOf { ch -> if (ch.code >= 0x2E80) 1.0 else 0.56 }.toFloat()
            val one = minOf(h.value * 0.36f, avail / units)
            if (one >= h.value * 0.30f) CardText(text, "seat", one.sp)
            else CardText(text, "seat", minOf(h.value * 0.30f, avail / (units * 0.55f)).sp, lines = 2)
        }
    }
}

@Composable
internal fun seatText(state: PlayState): String {
    val cfg = state.config
    val alert = state.alert
    return when {
        // feedback next to the action (D45): the control window sits to the side, so the reason is on the board too
        alert != null -> stringResource(when (alert.kind) {
            AlertKind.MUST_JUMP -> R.string.seat_must_jump
            AlertKind.STUCK -> R.string.status_stuck
            AlertKind.ILLEGAL -> R.string.seat_illegal
            AlertKind.NOT_YOURS -> R.string.seat_not_yours
            AlertKind.NO_DRAG -> R.string.seat_no_drag
            AlertKind.BUSY -> if (state.phase == Phase.THINKING) R.string.seat_thinking else R.string.seat_busy
        })
        state.restoredUntil != null && state.phase != Phase.RESULT -> stringResource(R.string.restored)
        cfg == null || state.phase in setOf(Phase.LOADING, Phase.WELCOME, Phase.SETUP) -> stringResource(R.string.seat_setup)
        state.result != null -> stringResource(
            when {
                state.result.winner == -1 -> R.string.seat_draw
                state.result.winner == cfg.human -> R.string.seat_win
                else -> R.string.seat_lose
            },
        )
        state.phase == Phase.CROWNING -> stringResource(R.string.seat_crown)
        state.phase == Phase.CHAIN -> stringResource(R.string.seat_chain)
        state.phase == Phase.THINKING -> stringResource(R.string.seat_thinking)
        state.phase == Phase.ANIMATING -> stringResource(if (state.anim?.side == cfg.human) R.string.status_moving else R.string.status_ai_moving)
        state.phase in setOf(Phase.IDLE, Phase.SELECTED) && state.toMove == cfg.human -> stringResource(R.string.seat_your_move, stringResource(if (cfg.human == Side.BLACK) R.string.color_black else R.string.color_red))
        else -> ""
    }
}

/** "吃了 5" on the front rim of each tray. */
@Composable
private fun TrayCard(state: PlayState, side: Int, w: Dp, h: Dp) {
    CardBox(w, h) { CardText(stringResource(R.string.tray_count, state.trays[side].score), "tray$side", (h.value * 0.5f).sp) }
}

@Composable
private fun CardBox(w: Dp, h: Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(w, h).clip(RoundedCornerShape(h * 0.3f)).background(KingsColors.CardBg),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CardText(text: String, tag: String, size: TextUnit, lines: Int = 1) {
    Text(
        text, color = KingsColors.Ink, fontSize = size, lineHeight = size * 1.1f, fontWeight = FontWeight.SemiBold, maxLines = lines, softWrap = lines > 1, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW card/$tag \"$text\"") },
    )
}

