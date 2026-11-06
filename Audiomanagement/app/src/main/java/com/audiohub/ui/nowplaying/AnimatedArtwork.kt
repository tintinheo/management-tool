package com.audiohub.ui.nowplaying

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.audiohub.ui.components.Artwork
import com.audiohub.ui.theme.ArtMotion
import com.audiohub.ui.theme.LocalUiTokens

private const val SPIN_MS_PER_TURN = 20_000
private const val LIFT_PAUSED_SCALE = 0.86f
private const val PULSE_PAUSED_SCALE = 0.9f
private const val PULSE_PEAK = 1.035f

/**
 * Album art that reacts to play / pause according to [motion] (default: the style's choice).
 *
 * All motion is applied in a graphics layer whose lambda reads the animation values, so animating
 * only redraws this layer — nothing recomposes or re-measures, and nothing runs while paused / still.
 */
@Composable
fun AnimatedArtwork(
    uri: Uri?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = LocalUiTokens.current.heroArtShape,
    motion: ArtMotion = LocalUiTokens.current.motion,
    elevation: Dp = 0.dp,
    placeholder: ImageVector = Icons.Rounded.MusicNote,
) {
    val rotation = remember { Animatable(0f) }
    val scale = remember {
        Animatable(if (isPlaying || motion == ArtMotion.Still || motion == ArtMotion.Spin) 1f else pausedScale(motion))
    }
    LaunchedEffect(isPlaying, motion) {
        when (motion) {
            ArtMotion.Still -> {
                scale.snapTo(1f); rotation.snapTo(0f)
            }
            ArtMotion.Lift -> {
                rotation.snapTo(0f)
                scale.animateTo(if (isPlaying) 1f else LIFT_PAUSED_SCALE, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow))
            }
            ArtMotion.Pulse -> {
                rotation.snapTo(0f)
                if (isPlaying) {
                    scale.animateTo(1f, spring(stiffness = Spring.StiffnessLow))
                    while (true) {
                        scale.animateTo(PULSE_PEAK, tween(2_400, easing = FastOutSlowInEasing))
                        scale.animateTo(1f, tween(2_400, easing = FastOutSlowInEasing))
                    }
                } else {
                    scale.animateTo(PULSE_PAUSED_SCALE, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
                }
            }
            ArtMotion.Spin -> {
                scale.snapTo(1f)
                if (isPlaying) {
                    while (true) {
                        rotation.animateTo(rotation.value + 360f, tween(SPIN_MS_PER_TURN, easing = LinearEasing))
                        rotation.snapTo(rotation.value % 360f)
                    }
                } else {
                    // Coast to a stop instead of freezing mid-turn.
                    rotation.animateTo(rotation.value + 10f, tween(900, easing = LinearOutSlowInEasing))
                }
            }
        }
    }

    val spinning = motion == ArtMotion.Spin
    val artShape = if (spinning) CircleShape else shape
    Box(
        modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            rotationZ = rotation.value
        },
        contentAlignment = Alignment.Center,
    ) {
        Artwork(
            uri,
            Modifier.fillMaxSize().then(
                if (elevation > 0.dp) Modifier.shadow(elevation, artShape, spotColor = Color.Black.copy(alpha = 0.35f)) else Modifier,
            ),
            shape = artShape,
            placeholder = placeholder,
        )
        if (spinning) VinylCenter()
    }
}

private fun pausedScale(motion: ArtMotion) = when (motion) {
    ArtMotion.Lift -> LIFT_PAUSED_SCALE
    ArtMotion.Pulse -> PULSE_PAUSED_SCALE
    else -> 1f
}

/** Spindle hole + label ring, so round art reads as a record. */
@Composable
private fun VinylCenter() {
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize(0.16f).border(1.dp, bg.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize(0.3f).background(bg, CircleShape))
    }
}

/**
 * Single-line text that scrolls when it doesn't fit (long song titles). With [animate] off it falls
 * back to a static ellipsis — e.g. the mini player only scrolls while music plays, to save frames.
 */
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    fontWeight: FontWeight? = null,
    animate: Boolean = true,
) {
    Text(
        text,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = if (animate) TextOverflow.Clip else TextOverflow.Ellipsis,
        modifier = if (animate) {
            modifier.fillMaxWidth().basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 1_500,
                repeatDelayMillis = 2_500,
                velocity = 34.dp,
            )
        } else {
            modifier
        },
    )
}
