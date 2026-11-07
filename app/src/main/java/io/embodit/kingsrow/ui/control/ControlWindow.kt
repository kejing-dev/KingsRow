package io.embodit.kingsrow.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.kingsrow.BuildConfig
import io.embodit.kingsrow.R
import io.embodit.kingsrow.domain.AlertKind
import io.embodit.kingsrow.domain.ConfirmKind
import io.embodit.kingsrow.domain.EndReason
import io.embodit.kingsrow.domain.Language
import io.embodit.kingsrow.domain.Phase
import io.embodit.kingsrow.domain.PlayState
import io.embodit.kingsrow.domain.Settings
import io.embodit.kingsrow.domain.Side
import io.embodit.kingsrow.domain.SidePick
import io.embodit.kingsrow.domain.Turns
import io.embodit.kingsrow.platform.Graph
import io.embodit.kingsrow.platform.TAG
import io.embodit.kingsrow.ui.Containers
import io.embodit.kingsrow.ui.Page
import io.embodit.kingsrow.ui.Ui
import io.embodit.kingsrow.ui.theme.KingsColors
import io.embodit.kingsrow.ui.theme.KingsTheme
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the board): all 2D UI of the app, four pages switched by the
 * native SpatialUI TabBar (D8): Play / New game / Rules / Settings. Dragging and closing are the system window
 * controls under the window (D21); nothing here draws a handle.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // The game picks the page at its milestones: first run -> rules, setup -> new, a game starts -> play.
    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.WELCOME -> Ui.page = Page.RULES
            Phase.SETUP -> Ui.page = Page.NEW
            Phase.IDLE, Phase.THINKING, Phase.RESULT, Phase.CHAIN -> if (Ui.page == Page.NEW || (Ui.page == Page.RULES && !Ui.pickedByUser)) Ui.page = Page.PLAY
            else -> Unit
        }
    }
    LaunchedEffect(Ui.page) {
        // an open confirmation belongs to the page it was opened on (critic suggestion 1)
        Ui.exitConfirm = false
        Ui.newConfirm = false
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.NEW -> NewPage(state, settings, graph)
            Page.RULES -> RulesPage(state, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // Tab labels are taken here, in the app's own language (KingsTheme above), and handed to the system TabBar window
    // as plain strings: resources read inside the tab bar window would follow the system language (gate tabbar-lang).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.NEW, R.drawable.ic_tab_new, stringResource(R.string.tab_new)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    Tabs(tabs)
}

@Composable
private fun PageTitle(text: String, tag: String) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------ Play

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    val cfg = state.config
    if (cfg == null || state.phase in setOf(Phase.SETUP, Phase.WELCOME, Phase.LOADING)) {
        // short page: spread over the whole window, big main button (build.md: blank above + below <= 25 %, no inner band > 20 %)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            PageTitle(stringResource(R.string.tab_play), "play")
            RulesDiagram(120.dp)
            Txt(stringResource(R.string.play_empty), "play/empty", style = PicoTheme.typography.titleMedium, maxLines = 3)
            PrimaryButton(stringResource(R.string.btn_new_game), "play/new", onClick = { Ui.page = Page.NEW }, modifier = Modifier.fillMaxWidth().height(72.dp))
            SecondaryButton(stringResource(R.string.tab_rules), "play/rules", icon = R.drawable.ic_tab_rules, onClick = { Ui.page = Page.RULES }, modifier = Modifier.fillMaxWidth().height(56.dp))
        }
        return
    }
    // The page fills the 640 dp window (D44: blank <= 25 %): turn line, status, a game card, then 64 dp buttons.
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // whose turn + tray score (same source as the seat card and the tray cards, D38)
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.result == null) {
                // while a move / crowning plays, the line still names the side that is moving (same as the seat card)
                val shown = state.crown?.side ?: state.anim?.side ?: state.toMove
                SideDot(shown, 24.dp)
                Spacer(Modifier.width(10.dp))
                Txt(
                    if (shown == cfg.human) stringResource(R.string.turn_you, colorName(cfg.human)) else stringResource(R.string.turn_ai, levelName(cfg.level)),
                    "play/turn", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                )
            } else Spacer(Modifier.weight(1f))
            Txt(stringResource(R.string.score, state.trays[cfg.human].score, state.trays[cfg.ai].score), "play/score", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimary)
        }
        if (state.result == null) StatusLine(state)
        // the game card stays on screen in every state; a confirmation / the result sits under it, the pair centred,
        // so no state leaves a band of empty glass > 25 % of the window (D44, QA #3 q3-1 / q3-2)
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
            GameCard(state)
            when {
                state.confirm == ConfirmKind.RESIGN_OR_RESTART -> ConfirmBlock(graph)
                state.result != null -> ResultBlock(state, graph)
                else -> Unit
            }
        }
        if (state.result == null && state.confirm == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val tall = Modifier.height(64.dp)
                if (state.phase == Phase.CHAIN) {
                    SecondaryButton(stringResource(R.string.btn_back_turn), "play/backturn", onClick = { flow.backTurn() }, modifier = tall.weight(1.3f), icon = R.drawable.ic_undo)
                } else {
                    SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.undo() }, enabled = state.canUndo, modifier = tall.weight(1.3f), icon = R.drawable.ic_undo)
                }
                ToggleButton(stringResource(R.string.btn_hint), "play/hint", on = state.hintOn, onClick = { flow.setHint(!state.hintOn) }, modifier = tall.weight(1.1f), icon = R.drawable.ic_hint)
                ToggleButton(stringResource(R.string.btn_mute), "play/mute", on = !settings.sound, onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, modifier = tall.weight(1f),
                    icon = if (settings.sound) R.drawable.ic_sound_on else R.drawable.ic_sound_off)
            }
            SecondaryButton(stringResource(R.string.btn_resign_restart), "play/resign", onClick = { flow.requestConfirm(ConfirmKind.RESIGN_OR_RESTART) }, icon = R.drawable.ic_flag,
                modifier = Modifier.fillMaxWidth().height(56.dp))
        }
    }
}

/** What a player glances at between moves: opponent, move number, last move, the 40-move count (same PlayState). */
@Composable
private fun GameCard(state: PlayState) {
    val cfg = state.config ?: return
    val last = state.snap.lastMove
    val rows = listOf(
        stringResource(R.string.card_opponent) to "AI ${levelName(cfg.level)} · ${levelSub(cfg.level)}",
        stringResource(R.string.card_move) to ((state.snap.ply / 2) + 1).toString(),
        stringResource(R.string.card_last) to (last?.notation ?: "—"),
        stringResource(R.string.card_no_progress) to "${state.noProgressMoves} / 40",
    )
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(PicoTheme.colorScheme.fillSecondary).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEachIndexed { i, (k, v) ->
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(k, "play/card$i/k", style = PicoTheme.typography.bodyLarge, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(150.dp))
                Txt(v, "play/card$i/v", style = PicoTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusLine(state: PlayState) {
    val cfg = state.config ?: return
    val alert = state.alert
    var coach by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.coachTurns > 0) { while (state.coachTurns > 0) { delay(6000); coach = (coach + 1) % 3 } }
    val count = state.noProgressMoves
    val text = when {
        alert != null && alert.kind == AlertKind.MUST_JUMP -> stringResource(when (alert.pulse.size) { 1 -> R.string.status_must_jump_one; 2 -> R.string.status_must_jump_two; else -> R.string.status_must_jump_many })
        alert != null && alert.kind == AlertKind.STUCK -> stringResource(R.string.status_stuck)
        alert != null && alert.kind == AlertKind.ILLEGAL -> stringResource(R.string.status_illegal)
        alert != null && alert.kind == AlertKind.NOT_YOURS -> stringResource(R.string.status_not_yours)
        alert != null && alert.kind == AlertKind.NO_DRAG -> stringResource(R.string.status_no_drag)
        alert != null && alert.kind == AlertKind.BUSY -> stringResource(if (state.phase == Phase.THINKING) R.string.seat_thinking else R.string.status_busy)
        state.result != null -> ""
        state.restoredUntil != null -> stringResource(R.string.restored)
        state.phase == Phase.CROWNING -> stringResource(if (state.crown?.side == cfg.human) R.string.status_crown_you else R.string.status_crown_ai)
        state.phase == Phase.CHAIN -> stringResource(R.string.seat_chain)
        state.phase == Phase.THINKING -> stringResource(R.string.seat_thinking)
        state.phase == Phase.ANIMATING -> stringResource(if (state.anim?.side == cfg.human) R.string.status_moving else R.string.status_ai_moving)
        state.coachTurns > 0 && state.humansTurn && state.phase == Phase.IDLE -> stringResource(listOf(R.string.coach_1, R.string.coach_2, R.string.coach_3)[coach])
        state.phase == Phase.SELECTED && state.jumpTargets.isNotEmpty() -> stringResource(R.string.status_selected_jump)
        state.phase == Phase.SELECTED -> stringResource(R.string.status_selected)
        state.humansTurn && count >= Turns.SHOW_COUNT_FROM / 2 -> stringResource(R.string.status_your_move) + " · " + stringResource(R.string.no_progress, count)
        state.humansTurn -> stringResource(R.string.status_your_move)
        else -> ""
    }
    val color = if (alert != null && alert.kind != AlertKind.BUSY) KingsColors.ErrorText else PicoTheme.colorScheme.labelPrimary
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.phase == Phase.THINKING) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Txt(text, "play/status", style = PicoTheme.typography.bodyLarge, color = color, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConfirmBlock(graph: Graph) {
    val flow = graph.flow
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(stringResource(R.string.confirm_resign_q), "confirm/q", style = PicoTheme.typography.titleMedium, maxLines = 2)
        Txt(stringResource(R.string.confirm_resign_note), "confirm/note", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        // Buttons share the row equally, cancel on the left (Cloud Air round 5).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = { flow.cancelConfirm() }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_restart), "confirm/restart", onClick = { flow.restart() }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_resign), "confirm/resign", danger = true, onClick = { flow.resign() }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ResultBlock(state: PlayState, graph: Graph) {
    val r = state.result ?: return
    val cfg = state.config ?: return
    val youWin = r.winner == cfg.human
    val title = stringResource(
        when {
            r.winner == -1 && r.reason == EndReason.FORTY_MOVES -> R.string.result_draw_forty
            r.winner == -1 -> R.string.result_draw_repeat
            r.reason == EndReason.RESIGN -> R.string.result_resigned
            youWin && r.reason == EndReason.NO_PIECES -> R.string.result_win_pieces
            youWin -> R.string.result_win_blocked
            r.reason == EndReason.NO_PIECES -> R.string.result_lose_pieces
            else -> R.string.result_lose_blocked
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(title, "result/title", style = PicoTheme.typography.titleLarge, maxLines = 2)
        Txt(stringResource(R.string.result_stats, (state.snap.ply + 1) / 2, state.trays[cfg.human].score, state.trays[cfg.ai].score, levelName(cfg.level)), "result/stats",
            style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_menu), "result/menu", onClick = { graph.flow.toMenu() }, modifier = Modifier.weight(1f).height(56.dp))
        }
        Txt(stringResource(R.string.source_line), "result/source", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

// ------------------------------------------------------------------ New game

@Composable
private fun NewPage(state: PlayState, settings: Settings, graph: Graph) {
    val inGame = state.config != null && state.result == null
    fun start() { graph.flow.newGame(settings.side, settings.level); Ui.newConfirm = false; Ui.page = Page.PLAY }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PageTitle(stringResource(R.string.new_title), "new")
            if (inGame) {
                SecondaryButton(stringResource(R.string.btn_continue), "new/continue", icon = R.drawable.ic_history, onClick = {
                    if (state.phase == Phase.SETUP) graph.flow.continueSaved()
                    Ui.newConfirm = false
                    Ui.page = Page.PLAY
                }, modifier = Modifier.fillMaxWidth())
            }
            OptionRow(stringResource(R.string.new_side), "new/side", labelWidth = 72.dp) {
                Segments(
                    listOf(SidePick.BLACK to stringResource(R.string.new_side_black), SidePick.RED to stringResource(R.string.new_side_red), SidePick.RANDOM to stringResource(R.string.new_side_random)),
                    settings.side, "new/side", { s -> graph.settings.update { it.copy(side = s) } }, Modifier.weight(1f),
                )
            }
            Txt(stringResource(R.string.new_level), "new/level", style = PicoTheme.typography.labelLarge, modifier = Modifier.padding(top = 2.dp))
            for (l in 1..5) ChoiceRow(levelName(l), levelSub(l), "new/level$l", settings.level == l) { graph.settings.update { it.copy(level = l) } }
        }
        // [开始] is pinned to the bottom (D44 首屏); with a game in progress it asks first (D44 丢进度入口)
        Spacer(Modifier.height(8.dp))
        if (Ui.newConfirm) {
            Txt(stringResource(R.string.confirm_new_q), "new/confirm-q", style = PicoTheme.typography.titleMedium, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "new/confirm-cancel", onClick = { Ui.newConfirm = false }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.btn_new_anyway), "new/confirm-yes", danger = true, onClick = { start() }, modifier = Modifier.weight(1f))
            }
        } else {
            PrimaryButton(stringResource(R.string.btn_start), "new/start", onClick = {
                if (inGame) { Ui.newConfirm = true; Log.i(TAG, "CONFIRM NEW S13") } else start()
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(state: PlayState, graph: Graph) {
    Column(Modifier.fillMaxSize()) {
        // every line fits the 640 dp window without scrolling (D44 首屏); the scroll stays only as a safety net
        val welcome = state.phase == Phase.WELCOME
        // without the pinned [开始] the page has room: bigger picture and spacing so the blank stays <= 25 % (D44)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageTitle(stringResource(if (welcome) R.string.welcome_title else R.string.rules_title), "rules")
            RulesDiagram(120.dp)
            listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3, R.string.rules_4).forEach { Txt(stringResource(it), "rules/line", style = PicoTheme.typography.bodyMedium, maxLines = 3) }
            Spacer(Modifier.height(2.dp))
            Txt(stringResource(R.string.rules_mistakes), "rules/mistakes", style = PicoTheme.typography.titleSmall)
            listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).forEach { Txt("· " + stringResource(it), "rules/mistake", style = PicoTheme.typography.bodyMedium, maxLines = 3) }
            Txt(stringResource(R.string.source_line), "rules/source", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        }
        if (state.phase == Phase.WELCOME) {
            Spacer(Modifier.height(10.dp))
            PrimaryButton(stringResource(R.string.btn_start), "rules/welcome-start", onClick = { graph.flow.welcomeDone() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** One picture, four cells: slide · jump · keep jumping · crowned (design panels.rules). */
@Composable
private fun RulesDiagram(cell: androidx.compose.ui.unit.Dp) {
    val labels = listOf(R.string.diagram_slide, R.string.diagram_jump, R.string.diagram_chain, R.string.diagram_crown).map { stringResource(it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until 4) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(cell).semantics { contentDescription = labels[i] }) { diagramCell(i) }
                Txt(labels[i], "rules/diagram$i", style = PicoTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

private fun DrawScope.diagramCell(kind: Int) {
    val n = 4
    val s = size.width / n
    for (r in 0 until n) for (c in 0 until n) {
        drawRect(if ((c + r) % 2 == 1) KingsColors.Walnut else Color(0xFFE6CC9C), topLeft = Offset(c * s, r * s), size = androidx.compose.ui.geometry.Size(s, s))
    }
    fun centre(c: Int, r: Int) = Offset((c + 0.5f) * s, (r + 0.5f) * s)
    fun piece(c: Int, r: Int, black: Boolean, king: Boolean = false) {
        drawCircle(if (black) KingsColors.Ebony else KingsColors.Wine, s * 0.38f, centre(c, r))
        drawCircle(Color.White.copy(alpha = 0.6f), s * 0.38f, centre(c, r), style = Stroke(1.5f))
        if (king) drawCircle(KingsColors.Gold, s * 0.16f, centre(c, r))
    }
    fun arrow(a: Offset, b: Offset, color: Color = KingsColors.Tertiary) {
        drawLine(color, a, b, strokeWidth = s * 0.09f)
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val ux = dx / len; val uy = dy / len
        val head = Path().apply {
            moveTo(b.x + ux * s * 0.12f, b.y + uy * s * 0.12f)
            lineTo(b.x - uy * s * 0.16f - ux * s * 0.1f, b.y + ux * s * 0.16f - uy * s * 0.1f)
            lineTo(b.x + uy * s * 0.16f - ux * s * 0.1f, b.y - ux * s * 0.16f - uy * s * 0.1f)
            close()
        }
        drawPath(head, color)
    }
    when (kind) {
        0 -> { piece(1, 2, true); arrow(centre(1, 2), centre(2, 1)) }
        1 -> { piece(0, 3, true); piece(1, 2, false); arrow(centre(0, 3), centre(2, 1)) }
        2 -> { piece(0, 3, true); piece(1, 2, false); piece(3, 0, false); arrow(centre(0, 3), centre(2, 1)); arrow(centre(2, 1), centre(3, 0).copy(x = centre(3, 0).x + s * 0.3f, y = centre(3, 0).y - s * 0.3f), KingsColors.CaptureLanding) }
        else -> { piece(2, 0, true, king = true); drawCircle(KingsColors.Gold, s * 0.45f, centre(2, 0), style = Stroke(s * 0.06f)) }
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change); graph.flow.settingsChanged() }
    val onOff = listOf(true to stringResource(R.string.on), false to stringResource(R.string.off))
    // every row fits the 640 dp window; [重开控制窗] and [退出应用] are on the first screen (D44)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PageTitle(stringResource(R.string.settings_title), "settings")
        OptionRow(stringResource(R.string.settings_language), "settings/lang") {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        // on / off as a 52 dp two-part control (a bare Switch is ~24 dp, B19)
        OptionRow(stringResource(R.string.settings_sound), "settings/sound") {
            Segments(onOff, settings.sound, "settings/sound", { v -> upd { it.copy(sound = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume") {
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_reduced), "settings/reduced") {
            Segments(onOff, settings.reducedMotion, "settings/reduced", { v -> upd { it.copy(reducedMotion = v) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_coach), "settings/coach") {
            SecondaryButton(stringResource(R.string.settings_coach_again), "settings/coach-again", onClick = {
                upd { it.copy(coachDone = false) }
                Ui.page = Page.RULES
            }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        SecondaryButton(stringResource(R.string.settings_recall), "settings/recall", icon = R.drawable.ic_recall,
            onClick = { graph.window.recall() }, modifier = Modifier.fillMaxWidth())
        val notice = graph.window.boardNotice
        Txt(
            stringResource(when (notice) { 1 -> R.string.board_reopening; 2 -> R.string.board_closed_fail; else -> R.string.settings_recall_hint }),
            "settings/recall-hint", style = PicoTheme.typography.bodySmall,
            color = if (notice == 2) KingsColors.ErrorText else PicoTheme.colorScheme.labelSecondary, maxLines = 2,
        )
        Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about-text", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Spacer(Modifier.weight(1f))
        // D26: quit is the last row; the confirmation opens right here (no second panel).
        if (!Ui.exitConfirm) {
            SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", icon = R.drawable.ic_exit, onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S13") }, modifier = Modifier.fillMaxWidth(), danger = true)
        } else {
            Txt(stringResource(R.string.confirm_exit), "settings/exit-q", style = PicoTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "settings/exit-cancel", onClick = { Ui.exitConfirm = false }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.confirm_yes_exit), "settings/exit-yes", onClick = { exitApp(context, navigator, graph) }, modifier = Modifier.weight(1f), danger = true)
            }
        }
    }
}

/** D26 / D44 exit: release, close both window containers (control window + board volume), remove every task, end. */
internal fun exitApp(context: Context, navigator: SpatialNavigator, graph: Graph) {
    Log.i(TAG, "EXIT release + close all + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground(); graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    runCatching { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
    runCatching { graph.window.detach() }
    runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }
    Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 400)
}

// ------------------------------------------------------------------ names (kept away from the TabBar block)

@Composable
internal fun levelName(level: Int): String = stringResource(
    when (level) { 1 -> R.string.level_1; 2 -> R.string.level_2; 3 -> R.string.level_3; 4 -> R.string.level_4; else -> R.string.level_5 },
)

@Composable
internal fun levelSub(level: Int): String = stringResource(
    when (level) { 1 -> R.string.level_1_sub; 2 -> R.string.level_2_sub; 3 -> R.string.level_3_sub; 4 -> R.string.level_4_sub; else -> R.string.level_5_sub },
)

@Composable
private fun colorName(side: Int): String = stringResource(if (side == Side.BLACK) R.string.color_black else R.string.color_red)

/** The native TabBar (D8). It gets ready-made labels; nothing below reads resources (they would follow the system language). */
@Composable
private fun Tabs(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page; Ui.pickedByUser = true },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}
