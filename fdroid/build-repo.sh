#!/usr/bin/env bash
# Builds/updates the self-hosted F-Droid repository.
#
#   fdroid/build-repo.sh --apks <dir> --pages <dir> [--work <dir>] [--keep N]
#
#   --apks   directory with the new release APKs (EasyTier-<version>-<abi>.apk)
#   --pages  checkout of the gh-pages branch (may be empty); previous repo state is read from
#            <pages>/fdroid/repo and the result is written back to <pages>/fdroid/
#   --work   scratch directory (default: ${RUNNER_TEMP:-/tmp}/fdroid-work)
#   --keep   number of most recent versions to keep (default: 5)
#
# Environment (secrets, never printed):
#   FDROID_KEYSTORE_BASE64  base64 of the repo signing keystore (PKCS12), or
#   FDROID_KEYSTORE_FILE    path to that keystore
#   FDROID_KEY_STORE_PASS   keystore password
#   FDROID_KEY_PASS         key password
# Requires: fdroidserver (fdroid), keytool, ANDROID_HOME with build-tools (apksigner).
set -euo pipefail

APP_ID=cc.ptoe.easytier.compose
KEY_ALIAS=repokey
KEEP=5
APKS="" PAGES="" WORK="${RUNNER_TEMP:-/tmp}/fdroid-work"

while [ $# -gt 0 ]; do
  case "$1" in
    --apks) APKS="$2"; shift 2 ;;
    --pages) PAGES="$2"; shift 2 ;;
    --work) WORK="$2"; shift 2 ;;
    --keep) KEEP="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$APKS" ] && [ -n "$PAGES" ] || { echo "usage: $0 --apks DIR --pages DIR [--work DIR] [--keep N]" >&2; exit 2; }
case "$KEEP" in ''|*[!0-9]*) echo "--keep must be a positive integer" >&2; exit 2 ;; esac
[ "$KEEP" -ge 1 ] || { echo "--keep must be >= 1" >&2; exit 2; }
: "${FDROID_KEY_STORE_PASS:?FDROID_KEY_STORE_PASS is not set}"
: "${FDROID_KEY_PASS:?FDROID_KEY_PASS is not set}"

SRC="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SRC/.." && pwd)"
APKS="$(cd "$APKS" && pwd)"
mkdir -p "$PAGES"; PAGES="$(cd "$PAGES" && pwd)"
rm -rf "$WORK"; mkdir -p "$WORK"; WORK="$(cd "$WORK" && pwd)"
FD="$WORK/fdroid"
mkdir -p "$FD/repo" "$FD/metadata"

# --- keystore (secret) ------------------------------------------------------------------
umask 077
if [ -n "${FDROID_KEYSTORE_FILE:-}" ]; then
  cp "$FDROID_KEYSTORE_FILE" "$FD/keystore.p12"
elif [ -n "${FDROID_KEYSTORE_BASE64:-}" ]; then
  printf '%s' "$FDROID_KEYSTORE_BASE64" | base64 -d > "$FD/keystore.p12"
else
  echo "Set FDROID_KEYSTORE_BASE64 (or FDROID_KEYSTORE_FILE)" >&2; exit 1
fi
umask 022

# --- configuration and metadata -----------------------------------------------------------
cp "$SRC/config.yml" "$FD/config.yml"
chmod 600 "$FD/config.yml"
cp -r "$SRC/metadata/." "$FD/metadata/"
meta="$FD/metadata/$APP_ID"
mkdir -p "$meta/en-US/phoneScreenshots"
cp "$ROOT/app/src/main/ic_launcher-playstore.png" "$meta/en-US/icon.png"
n=0
for shot in dashboard profiles peers settings; do
  n=$((n + 1))
  cp "$ROOT/docs/$shot.png" "$meta/en-US/phoneScreenshots/$n-$shot.png"
done

# --- previous repo state, new APKs, retention ---------------------------------------------------
if [ -d "$PAGES/fdroid/repo" ]; then
  find "$PAGES/fdroid/repo" -maxdepth 1 -name '*.apk' -exec cp -p {} "$FD/repo/" \;
fi
shopt -s nullglob
new=("$APKS"/*.apk)
[ "${#new[@]}" -gt 0 ] || { echo "no APKs found in $APKS" >&2; exit 1; }
cp -p "${new[@]}" "$FD/repo/"

version_of() { local f="${1##*/}"; f="${f#EasyTier-}"; echo "${f%%-*}"; }
mapfile -t versions < <(for f in "$FD"/repo/EasyTier-*.apk; do version_of "$f"; done | sort -Vu)
if [ "${#versions[@]}" -gt "$KEEP" ]; then
  drop=$(( ${#versions[@]} - KEEP ))
  for v in "${versions[@]:0:$drop}"; do
    echo "pruning version $v"
    rm -f "$FD"/repo/EasyTier-"$v"-*.apk
  done
  mapfile -t versions < <(for f in "$FD"/repo/EasyTier-*.apk; do version_of "$f"; done | sort -Vu)
fi

# --- index ------------------------------------------------------------------------------
( cd "$FD" && fdroid update --verbose )

# --- fingerprint of the repo signing certificate (SHA-256, upper-case hex) --------------------------
fingerprint="$(keytool -exportcert -alias "$KEY_ALIAS" -keystore "$FD/keystore.p12" \
  -storepass:env FDROID_KEY_STORE_PASS 2>/dev/null | sha256sum | cut -d' ' -f1 | tr 'a-f' 'A-F')"
[ "${#fingerprint}" -eq 64 ] || { echo "could not compute fingerprint" >&2; exit 1; }
repo_url="$(sed -n 's/^repo_url: *//p' "$FD/config.yml")"

cat > "$FD/repo-info.md" <<INFO
# EasyTier Compose F-Droid repository

| | |
|---|---|
| Repository URL | \`$repo_url\` |
| Signing key fingerprint (SHA-256) | \`$fingerprint\` |
| Add with fingerprint | <$repo_url?fingerprint=$fingerprint> |
| Versions in the repository | ${versions[*]} |
| Generated | $(date -u +'%Y-%m-%d %H:%M:%S UTC') |

Add the URL to F-Droid (Settings → Repositories) and compare the fingerprint it shows with the one above.
This is the repository key, which is different from the APK signing key.
INFO

# --- publish into the gh-pages working copy ---------------------------------------------------
mkdir -p "$PAGES/fdroid"
rm -rf "$PAGES/fdroid/repo"
cp -r "$FD/repo" "$PAGES/fdroid/repo"
cp "$FD/repo-info.md" "$PAGES/fdroid/repo-info.md"
touch "$PAGES/.nojekyll"
cat > "$PAGES/index.html" <<'HTML'
<!doctype html>
<meta charset="utf-8">
<meta http-equiv="refresh" content="0; url=fdroid/repo/">
<title>EasyTier Compose F-Droid repository</title>
<a href="fdroid/repo/">F-Droid repository</a> · <a href="fdroid/repo-info.md">repository info</a>
HTML

echo
echo "Repository URL : $repo_url"
echo "Fingerprint    : $fingerprint"
echo "Versions       : ${versions[*]}"
echo "$fingerprint" > "$WORK/fingerprint.txt"
