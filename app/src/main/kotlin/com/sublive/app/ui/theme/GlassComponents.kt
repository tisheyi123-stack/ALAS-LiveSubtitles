package com.sublive.app.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sublive.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen ambient lighting container that renders smooth background glow orbs
 * to give realistic depth and refraction behind translucent glass cards.
 */
@Composable
fun AmbientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_glow")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepSpace)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Top-Right Cyan/Blue Ambient Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NeonCyan.copy(alpha = 0.18f * pulseAlpha),
                        NeonViolet.copy(alpha = 0.08f * pulseAlpha),
                        Color.Transparent
                    ),
                    center = Offset(width * 0.85f, height * 0.15f),
                    radius = width * 0.75f
                )
            )

            // Center-Left Indigo/Purple Ambient Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NeonPurple.copy(alpha = 0.14f * pulseAlpha),
                        NeonBlue.copy(alpha = 0.05f * pulseAlpha),
                        Color.Transparent
                    ),
                    center = Offset(width * 0.1f, height * 0.55f),
                    radius = width * 0.8f
                )
            )

            // Bottom-Center Emerald/Teal Subtle Floor Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AccentTeal.copy(alpha = 0.12f * pulseAlpha),
                        Color.Transparent
                    ),
                    center = Offset(width * 0.5f, height * 0.95f),
                    radius = width * 0.7f
                )
            )
        }

        content()
    }
}

/**
 * Glassmorphic container with frosted translucent surface, dual-tone gradient fill,
 * and high-definition crystal border reflection.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    backgroundColor: Color = GlassSurfaceDark,
    borderColor: Color = Color.White,
    borderAlpha: Float = 0.20f,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val glassBrush = Brush.verticalGradient(
        colors = listOf(
            backgroundColor.copy(alpha = 0.75f),
            backgroundColor.copy(alpha = 0.45f)
        )
    )

    val borderBrush = Brush.linearGradient(
        colors = listOf(
            borderColor.copy(alpha = borderAlpha),
            borderColor.copy(alpha = borderAlpha * 0.2f),
            borderColor.copy(alpha = borderAlpha * 0.6f)
        ),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .then(clickableModifier)
            .background(glassBrush)
            .border(BorderStroke(1.dp, borderBrush), shape = shape)
    ) {
        content()
    }
}

/**
 * Glassmorphic circular icon button with frosted backdrop and neon tint.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = TextPrimary,
    size: Dp = 44.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.04f)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                ),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/**
 * Modern Status Card with live pulsing glowing halo, state badge pill, and smooth typography.
 */
@Composable
fun StatusBadge(
    isConnected: Boolean,
    statusText: String,
    modifier: Modifier = Modifier
) {
    val glowColor = if (isConnected) NeonCyan else Color(0xFF64748B)

    val infiniteTransition = rememberInfiniteTransition(label = "status_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnected) 1.8f else 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnected) 900 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        backgroundColor = if (isConnected) Color(0x700B1A2F) else Color(0x550F172A),
        borderColor = if (isConnected) NeonCyan else Color.White,
        borderAlpha = if (isConnected) 0.40f else 0.12f
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Pulsing Outer Halo
                    Box(
                        modifier = Modifier
                            .size((12 * pulseScale).dp)
                            .clip(CircleShape)
                            .background(glowColor.copy(alpha = if (isConnected) 0.35f else 0.15f))
                    )
                    // Core Dot
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(glowColor)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = stringResource(R.string.system_status),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) Color.White else TextSecondary,
                            fontSize = 15.sp
                        )
                    )
                }
            }

            // Dynamic State Badge Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isConnected) NeonCyan.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.08f))
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isConnected) NeonCyan.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f)
                        ),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isConnected) "LIVE" else "IDLE",
                    color = if (isConnected) NeonCyan else TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
