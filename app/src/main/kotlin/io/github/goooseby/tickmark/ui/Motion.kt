package io.github.goooseby.tickmark.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Finite animations use Compose's duration scale, including the system's zero scale. */
@Composable internal fun chartReveal(key:Any):State<Float> {
    val progress=remember(key){Animatable(0f)}
    LaunchedEffect(progress){progress.animateTo(1f,tween(520,easing=FastOutSlowInEasing))}
    return progress.asState()
}

@Composable internal fun Modifier.pageEntry(key:Any):Modifier {
    val progress=remember(key){Animatable(0f)}
    val distance=with(LocalDensity.current){12.dp.toPx()}
    LaunchedEffect(progress){progress.animateTo(1f,tween(240,easing=FastOutSlowInEasing))}
    return graphicsLayer {
        alpha=progress.value
        translationY=distance*(1f-progress.value)
    }
}

/** Departing pages remain drawable, but must not handle taps or accessibility actions. */
internal fun Modifier.destinationInput(active:Boolean):Modifier = if(active) this else
    clearAndSetSemantics{}.pointerInput(Unit) {
        awaitPointerEventScope {
            while(true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach{it.consume()}
        }
    }
