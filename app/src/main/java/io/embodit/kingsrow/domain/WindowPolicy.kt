package io.embodit.kingsrow.domain

/**
 * Where the control window opens (profile D44, corrected 2026-11-05 afternoon): Shared Space gives no head pose and
 * the system's default placement does not follow the head (a reopen lands where the world started, on top of the board).
 * So the first open AND every reopen ([重开控制窗], the pinch on the board after the system ×) use
 * Placement.right(board volume): the window comes back beside the board and never covers it. Nothing promises
 * a position near the user; the user moves it with the system handle under the window.
 */
enum class WindowAnchor { RIGHT_OF_BOARD }

object WindowPolicy {
    fun anchor(@Suppress("UNUSED_PARAMETER") reopen: Boolean = false): WindowAnchor = WindowAnchor.RIGHT_OF_BOARD

    /**
     * The container the control window is placed beside: the board volume, or (board gone, e.g. the user closed the
     * volume with its system ×) any other open container, or null. Never throws (QA #3: `first {}` could crash).
     */
    fun <T> anchorOf(containers: List<T>, boardId: String, controlId: String, id: (T) -> String): T? =
        containers.firstOrNull { id(it) == boardId } ?: containers.firstOrNull { id(it) != controlId }

    /** What [重开控制窗] does: reopen the panel beside the board, or bring the board back first when it is closed. */
    enum class Reopen { PANEL, BOARD_FIRST }

    fun reopenAction(boardAlive: Boolean): Reopen = if (boardAlive) Reopen.PANEL else Reopen.BOARD_FIRST
}
