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
import io.embodit.kingsrow.domain.WindowAnchor
import io.embodit.kingsrow.domain.WindowPolicy
import io.embodit.kingsrow.ui.Containers
import io.embodit.kingsrow.ui.board.BoardScreen
import io.embodit.kingsrow.ui.control.ControlWindow
import io.embodit.kingsrow.ui.theme.KingsTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the board volume (default container,
 * 0.80 x 0.44 x 0.62 m from the manifest) and the control window (Planar 600 x 640 dp: the longest page fits
 * without scrolling, D44) placed to its right by the system; reopens come up in front (WindowPolicy). Nothing else.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            KingsTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 600.dp, height = 640.dp),
            // D44: first open beside the board; every reopen (叫回面前 / pinch after ×) in front of the user.
            placement = {
                if (WindowPolicy.anchor() == WindowAnchor.IN_FRONT) Placement.none()
                else containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none()
            },
        ) {
            KingsTheme {
                Box(Modifier.windowConstraints(minWidth = 560.dp, minHeight = 560.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
