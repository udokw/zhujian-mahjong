package mahjong.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mahjong.app.theme.LocalMjColors
import mahjong.app.theme.LocalMjType
import mahjong.app.theme.MjRadius

@Composable
fun IconSquare(
    icon: MjIconKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalMjColors.current.ink2,
) {
    val colors = LocalMjColors.current
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        MjIcon(icon, size = 18.dp, tint = tint)
    }
}

@Composable
fun SheetTitle(title: String, subtitle: String? = null) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp)) {
        MjText(title, type.h2, colors.ink)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            MjText(subtitle, type.caption, colors.ink3)
        }
    }
}

@Composable
fun DividerLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalMjColors.current.line))
}

@Composable
fun ChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    val colors = LocalMjColors.current
    val type = LocalMjType.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                MjText(
                    title,
                    type.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                    if (selected) colors.primaryDeep else colors.ink,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(1.dp))
                    MjText(subtitle, type.caption, colors.ink3)
                }
            }
            Spacer(Modifier.width(10.dp))
            if (selected) {
                MjIcon(MjIconKind.CHECK, size = 18.dp, tint = colors.primary)
            }
        }
        if (showDivider) DividerLine()
    }
}

/** 底部弹出的选择面板（自带遮罩与 24dp 圆角）。作为全屏覆盖层使用。 */
@Composable
fun ModalSheet(
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = LocalMjColors.current
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(colors.surface)
                .border(1.dp, colors.line, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

/**
 * 统一的确认弹窗容器：遮罩、卡片、描边、投影全部走主题色。
 * 新增确认类弹窗一律用它，避免在浅色卡片上误用牌桌专用的白字按钮。
 */
@Composable
fun MjDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 320.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = LocalMjColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth(0.9f)
                .shadow(
                    elevation = if (colors.isDark) 10.dp else 22.dp,
                    shape = RoundedCornerShape(MjRadius.card),
                )
                .clip(RoundedCornerShape(MjRadius.card))
                .background(colors.surface)
                .border(1.dp, colors.lineStrong, RoundedCornerShape(MjRadius.card))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(20.dp),
            content = content,
        )
    }
}

@Composable
fun DialogTitle(text: String, align: TextAlign = TextAlign.Center) {
    MjText(
        text,
        LocalMjType.current.bodyStrong,
        LocalMjColors.current.ink,
        modifier = Modifier.fillMaxWidth(),
        textAlign = align,
    )
}

@Composable
fun DialogMessage(text: String, align: TextAlign = TextAlign.Center) {
    MjText(
        text,
        LocalMjType.current.caption,
        LocalMjColors.current.ink2,
        modifier = Modifier.fillMaxWidth(),
        textAlign = align,
    )
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = LocalMjColors.current.primary) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.16f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
    }
}

@Composable
fun TagRow(text: String, color: Color = LocalMjColors.current.ink3) {
    MjText(text, LocalMjType.current.caption, color, maxLines = 1)
}

@Composable
fun HairlineSpace(height: Int) {
    Spacer(Modifier.height(height.dp))
}

@Composable
fun PillDivider() {
    Box(
        Modifier
            .padding(horizontal = 8.dp)
            .size(2.dp)
            .background(LocalMjColors.current.lineStrong, RoundedCornerShape(999.dp)),
    )
}

@Composable
fun ScreenSpacer() {
    Spacer(Modifier.height(MjRadius.card))
}
