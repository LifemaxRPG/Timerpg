#!/bin/sh
# Publishes the built APK as the update the game finds (AppUpdater, BuildConfig.UPDATE_URL): the repository's "apk" branch
# holds update.json and the APK, as one commit that is replaced each time (no history of binaries piles up).
#   tools/publish-apk.sh ["what is new"]        (after gradle :app:assembleDebug)
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
apk="$here/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$apk" ] || { echo "no APK at $apk: build it first (gradle :app:assembleDebug)" >&2; exit 1; }
sdk="${ANDROID_HOME:-/opt/android-sdk}"
bt="$sdk/build-tools/$(ls "$sdk/build-tools" | sort -V | tail -1)"
badge="$("$bt/aapt2" dump badging "$apk" | head -1)"
vc="$(echo "$badge" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
vn="$(echo "$badge" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
# the same key as every build before it, or Android would refuse it as an update
"$bt/apksigner" verify --print-certs "$apk" 2>/dev/null | grep -q "66fa11dda603331624d821ef95a629dea13238338e80e05e733e9412cb5850b0" \
  || { echo "the APK is not signed with the game's key (app/signing/timerpg-dev.keystore)" >&2; exit 1; }
remote="$(git -C "$here" remote get-url origin)"
repo="lifemaxrpg/timerpg"
name="timerpg-$vc.apk"
tmp="$(mktemp -d)"
cp "$apk" "$tmp/$name"
sha="$(sha256sum "$tmp/$name" | cut -d' ' -f1)"
bytes="$(stat -c %s "$tmp/$name")"
notes="${1:-}"
python3 - "$tmp/update.json" "$vc" "$vn" "https://raw.githubusercontent.com/$repo/apk/$name" "$sha" "$bytes" "$notes" <<'P'
import json, sys
f, vc, vn, url, sha, n, notes = sys.argv[1:]
json.dump({"versionCode": int(vc), "versionName": vn, "apk": url, "sha256": sha, "bytes": int(n), "notes": notes}, open(f, "w"), indent=1)
P
cd "$tmp"
git init -q -b apk
git add update.json "$name"
git -c user.name="Timerpg builds" -c user.email="builds@timerpg.invalid" commit -q -m "Build $vn"
git push -q --force "$remote" apk
echo "published $vn ($bytes bytes): https://raw.githubusercontent.com/$repo/apk/update.json"
rm -rf "$tmp"
