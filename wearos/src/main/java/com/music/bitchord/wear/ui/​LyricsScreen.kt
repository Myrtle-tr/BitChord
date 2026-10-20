package com.music.bitchord.wearos.lyrics

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay

private const val IDLE_SNAPBACK_MS = 4_000L
private const val CONTROLS_HIDE_MS = 4_000L
private val SwipeThreshold = 72.dp

/**
 * Entry point. Drop it into the NavHost / NowPlaying flow:
 *
 *     LyricsScreen()
 */
@Composable
fun LyricsScreen(
    modifier: Modifier = Modifier,
    viewModel: LyricsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val positionState = viewModel.positionMs.collectAsStateWithLifecycle()
    val ambient = rememberAmbientState()
    val haptics = remember { HapticPlayer(context) }

    // Tell the ViewModel when the clock must run (saves battery while the screen is off).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setUiVisible(true)
                Lifecycle.Event.ON_STOP -> viewModel.setUiVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Ambient: 1 Hz ticker + simplified UI.
    LaunchedEffect(ambient.isAmbient) { viewModel.setAmbient(ambient.isAmbient) }

    // Haptics on natural line / chorus transitions (never in ambient).
    val isAmbientNow by rememberUpdatedState(ambient.isAmbient)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (isAmbientNow) return@collect
            when (event) {
                LyricsEvent.LineChanged -> haptics.tick()
                LyricsEvent.ChorusEntered -> haptics.click()
            }
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (ambient.isAmbient) {
            AmbientLyrics(state = state, positionState = positionState, ambient = ambient)
        } else {
            InteractiveLyrics(
                state = state,
                positionState = positionState,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onTogglePlay = viewModel::togglePlayPause,
                onRetry = viewModel::requestSync,
                haptics = haptics,
            )
        }
    }
}

// =====================================================================================
// Interactive mode
// =====================================================================================

@Composable
private fun InteractiveLyrics(
    state: LyricsUiState,
    positionState: State<Long>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    onRetry: () -> Unit,
    haptics: HapticPlayer,
) {
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val swipeThresholdPx = with(LocalDensity.current) { SwipeThreshold.toPx() }

    var userBrowsing by remember { mutableStateOf(false) }
    var interactionStamp by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsStamp by remember { mutableLongStateOf(0L) }
    var programmaticScroll by remember { mutableStateOf(false) }
    var didInitialScroll by remember { mutableStateOf(false) }

    fun markBrowsing() {
        userBrowsing = true
        interactionStamp = SystemClock.elapsedRealtime()
    }

    fun wakeControls() {
        controlsVisible = true
        controlsStamp = SystemClock.elapsedRealtime()
    }

    val onNextState by rememberUpdatedState(onNext)
    val onPreviousState by rememberUpdatedState(onPrevious)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 4 s without touch/rotary input -> resume auto-follow.
    LaunchedEffect(interactionStamp) {
        if (interactionStamp != 0L) {
            delay(IDLE_SNAPBACK_MS)
            userBrowsing = false
        }
    }

    // Controls fade out after 4 s of no input (they stay solid while paused).
    LaunchedEffect(controlsStamp) {
        delay(CONTROLS_HIDE_MS)
        controlsVisible = false
    }

    // Touch-scrolling the list counts as browsing (programmatic follow-scrolls do not).
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !programmaticScroll) markBrowsing()
        }
    }

    // Auto-follow the active line (header is item 0, lines start at 1).
    val lines = state.lines
    val targetItem = if (state.activeLineIndex < 0) 0 else state.activeLineIndex + 1
    LaunchedEffect(targetItem, userBrowsing, state.trackId, lines.size) {
        if (userBrowsing || lines.isEmpty()) return@LaunchedEffect
        programmaticScroll = true
        try {
            if (!didInitialScroll) {
                listState.scrollToItem(targetItem)
                didInitialScroll = true
            } else {
                listState.animateScrollToItem(targetItem)
            }
        } finally {
            programmaticScroll = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Any touch wakes the controls; never consumes the event.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    wakeControls()
                }
            }
            // Swipe left = next track, swipe right = previous track.
            .pointerInput(swipeThresholdPx) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        when {
                            total <= -swipeThresholdPx -> {
                                haptics.click()
                                onNextState()
                            }
                            total >= swipeThresholdPx -> {
                                haptics.click()
                                onPreviousState()
                            }
                        }
                        total = 0f
                    },
                    onDragCancel = { total = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    total += dragAmount
                }
            },
    ) {
        if (lines.isEmpty()) {
            StatusMessage(state = state, onRetry = onRetry)
        } else {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .onRotaryScrollEvent { event ->
                        markBrowsing()
                        listState.dispatchRawDelta(event.verticalScrollPixels)
                        true
                    }
                    .focusRequester(focusRequester)
                    .focusable(),
                anchorType = ScalingLazyListAnchorType.ItemCenter,
                autoCentering = AutoCenteringParams(itemIndex = 0),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                item(key = "header") {
                    TrackHeader(title = state.title, artist = state.artist, connection = state.connection)
                }
                items(count = lines.size, key = { lines[it].index }) { i ->
                    LyricLineItem(
                        line = lines[i],
                        isActive = i == state.activeLineIndex,
                        positionState = positionState,
                        modifier = Modifier.padding(horizontal = 26.dp),
                    )
                }
            }
        }

        // Top vignette: hides lines that scale away near the bezel.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(36.dp)
                .background(Brush.verticalGradient(listOf(Color.Black, Color.Transparent))),
        )

        // Bottom overlay: scrim + touch transport controls.
        val controlsAlpha by animateFloatAsState(
            targetValue = if (controlsVisible || !state.isPlaying) 1f else 0.28f,
            animationSpec = tween(durationMillis = 400),
            label = "controlsAlpha",
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(92.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)))),
        )
        TransportControls(
            isPlaying = state.isPlaying,
            onPrevious = onPrevious,
            onTogglePlay = onTogglePlay,
            onNext = onNext,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .alpha(controlsAlpha),
        )
    }
}

@Composable
private fun TrackHeader(title: String, artist: String, connection: ConnectionState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 36.dp, vertical = 6.dp),
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (connection == ConnectionState.STALE) "Phone not responding" else artist,
            color = if (connection == ConnectionState.STALE) Color(0xFFFFB74D) else LyricsPalette.Cyan.copy(alpha = 0.8f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusMessage(state: LyricsUiState, onRetry: () -> Unit) {
    val message = when {
        state.connection == ConnectionState.WAITING && state.trackId == null ->
            "Play a song in BitChord on your phone.\nTap to reconnect."
        state.connection == ConnectionState.STALE -> "Phone not responding.\nTap to retry."
        state.lyricsStatus == LyricsStatus.UNAVAILABLE -> "No synced lyrics for this song."
        else -> "Loading lyrics"
    }
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onRetry)
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.title.isNotBlank()) {
                Text(
                    text = state.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Box(Modifier.height(6.dp))
            }
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// =====================================================================================
// Transport controls (touch)
// =====================================================================================

private enum class TransportIcon { Play, Pause, Next, Previous }

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            icon = TransportIcon.Previous,
            description = "Previous track",
            size = 38.dp,
            filled = false,
            onClick = onPrevious,
        )
        TransportButton(
            icon = if (isPlaying) TransportIcon.Pause else TransportIcon.Play,
            description = if (isPlaying) "Pause" else "Play",
            size = 46.dp,
            filled = true,
            onClick = onTogglePlay,
        )
        TransportButton(
            icon = TransportIcon.Next,
            description = "Next track",
            size = 38.dp,
            filled = false,
            onClick = onNext,
        )
    }
}

@Composable
private fun TransportButton(
    icon: TransportIcon,
    description: String,
    size: Dp,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val background = if (filled) LyricsPalette.Cyan else Color(0xFF1B2326)
    val tint = if (filled) Color.Black else Color.White
    Button(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .semantics { contentDescription = description },
        colors = ButtonDefaults.buttonColors(backgroundColor = background, contentColor = tint),
    ) {
        Canvas(Modifier.size(size * 0.48f)) { drawTransportIcon(icon, tint) }
    }
}

private fun DrawScope.drawTransportIcon(icon: TransportIcon, tint: Color) {
    val w = size.width
    val h = size.height
    when (icon) {
        TransportIcon.Play -> drawPath(
            Path().apply {
                moveTo(w * 0.18f, h * 0.05f)
                lineTo(w * 0.18f, h * 0.95f)
                lineTo(w * 0.95f, h * 0.5f)
                close()
            },
            tint,
        )
        TransportIcon.Pause -> {
            val barW = w * 0.28f
            val radius = CornerRadius(barW * 0.25f)
            drawRoundRect(tint, Offset(w * 0.08f, 0f), Size(barW, h), radius)
            drawRoundRect(tint, Offset(w - barW - w * 0.08f, 0f), Size(barW, h), radius)
        }
        TransportIcon.Next -> drawSkipNext(tint)
        TransportIcon.Previous -> scale(scaleX = -1f, scaleY = 1f, pivot = center) { drawSkipNext(tint) }
    }
}

private fun DrawScope.drawSkipNext(tint: Color) {
    val w = size.width
    val h = size.height
    drawPath(
        Path().apply {
            moveTo(0f, h * 0.05f)
            lineTo(0f, h * 0.95f)
            lineTo(w * 0.72f, h * 0.5f)
            close()
        },
        tint,
    )
    drawRoundRect(tint, Offset(w * 0.8f, h * 0.05f), Size(w * 0.2f, h * 0.9f), CornerRadius(w * 0.05f))
}

// =====================================================================================
// Ambient (AOD) mode
// =====================================================================================

/**
 * Always-on layout: pure black, no animation, no accent colour, no scrolling list. Only the current
 * and next line are shown. Position updates arrive at 1 Hz from the ViewModel; when burn-in
 * protection is required the content drifts by a few dp every minute.
 */
@Composable
private fun AmbientLyrics(
    state: LyricsUiState,
    positionState: State<Long>,
    ambient: AmbientState,
) {
    val positionMs = positionState.value
    val current = state.lines.getOrNull(state.activeLineIndex)
    val next = state.lines.getOrNull(state.activeLineIndex + 1)

    val shiftDp = if (ambient.burnInProtectionRequired) {
        (((positionMs / 60_000L) % 5).toInt() - 2) * 2
    } else {
        0
    }
    val secondary = if (ambient.deviceHasLowBitAmbient) LyricsPalette.AmbientPrimary else LyricsPalette.AmbientSecondary

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .offset(x = shiftDp.dp, y = shiftDp.dp)
            .padding(horizontal = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (current == null) {
                Text(
                    text = state.title.ifBlank { "BitChord" },
                    color = LyricsPalette.AmbientPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = current.text,
                    color = LyricsPalette.AmbientPrimary,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (next != null) {
                    Text(
                        text = next.text,
                        color = secondary,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}