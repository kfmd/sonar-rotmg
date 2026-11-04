#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Build the macOS app on macOS, not Windows or Linux." >&2
  exit 1
fi
JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
if [[ -z "$JAVA_HOME" ]] && command -v brew >/dev/null 2>&1; then
  BREW_JDK="$(brew --prefix openjdk@17 2>/dev/null || true)"
  if [[ -n "$BREW_JDK" && -x "$BREW_JDK/libexec/openjdk.jdk/Contents/Home/bin/java" ]]; then
    JAVA_HOME="$BREW_JDK/libexec/openjdk.jdk/Contents/Home"
  elif [[ -n "$BREW_JDK" && -x "$BREW_JDK/bin/java" ]]; then
    JAVA_HOME="$BREW_JDK"
  fi
fi
if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 17 is required. Install it with: brew install openjdk@17" >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

VERSION="$(sed -n 's/.*CURRENT = "\([0-9.]*\)".*/\1/p' src/main/java/sonar/ui/Updater.java | head -n 1)"
if [[ -z "$VERSION" ]]; then echo "Cannot read Sonar version." >&2; exit 1; fi

./gradlew --no-daemon clean shadowJar

# Prefer JDK 21's jpackage/runtime for current macOS; the application bytecode
# remains compatible with JDK 17. Fall back to JDK 17 if 21 is unavailable.
JPACKAGE_JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
if [[ -z "$JPACKAGE_JAVA_HOME" ]] && command -v brew >/dev/null 2>&1; then
  BREW_JDK21="$(brew --prefix openjdk@21 2>/dev/null || true)"
  if [[ -n "$BREW_JDK21" && -x "$BREW_JDK21/libexec/openjdk.jdk/Contents/Home/bin/jpackage" ]]; then
    JPACKAGE_JAVA_HOME="$BREW_JDK21/libexec/openjdk.jdk/Contents/Home"
  fi
fi
if [[ -z "$JPACKAGE_JAVA_HOME" || ! -x "$JPACKAGE_JAVA_HOME/bin/jpackage" ]]; then
  JPACKAGE_JAVA_HOME="$JAVA_HOME"
  echo "JDK 21 not found; packaging with JDK 17. Install openjdk@21 for the recommended runtime." >&2
fi

rm -rf build/macos
mkdir -p build/macos
COMMON=(
  --name Sonar
  --input build/libs
  --main-jar Sonar.jar
  --main-class sonar.Sonar
  --app-version "$VERSION"
  --mac-package-identifier ai.justautomate.sonar
  --java-options=-Dsonar.font=Dialog
  --java-options=--add-exports=java.desktop/sun.awt=ALL-UNNAMED
  --java-options=--add-opens=java.desktop/sun.awt=ALL-UNNAMED
  --java-options=--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED
  --java-options=--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED
  "--java-options=-Dsonar.data=$HOME/Library/Application Support/Sonar"
)

"$JPACKAGE_JAVA_HOME/bin/jpackage" --type app-image --dest build/macos "${COMMON[@]}"
"$JPACKAGE_JAVA_HOME/bin/jpackage" --type dmg --dest build/macos "${COMMON[@]}"
echo
printf 'Created:\n  %s\n  %s\n' "$ROOT/build/macos/Sonar.app" "$ROOT/build/macos/Sonar-$VERSION.dmg"
echo "The app is unsigned. On first launch, use Control-click > Open if Gatekeeper blocks it."
