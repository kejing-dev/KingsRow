package io.embodit.kingsrow.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.kingsrow.domain.Language
import io.embodit.kingsrow.platform.Graph
import java.util.Locale

/** design theme.colorScheme: old card-room table, light oak + wine red + ebony, amber accent. */
object KingsColors {
    val Primary = Color(0xFF7A1F2B)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFF6D5D9)
    val OnPrimaryContainer = Color(0xFF3B0810)
    val Secondary = Color(0xFF8A6A3E)
    val SecondaryContainer = Color(0xFFF1E3CC)
    val OnSecondaryContainer = Color(0xFF3E2A0E)
    val Tertiary = Color(0xFFD99A2B)
    val OnTertiary = Color(0xFF2B1D05)
    val TertiaryContainer = Color(0xFFFFE2A8)
    val OnTertiaryContainer = Color(0xFF3E2A00)
    val Error = Color(0xFFC8423F)
    val OnError = Color(0xFFFFFFFF)
    /** Error-coloured TEXT on the window glass: darker than Error so it keeps >= 4.5:1 (ui-audit contrast table). */
    val ErrorText = Color(0xFF7A1A18)
    val Ink = Color(0xFF1B1F23)
    val InkSoft = Color(0xFF3D434A)

    // board marks (design theme.boardMarks)
    val SelectBlack = Color(0xFF2A2A2A)
    val Target = Color(0xFF3A7BD5)
    val CaptureLanding = Color(0xFFE07A2A)
    val ChainGreen = Color(0xFF3F8A5A)

    // 3D wood skin
    val Oak = Color(0xFFD9B98A)
    val OakEdge = Color(0xFFB8925E)
    val Walnut = Color(0xFF6B4A2E)
    val Ebony = Color(0xFF1E1A18)
    val Wine = Color(0xFF7A1F2B)
    val Gold = Color(0xFFE2B347)
    val CardBg = Color(0xF2FBF6EC)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun KingsTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = KingsColors.OnPrimary,
        labelPrimary = KingsColors.Ink,
        labelSecondary = KingsColors.InkSoft,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = KingsColors.Error,
        alert = KingsColors.Tertiary,
        passable = KingsColors.ChainGreen,
        interaction = KingsColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
