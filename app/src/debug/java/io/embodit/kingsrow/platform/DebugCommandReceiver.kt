package io.embodit.kingsrow.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.embodit.kingsrow.domain.ConfirmKind
import io.embodit.kingsrow.domain.GameConfig
import io.embodit.kingsrow.domain.Language
import io.embodit.kingsrow.domain.Move
import io.embodit.kingsrow.domain.Phase
import io.embodit.kingsrow.domain.Position
import io.embodit.kingsrow.domain.Side
import io.embodit.kingsrow.domain.SidePick
import io.embodit.kingsrow.domain.Sq
import io.embodit.kingsrow.ui.Page
import io.embodit.kingsrow.ui.Ui
import io.embodit.kingsrow.ui.board.SceneHolder

/**
 * Debug builds only. Every command goes through the same GameFlow calls the board and the window use
 * (tap = the pinch / trigger path; there are no shortcuts that write the state directly).
 *
 *   adb shell am broadcast -a io.embodit.kingsrow.DEBUG -p io.embodit.kingsrow --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S01..S17           reach a named state (see AGENTS.md for the set-up each one uses)
 *   tap c3 · move c3e5 (or c3xe5xg7, taps every square) · nothing (pinch on empty air)
 *   setup "b:c3,e3;B:h8;r:d4;R:a1" [+ " red" for Red to move]
 *   new "black|red|random 1-5" · undo · backturn · hint on|off · resign · restart · confirm · cancel
 *   freeze 250 | freeze off   hold the running animation at 250 ms (flow waits too) · hold on|off (AI keeps thinking)
 *   lang zh|en|system · sound on|off · volume 0-2 · reduced on|off · level 1-5 · seed N
 *   page play|new|rules|settings · exitask · closepanel · reopen · recall · dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim()
        val on = a == "on" || a == "true" || a == "1"
        val main = Handler(Looper.getMainLooper())
        Log.i(TAG, "CMD $cmd $a")
        fun sq(s: String) = Sq.parse(s)
        fun settings(change: (io.embodit.kingsrow.domain.Settings) -> io.embodit.kingsrow.domain.Settings) { graph.settings.update(change); flow.settingsChanged() }
        fun ensureGame() {
            val st = flow.state.value
            if (st.config == null || st.phase in setOf(Phase.SETUP, Phase.WELCOME, Phase.RESULT)) flow.newGame(SidePick.BLACK, 2)
            Ui.page = Page.PLAY
        }
        fun setup(spec: String, toMove: Int = Side.BLACK) {
            flow.setup(Position.setup(spec, toMove), GameConfig(Side.BLACK, flow.state.value.config?.level ?: 2))
            Ui.page = Page.PLAY
        }
        fun taps(vararg squares: String) = squares.mapNotNull(::sq).forEach { flow.tap(it) }
        when (cmd) {
            "goto" -> when (a.uppercase()) {
                "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: force-stop and start, then capture at once")
                "S01" -> { settings { it.copy(firstRunDone = false) }; flow.showWelcome(); Ui.page = Page.RULES }
                "S02" -> { flow.toMenu(); Ui.page = Page.NEW }
                "S03" -> ensureGame()
                "S04" -> { setup("b:a3,c3,e3,g3,b2,d2,f2,h2;r:b6,d6,f6,h6,a7,c7"); taps("c3") }
                "S05" -> { setup("b:c3,a1;r:d4,f6,h8"); flow.freeze(260); taps("c3", "e5") }
                "S06" -> { flow.holdThinking = true; setup("b:c3,e3,g3,b2;r:b6,d6,f6,h6", Side.RED) }
                "S07" -> { setup("b:c3,a1;r:d4,d6,f6,h8"); taps("c3", "e5") }
                "S08" -> { setup("b:c3,g3,a1;r:d4,h8"); taps("g3") }
                "S09" -> { setup("b:c3,a1;r:d4"); taps("c3", "e5") }
                "S10" -> graph.window.closeNow()
                "S11" -> Ui.page = Page.SETTINGS
                "S12" -> Ui.page = Page.RULES
                "S13" -> { ensureGame(); flow.requestConfirm(ConfirmKind.RESIGN_OR_RESTART) }
                "S16" -> { Log.i(TAG, "restore=${flow.continueSaved()}"); Ui.page = Page.PLAY }
                "S17" -> {
                    setup("b:c7,a1;r:h8,a5")
                    taps("c7", "d8")
                    main.postDelayed({ flow.freeze(260) }, 420)
                }
                else -> Log.w(TAG, "unknown state $a")
            }
            "tap" -> sq(a)?.let { flow.tap(it) }
            "nothing" -> flow.tapNothing()
            "move" -> Move.parse(a)?.let { (from, path) -> flow.tap(from); path.forEach { s -> flow.tap(s) } }
            "setup" -> {
                val red = a.endsWith(" red")
                setup(a.removeSuffix(" red").trim().trim('"'), if (red) Side.RED else Side.BLACK)
            }
            "new" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                val pick = when (f.getOrNull(0)) { "red" -> SidePick.RED; "random" -> SidePick.RANDOM; else -> SidePick.BLACK }
                flow.newGame(pick, f.getOrNull(1)?.toIntOrNull() ?: 2); Ui.page = Page.PLAY
            }
            "undo" -> flow.undo()
            "backturn" -> flow.backTurn()
            "hint" -> flow.setHint(on)
            "resign" -> flow.resign()
            "restart" -> flow.restart()
            "confirm" -> flow.requestConfirm(ConfirmKind.RESIGN_OR_RESTART)
            "cancel" -> flow.cancelConfirm()
            "freeze" -> flow.freeze(if (a == "off") null else a.toLongOrNull())
            "hold" -> flow.holdThinking = on
            "seed" -> a.toIntOrNull()?.let { flow.aiSeed = it }
            "level" -> a.toIntOrNull()?.let { l -> settings { it.copy(level = l.coerceIn(1, 5)) } }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "coach" -> settings { it.copy(coachDone = !on) }
            "page" -> Ui.page = when (a) { "new" -> Page.NEW; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "closepanel" -> graph.window.closeNow()
            "reopen" -> graph.window.request("debug")
            "recall" -> graph.window.recall()
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
