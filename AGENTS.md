# Kings Row 西洋跳棋: project guidance

English / American checkers for PICO OS 6 Shared Space, PICO Spatial SDK 6.1.9, package `io.embodit.kingsrow`,
v0.1.0. Design contract: factory `apps/kings-row/design-package.json` (v1 = everything outside `v2[]`), sample
`apps/kings-row/sample.html`. Started from the Cloud Air skeleton (gradle, fonts, theme pattern, SoundPool, icon
builder); the domain, engine and scene are new.

Two surfaces:
- **Board volume** (default container `KingsBoard`, Volumetric 0.80 x 0.44 x 0.62 m, fixed world scale, gravity
  aligned): oak board 0.56 m tipped 20° about its near bottom edge, 24 discs (Ø 4.6 x 1.0 cm; a king = two discs),
  two trays (Black's on the right, Red's on the left) with 3 stacks + a spare cell, an upright seat card behind the
  far edge and a "吃了 N" card on each tray. No buttons in the volume.
- **Control window** (`WindowContainer("KingsControl")`, Planar 560 x 440 dp, `Placement.right(board)`), native
  `TabBar` with four pages: 对局 / 新局 / 规则 / 设置 (exit with in-page confirm at the bottom of 设置).

## Rules that stay in force
- SpatialUI + PicoTheme only (no Material). Copy in `res/values` + `res/values-zh`; the language setting wraps the
  context (ContextWrapper overriding getResources only). AttachmentPanel content needs its own `KingsTheme {}`.
- Rules in `domain/Checkers.kt` only (perft 1..7 = 7/49/302/1469/7361/36768/179740 in the tests). Jumps forced,
  chains must finish, crowning ends the turn, men never jump backwards, 40 moves without capture/crowning = draw,
  threefold repetition = draw, no move / no piece = loss.
- `GameFlow` commits and saves a move before anything moves; the scene draws `PlayState` (anim / crown carry their
  start time) and the flow moves on by `Timeline` durations itself. Chain prefixes are saved (restore -> S07).
- Per-frame motion lives in `BoardScene.onFrame` (ECS `BoardFrameSystem`), never in Compose.
- Input: pinch / trigger arrive through `detectSpatialTapGesture` on `sq:<i>` pads and `pc:<i>` piece colliders;
  drag through `detectSpatialDragGesture` (drop snaps to the nearest dark square within 1.5 squares). The head pose
  is read only by `WindowCtl` for the D9 level gate (Shared Space reports NOT_IN_FULL_SPACE -> 1.5 s fallback).

## Layout
| Path | What |
| --- | --- |
| `domain/Checkers.kt` | squares (r = 0 far edge, a1 near-left dark), pieces, `Position`, `Move`, `Rules` |
| `domain/Engine.kt` | alpha-beta, iterative deepening, capture extension, history, 5 `Level`s + hint level |
| `domain/Game.kt` | trays, snapshots, `Turns` (play / results / crown source), `Timeline`, `PlayState`, `Phase` |
| `domain/GameFlow.kt` | turns, AI timing, input, undo / back-turn, hint, confirm, save, debug hooks |
| `domain/Layout.kt` | every metre of the volume + the D35 occlusion check used by the unit test |
| `domain/SaveCodec.kt`, `data/Stores.kt` | line-format save (atomic + .bak), settings |
| `ui/board/*` | `BoardScreen` (SpatialView, gestures, cards), `BoardScene`, `SceneResources`, `BoardTextures` |
| `ui/control/*` | `ControlWindow` (4 pages + TabBar), `Widgets` |
| `platform/*` | Application / Graph / SoundPlayer, `WindowCtl` (+ `LevelGate`), debug `DebugCommandReceiver` |
| `tools/` | `build_icon.py` (layered icon, Pillow), `make_sounds.py` (numpy), `svg2vector.py` (PICO ICON 7.0) |

## Build / run (macOS, kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest --console=plain
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n io.embodit.kingsrow/.platform.LaunchActivity
```
Emulators on this machine are shared: `python3 -I bin/emu_borrow.py borrow kings-row` / `return kings-row`.
If another app's window is left open on the borrowed emulator it takes the centre slot and pushes the volume aside;
force-stop that leftover app before screenshots.

## Debug broadcast (debug builds) — same entry points as real input
`adb shell am broadcast -a io.embodit.kingsrow.DEBUG -p io.embodit.kingsrow --es cmd <cmd> [--es a "<arg>"]`

| cmd | effect |
| --- | --- |
| `goto S01..S17` | S01 rules page (welcome) · S02 new page · S03 a game · S04 c3 selected (blue dots) · S05 jump c3xe5xg7 frozen at 260 ms (captured piece in the air; `freeze off` after) · S06 AI thinking (hold on; `hold off` after) · S07 fork after c3xe5 (green ring + two orange rings) · S08 must-jump alert (tap g3, c3 pulses) · S09 win by c3xe5 · S10 control window closed · S11 settings · S12 rules · S13 resign/restart confirm · S16 continue saved game · S17 crowning c7-d8 frozen mid-flight (`freeze off` after) |
| `tap c3` · `move c3-d4` / `move c3xe5xg7` · `nothing` | pinch on a square / a whole move by taps / pinch on empty air |
| `setup "b:c3;B:h8;r:d4;R:a1"` (`... red` = Red to move) | replace the position |
| `new "black 1"` / `new "red 3"` / `new "random 5"` · `undo` · `backturn` · `hint on|off` · `resign` · `restart` · `confirm` · `cancel` | buttons |
| `freeze 260|off` · `hold on|off` · `seed N` | screenshot helpers |
| `lang zh|en|system` · `sound on|off` · `volume 0-2` · `reduced on|off` · `coach on|off` · `level 1-5` | settings |
| `page play|new|rules|settings` · `exitask` · `closepanel` · `reopen` · `recall` · `dump` · `evidence` | window |

S00 = the first 0.5 s after a cold start (fallback slab + discs). Log tag `KingsRow`: `GAME`, `TURN`, `SELECT`,
`MOVE`, `CHAIN`, `CROWN`, `AI think|move`, `ALERT`, `UNDO`, `RESULT`, `WINDOW`, `PAGE`, `OVERFLOW`, `SCENE`, `CMD`.

## QA screenshot list
S00 S01 S02 S03(+首局引导版) S04 S05(中段) S06 S07 S08 S09 S10 S11 S12 S13(认输/重开 + 退出) S16(+连跳中途) S17(中段 + 落定),
each in 中文 and English; modifiers: 减少动态 (S05/S17), 静音键 on.
