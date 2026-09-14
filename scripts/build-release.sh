#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
umask 077

if [[ ! -d .signing ]]; then
    command -v keytool >/dev/null || { echo "Install a JDK with keytool first." >&2; exit 1; }
    command -v openssl >/dev/null || { echo "Install openssl first." >&2; exit 1; }
    mkdir .signing
    openssl rand -base64 32 > .signing/password
    keytool -genkeypair -keystore .signing/release.p12 -storetype PKCS12 \
        -storepass:file .signing/password -keypass:file .signing/password \
        -alias air-remote -keyalg RSA -keysize 3072 -validity 10000 \
        -dname "CN=Air Remote" -noprompt
    echo "Release key created. Back up .signing/ securely; future updates need this key."
fi

if [[ ! -s .signing/release.p12 || ! -s .signing/password ]]; then
    echo "Incomplete .signing/. Restore its key and password from backup; no key was replaced." >&2
    exit 1
fi

./gradlew assembleRelease "$@"
echo "Signed APK: app/build/outputs/apk/release/app-release.apk"
