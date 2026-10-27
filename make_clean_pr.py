#!/usr/bin/env python3
"""
make_clean_pr.py  -  adds the watch-lyrics feature to a checkout of the UPSTREAM project, and nothing else.

Run it on a branch created from upstream/main (see the steps you were given), with these files
uploaded next to it in the repo root:
    WearSyncPaths.kt  SyncedLyricsPayload.kt  LyricsCache.kt  LyricsViewModel.kt
    LyricsScreen.kt   LyricLineItem.kt        WearLyricsSync.kt
The files are MOVED to their places (the root is left clean), the few hooks in existing files are
added, and a report at the end lists exactly what changed, so the pull request holds only the feature:
no workflow changes, no version bump, no helper scripts, no stray files.

    python3 make_clean_pr.py              # English strings only (what an upstream PR should carry)
    python3 make_clean_pr.py --turkish    # also values-tr, for your own build
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path.cwd()
OLD_PKG = "com.music.bitchord.shared.wear"
NEW_PKG = "com.music.bitchord.wear.lyrics"
UI = ROOT / "wearos/src/main/java/com/music/bitchord/wear/ui"
RES = ROOT / "wearos/src/main/res"
APP_WEAR = ROOT / "app/src/main/java/com/music/bitchord/wear"
TV_WEAR = ROOT / "tv/src/main/java/com/music/bitchord/wear"
SERVICE = ROOT / "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt"
MAIN_VM = ROOT / "app/src/main/java/com/music/bitchord/ui/MainViewModel.kt"
problems, touched, created = [], [], []


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True).stdout


def read(p):
    return p.read_text(encoding="utf-8")


def write_new(p, text):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")
    created.append(p)


def patch(path, old, new, marker, what):
    if not path.exists():
        problems.append(f"{path.relative_to(ROOT)} not found ({what})")
        return
    txt = read(path)
    if marker in txt:
        print(f"  = {path.name}: {what} (already there)")
        return
    if txt.count(old) != 1:
        problems.append(f"{path.name}: expected exactly one place for: {what} (found {txt.count(old)}). "
                        f"Is this checkout really upstream/main?")
        return
    path.write_text(txt.replace(old, new, 1), encoding="utf-8")
    if path not in touched:
        touched.append(path)
    print(f"  + {path.name}: {what}")


# ---------------------------------------------------------------------------------- 0. guards
print("[0/6] checking where we are")
if not (ROOT / "settings.gradle.kts").exists():
    sys.exit("Run from the repo root.")
if "upstream" not in git("remote").split():
    sys.exit("No 'upstream' remote. Run:  git remote add upstream https://github.com/kushagrasinghx/BitChord.git && git fetch upstream")
if git("diff", "--name-only", "upstream/main", "--").strip():
    sys.exit("This branch already differs from upstream/main:\n" + git("diff", "--stat", "upstream/main", "--") +
             "\nStart from a clean copy of upstream:  git checkout -b lyrics-bridge upstream/main")
print("  = branch is identical to upstream/main")

FEATURE_FILES = {
    "WearSyncPaths.kt": None, "SyncedLyricsPayload.kt": None,   # protocol: folder decided below
    "LyricsCache.kt": UI, "LyricsViewModel.kt": UI, "LyricsScreen.kt": UI, "LyricLineItem.kt": UI,
    "WearLyricsSync.kt": APP_WEAR,
}
missing = [n for n in FEATURE_FILES if not (ROOT / n).exists()]
if missing:
    sys.exit("Upload these to the repo root first: " + ", ".join(missing))

# the protocol folder sits next to the project's own WearProtocol.kt
proto = next(iter((ROOT / "wearos/protocol").rglob("WearProtocol.kt")), None)
PROTO_DIR = (proto.parent / "lyrics") if proto else (ROOT / "wearos/protocol/com/music/bitchord/wear/lyrics")

# ---------------------------------------------------------------------------------- 1. new files
print("[1/6] new files (package " + NEW_PKG + ")")
for name, folder in FEATURE_FILES.items():
    src = ROOT / name
    dest = (folder or PROTO_DIR) / name
    write_new(dest, read(src).replace(OLD_PKG, NEW_PKG))
    src.unlink()
    print(f"  + {dest.relative_to(ROOT)}")

# ---------------------------------------------------------------------------------- 2. hooks
print("[2/6] hooks in existing files")
np_ = UI / "NowPlayingScreen.kt"
patch(np_, "    val openMore: () -> Unit,\n)", "    val openMore: () -> Unit,\n    val openLyrics: () -> Unit,\n)",
      "val openLyrics", "PlayerActions.openLyrics")
patch(np_, "            Row(horizontalArrangement = Arrangement.spacedBy(w * 0.045f)) {\n                val chip = w * 0.155f\n",
      "            Row(horizontalArrangement = Arrangement.spacedBy(w * 0.035f)) {\n                val chip = if (np.canLike) w * 0.14f else w * 0.155f\n",
      "if (np.canLike) w * 0.14f", "room for a fourth chip")
patch(np_, "                GlyphButton(\n                    icon = R.drawable.ic_queue,",
      "                GlyphButton(\n                    icon = R.drawable.ic_lyrics,\n"
      "                    description = stringResource(R.string.wear_lyrics),\n"
      "                    size = chip,\n                    iconSize = glyph,\n"
      "                    background = Color.White.copy(alpha = 0.14f),\n"
      "                    onClick = actions.openLyrics,\n                )\n"
      "                GlyphButton(\n                    icon = R.drawable.ic_queue,",
      "actions.openLyrics", "Lyrics chip")

wa = UI / "WearApp.kt"
patch(wa, 'private const val OUTPUT = "output"', 'private const val OUTPUT = "output"\nprivate const val LYRICS = "lyrics"',
      "private const val LYRICS", "route name")
patch(wa, "                    openMore = { nav.navigate(MORE) },\n                )",
      "                    openMore = { nav.navigate(MORE) },\n                    openLyrics = { nav.navigate(LYRICS) },\n                )",
      "openLyrics = { nav.navigate(LYRICS) }", "PlayerRoute call")
patch(wa, "            composable(OUTPUT) {",
      "            composable(LYRICS) {\n                LyricsScreen(\n                    np = np,\n                    onPrevious = link::previous,\n"
      "                    onNext = link::next,\n                    onTogglePlay = link::togglePlay,\n"
      "                    onSeek = link::seekTo,\n                )\n            }\n            composable(OUTPUT) {",
      "composable(LYRICS)", "lyrics destination")
patch(wa, "private fun PlayerRoute(link: PhoneLink, openQueue: () -> Unit, openMore: () -> Unit) {",
      "private fun PlayerRoute(\n    link: PhoneLink,\n    openQueue: () -> Unit,\n    openMore: () -> Unit,\n    openLyrics: () -> Unit,\n) {",
      "openLyrics: () -> Unit,\n) {", "PlayerRoute signature")
patch(wa, "            openQueue = openQueue,\n            openMore = openMore,\n        )",
      "            openQueue = openQueue,\n            openMore = openMore,\n            openLyrics = openLyrics,\n        )",
      "openLyrics = openLyrics", "PlayerActions construction")

patch(SERVICE, "import com.music.bitchord.wear.WearBridge\n", "import com.music.bitchord.wear.WearBridge\nimport com.music.bitchord.wear.WearLyricsSync\n",
      "import com.music.bitchord.wear.WearLyricsSync", "import")
patch(SERVICE, "    override fun onCreate() {\n        super.onCreate()\n\n        if (com.music.bitchord.data.innertube.Innertube.cookie == null) {",
      "    override fun onCreate() {\n        super.onCreate()\n        WearLyricsSync.start(applicationContext)\n"
      "        WearLyricsSync.playhead = {\n"
      "            player?.let { WearLyricsSync.Playhead(it.currentPosition, it.isPlaying, it.playbackParameters.speed) }\n"
      "        }\n\n        if (com.music.bitchord.data.innertube.Innertube.cookie == null) {",
      "WearLyricsSync.start(applicationContext)", "start the watch lyrics sender")
patch(SERVICE, "    override fun onDestroy() {\n        WearBridge.attachStreams(null)\n        WearBridge.releaseSpeaker()",
      "    override fun onDestroy() {\n        WearLyricsSync.stop(applicationContext)\n        WearBridge.attachStreams(null)\n        WearBridge.releaseSpeaker()",
      "WearLyricsSync.stop(applicationContext)", "stop it")
patch(SERVICE, "        if (!AppSettings.syncedLyrics.value) {\n            serviceLyrics = null\n            stopLyricsTicker()\n            return\n        }\n\n        val trackDurationMs",
      "        if (!AppSettings.syncedLyrics.value) {\n            serviceLyrics = null\n            stopLyricsTicker()\n"
      "            WearLyricsSync.publish(applicationContext, currentSong.videoId, currentSong.title, currentSong.artist, 0L, null, album = currentSong.albumName)\n"
      "            return\n        }\n\n        val trackDurationMs",
      "0L, null, album = currentSong.albumName)", "lyrics switched off")
patch(SERVICE, "            withContext(Dispatchers.Main) {\n                serviceLyrics = lines\n                if (player?.isPlaying == true) {",
      "            withContext(Dispatchers.Main) {\n                serviceLyrics = lines\n"
      "                WearLyricsSync.publish(applicationContext, currentSong.videoId, currentSong.title, currentSong.artist, trackDurationMs, lines, album = currentSong.albumName)\n"
      "                if (player?.isPlaying == true) {",
      "trackDurationMs, lines, album = currentSong.albumName)", "lyrics loaded")

patch(MAIN_VM, "_lyrics.value = embedded", "_lyrics.value = embedded\n                    wearPublishLyrics(embedded)",
      "wearPublishLyrics(embedded)", "embedded lyrics")
patch(MAIN_VM, "_lyrics.value = selected?.lines", "_lyrics.value = selected?.lines\n            selected?.lines?.let { wearPublishLyrics(it) }",
      "selected?.lines?.let { wearPublishLyrics(it) }", "automatic lookup")
patch(MAIN_VM, "lyricsProviderResults[source]?.let(::showLyricsResult)",
      "lyricsProviderResults[source]?.let { showLyricsResult(it); wearPublishLyrics(it.lines) }",
      "showLyricsResult(it); wearPublishLyrics(it.lines)", "source picked in the drawer")
patch(MAIN_VM, "if (selectedLyricsSource == source) showLyricsResult(result)",
      "if (selectedLyricsSource == source) {\n            showLyricsResult(result)\n            wearPublishLyrics(result.lines)\n        }",
      "wearPublishLyrics(result.lines)", "source that finished after being picked")
patch(MAIN_VM, "    /**\n     * Selects a provider from the player drawer.",
      "    /** What the player is showing now goes to the watch too (see WearLyricsSync). */\n"
      "    private fun wearPublishLyrics(lines: List<com.music.bitchord.data.lyrics.LyricLine>) {\n"
      "        val request = currentLyricsRequest ?: return\n"
      "        com.music.bitchord.wear.WearLyricsSync.publish(\n"
      "            getApplication<android.app.Application>(),\n"
      "            request.videoId, request.title, request.artist, request.durationMs, lines,\n"
      "            album = request.album,\n            fromUi = true,\n        )\n    }\n\n"
      "    /**\n     * Selects a provider from the player drawer.",
      "private fun wearPublishLyrics", "helper")

wbuild = ROOT / "wearos/build.gradle.kts"
if wbuild.exists() and "lifecycle-viewmodel-compose" not in read(wbuild):
    m = re.search(r'^(\s*)implementation\("androidx\.lifecycle:lifecycle-runtime-compose:([^"]+)"\)\s*$', read(wbuild), re.M)
    if m:
        t = read(wbuild)
        wbuild.write_text(t[:m.end()] + f'\n{m.group(1)}implementation("androidx.lifecycle:lifecycle-viewmodel-compose:{m.group(2)}")' + t[m.end():], encoding="utf-8")
        touched.append(wbuild)
        print("  + build.gradle.kts (wearos): lifecycle-viewmodel-compose, same version as lifecycle-runtime-compose")
    else:
        problems.append('wearos/build.gradle.kts: add implementation("androidx.lifecycle:lifecycle-viewmodel-compose:<same version as runtime-compose>")')

# ---------------------------------------------------------------------------------- 3. TV
print("[3/6] the TV build shares the phone's service, and has no Wear OS libraries")
tvb = ROOT / "tv/build.gradle.kts"
if tvb.exists():
    t = read(tvb)
    if "WearLyricsSync" in t:
        print("  = tv/build.gradle.kts already excludes it")
    elif re.search(r'"[^"]*WearBridge\.kt"', t):
        new = re.sub(r'("[^"]*?)WearBridge\.kt"', r'\1WearBridge.kt", \1WearLyricsSync.kt"', t)
        tvb.write_text(new, encoding="utf-8")
        touched.append(tvb)
        print('  + tv/build.gradle.kts: WearLyricsSync.kt excluded next to WearBridge.kt')
    else:
        problems.append("tv/build.gradle.kts: could not find where WearBridge.kt is excluded. Lines mentioning it:\n      " +
                        "\n      ".join(l for l in t.splitlines() if "WearBridge" in l or "srcDir" in l))
    write_new(TV_WEAR / "WearLyricsSync.kt", '''package com.music.bitchord.wear

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine

/**
 * The TV build has no watch to talk to. Same surface as the phone's WearLyricsSync, doing nothing,
 * the way this folder's WearBridge does.
 */
object WearLyricsSync {
    class Playhead(val positionMs: Long, val advancing: Boolean, val speed: Float)

    @Volatile var playhead: (() -> Playhead?)? = null

    fun start(context: Context) = Unit

    fun stop(context: Context) = Unit

    fun publish(
        context: Context,
        mediaId: String,
        title: String,
        artist: String,
        durationMs: Long,
        lines: List<LyricLine>?,
        album: String? = null,
        fromUi: Boolean = false,
    ) = Unit
}
''')
else:
    print("  = no tv/ module here")

# ---------------------------------------------------------------------------------- 4. resources
print("[4/6] resources")
code = "".join(read(p) for p in created if p.suffix == ".kt") + read(np_)
used = sorted(set(re.findall(r"R\.string\.(wear_lyrics\w*)", code)))
EN = {
    "wear_lyrics": "Lyrics",
    "wear_lyrics_waiting": "Play a song in BitChord on your phone. Tap to reconnect.",
    "wear_lyrics_loading": "Loading lyrics",
    "wear_lyrics_none": "No synced lyrics for this song.",
    "wear_lyrics_translate": "Translate",
    "wear_lyrics_romanize": "Latin script",
    "wear_lyrics_extra_loading": "Loading…",
    "wear_lyrics_extra_same": "Nothing to convert",
    "wear_lyrics_extra_unavailable": "Not available for this song",
    "wear_lyrics_tune_title": "Sync",
    "wear_lyrics_keep_awake": "Keep screen on",
    "wear_lyrics_keep_awake_on": "Screen stays on",
    "wear_lyrics_auto_sync": "Auto sync",
    "wear_lyrics_auto_sync_waiting": "Auto sync: measuring…",
    "wear_lyrics_close": "Close",
    "wear_lyrics_source": "Lyrics source",
    "wear_lyrics_source_back": "Back",
    "wear_lyrics_source_searching": "Searching…",
    "wear_lyrics_source_none": "No source has synced lyrics for this song",
    "wear_lyrics_source_word": "Word by word",
    "wear_lyrics_source_line": "Line by line",
}
TR = {
    "wear_lyrics": "Sözler",
    "wear_lyrics_waiting": "Telefonda BitChord ile bir şarkı çalın. Yeniden bağlanmak için dokunun.",
    "wear_lyrics_loading": "Sözler yükleniyor",
    "wear_lyrics_none": "Bu şarkı için senkronlu söz yok.",
    "wear_lyrics_translate": "Çeviri",
    "wear_lyrics_romanize": "Latin harfleri",
    "wear_lyrics_extra_loading": "Yükleniyor…",
    "wear_lyrics_extra_same": "Dönüştürülecek bir şey yok",
    "wear_lyrics_extra_unavailable": "Bu şarkı için yok",
    "wear_lyrics_tune_title": "Senkron",
    "wear_lyrics_keep_awake": "Ekran açık kalsın",
    "wear_lyrics_keep_awake_on": "Ekran açık kalıyor",
    "wear_lyrics_auto_sync": "Otomatik senkron",
    "wear_lyrics_auto_sync_waiting": "Otomatik senkron: ölçülüyor…",
    "wear_lyrics_close": "Kapat",
    "wear_lyrics_source": "Söz kaynağı",
    "wear_lyrics_source_back": "Geri",
    "wear_lyrics_source_searching": "Aranıyor…",
    "wear_lyrics_source_none": "Bu şarkı için senkronlu söz kaynağı yok",
    "wear_lyrics_source_word": "Kelime kelime",
    "wear_lyrics_source_line": "Satır satır",
}
unknown = [n for n in used if n not in EN]
if unknown:
    problems.append("strings used by the code but not defined here: " + ", ".join(unknown))


def strings(table):
    body = "".join(f'    <string name="{k}">{table[k]}</string>\n' for k in used if k in table)
    return f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n{body}</resources>\n'


write_new(RES / "values/strings_lyrics.xml", strings(EN))
if "--turkish" in sys.argv:
    write_new(RES / "values-tr/strings_lyrics.xml", strings(TR))
HEAD = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp"\n    android:height="24dp"\n'
        '    android:viewportWidth="24"\n    android:viewportHeight="24">\n    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="')
TAIL = '" />\n</vector>\n'
write_new(RES / "drawable/ic_lyrics.xml", HEAD + "M4,6h16v2H4z M4,11h16v2H4z M4,16h10v2H4z" + TAIL)
write_new(RES / "drawable/ic_lyrics_translate.xml", HEAD + (
    "M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53L17,6L17,4h-7L10,2L8,2v2L1,4v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 "
    "8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12z"
    "M15.88,17l1.62,-4.33L19.12,17h-3.24z") + TAIL)
write_new(RES / "drawable/ic_lyrics_romanize.xml", HEAD + (
    "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM18.92,8h-2.95c-0.32,-1.25 -0.78,-2.45 -1.38,-3.56 "
    "1.84,0.63 3.37,1.91 4.33,3.56zM12,4.04c0.83,1.2 1.48,2.53 1.91,3.96h-3.82c0.43,-1.43 1.08,-2.76 1.91,-3.96zM4.26,14C4.1,13.36 4,12.69 4,12s0.1,-1.36 "
    "0.26,-2h3.38c-0.08,0.66 -0.14,1.32 -0.14,2 0,0.68 0.06,1.34 0.14,2L4.26,14zM5.08,16h2.95c0.32,1.25 0.78,2.45 1.38,3.56 -1.84,-0.63 -3.37,-1.9 -4.33,-3.56z"
    "M8.03,8L5.08,8c0.96,-1.66 2.49,-2.93 4.33,-3.56C8.81,5.55 8.35,6.75 8.03,8zM12,19.96c-0.83,-1.2 -1.48,-2.53 -1.91,-3.96h3.82c-0.43,1.43 -1.08,2.76 -1.91,3.96z"
    "M14.34,14L9.66,14c-0.09,-0.66 -0.16,-1.32 -0.16,-2 0,-0.68 0.07,-1.35 0.16,-2h4.68c0.09,0.65 0.16,1.32 0.16,2 0,0.68 -0.07,1.34 -0.16,2z"
    "M14.59,19.56c0.6,-1.11 1.06,-2.31 1.38,-3.56h2.95c-0.96,1.65 -2.49,2.93 -4.33,3.56zM16.36,14c0.08,-0.66 0.14,-1.32 0.14,-2 0,-0.68 -0.06,-1.34 -0.14,-2h3.38"
    "c0.16,0.64 0.26,1.31 0.26,2s-0.1,1.36 -0.26,2h-3.38z") + TAIL)
print(f"  + {len(used)} strings, 3 icons")

# ---------------------------------------------------------------------------------- 5. checks
print("[5/6] checks")
for p in created + touched:
    if p.suffix == ".kt":
        s = read(p)
        if s.count("{") != s.count("}"):
            problems.append(f"{p.relative_to(ROOT)}: unbalanced braces")
leftovers = [p.name for p in ROOT.glob("*") if p.is_file() and p.suffix in (".kt", ".bak", ".py", ".txt", ".log", ".b64", ".keystore")
             and p.name != "make_clean_pr.py"]
if leftovers:
    print("  ! files in the repo root that are NOT part of the PR (leave them out of the commit): " + ", ".join(leftovers))

# ---------------------------------------------------------------------------------- 6. report
print("[6/6] what the pull request will contain")
rel = lambda p: str(p.relative_to(ROOT))
print("  new files:")
for p in created:
    print("    " + rel(p))
print("  changed files:")
for p in touched:
    print("    " + rel(p))
status = git("status", "--porcelain")
bad = [l for l in status.splitlines() if l.startswith((" D", "D ", "R ")) or ".github" in l or "build.gradle.kts" in l and "wearos/" not in l and "tv/" not in l]
if bad:
    problems.append("these must NOT be in the PR: " + "; ".join(bad))
if problems:
    print("\n!! NEEDS ATTENTION")
    for p in problems:
        print("  - " + p)
    sys.exit(1)
paths = " ".join(rel(p) for p in created + touched)
print("\nOK. Next:\n  git add " + paths + "\n  git status --short      # nothing else should be listed as staged\n"
      "  git commit -m \"Watch app: synced lyrics screen\"\n  git push -u origin HEAD")
