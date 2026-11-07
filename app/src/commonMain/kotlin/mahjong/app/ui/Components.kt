package mahjong.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.MjMotion
import mahjong.app.theme.MjRadius
import mahjong.app.theme.MjSpace
import androidx.compose.animation.core.CubicBezierEasing

val MjEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
val MjExitEase = CubicBezierEasing(0.4f, 0f, 1f, 1f)

@Composable
fun MjText(
    text: String,
    style: TextStyle = LocalMjType.current.body,
    color: Color = LocalMjColors.current.ink,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

@Composable
fun MjCard(
    modifier: Modifier = Modifier,
    color: Color = LocalMjColors.current.surface,
    borderColor: Color = LocalMjColors.current.line,
    radius: Dp = MjRadius.card,
    padding: Dp = MjSpace.cardPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(color)
            .border(1.dp, borderColor, RoundedCornerShape(radius))
            .padding(padding),
        content = content,
    )
}

@Composable
private fun pressScale(source: MutableInteractionSource, pressedScale: Float = 0.985f): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(MjMotion.micro, easing = MjEase),
        label = "press-scale",
    )
    return scale
}

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subLabel: String? = null,
    height: Dp = 52.dp,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(MjRadius.control))
            .background(if (enabled) colors.primary else colors.surface3)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MjText(
            text = label,
            style = type.title,
            color = if (enabled) colors.onPrimary else colors.disabled,
        )
        if (subLabel != null) {
            Spacer(Modifier.width(8.dp))
            MjText(
                text = subLabel,
                style = type.caption,
                color = if (enabled) colors.onPrimary.copy(alpha = 0.72f) else colors.disabled,
            )
        }
    }
}

@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    enabled: Boolean = true,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(MjRadius.control))
            .border(1.dp, colors.line, RoundedCornerShape(MjRadius.control))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MjText(label, type.title, if (enabled) colors.ink2 else colors.disabled)
    }
}

@Composable
fun SmallButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val colors = LocalMjColors.current
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(MjRadius.small))
            .background(if (primary) colors.primary else colors.surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        MjText(
            label,
            LocalMjType.current.label,
            when {
                !enabled -> colors.disabled
                primary -> colors.onPrimary
                else -> colors.ink2
            },
        )
    }
}

@Composable
fun TableActionButton(
    label: String,
    onClick: () -> Unit,
    kind: ActionKind = ActionKind.NORMAL,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 44.dp,
) {
    val colors = LocalMjColors.current
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source, 0.96f)
    val bg = when (kind) {
        ActionKind.NORMAL -> Color.White.copy(alpha = 0.09f)
        ActionKind.HU -> colors.tableGold
        ActionKind.PASS -> Color.Transparent
    }
    val fg = when (kind) {
        ActionKind.NORMAL -> colors.onTable
        ActionKind.HU -> colors.tableGoldInk
        ActionKind.PASS -> colors.onTable2
    }
    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(MjRadius.control))
            .background(if (enabled) bg else Color.White.copy(alpha = 0.04f))
            .then(
                if (kind != ActionKind.HU) {
                    Modifier.border(1.dp, colors.tableLine, RoundedCornerShape(MjRadius.control))
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        MjText(
            label,
            LocalMjType.current.title.copy(fontWeight = FontWeight.SemiBold),
            if (enabled) fg else colors.onTable2.copy(alpha = 0.5f),
        )
    }
}

enum class ActionKind { NORMAL, HU, PASS }

@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MjRadius.control))
            .background(colors.surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height - 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.surface else Color.Transparent)
                    .then(
                        if (selected) {
                            Modifier.border(1.dp, colors.line, RoundedCornerShape(10.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                MjText(
                    label,
                    style = type.label.copy(fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (selected) colors.ink else colors.ink2,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun MjSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val offset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(MjMotion.micro, easing = MjEase),
        label = "switch",
    )
    Box(
        modifier = modifier
            .size(46.dp, 27.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(lerpColor(colors.surface3, colors.primary, offset))
            .clickable { onChange(!checked) }
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = offset * 19.dp.toPx()
                }
                .size(21.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.surface),
        )
    }
}

@Composable
fun Avatar(name: String, size: Dp = 40.dp, large: Boolean = false, dim: Boolean = false) {
    val colors = LocalMjColors.current
    val initial = name.take(1).ifBlank { "客" }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(if (large) 18.dp else MjRadius.small))
            .background(if (dim) colors.surface3 else colors.primarySoft),
        contentAlignment = Alignment.Center,
    ) {
        MjText(
            initial,
            style = LocalMjType.current.h2.copy(fontSize = (size.value * 0.42f).sp),
            color = if (dim) colors.ink3 else colors.primaryDeep,
        )
    }
}

@Composable
fun FanChip(fan: Int, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    val (bg, fg) = when {
        fan >= 10 -> colors.redSoft to colors.red
        fan >= 6 -> colors.goldSoft to colors.gold
        fan >= 2 -> colors.greenSoft to colors.green
        else -> colors.surface2 to colors.ink2
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        MjText("$fan 番", LocalMjType.current.caption.copy(fontWeight = FontWeight.SemiBold), fg, maxLines = 1)
    }
}

@Composable
fun SectionTitle(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MjText(title, LocalMjType.current.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
        Spacer(Modifier.weight(1f))
        if (hint != null) MjText(hint, LocalMjType.current.caption, colors.ink3)
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalMjColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MjText(title, LocalMjType.current.body.copy(fontWeight = FontWeight.Medium), colors.ink)
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                MjText(subtitle, LocalMjType.current.caption, colors.ink3, maxLines = 1)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) trailing()
    }
    if (showDivider) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

@Composable
fun SettingsGroup(title: String, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalMjColors.current
    MjCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MjText(title, LocalMjType.current.label.copy(fontWeight = FontWeight.SemiBold), colors.ink2)
            Spacer(Modifier.weight(1f))
            if (hint != null) MjText(hint, LocalMjType.current.caption, colors.ink3)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
fun DashedDivider(modifier: Modifier = Modifier) {
    val colors = LocalMjColors.current
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        var x = 0f
        while (x < size.width) {
            drawLine(colors.lineStrong, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x + 6.dp.toPx(), 0f), 1f)
            x += 10.dp.toPx()
        }
    }
}

@Composable
fun Badge(text: String, primary: Boolean = true) {
    val colors = LocalMjColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (primary) colors.primarySoft else colors.surface2)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        MjText(
            text,
            LocalMjType.current.caption.copy(fontWeight = FontWeight.SemiBold),
            if (primary) colors.primaryDeep else colors.ink2,
            maxLines = 1,
        )
    }
}

@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalMjColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                MjIcon(MjIconKind.BACK, size = 18.dp, tint = colors.ink2)
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            MjText(title, LocalMjType.current.title, colors.ink, maxLines = 1)
            if (subtitle != null) {
                MjText(subtitle, LocalMjType.current.caption, colors.ink3, maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun ChevronRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalMjColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MjText(title, LocalMjType.current.body, colors.ink)
        Spacer(Modifier.weight(1f))
        if (value != null) {
            MjText(value, LocalMjType.current.label, colors.ink3, maxLines = 1)
            Spacer(Modifier.width(6.dp))
        }
        MjIcon(MjIconKind.CHEVRON_RIGHT, size = 16.dp, tint = colors.ink3)
    }
}

fun lerpColor(start: Color, stop: Color, fraction: Float): Color = Color(
    red = start.red + (stop.red - start.red) * fraction,
    green = start.green + (stop.green - start.green) * fraction,
    blue = start.blue + (stop.blue - start.blue) * fraction,
    alpha = start.alpha + (stop.alpha - start.alpha) * fraction,
)
