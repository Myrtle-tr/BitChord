package com.music.bitchord.wearos.lyrics

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.music.bitchord.shared.wear.PlaybackCommand
import com.music.bitchord.shared.wear.PlaybackSyncMessage
import com.music.bitchord.shared.wear.SyncEvent
import com.music.bitchord.shared.wear.SyncedLine
import com.music.bitchord.shared.wear.SyncedLyricsPayload
import com.music.bitchord.shared.wear.WearSyncPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class ConnectionState { WAITING, CONNECTED, STALE }

enum class LyricsStatus { IDLE, LOADING, READY, UNAVAILABLE }

data class LyricsUiState(
    val trackId: String? = null,
    val title: String = "",
    val artist: String = "",
    val lines: List<SyncedLine> = emptyList(),
    val lyricsStatus: LyricsStatus = LyricsStatus.IDLE,
    val isPlaying: Boolean = false,
    val activeLineIndex: Int = -1,
    val connection: ConnectionState = ConnectionState.WAITING,
)

sealed interface LyricsEvent {
    /** The clock naturally advanced to the next line. */
    data object LineChanged : LyricsEvent

    /** The clock naturally advanced into a chorus block. */
    data object ChorusEntered : LyricsEvent
}

/**
 * Receives phone events (DataClient + MessageClient), keeps a local playback clock and exposes:
 *  - [state]      low-frequency UI state (changes on track / line / play-pause)
 *  - [positionMs] interpolated position, ~60 Hz while playing & visible, 1 Hz in ambient
 *  - [events]     haptic triggers
 *
 * Clock model: position = anchor + elapsed * speed, plus a decaying "blend" term.
 *  - drift > [WearSyncPaths.DRIFT_SNAP_THRESHOLD_MS]: snap immediately
 *  - otherwise: re-anchor and blend the visible error out linearly over [BLEND_WINDOW_MS]
 */
class LyricsViewModel(application: Application) : AndroidViewModel(application),
    DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener {

    private val dataClient = Wearable.getDataClient(application)
    private val messageClient = Wearable.getMessageClient(application)
    private val nodeClient = Wearable.getNodeClient(application)
    private val cache = LyricsCache(application)

    private val _state = MutableStateFlow(LyricsUiState())
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _events = MutableSharedFlow<LyricsEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<LyricsEvent> = _events.asSharedFlow()

    // --- Clock (main thread only) ---------------------------------------------------
    private var payload: SyncedLyricsPayload? = null
    private var durationMs = 0L
    private var playing = false
    private var speed = 1f
    private var anchorPositionMs = 0L
    private var anchorElapsedMs = SystemClock.elapsedRealtime()
    private var blendFromMs = 0L
    private var blendStartElapsedMs = 0L
    private var lastPhoneContactElapsedMs = 0L
    private var hapticSuppressUntilElapsedMs = 0L

    private var ambient = false
    private var uiVisible = false
    private var tickerJob: Job? = null
    private var lyricsRequestJob: Job? = null

    init {
        dataClient.addListener(this)
        messageClient.addListener(this)
        viewModelScope.launch {
            restoreLatestPayload()
            requestSync()
        }
    }

    // ---------------------------------------------------------------------------------
    // Public API for the UI
    // ---------------------------------------------------------------------------------

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (visible) {
            if (_state.value.connection != ConnectionState.CONNECTED) requestSync()
            tick()
        }
        updateTicker()
    }

    /** Ambient (AOD): the ticker drops to 1 Hz; the UI simplifies itself. */
    fun setAmbient(value: Boolean) {
        if (ambient == value) return
        ambient = value
        tick()
        updateTicker()
    }

    fun togglePlayPause() {
        val wasPlaying = playing
        snapTo(currentPositionMs(), !wasPlaying, speed) // optimistic; phone confirms
        sendCommand(if (wasPlaying) PlaybackCommand.PAUSE else PlaybackCommand.PLAY)
    }

    fun next() = sendCommand(PlaybackCommand.NEXT)

    fun previous() = sendCommand(PlaybackCommand.PREVIOUS)

    fun sendCommand(command: PlaybackCommand) {
        viewModelScope.launch { sendToPhone(WearSyncPaths.PLAYBACK_COMMAND, command.toBytes()) }
    }

    /** Ask the phone for the current state (cold start, reconnect, user tapped "retry"). */
    fun requestSync() {
        viewModelScope.launch { sendToPhone(WearSyncPaths.LYRICS_REQUEST, ByteArray(0)) }
    }

    // ---------------------------------------------------------------------------------
    // Data Layer callbacks
    // ---------------------------------------------------------------------------------

    override fun onDataChanged(events: DataEventBuffer) {
        // Extract synchronously: the buffer is released when this method returns.
        val maps = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearSyncPaths.LYRICS_PAYLOAD }
            .map { DataMapItem.fromDataItem(it.dataItem).dataMap }
        val latest = maps.lastOrNull() ?: return
        viewModelScope.launch { handlePayloadDataMap(latest) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        if (path != WearSyncPaths.PLAYBACK_STATE && path != WearSyncPaths.PLAYBACK_HEARTBEAT) return
        val message = PlaybackSyncMessage.fromBytes(event.data) ?: return
        viewModelScope.launch { handlePlaybackMessage(message) }
    }

    override fun onCleared() {
        dataClient.removeListener(this)
        messageClient.removeListener(this)
        super.onCleared()
    }

    // ---------------------------------------------------------------------------------
    // Playback messages
    // ---------------------------------------------------------------------------------

    private fun handlePlaybackMessage(msg: PlaybackSyncMessage) {
        lastPhoneContactElapsedMs = SystemClock.elapsedRealtime()
        if (_state.value.connection != ConnectionState.CONNECTED) {
            _state.update { it.copy(connection = ConnectionState.CONNECTED) }
        }

        val trackChanged = msg.trackId != null && msg.trackId != _state.value.trackId
        if (trackChanged) startTrack(msg)
        if (msg.durationMs > 0) durationMs = msg.durationMs
        if (msg.trackId != null && msg.lyricsHash != null) ensureLyrics(msg.trackId!!, msg.lyricsHash!!)

        val estimate = estimatePosition(msg)
        val isHeartbeat = msg.event == SyncEvent.HEARTBEAT
        if (isHeartbeat && !trackChanged && playing && msg.isPlaying) {
            correctDrift(estimate, msg.speed)
        } else {
            snapTo(estimate, msg.isPlaying, msg.speed)
        }
    }

    /** Phone position "now": reported position + clamped transit latency (only while playing). */
    private fun estimatePosition(msg: PlaybackSyncMessage): Long {
        if (!msg.isPlaying) return msg.positionMs
        val latency = (System.currentTimeMillis() - msg.sentAtWallMs).coerceIn(0L, MAX_LATENCY_MS)
        return msg.positionMs + (latency * msg.speed).toLong()
    }

    private fun startTrack(msg: PlaybackSyncMessage) {
        lyricsRequestJob?.cancel()
        payload = null
        _state.update {
            it.copy(
                trackId = msg.trackId,
                title = msg.title,
                artist = msg.artist,
                lines = emptyList(),
                lyricsStatus = LyricsStatus.LOADING,
                activeLineIndex = -1,
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Clock
    // ---------------------------------------------------------------------------------

    private fun currentPositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val base = if (playing) {
            anchorPositionMs + ((now - anchorElapsedMs) * speed).toLong()
        } else {
            anchorPositionMs
        }
        val t = ((now - blendStartElapsedMs).toFloat() / BLEND_WINDOW_MS).coerceIn(0f, 1f)
        val blended = base + (blendFromMs * (1f - t)).toLong()
        val max = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return blended.coerceIn(0L, max)
    }

    private fun snapTo(positionMs: Long, isPlaying: Boolean, newSpeed: Float) {
        val now = SystemClock.elapsedRealtime()
        anchorPositionMs = positionMs
        anchorElapsedMs = now
        blendFromMs = 0L
        blendStartElapsedMs = now
        playing = isPlaying
        speed = newSpeed
        hapticSuppressUntilElapsedMs = now + HAPTIC_SUPPRESS_AFTER_SNAP_MS
        _state.update { it.copy(isPlaying = isPlaying) }
        tick()
        updateTicker()
    }

    private fun correctDrift(estimateMs: Long, newSpeed: Float) {
        val now = SystemClock.elapsedRealtime()
        val shown = currentPositionMs(now)
        val drift = estimateMs - shown
        if (abs(drift) > WearSyncPaths.DRIFT_SNAP_THRESHOLD_MS) {
            snapTo(estimateMs, true, newSpeed)
            return
        }
        // Re-anchor to the phone's truth, but keep the *visible* position continuous and let the
        // error fade out linearly: shown(t0) == estimate + blendFrom  =>  blendFrom = -drift.
        anchorPositionMs = estimateMs
        anchorElapsedMs = now
        blendFromMs = -drift
        blendStartElapsedMs = now
        speed = newSpeed
    }

    private fun updateTicker() {
        if (!(playing && uiVisible)) return
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive && playing && uiVisible) {
                tick()
                delay(if (ambient) AMBIENT_TICK_MS else FRAME_MS)
            }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()

        // Watchdog: playing but the phone went silent -> freeze the clock instead of drifting forever.
        if (playing && lastPhoneContactElapsedMs != 0L &&
            now - lastPhoneContactElapsedMs > HEARTBEAT_TIMEOUT_MS
        ) {
            anchorPositionMs = currentPositionMs(now)
            anchorElapsedMs = now
            blendFromMs = 0L
            blendStartElapsedMs = now
            playing = false
            _state.update { it.copy(isPlaying = false, connection = ConnectionState.STALE) }
        }

        val pos = currentPositionMs(now)
        _positionMs.value = pos

        val p = payload
        val idx = p?.lineIndexAt(pos) ?: -1
        val prevIdx = _state.value.activeLineIndex
        if (idx == prevIdx) return

        _state.update { it.copy(activeLineIndex = idx) }

        val natural = p != null && idx >= 0 && idx == prevIdx + 1 &&
            playing && !ambient && now >= hapticSuppressUntilElapsedMs
        if (natural) {
            val line = p!!.lines[idx]
            val prevLine = p.lines.getOrNull(prevIdx)
            _events.tryEmit(
                if (line.isChorus && prevLine?.isChorus != true) LyricsEvent.ChorusEntered
                else LyricsEvent.LineChanged,
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // Lyrics loading: cache first, DataItem second, explicit request last
    // ---------------------------------------------------------------------------------

    private fun ensureLyrics(trackId: String, hash: String) {
        val current = payload
        if (hash == WearSyncPaths.NO_LYRICS_HASH) {
            if (current?.trackId != trackId) {
                _state.update { it.copy(lyricsStatus = LyricsStatus.UNAVAILABLE, lines = emptyList()) }
            }
            return
        }
        if (current != null && current.trackId == trackId && current.contentHash == hash) return

        lyricsRequestJob?.cancel()
        lyricsRequestJob = viewModelScope.launch {
            cache.get(trackId, hash)?.let {
                adoptPayload(it)
                return@launch
            }
            _state.update { it.copy(lyricsStatus = LyricsStatus.LOADING) }
            // The phone usually pushes the DataItem on its own; give it a moment before asking.
            delay(PAYLOAD_GRACE_MS)
            val now = payload
            if (now == null || now.trackId != trackId || now.contentHash != hash) {
                sendToPhone(WearSyncPaths.LYRICS_REQUEST, trackId.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private suspend fun restoreLatestPayload() {
        val map = try {
            val uri = Uri.Builder()
                .scheme(PutDataRequest.WEAR_URI_SCHEME)
                .path(WearSyncPaths.LYRICS_PAYLOAD)
                .build()
            val buffer = dataClient.getDataItems(uri).await()
            try {
                buffer.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap }
            } finally {
                buffer.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        handlePayloadDataMap(map)
    }

    private suspend fun handlePayloadDataMap(map: DataMap) {
        val trackId = map.getString(WearSyncPaths.KEY_TRACK_ID) ?: return
        val hash = map.getString(WearSyncPaths.KEY_HASH)

        val current = payload
        if (current != null && current.trackId == trackId && current.contentHash == hash) return

        val loaded = cache.get(trackId, hash) ?: run {
            val asset = map.getAsset(WearSyncPaths.KEY_ASSET) ?: return
            val fresh = readAsset(asset) ?: return
            cache.put(fresh)
            fresh
        }

        // A stale DataItem for another track must not overwrite what the phone is playing now;
        // it is cached, and TRACK/HEARTBEAT will pull it from the cache if it becomes current.
        val known = _state.value.trackId
        if (known == null || known == loaded.trackId) adoptPayload(loaded)
    }

    private suspend fun readAsset(asset: Asset): SyncedLyricsPayload? = try {
        val response = dataClient.getFdForAsset(asset).await()
        try {
            withContext(Dispatchers.IO) {
                response.inputStream.use { SyncedLyricsPayload.fromGzipStream(it) }
            }
        } finally {
            response.release()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read lyrics asset", e)
        null
    }

    private suspend fun adoptPayload(p: SyncedLyricsPayload) {
        payload = p
        _state.update {
            it.copy(
                trackId = p.trackId,
                title = p.title.ifBlank { it.title },
                artist = p.artist.ifBlank { it.artist },
                lines = p.lines,
                lyricsStatus = LyricsStatus.READY,
                activeLineIndex = -1,
            )
        }
        if (p.durationMs > 0 && durationMs == 0L) durationMs = p.durationMs
        hapticSuppressUntilElapsedMs = SystemClock.elapsedRealtime() + HAPTIC_SUPPRESS_AFTER_SNAP_MS
        tick()
        cache.put(p) // no-op overwrite when it came from the cache; refreshes LRU order
    }

    // ---------------------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------------------

    private suspend fun sendToPhone(path: String, body: ByteArray) {
        val nodes = nodeClient.connectedNodes.awaitOrNull().orEmpty()
        nodes.forEach { messageClient.sendMessage(it.id, path, body).awaitOrNull() }
    }

    private suspend fun <T> Task<T>.awaitOrNull(): T? = try {
        await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "LyricsViewModel"
        const val FRAME_MS = 16L
        const val AMBIENT_TICK_MS = 1_000L
        const val BLEND_WINDOW_MS = 2_500L
        const val MAX_LATENCY_MS = 1_500L
        const val HEARTBEAT_TIMEOUT_MS = 15_000L
        const val PAYLOAD_GRACE_MS = 1_500L
        const val HAPTIC_SUPPRESS_AFTER_SNAP_MS = 250L
    }
}