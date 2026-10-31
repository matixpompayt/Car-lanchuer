package pl.mati.carlauncher.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.mati.carlauncher.data.Prefs

/**
 * Animacje systemu CAR OS: wciśnięcia, wejście kart (po kolei), przejścia ekranów.
 * Wszystko można wyłączyć: Ustawienia → Wygląd → Animacje (wolniejsze radia).
 */

/** Licznik kolejności kart na ekranie – każda kolejna wchodzi chwilę później. */
class StaggerCounter {
    var next = 0
}

val LocalStagger = staticCompositionLocalOf<StaggerCounter?> { null }

/** Wejście elementu: pojawienie się + lekki wjazd od dołu, z opóźnieniem wg kolejności. */
fun Modifier.stagger(): Modifier = composed {
    val anim by Prefs.animations.collectAsState()
    val counter = LocalStagger.current
    val idx = remember { counter?.let { it.next++ } ?: 0 }
    val p = remember { Animatable(if (anim) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (p.value < 1f) {
            delay(idx.coerceAtMost(10) * 40L)
            p.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        }
    }
    Modifier.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 26.dp.toPx()
        val s = 0.97f + 0.03f * p.value
        scaleX = s
        scaleY = s
    }
}

/** Sprężyste „wciśnięcie” – element lekko się zmniejsza pod palcem. */
fun Modifier.pressScale(src: MutableInteractionSource, pressed: Float = 0.95f): Modifier = composed {
    val anim by Prefs.animations.collectAsState()
    val isPressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (isPressed && anim) pressed else 1f,
        spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "press",
    )
    Modifier.graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

/** Przejście między ekranami z docka: w dół / w górę zgodnie z kolejnością ikon. */
fun AnimatedContentTransitionScope<Screen>.screenTransition(enabled: Boolean): ContentTransform {
    if (!enabled) return EnterTransition.None togetherWith ExitTransition.None
    val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
    return (
        fadeIn(tween(260, delayMillis = 70)) +
            slideInVertically(tween(340, easing = FastOutSlowInEasing)) { h -> dir * h / 12 } +
            scaleIn(tween(340, easing = FastOutSlowInEasing), initialScale = 0.97f)
        ) togetherWith (
        fadeOut(tween(150)) + slideOutVertically(tween(220)) { h -> -dir * h / 18 }
        )
}

/** Przejście między stronami ustawień: w bok. */
fun AnimatedContentTransitionScope<String>.pageTransition(enabled: Boolean): ContentTransform {
    if (!enabled) return EnterTransition.None togetherWith ExitTransition.None
    return (fadeIn(tween(240, delayMillis = 60)) + slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { w -> w / 14 }) togetherWith
        (fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { w -> -w / 20 })
}
