package io.embodit.kingsrow.domain

/**
 * Where the control window opens (profile D44, corrected 2026-11-05 afternoon): Shared Space gives no head pose and
 * Placement.none() does not follow the head (a reopen lands at the world's initial front, right on top of the board).
 * So the first open AND every reopen ([重开控制窗], the pinch on the board after the system ×) use
 * Placement.right(board volume): the window comes back beside the board and never covers it. Nothing promises
 * "in front of you"; the user moves it with the system handle under the window.
 */
enum class WindowAnchor { RIGHT_OF_BOARD }

object WindowPolicy {
    fun anchor(@Suppress("UNUSED_PARAMETER") reopen: Boolean = false): WindowAnchor = WindowAnchor.RIGHT_OF_BOARD
}
