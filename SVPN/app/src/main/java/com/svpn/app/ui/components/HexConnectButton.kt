package com.svpn.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.svpn.app.ui.theme.PeladuBlackElevated
import com.svpn.app.ui.theme.PeladuGreen
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pointy-top hexagon (one vertex up, one down), per the reference design. */
class FlatTopHexagonShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = hexagonPath(size)
        return Outline.Generic(path)
    }
}

private fun hexagonPath(size: Size): Path {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = minOf(size.width, size.height) / 2f
    val path = Path()
    for (i in 0 until 6) {
        // Pointy-top: vertices at 30, 90 (bottom point), 150, 210, 270 (top point), 330 degrees.
        val angle = PI / 180.0 * (60 * i + 30)
        val x = (cx + r * cos(angle)).toFloat()
        val y = (cy + r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

@Composable
fun HexConnectButton(
    modifier: Modifier = Modifier,
    sizeDp: androidx.compose.ui.unit.Dp = 160.dp,
    connected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val shape = remember { FlatTopHexagonShape() }
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(shape)
            .drawBehind {
                // Fill
                drawHexFill(this, PeladuBlackElevated)
                // Border: white by default, green once actually connected.
                drawHexBorder(this, if (connected) PeladuGreen else com.svpn.app.ui.theme.PeladuWhite)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        content()
    }
}

/**
 * Generic hexagon button used for the honeycomb quick-menu (add server,
 * settings, news, server list). Outline-only by default, matching the
 * reference design (no fill, thin light stroke).
 */
@Composable
fun HexButton(
    modifier: Modifier = Modifier,
    sizeDp: androidx.compose.ui.unit.Dp = 120.dp,
    borderColor: Color = com.svpn.app.ui.theme.PeladuWhite.copy(alpha = 0.85f),
    borderWidth: Float = 2.5f,
    fillColor: Color? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val shape = remember { FlatTopHexagonShape() }
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(shape)
            .drawBehind {
                fillColor?.let { drawHexFill(this, it) }
                drawHexBorder(this, borderColor, borderWidth)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        content()
    }
}

/**
 * Hexagon-outlined multiline input, used for the "paste key / link" field
 * on the Add Server screen. The hex is drawn (not clipped) with generous
 * inner padding so the caret/text never crowds the angled corners.
 */
@Composable
fun HexInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 280.dp,
    height: androidx.compose.ui.unit.Dp = 260.dp
) {
    Box(
        modifier = modifier
            .size(width, height)
            .drawBehind {
                drawHexBorder(this, com.svpn.app.ui.theme.PeladuWhite.copy(alpha = 0.85f), 2.5f)
            }
            .padding(horizontal = width * 0.22f, vertical = height * 0.22f),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxSize(),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = com.svpn.app.ui.theme.PeladuWhite,
                fontSize = 15.sp
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PeladuGreen),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            color = com.svpn.app.ui.theme.PeladuWhite.copy(alpha = 0.4f),
                            fontSize = 15.sp
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

private fun drawHexFill(scope: DrawScope, color: Color) {
    val path = hexagonPath(scope.size)
    scope.drawPath(path, color = color)
}

private fun drawHexBorder(scope: DrawScope, color: Color, width: Float = 4f) {
    val path = hexagonPath(scope.size)
    scope.drawPath(path, color = color, style = Stroke(width = width))
}
