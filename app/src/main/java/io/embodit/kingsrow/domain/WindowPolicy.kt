package io.embodit.kingsrow.domain

/**
 * Where the control window opens (profile D44 第 1 条): the first open sits beside the board volume
 * (Placement.right), every reopen ("叫回面前", the pinch-to-reopen after the system ×) uses the system's
 * in-front default (Placement.none()). Reusing Placement.right for a reopen only reopens it where it was.
 */
enum class WindowAnchor { RIGHT_OF_BOARD, IN_FRONT }

object WindowPolicy {
    @Volatile var reopened: Boolean = false

    fun anchor(reopen: Boolean = reopened): WindowAnchor = if (reopen) WindowAnchor.IN_FRONT else WindowAnchor.RIGHT_OF_BOARD
}
