package io.embodit.kingsrow.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The board is the default container (manifest). */
object Containers {
    const val BOARD = "KingsBoard"
    const val CONTROL = "KingsControl"
    const val CONTROL_TAG = "control"
}

enum class Page(val stateId: String) { PLAY("S03"), NEW("S02"), RULES("S12"), SETTINGS("S11") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.RULES)
    var exitConfirm by mutableStateOf(false)
    /** The user opened the current page from the TabBar (the game then leaves it alone). */
    var pickedByUser = false
}
