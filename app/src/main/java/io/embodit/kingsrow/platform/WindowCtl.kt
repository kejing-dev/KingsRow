package io.embodit.kingsrow.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.kingsrow.domain.LevelGate
import io.embodit.kingsrow.domain.WindowPolicy
import kotlin.math.asin

/**
 * Opens / reopens the control window (native-window-move.md §3.5 and §7 item 9). Every open first asks the
 * [LevelGate]; the board's frame system feeds it the head pitch each frame. The head pose is read ONLY for this
 * gate (is the head level?), never to aim, place or select anything (D1). In Shared Space the head pose is usually
 * not given to the app, so the gate opens after its no-head fallback (1.5 s). "Bring it back" = close, wait for
 * ON_DESTROY, then open again (§3.5).
 */
class WindowCtl {
    val gate = LevelGate()
    var levelHint by mutableStateOf(false)
        private set
    var panelState by mutableStateOf("never")
        private set
    /** The board volume's composition is alive (attach / detach). */
    var boardAlive by mutableStateOf(false)
        private set
    /** Set when [重开控制窗] found the board closed: 0 none, 1 reopening the board, 2 could not reopen it. */
    var boardNotice by mutableStateOf(0)
        private set

    private var open: (() -> Unit)? = null
    private var close: (() -> Unit)? = null
    private var closeBoard: (() -> Unit)? = null
    private var recallPending = false
    private var lastStepMs = 0L
    private val main = Handler(Looper.getMainLooper())
    private var hmd: HMDTrackingProvider? = null
    private var hmdTried = false

    val closed: Boolean get() = panelState == "ON_DESTROY"

    /** The board screen hands over the navigator calls (the window's own composition can't reopen itself). */
    fun attach(open: () -> Unit, close: () -> Unit, closeBoard: () -> Unit) {
        this.open = open
        this.close = close
        this.closeBoard = closeBoard
        boardAlive = true
        boardNotice = 0
    }

    /** Debug: close only the board volume (like its system ×). */
    fun closeBoardOnly() { Log.i(TAG, "WINDOW close board (debug)"); closeBoard?.invoke() }

    /** D26 / D44 exit: close both containers (control window, then the board volume). */
    fun closeAll() {
        Log.i(TAG, "WINDOW close all (exit)")
        runCatching { close?.invoke() }.onFailure { Log.w(TAG, "close control: $it") }
        runCatching { closeBoard?.invoke() }.onFailure { Log.w(TAG, "close board: $it") }
    }

    fun detach() {
        open = null; close = null; closeBoard = null
        boardAlive = false
        gate.cancel()
        main.removeCallbacks(watchdog)
        runCatching { hmd?.stop() }
        hmd = null
        hmdTried = false
    }

    /** Ask for the window; it opens once the head is level (or the fallback fires). */
    fun request(why: String) {
        gate.ask(why, SystemClock.elapsedRealtime())
        Log.i(TAG, "WINDOW ask why=$why state=$panelState")
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 3000)
    }

    /**
     * Settings [重开控制窗]: close, wait for ON_DESTROY, open again beside the board (Placement.right, D44 corrected;
     * the copy only says "next to the board"). No level gate wait: Shared Space gives no head pose.
     */
    fun recall(reopenBoard: () -> Unit = {}) {
        if (WindowPolicy.reopenAction(boardAlive) == WindowPolicy.Reopen.BOARD_FIRST) {
            // the board volume was closed: the panel has nothing to sit beside, so bring the board back instead
            Log.i(TAG, "WINDOW recall: board closed -> reopen board")
            boardNotice = if (runCatching(reopenBoard).onFailure { Log.w(TAG, "WINDOW reopen board failed: $it") }.isSuccess) 1 else 2
            return
        }
        if (panelState == "never" || panelState == "ON_DESTROY") openNow("recall", null)
        else if (!recallPending) { recallPending = true; Log.i(TAG, "WINDOW recall close"); close?.invoke() }
    }

    /** The control window was closed with the system ×: a pinch on the board reopens it beside the board. */
    fun reopen(why: String) {
        openNow(why, null)
    }

    /** Debug: close the window like the system ×. */
    fun closeNow() { Log.i(TAG, "WINDOW close (debug)"); close?.invoke() }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (recallPending && event == "ON_DESTROY") { recallPending = false; openNow("recall", null) }
    }

    /** Called every frame by the board's ECS system. */
    fun stepFrame() {
        val now = SystemClock.elapsedRealtime()
        lastStepMs = now
        if (!gate.waiting) return
        val pitch = headPitch()
        levelHint = gate.hintWanted(pitch)
        gate.step(now, pitch)?.let { why -> levelHint = false; openNow(why, pitch) }
    }

    private fun openNow(why: String, pitch: Float?) {
        main.removeCallbacks(watchdog)
        gate.cancel()
        levelHint = false
        Log.i(TAG, "WINDOW open why=$why pitch=$pitch anchor=${WindowPolicy.anchor()}")
        open?.invoke()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) { val why = gate.pending!!; gate.cancel(); levelHint = false; openNow("$why/noframes", null) } else main.postDelayed(this, 3000)
        }
    }

    /** Head pitch in degrees (+ up), or null without a valid head pose (all zero / identity counts as invalid). */
    private fun headPitch(): Float? {
        if (!hmdTried) {
            hmdTried = true
            hmd = runCatching {
                HMDTrackingProvider().also { p ->
                    val r = p.start()
                    Log.i(TAG, "HMD start=$r support=${p.supportState} state=${p.state}")
                }
            }.onFailure { Log.w(TAG, "HMD unavailable: $it") }.getOrNull()
        }
        val provider = hmd ?: return null
        if (provider.state != DataProvider.State.STARTED) return null
        // gate:allow head-pose only reads the head pitch for the window level gate (D9), never aims or places
        val pose = runCatching { provider.latestData?.hmdPose }.getOrNull() ?: return null
        val q = pose.rotation
        val p = pose.position
        if (p.x == 0f && p.y == 0f && p.z == 0f && q.w == 1f && q.x == 0f && q.y == 0f && q.z == 0f) return null
        val f = q.rotateVector(Vector3(0f, 0f, -1f))
        return Math.toDegrees(asin(f.y.coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}
