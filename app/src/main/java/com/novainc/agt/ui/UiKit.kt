package com.novainc.agt.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AgtCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun StatusPill(text: String, color: Color, pulsing: Boolean = false) {
    val pulse = rememberInfiniteTransition(label = "pill")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = if (pulsing) 1.45f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pillScale"
    )
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pillAlpha"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .scale(scale)
                .alpha(alpha)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** The design's tinted status pill: translucent color fill, colored
 * border and dot, as on the Deployments and Deployment Detail screens. */
@Composable
fun TintedPill(text: String, color: Color, pulsing: Boolean = false) {
    val pulse = rememberInfiniteTransition(label = "tpill")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = if (pulsing) 1.45f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "tpillScale"
    )
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "tpillAlpha"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(scale)
                .alpha(alpha)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(7.dp))
        Text(text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(text, color = AgtColors.GoldSoft)
    }
}

@Composable
fun AgtField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    placeholder: String = ""
) {
    Column(modifier = modifier) {
        Text(label, color = AgtColors.TextDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = AgtColors.TextPrimary, fontSize = 15.sp),
            cursorBrush = SolidColor(AgtColors.Gold),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AgtColors.SurfaceHigh)
                .border(1.dp, AgtColors.Stroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = AgtColors.TextDim, fontSize = 15.sp)
                }
                inner()
            }
        )
    }
}

/** The design's circular icon button: search, gear, back, close. */
@Composable
fun CircleIconButton(
    icon: IconKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AgtColors.TextDim,
    borderColor: Color = AgtColors.Stroke
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(AgtColors.Surface)
            .border(1.dp, borderColor, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        LineIcon(icon, tint, Modifier.size(20.dp))
    }
}

/** A gold call to action with a leading drawn icon, the design's
 * primary button form ("Deploy to this phone", "New Deployment"). */
@Composable
fun GoldIconButton(
    icon: IconKind,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AgtColors.Gold, contentColor = Color.Black),
        modifier = modifier,
        enabled = enabled
    ) {
        LineIcon(icon, Color.Black, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AgtColors.Surface)
            .border(1.dp, AgtColors.Stroke, RoundedCornerShape(16.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = AgtColors.TextDim, fontSize = 13.sp)
    }
}
