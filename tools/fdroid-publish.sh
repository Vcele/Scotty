#!/usr/bin/env bash
# Builds a signed release APK of this checkout and adds it to a self-hosted F-Droid repo.
#
#   tools/fdroid-publish.sh            build locally, then publish
#   tools/fdroid-publish.sh vX.Y.Z     publish the APK of that GitHub release instead
#
# Settings live outside the repo, in ~/.config/scotty/fdroid.env (all optional except FDROID_REPO):
#   FDROID_REPO=~/path/to/fdroid-repo     directory with config.yml (made by `fdroid init`)
#   FDROID_HOST=user@server               rsync target host; unset = local repo only
#   FDROID_DEST=/srv/fdroid               directory on the host that is served
#   SCOTTY_KEYSTORE=~/.android/debug.keystore   + SCOTTY_KEYSTORE_PASSWORD / SCOTTY_KEY_ALIAS / SCOTTY_KEY_PASSWORD
# The APK must be signed with the same key every time, or phones refuse it as an update.
set -euo pipefail
cd "$(dirname "$0")/.."
conf=${SCOTTY_FDROID_CONF:-$HOME/.config/scotty/fdroid.env}
# shellcheck disable=SC1090
[ -f "$conf" ] && set -a && . "$conf" && set +a
: "${FDROID_REPO:?set FDROID_REPO in $conf}"
FDROID_REPO=${FDROID_REPO/#\~/$HOME}
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT

if [ $# -gt 0 ]; then
    gh release download "$1" -p '*.apk' -D "$work"
    apk=$(ls "$work"/*.apk)
else
    export SCOTTY_KEYSTORE=${SCOTTY_KEYSTORE/#\~/$HOME}
    nix develop -c ./gradlew -q assembleRelease
    apk=app/build/outputs/apk/release/app-release.apk
fi

tools=$(nix build --no-link --print-out-paths nixpkgs#aapt)/bin  # provides aapt2
code=$("$tools/aapt2" dump badging "$apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
name=$("$tools/aapt2" dump badging "$apk" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
target="$FDROID_REPO/repo/app.scotty_$code.apk"
if [ -e "$target" ] && ! cmp -s "$apk" "$target"; then
    echo "versionCode $code is already in the repo with a different APK – bump versionCode in app/build.gradle.kts" >&2
    exit 1
fi
cp "$apk" "$target"
mkdir -p "$FDROID_REPO/metadata/app.scotty/en-US"
cp tools/fdroid/app.scotty.yml "$FDROID_REPO/metadata/app.scotty.yml"
cp art/icon-512.png "$FDROID_REPO/metadata/app.scotty/en-US/icon.png"

(cd "$FDROID_REPO" && nix-shell -p fdroidserver jdk17 --run \
    'JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v java)))) fdroid update --pretty')

if [ -n "${FDROID_HOST:-}" ]; then
    rsync -a "$FDROID_REPO/repo" "$FDROID_REPO/archive" "$FDROID_HOST:${FDROID_DEST:?set FDROID_DEST}/"
fi
echo "published Scotty $name (versionCode $code)"
