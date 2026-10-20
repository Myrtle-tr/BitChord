package com.music.bitchord.wear

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.music.bitchord.shared.LyricLine // TODO: adjust to the real package of LyricLine
import com.music.bitchord.shared.wear.PlaybackCommand
import com.music.bitchord.shared.wear.PlaybackSyncMessage
import com.music.bitchord.shared.wear.RawLyricLine
import com.music.bitchord.shared.wear.RawSyllable
import com.music.bitchord.shared.wear.SyncEvent
import com.music.bitchord.shared.wear.SyncedLyricsPayload
import com.music.bitchord.shared.wear.WearSyncPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Phone -> watch lyrics sync. Purely additive; does not touch [WearBridge].
 *
 *  - Track start/change  : resolves lyrics via [lyricsProvider], builds a [SyncedLyricsPayload] and
 *                          publishes it with DataClient (gzip asset). Skipped when the same
 *                          (trackId, hash) was already delivered; the watch asks again on a cache miss.
 *  - Play/pause/seek/track: MessageClient -> [WearSyncPaths.PLAYBACK_STATE]
 *  - Heartbeat           : MessageClient -> [WearSyncPaths.PLAYBACK_HEARTBEAT] every 5 s while playing
 *  - Watch commands      : [WearSyncPaths.PLAYBACK_COMMAND] / [WearSyncPaths.LYRICS_REQUEST]
 *
 * Usage (see INTEGRATION.md): create in PlaybackService.onCreate once the Media3 [Player] exists,
 * call [start]; call [stop] in onDestroy.
 *
 * All [Player] access happens on the main thread.
 */
class WearLyricsBridge(
    context: Context,
    private val player: Player,
    /** One-line adapter around LyricsRepository, e.g. `{ item -> repo.getLyrics(item.mediaId) }`. */
    private val lyricsProvider: suspend (MediaItem) -> List<LyricLine>?,
) : MessageClient.OnMessageReceivedListener {

    private val appContext = context.applicationContext
    private val dataClient = Wearable.getDataClient(appContext)
    private val messageClient = Wearable.getMessageClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val deliveredLog = DeliveredLog(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sendMutex = Mutex()

    private var started = false
    private var heartbeatJob: Job? = null
    private var lyricsJob: Job? = null

    // State for the current track (main thread only).
    private var lyricsTrackId: String? = null
    private var lyricsHash: String? = null
    private var currentPayload: SyncedLyricsPayload? = null

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) onTrackChanged(mediaItem) else clearTrack()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            enqueue(if (isPlaying) SyncEvent.PLAY else SyncEvent.PAUSE)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                enqueue(SyncEvent.SEEK)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            enqueue(SyncEvent.SEEK) // speed change == re-anchor the watch clock
        }
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        player.addListener(playerListener)
        messageClient.addListener(this)

        heartbeatJob = scope.launch {
            while (isActive) {
                delay(WearSyncPaths.HEARTBEAT_INTERVAL_MS)
                if (player.isPlaying && player.currentMediaItem != null) enqueue(SyncEvent.HEARTBEAT)
            }
        }
        player.currentMediaItem?.let { onTrackChanged(it) }
    }

    fun stop() {
        if (!started) return
        started = false
        player.removeListener(playerListener)
        messageClient.removeListener(this)
        scope.coroutineContext.cancelChildren()
        heartbeatJob = null
        lyricsJob = null
    }

    // ---------------------------------------------------------------------------------
    // Track handling
    // ---------------------------------------------------------------------------------

    private fun clearTrack() {
        lyricsJob?.cancel()
        lyricsTrackId = null
        lyricsHash = null
        currentPayload = null
    }

    private fun onTrackChanged(item: MediaItem) {
        lyricsJob?.cancel()
        val trackId = trackIdOf(item)
        lyricsTrackId = trackId
        lyricsHash = null
        currentPayload = null

        // 1) Immediate TRACK event (hash == null -> "lyrics loading") so the watch switches at once.
        enqueue(SyncEvent.TRACK)

        // 2) Resolve lyrics, publish them, then re-announce the track with the hash.
        lyricsJob = scope.launch {
            val lines = try {
                lyricsProvider(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics lookup failed for $trackId", e)
                null
            }

            if (lines.isNullOrEmpty()) {
                lyricsHash = WearSyncPaths.NO_LYRICS_HASH
                enqueue(SyncEvent.TRACK)
                return@launch
            }

            val title = item.mediaMetadata.title?.toString().orEmpty()
            val artist = item.mediaMetadata.artist?.toString().orEmpty()
            val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

            val payload = withContext(Dispatchers.Default) {
                SyncedLyricsPayload.build(
                    trackId = trackId,
                    title = title,
                    artist = artist,
                    durationMs = duration,
                    raw = lines.map { it.toRaw() },
                )
            }

            currentPayload = payload
            lyricsHash = payload.contentHash

            if (!deliveredLog.contains(trackId, payload.contentHash)) {
                if (putPayload(payload)) deliveredLog.add(trackId, payload.contentHash)
            }
            enqueue(SyncEvent.TRACK)
        }
    }

    private fun trackIdOf(item: MediaItem): String =
        item.mediaId.ifEmpty {
            "${item.mediaMetadata.title}|${item.mediaMetadata.artist}"
        }

    // ---------------------------------------------------------------------------------
    // DataClient
    // ---------------------------------------------------------------------------------

    private suspend fun putPayload(payload: SyncedLyricsPayload): Boolean {
        return try {
            val bytes = withContext(Dispatchers.Default) { payload.toGzipBytes() }
            val request = PutDataMapRequest.create(WearSyncPaths.LYRICS_PAYLOAD).apply {
                dataMap.putString(WearSyncPaths.KEY_TRACK_ID, payload.trackId)
                dataMap.putString(WearSyncPaths.KEY_HASH, payload.contentHash)
                dataMap.putAsset(WearSyncPaths.KEY_ASSET, Asset.createFromBytes(bytes))
                // Always differs, so a forced re-send fires onDataChanged on the watch.
                dataMap.putLong(WearSyncPaths.KEY_UPDATED_AT, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "putDataItem failed", e)
            false
        }
    }

    // ---------------------------------------------------------------------------------
    // MessageClient (phone -> watch)
    // ---------------------------------------------------------------------------------

    /**
     * Builds the message NOW (so position/time are accurate) and sends it in FIFO order.
     * Heartbeats use [WearSyncPaths.PLAYBACK_HEARTBEAT]; everything else uses PLAYBACK_STATE.
     */
    private fun enqueue(event: SyncEvent) {
        val message = buildMessage(event) ?: return
        val path = if (event == SyncEvent.HEARTBEAT) {
            WearSyncPaths.PLAYBACK_HEARTBEAT
        } else {
            WearSyncPaths.PLAYBACK_STATE
        }
        scope.launch {
            sendMutex.withLock {
                val nodes = nodeClient.connectedNodes.awaitOrNull().orEmpty()
                if (nodes.isEmpty()) return@withLock
                val bytes = message.toBytes()
                nodes.forEach { messageClient.sendMessage(it.id, path, bytes).awaitOrNull() }
            }
        }
    }

    private fun buildMessage(event: SyncEvent): PlaybackSyncMessage? {
        val item = player.currentMediaItem ?: return null
        val trackId = trackIdOf(item)
        return PlaybackSyncMessage(
            event = event,
            trackId = trackId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            speed = player.playbackParameters.speed,
            sentAtWallMs = System.currentTimeMillis(),
            lyricsHash = if (lyricsTrackId == trackId) lyricsHash else null,
        )
    }

    // ---------------------------------------------------------------------------------
    // MessageClient (watch -> phone)
    // ---------------------------------------------------------------------------------

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data
        scope.launch {
            when (path) {
                WearSyncPaths.PLAYBACK_COMMAND -> PlaybackCommand.fromBytes(data)?.let(::handleCommand)
                WearSyncPaths.LYRICS_REQUEST -> handleLyricsRequest(String(data, Charsets.UTF_8))
            }
        }
    }

    private fun handleCommand(command: PlaybackCommand) {
        when (command) {
            PlaybackCommand.PLAY -> player.play()
            PlaybackCommand.PAUSE -> player.pause()
            PlaybackCommand.NEXT -> player.seekToNext()
            PlaybackCommand.PREVIOUS -> player.seekToPrevious()
        }
    }

    private suspend fun handleLyricsRequest(requestedTrackId: String) {
        val payload = currentPayload
        if (requestedTrackId.isNotEmpty() && payload != null && payload.trackId == requestedTrackId) {
            if (putPayload(payload)) deliveredLog.add(payload.trackId, payload.contentHash)
        }
        enqueue(SyncEvent.TRACK) // always answer with the current state + hash
    }

    // ---------------------------------------------------------------------------------
    // Mapping  LyricLine -> RawLyricLine
    // ---------------------------------------------------------------------------------

    /**
     * ONLY place that knows the shape of `LyricLine`. Adjust the property names here if they
     * differ in the repo (assumed: text, startTimeMs, endTimeMs, syllables[text,startTimeMs,endTimeMs]).
     */
    private fun LyricLine.toRaw(): RawLyricLine = RawLyricLine(
        text = text,
        startMs = startTimeMs,
        endMs = endTimeMs,
        syllables = syllables.map { RawSyllable(it.text, it.startTimeMs, it.endTimeMs) },
    )

    private companion object {
        const val TAG = "WearLyricsBridge"
    }
}

/** Persistent, bounded, ordered record of "(trackId, hash) already pushed to the Data Layer". */
private class DeliveredLog(context: Context) {
    private val prefs = context.getSharedPreferences("wear_lyrics_bridge", Context.MODE_PRIVATE)
    private val entries: ArrayDeque<String> = ArrayDeque(
        prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotEmpty() },
    )

    @Synchronized
    fun contains(trackId: String, hash: String) = entries.contains(key(trackId, hash))

    @Synchronized
    fun add(trackId: String, hash: String) {
        val k = key(trackId, hash)
        entries.remove(k)
        entries.addLast(k)
        while (entries.size > MAX) entries.removeFirst()
        prefs.edit().putString(KEY, entries.joinToString("\n")).apply()
    }

    private fun key(trackId: String, hash: String) = "${trackId.replace('\n', ' ')}#$hash"

    private companion object {
        const val KEY = "delivered"
        const val MAX = 150
    }
}

private suspend fun <T> Task<T>.awaitOrNull(): T? = try {
    await()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}