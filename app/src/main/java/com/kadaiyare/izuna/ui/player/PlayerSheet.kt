package com.kadaiyare.izuna.ui.player

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

// Keep live coordinates so rotation, text size and the sheet's translation are reflected at touch-down.
internal val LocalPlayerArtworkRegistration = staticCompositionLocalOf<((LayoutCoordinates) -> Unit)?> { null }
private class PlayerDragRegion {
    var parent: LayoutCoordinates? = null
    var artwork: LayoutCoordinates? = null

    fun containsArtwork(position: androidx.compose.ui.geometry.Offset): Boolean {
        val parent = parent?.takeIf { it.isAttached } ?: return false
        val artwork = artwork?.takeIf { it.isAttached } ?: return false
        return parent.localBoundingBoxOf(artwork, clipBounds = false).contains(position)
    }
}

/** Touch coordinates belong to the stationary parent; only its child is translated. */
@Composable
internal fun PlayerSheet(visible: Boolean, onDismiss: () -> Unit, artworkDrag: Boolean = true,
                         backEnabled: Boolean = true,
                         content: @Composable () -> Unit) {
    var height by remember { mutableIntStateOf(1) }
    var mounted by remember { mutableStateOf(false) }
    var offset by remember { mutableFloatStateOf(0f) }
    var animation by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val dragRegion = remember { PlayerDragRegion() }
    val registerArtwork = remember(dragRegion) { { coordinates: LayoutCoordinates -> dragRegion.artwork = coordinates } }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentArtworkDrag by rememberUpdatedState(artworkDrag)
    fun settle(target: Float, closing: Boolean = false) {
        animation?.cancel()
        animation = scope.launch {
            animate(offset, target, animationSpec = tween(if (closing) 280 else 220)) { value, _ -> offset = value }
            if (closing) mounted = false
        }
    }
    LaunchedEffect(visible) {
        animation?.cancel()
        if (visible) {
            if (!mounted) offset = height.toFloat()
            mounted = true
            settle(0f)
        } else if (mounted) settle(height.toFloat(), closing = true)
    }
    Box(Modifier.fillMaxSize().onSizeChanged { height = it.height }.onGloballyPositioned { dragRegion.parent = it }
        .then(if (mounted) Modifier.pointerInput(visible) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Queue rows must retain their vertical scroll gestures. Its toolbar can dismiss.
                if (!visible) {
                    down.consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    return@awaitEachGesture
                }
                val inToolbar = down.position.y <= 80f * density + offset
                if (!inToolbar && !(currentArtworkDrag && dragRegion.containsArtwork(down.position))) return@awaitEachGesture
                var claimed = false
                var rejected = false
                var origin = offset
                var complete = false
                var cancelled = false
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed) {
                            cancelled = true
                            break
                        }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!claimed && !rejected && (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)) {
                            claimed = dy > viewConfiguration.touchSlop && dy > abs(dx)
                            rejected = !claimed
                            if (claimed) { animation?.cancel(); origin = offset }
                        }
                        if (claimed) {
                            offset = (origin + dy).coerceIn(0f, size.height.toFloat())
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    complete = true
                    if (claimed) {
                        if (!cancelled && offset > size.height * 0.16f) currentDismiss() else settle(0f)
                    }
                } finally {
                    if (!complete && claimed && visible) settle(0f)
                }
            }
        } else Modifier)) {
        if (mounted) Box(Modifier.fillMaxSize()
            .graphicsLayer { translationY = offset }
            .background(MaterialTheme.colorScheme.background)
            .testTag("playerSheet").systemBarsPadding()) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                LocalPlayerArtworkRegistration provides registerArtwork) { content() }
        }
    }
    PredictiveBackHandler(enabled = visible && backEnabled) { events ->
        animation?.cancel()
        try {
            events.collect { offset = height * it.progress }
            currentDismiss()
        } catch (_: CancellationException) { settle(0f) }
    }
}
