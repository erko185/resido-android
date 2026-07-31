#!/usr/bin/env bash
# Release script for the Resido Android client - the counterpart of
# windows_app_script/script/build.ps1: ask which version to release, build a
# signed release APK, then upload the APK + latest.json to the update host
# over SFTP.
#
# Usage:
#   ./build.sh                     # interactive (version + SFTP password)
#   ./build.sh --version 1.2.0     # skip the version prompt
#   ./build.sh --skip-upload       # build only, upload manually
#   ./build.sh --play              # build the Google Play AAB instead of the
#                                  # sideload APK (no SFTP upload - the bundle
#                                  # is uploaded manually to Play Console)
#
# Requires: JDK 17+, Android SDK (sdk.dir in resido-client/local.properties
# or ANDROID_HOME), keytool, shasum, sftp.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT_DIR="$SCRIPT_DIR/../resido-client"
ENV_FILE="$SCRIPT_DIR/.env"
DIST_DIR="$CLIENT_DIR/dist"

SFTP_HOST="37.9.175.196"
# Default SFTP login; override with RESIDO_ANDROID_SFTP_USER in script/.env
# when the hosting panel generated a different username for the subdomain.
SFTP_USER="residoandroid.vorntech.sk"
SFTP_PORT=22
UPDATE_BASE_URL="https://residoandroid.vorntech.sk"

VERSION=""
SKIP_UPLOAD=0
PLAY_BUILD=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --skip-upload) SKIP_UPLOAD=1; shift ;;
    --play) PLAY_BUILD=1; shift ;;
    *) echo "Neznamy parameter: $1" >&2; exit 1 ;;
  esac
done

# --- 1. Which version to release ------------------------------------------

current_version="(neznama)"
if [[ -f "$ENV_FILE" ]]; then
  current_version="$(grep -E '^RESIDO_ANDROID_CLIENT_VERSION=' "$ENV_FILE" | head -1 | cut -d= -f2- || true)"
  current_version="${current_version:-"(neznama)"}"
fi

if [[ -z "$VERSION" ]]; then
  read -r -p "Aka verzia sa ma nastavit? (aktualna: $current_version, Enter = bez zmeny) " answer
  VERSION="${answer:-$current_version}"
fi

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Verzia musi byt v tvare X.Y.Z (napr. 1.2.0), zadane: $VERSION" >&2
  exit 1
fi

if [[ -f "$ENV_FILE" ]] && grep -qE '^RESIDO_ANDROID_CLIENT_VERSION=' "$ENV_FILE"; then
  sed -i '' "s/^RESIDO_ANDROID_CLIENT_VERSION=.*/RESIDO_ANDROID_CLIENT_VERSION=$VERSION/" "$ENV_FILE"
else
  echo "RESIDO_ANDROID_CLIENT_VERSION=$VERSION" >> "$ENV_FILE"
fi
echo "RESIDO_ANDROID_CLIENT_VERSION nastavena na $VERSION"

# Monotonic versionCode from X.Y.Z: major*10000 + minor*100 + patch.
IFS='.' read -r v_major v_minor v_patch <<< "$VERSION"
VERSION_CODE=$((v_major * 10000 + v_minor * 100 + v_patch))

# --- 2. App name from the Laravel project root .env -------------------------

APP_NAME="Resido"
for candidate in "$SCRIPT_DIR/../../.env" "$SCRIPT_DIR/env"; do
  if [[ -f "$candidate" ]]; then
    line="$(grep -E '^APP_NAME=' "$candidate" | head -1 || true)"
    if [[ -n "$line" ]]; then
      APP_NAME="$(echo "$line" | sed -E "s/^APP_NAME=['\"]?([^'\"]+)['\"]?$/\1/" | tr -d '\r')"
    fi
    break
  fi
done
echo "APP_NAME: $APP_NAME"

# --- 3. Signing keystore -----------------------------------------------------

KEYSTORE_DIR="$CLIENT_DIR/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/resido-release.jks"
KEYSTORE_PROPS="$CLIENT_DIR/keystore.properties"

if [[ ! -f "$KEYSTORE_FILE" ]]; then
  echo ""
  echo "Keystore neexistuje, generujem novy: $KEYSTORE_FILE"
  mkdir -p "$KEYSTORE_DIR"
  STORE_PASS="$(openssl rand -hex 16)"

  keytool -genkeypair \
    -keystore "$KEYSTORE_FILE" \
    -alias resido \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
    -dname "CN=Resido, O=eFabrica, C=SK"

  cat > "$KEYSTORE_PROPS" <<EOF
storeFile=keystore/resido-release.jks
storePassword=$STORE_PASS
keyAlias=resido
keyPassword=$STORE_PASS
EOF

  echo ""
  echo "!!! DOLEZITE: ZALOHUJ $KEYSTORE_FILE a $KEYSTORE_PROPS !!!"
  echo "!!! Android odmietne update podpisany inym klucom - strata keystore !!!"
  echo "!!! znamena koniec auto-updatov pre vsetky nainstalovane tablety.   !!!"
  echo ""
fi

if [[ ! -f "$KEYSTORE_PROPS" ]]; then
  echo "Chyba keystore.properties (keystore existuje, ale properties nie) - dopln ho podla keystore.properties.example" >&2
  exit 1
fi

# --- 4. Build ----------------------------------------------------------------

if [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  # Prefer a JDK version the Android Gradle Plugin actually supports - a
  # too-new JDK breaks AGP's jlink-based core-for-system-modules transform.
  # java_home -v N means "N or newer", so verify the candidate's real major
  # version before accepting it.
  for jdk_version in 21 17 23 22 20 19 18; do
    candidate="$(/usr/libexec/java_home -v "$jdk_version" 2>/dev/null || true)"
    if [[ -n "$candidate" ]]; then
      actual_major="$("$candidate/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)[^"]*".*/\1/')"
      if [[ "$actual_major" == "$jdk_version" ]]; then
        JAVA_HOME="$candidate"
        break
      fi
    fi
  done
  if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
    echo "UPOZORNENIE: nenasiel sa JDK 17-23, pouzivam $JAVA_HOME - build moze zlyhat na jlink transformacii." >&2
  fi
  export JAVA_HOME
fi
echo "JAVA_HOME: ${JAVA_HOME:-"(nenastavene)"}"

if [[ "$PLAY_BUILD" -eq 1 ]]; then
  # Google Play channel: AAB bundle, no self-updater inside (play flavor),
  # uploaded manually to Play Console - the store distributes updates.
  echo "Buildujem Google Play AAB (verzia $VERSION, versionCode $VERSION_CODE)..."
  (
    cd "$CLIENT_DIR"
    ./gradlew --no-daemon clean :app:bundlePlayRelease \
      -PresidoVersionName="$VERSION" \
      -PresidoVersionCode="$VERSION_CODE" \
      -PresidoAppName="$APP_NAME" \
      -PresidoUpdateUrl="$UPDATE_BASE_URL/"
  )

  BUILT_AAB="$CLIENT_DIR/app/build/outputs/bundle/playRelease/app-play-release.aab"
  if [[ ! -f "$BUILT_AAB" ]]; then
    echo "Play AAB sa nenasiel: $BUILT_AAB - build zrejme zlyhal." >&2
    exit 1
  fi

  mkdir -p "$DIST_DIR"
  rm -f "$DIST_DIR"/*.aab
  AAB_NAME="resido-$VERSION.aab"
  cp "$BUILT_AAB" "$DIST_DIR/$AAB_NAME"

  echo ""
  echo "Zbuildovany bundle pre Google Play:"
  echo "  - $DIST_DIR/$AAB_NAME"
  echo ""
  echo "Nahraj ho rucne v Play Console (Produkcia/Testovanie -> Vytvorit vydanie)."
  exit 0
fi

echo "Buildujem release APK (verzia $VERSION, versionCode $VERSION_CODE)..."
(
  cd "$CLIENT_DIR"
  ./gradlew --no-daemon clean :app:assembleSideloadRelease \
    -PresidoVersionName="$VERSION" \
    -PresidoVersionCode="$VERSION_CODE" \
    -PresidoAppName="$APP_NAME" \
    -PresidoUpdateUrl="$UPDATE_BASE_URL/"
)

BUILT_APK="$CLIENT_DIR/app/build/outputs/apk/sideload/release/app-sideload-release.apk"
if [[ ! -f "$BUILT_APK" ]]; then
  echo "Release APK sa nenasiel: $BUILT_APK - build zrejme zlyhal." >&2
  exit 1
fi

mkdir -p "$DIST_DIR"
rm -f "$DIST_DIR"/*.apk "$DIST_DIR"/latest.json
APK_NAME="resido-$VERSION.apk"
cp "$BUILT_APK" "$DIST_DIR/$APK_NAME"

SHA256="$(shasum -a 256 "$DIST_DIR/$APK_NAME" | cut -d' ' -f1)"

cat > "$DIST_DIR/latest.json" <<EOF
{
  "versionCode": $VERSION_CODE,
  "versionName": "$VERSION",
  "apkUrl": "$UPDATE_BASE_URL/$APK_NAME",
  "sha256": "$SHA256"
}
EOF

echo ""
echo "Zbuildovane subory:"
echo "  - $DIST_DIR/$APK_NAME"
echo "  - $DIST_DIR/latest.json"

# --- 5. Upload over SFTP -----------------------------------------------------

if [[ "$SKIP_UPLOAD" -eq 1 ]]; then
  echo ""
  echo "--skip-upload zadany, subory hore nahraj rucne na $SFTP_HOST (SFTP)."
  exit 0
fi

if [[ -f "$ENV_FILE" ]]; then
  env_user="$(grep -E '^RESIDO_ANDROID_SFTP_USER=' "$ENV_FILE" | head -1 | cut -d= -f2- || true)"
  if [[ -n "$env_user" ]]; then
    SFTP_USER="$env_user"
  fi
fi

echo ""
echo "Nahravam na $SFTP_HOST ako $SFTP_USER (SFTP, port $SFTP_PORT)..."
echo "(sftp sa opyta na heslo, max 3 pokusy)"

# Commands go through stdin instead of -b: batch mode (-b) disables
# interactive password authentication entirely, which made the upload fail
# with "Permission denied" before the password prompt could even appear.
# Old files are removed first so only the newest release lives on the host,
# same policy as the Windows build script (rm errors on a fresh host are
# harmless - sftp just prints them and continues).
sftp -P "$SFTP_PORT" "$SFTP_USER@$SFTP_HOST" <<EOF
rm *.apk
rm latest.json
put $DIST_DIR/$APK_NAME
put $DIST_DIR/latest.json
ls -l
EOF

echo ""
echo "Hotovo. Vsetky subory su nahrate na $SFTP_HOST."
