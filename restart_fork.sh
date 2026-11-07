#!/bin/bash
# restart_fork.sh - rebuild the fork's `main` from scratch: upstream/main + the watch-lyrics feature + the fork's
# own extras (CI workflow, version bump, Turkish strings, docs). ONE branch, no separate PR branch.
#
#   unzip -o pr_bundle.zip && bash restart_fork.sh --code 50 --yes
#
# --code N     versionCode for the fork build (must be higher than what is installed; default 50)
# --no-turkish leave out values-tr
# --yes        do not ask before resetting local main
# The old local main is kept as the branch `main-before-restart`.
set -e
cd "$(git rev-parse --show-toplevel)"
CODE=50; TURKISH=1; YES=0
while [ $# -gt 0 ]; do case "$1" in --code) CODE="$2"; shift;; --no-turkish) TURKISH=0;; --yes) YES=1;; esac; shift; done

for f in WearSyncPaths.kt SyncedLyricsPayload.kt LyricsCache.kt LyricsViewModel.kt LyricsScreen.kt LyricLineItem.kt WearLyricsSync.kt make_clean_pr.py build-apk.yml; do
  [ -f "$f" ] || { echo "missing $f in the repo root (unzip pr_bundle.zip here first)"; exit 1; }
done
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream https://github.com/kushagrasinghx/BitChord.git
git fetch upstream
if [ $YES -ne 1 ]; then
  read -r -p "This resets local 'main' to upstream/main and rebuilds the fork on it. Type yes: " a
  [ "$a" = yes ] || exit 1
fi

# The checkout below deletes every file that is tracked on the CURRENT branch but not in upstream (that is how
# an earlier run lost make_clean_pr.py and the .kt files). So the bundle is put aside first and restored after.
STASH="$(mktemp -d)"
for f in *.kt make_clean_pr.py build-apk.yml update_files.sh export_pr.sh strings_lyrics_tr.xml WATCH_LYRICS.md; do
  [ -f "$f" ] && cp -f "$f" "$STASH/"
done
# The fork's published main is what a restart replaces: keep it under another name.
if git rev-parse -q --verify origin/main >/dev/null; then
  git branch -f main-before-restart origin/main
  echo "(the fork's current main is kept as main-before-restart)"
fi
git checkout -f -B main upstream/main
cp -f "$STASH"/* .

echo "== 1/3 the feature on top of upstream"
python3 make_clean_pr.py
git add app wearos tv
git commit -q -m "Watch app: synced lyrics screen"

echo "== 2/3 the fork's extras"
mkdir -p .github/workflows docs
cp build-apk.yml .github/workflows/build-apk.yml
grep -qxF '*.bak' .gitignore 2>/dev/null || echo '*.bak' >> .gitignore
if grep -q 'bitchordVersionCode' build.gradle.kts; then
  sed -i "s/extra\[\"bitchordVersionCode\"\] = [0-9]*/extra[\"bitchordVersionCode\"] = $CODE/; s/extra\[\"bitchordBetaSuffix\"\] = \"[^\"]*\"/extra[\"bitchordBetaSuffix\"] = \"beta1\"/" build.gradle.kts
else
  echo "! build.gradle.kts has no bitchordVersionCode: bump the version by hand"
fi
[ -f WATCH_LYRICS.md ] && cp WATCH_LYRICS.md docs/WATCH_LYRICS.md
PATHS=".github .gitignore build.gradle.kts docs"
if [ $TURKISH -eq 1 ] && [ -f strings_lyrics_tr.xml ]; then
  mkdir -p wearos/src/main/res/values-tr
  cp strings_lyrics_tr.xml wearos/src/main/res/values-tr/strings_lyrics.xml
  PATHS="$PATHS wearos/src/main/res/values-tr"
fi
git add $PATHS
git commit -q -m "Fork build: CI, version, Turkish strings, docs"

echo "== 3/3 done. Check, then publish:"
git log --oneline -3
echo "  git push --force-with-lease origin main      # replaces the fork's main (the old one is in main-before-restart)"
echo "  then: GitHub > Actions > Build phone APK > Run workflow"
echo "Leftover helper files in the repo root are not committed; delete them when you like:"
echo "  rm -f *.kt make_clean_pr.py update_files.sh restart_fork.sh export_pr.sh build-apk.yml strings_lyrics_tr.xml WATCH_LYRICS.md pr_bundle.zip"
