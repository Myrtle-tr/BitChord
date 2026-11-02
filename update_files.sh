#!/bin/bash
# update_files.sh - puts updated feature files in place on the lyrics-bridge branch. No script logic, no
# package renaming: the files in this folder already carry their final names.
#   unzip -o pr_bundle.zip && bash update_files.sh
set -e
cd "$(git rev-parse --show-toplevel)"
UI=wearos/src/main/java/com/music/bitchord/wear/ui
PROTO="$(dirname "$(find wearos/protocol -name WearProtocol.kt | head -1)")/lyrics"
mkdir -p "$PROTO"
for f in LyricsScreen.kt LyricsViewModel.kt LyricLineItem.kt LyricsCache.kt; do [ -f "$f" ] && mv "$f" "$UI/$f" && echo "  + $UI/$f"; done
for f in SyncedLyricsPayload.kt WearSyncPaths.kt; do [ -f "$f" ] && mv "$f" "$PROTO/$f" && echo "  + $PROTO/$f"; done
[ -f WearLyricsSync.kt ] && mv WearLyricsSync.kt app/src/main/java/com/music/bitchord/wear/WearLyricsSync.kt && echo "  + app/.../WearLyricsSync.kt"
git status --short
