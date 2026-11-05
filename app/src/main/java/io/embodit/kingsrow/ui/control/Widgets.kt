package io.embodit.kingsrow.ui.control

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.kingsrow.domain.Side
import io.embodit.kingsrow.platform.TAG
import io.embodit.kingsrow.ui.theme.KingsColors

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; the ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 56.dp, minWidth = 140.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(
    text: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    @DrawableRes icon: Int? = null,
) {
    val fg = if (danger) KingsColors.OnError else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 88.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) KingsColors.Error else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Box(Modifier.width(6.dp))
        }
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A switch-like button (D19): its on / off state is the fill plus a small lamp on the left. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier,
        size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 88.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) KingsColors.PrimaryContainer else Color.Transparent).border(1.5.dp, fg, CircleShape))
        }
        Box(Modifier.width(6.dp))
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A labelled row holding a same-page option switch (SegmentControl never changes pages). */
@Composable
internal fun OptionRow(label: String, tag: String, labelWidth: Dp = 156.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(labelWidth))
        content()
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SegmentControl(modifier = modifier) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Txt(label, "$tag/segment", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
                },
            )
        }
    }
}

/** One choice in a vertical list (AI levels): the whole row is the button, selected = wine fill. */
@Composable
internal fun ChoiceRow(title: String, subtitle: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    val fg = if (selected) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // design-style: inherited-content-color Button
            Txt(title, "$tag/title", style = PicoTheme.typography.labelLarge, color = fg, modifier = Modifier.width(110.dp))
            // design-style: inherited-content-color Button
            Txt(subtitle, "$tag/sub", style = PicoTheme.typography.bodyMedium, color = fg, modifier = Modifier.weight(1f))
        }
    }
}

/** A piece-coloured dot for whose turn it is (ebony with a white rim / wine red). */
@Composable
internal fun SideDot(side: Int, size: Dp = 20.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (side == Side.BLACK) KingsColors.Ebony else KingsColors.Wine)
            .border(2.dp, Color.White, CircleShape),
    )
}

@Composable
internal fun Section(content: @Composable () -> Unit) {
    Column { content() }
}
