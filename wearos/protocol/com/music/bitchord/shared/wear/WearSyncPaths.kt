package com.music.bitchord.shared.wear

object WearSyncPaths {
    const val APPLICATION_ID = "com.music.bitchord"

    const val LYRICS_PAYLOAD = "/bitchord/lyrics/payload"
    const val PLAYBACK_STATE = "/bitchord/playback/state"
    const val PLAYBACK_HEARTBEAT = "/bitchord/playback/heartbeat"

    const val PLAYBACK_COMMAND = "/bitchord/playback/command"
    const val LYRICS_REQUEST = "/bitchord/lyrics/request"

    /** Watch -> phone: "trackId", asks which sources have synced lyrics for it. Answer: [LYRICS_SOURCES]. */
    const val LYRICS_SOURCES_REQUEST = "/bitchord/lyrics/sources/request"

    /** Watch -> phone: "trackId\nsourceId", play the lyrics of that source on the watch. */
    const val LYRICS_SELECT = "/bitchord/lyrics/select"

    /** Phone -> watch (DataClient): the sources that have synced lyrics for the track, as they are found. */
    const val LYRICS_SOURCES = "/bitchord/lyrics/sources"
    const val KEY_IDS = "ids"
    const val KEY_LABELS = "labels"
    const val KEY_KINDS = "kinds"
    const val KEY_CURRENT = "current"
    const val KEY_DONE = "done"

    /** Phone -> watch (DataClient): the player's lyrics offset in ms; positive = lyrics later. */
    const val LYRICS_OFFSET = "/bitchord/lyrics/offset"
    const val KEY_OFFSET_MS = "offsetMs"

    /** Watch -> phone: "trackId\nmode", mode = translation | romanization | original. */
    const val LYRICS_TRANSFORM = "/bitchord/lyrics/transform"

    /** Phone -> watch (DataClient): per-line translated or romanized text, aligned to the payload's lines. */
    const val LYRICS_EXTRA = "/bitchord/lyrics/extra"
    const val KEY_KIND = "kind"
    const val KEY_STATUS = "status"
    const val KEY_LANG = "lang"
    const val KEY_TEXTS = "texts"
    const val KIND_TRANSLATION = "translation"
    const val KIND_ROMANIZATION = "romanization"
    const val KIND_ORIGINAL = "original"
    const val STATUS_OK = "ok"
    const val STATUS_SAME = "same"
    const val STATUS_UNAVAILABLE = "unavailable"

    /** Watch -> phone: its send time. Phone -> watch: that time echoed, then the phone's clock. */
    const val CLOCK_PING = "/bitchord/lyrics/clock/ping"
    const val CLOCK_PONG = "/bitchord/lyrics/clock/pong"

    const val KEY_TRACK_ID = "trackId"
    const val KEY_HASH = "hash"
    const val KEY_ASSET = "lyricsAsset"
    const val KEY_UPDATED_AT = "updatedAt"

    const val NO_LYRICS_HASH = "none"

    const val HEARTBEAT_INTERVAL_MS = 5_000L
    const val DRIFT_SNAP_THRESHOLD_MS = 500L
}
