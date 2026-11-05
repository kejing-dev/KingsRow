package io.embodit.kingsrow

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.kingsrow.ui.Containers
import io.embodit.kingsrow.ui.board.BoardScreen
import io.embodit.kingsrow.ui.control.ControlWindow
import io.embodit.kingsrow.ui.theme.KingsTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the board volume (default container,
 * 0.80 x 0.44 x 0.62 m from the manifest) and the control window (Planar 560 x 440 dp) placed to its right by the
 * system. Nothing else.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            KingsTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 440.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            KingsTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 360.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
