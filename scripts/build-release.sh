#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
umask 077
# Android Studio bundles the JDK used by Gradle; also use it for keytool/apksigner.
if [[ -z "${JAVA_HOME:-}" && -x '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java' ]]; then
    export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
    export PATH="$JAVA_HOME/bin:$PATH"
fi
command -v python3 >/dev/null || { echo "Install Python 3 first." >&2; exit 1; }

if [[ ! -s .signing/release.p12 || ! -s .signing/password ]]; then
    echo "Restore .signing/release.p12 and .signing/password from backup; updates require the original key." >&2
    exit 1
fi

./gradlew testDebugUnitTest lintDebug assembleRelease "$@"
python3 scripts/prepare-release.py
