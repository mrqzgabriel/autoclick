#!/usr/bin/env bash
# Compila o APK aqui no computador, do MESMO jeito que o Dockerfile faz no
# servidor, e deixa o resultado em apk/AutoClick.apk (+ apk/version.json).
# Serve pra instalar pelo cabo e pra testar o servidor local:
#   scripts/build-apk.sh && node server/index.js
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-17.0.19+10/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

# segundos desde 2023-11-14: cresce a cada build, igual ao Dockerfile
CODE=$(( $(date +%s) - 1700000000 ))
echo "== compilando (versionCode $CODE) =="
./gradlew --no-daemon -q :app:assembleRelease -Pautoclick.versionCode="$CODE" "$@"

APK=app/build/outputs/apk/release/app-release.apk
AAPT2=$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
BADGING=$("$AAPT2" dump badging "$APK" | grep "^package:")
VC=$(echo "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
VN=$(echo "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")

mkdir -p apk
cp "$APK" apk/AutoClick.apk
cp "$APK" "AutoClick-v$VN.apk"
printf '{"versionCode":%s,"versionName":"%s","builtAt":"%s"}\n' "$VC" "$VN" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > apk/version.json
echo "== pronto: apk/AutoClick.apk = versão $VN (build $VC) =="
cat apk/version.json
