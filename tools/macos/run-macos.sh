#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This script must be run on macOS." >&2
  exit 1
fi

resolve_jdk() {
  local version="$1"
  local home=""
  home="$(/usr/libexec/java_home -v "$version" 2>/dev/null || true)"
  if [[ -z "$home" ]] && command -v brew >/dev/null 2>&1; then
    local prefix=""
    prefix="$(brew --prefix "openjdk@$version" 2>/dev/null || true)"
    if [[ -n "$prefix" && -x "$prefix/libexec/openjdk.jdk/Contents/Home/bin/java" ]]; then
      home="$prefix/libexec/openjdk.jdk/Contents/Home"
    elif [[ -n "$prefix" && -x "$prefix/bin/java" ]]; then
      home="$prefix"
    fi
  fi
  if [[ -n "$home" && -x "$home/bin/java" ]]; then printf '%s' "$home"; fi
}

# Gradle 7.6.4 runs on JDK 17. Use JDK 21 for the macOS GUI runtime when installed.
BUILD_JAVA_HOME="$(resolve_jdk 17)"
if [[ -z "$BUILD_JAVA_HOME" ]]; then
  echo "JDK 17 is required to build Sonar. Install it with: brew install openjdk@17" >&2
  exit 1
fi
RUNTIME_JAVA_HOME="$(resolve_jdk 21)"
if [[ -z "$RUNTIME_JAVA_HOME" ]]; then
  RUNTIME_JAVA_HOME="$BUILD_JAVA_HOME"
  echo "JDK 21 not found; launching with JDK 17. Install openjdk@21 for the recommended macOS runtime." >&2
fi

export JAVA_HOME="$BUILD_JAVA_HOME"
export PATH="$BUILD_JAVA_HOME/bin:$PATH"
./gradlew --no-daemon clean shadowJar

SONAR_DATA="$HOME/Library/Application Support/Sonar"
mkdir -p "$SONAR_DATA"
DEFAULT_EXALT_OUTPUT="$SONAR_DATA/exalt-extractor-output"
EXALT_OUTPUT="${SONAR_EXALT_OUTPUT_DIR:-$DEFAULT_EXALT_OUTPUT}"

# Optional: run the separately installed Exalt Extractor on every launch so the
# imported XML/sprites always correspond to the current Production client.
if [[ -n "${SONAR_EXALT_EXTRACTOR_DIR:-}" ]]; then
  EXTRACTOR_ROOT="$SONAR_EXALT_EXTRACTOR_DIR"
  EXTRACTOR_PYTHON="${SONAR_EXALT_PYTHON:-$EXTRACTOR_ROOT/.venv/bin/python}"
  GAME_DATA="${SONAR_EXALT_GAME_DATA:-$HOME/.local/share/RealmOfTheMadGod/Production/RotMGExalt.app/Contents/Resources/Data}"
  if [[ ! -f "$EXTRACTOR_ROOT/extractor.py" ]]; then
    echo "Cannot find extractor.py in SONAR_EXALT_EXTRACTOR_DIR: $EXTRACTOR_ROOT" >&2
    exit 1
  fi
  if [[ ! -x "$EXTRACTOR_PYTHON" ]]; then
    echo "Cannot execute the Exalt Extractor Python interpreter: $EXTRACTOR_PYTHON" >&2
    echo "Set SONAR_EXALT_PYTHON to the Python executable in its virtual environment." >&2
    exit 1
  fi
  if [[ ! -d "$GAME_DATA" ]]; then
    echo "ROTMG Production Data directory not found: $GAME_DATA" >&2
    echo "Set SONAR_EXALT_GAME_DATA to the installed client's Contents/Resources/Data folder." >&2
    exit 1
  fi
  mkdir -p "$EXALT_OUTPUT"
  echo "Extracting current ROTMG Production assets..."
  "$EXTRACTOR_PYTHON" "$EXTRACTOR_ROOT/extractor.py" --input "$GAME_DATA" --output "$EXALT_OUTPUT"
fi

if [[ ! -d "$EXALT_OUTPUT/xml" || ! -f "$EXALT_OUTPUT/assets_manifest.json" || ! -f "$EXALT_OUTPUT/spritesheets/spritesheet.json" ]]; then
  cat >&2 <<EOF
No valid Exalt Extractor output found.

Run the extractor once, for example:
  python extractor.py --input "/path/to/RotMGExalt.app/Contents/Resources/Data" --output "$DEFAULT_EXALT_OUTPUT"

Or set SONAR_EXALT_OUTPUT_DIR to an existing extractor output directory.
To have this script run extraction automatically on every launch, set
SONAR_EXALT_EXTRACTOR_DIR to the cloned rotmg-network/exalt-extractor repository.
EOF
  exit 1
fi

JAVA_ARGS=(
  --add-exports=java.desktop/sun.awt=ALL-UNNAMED
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED
  --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED
  --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED
  "-Dsonar.font=${SONAR_FONT:-Dialog}"
  "-Dsonar.data=$SONAR_DATA"
  "-Dsonar.exalt.output=$EXALT_OUTPUT"
)

echo "Importing Exalt Extractor output: $EXALT_OUTPUT"
echo "Build JDK: $BUILD_JAVA_HOME"
echo "Runtime JDK: $RUNTIME_JAVA_HOME"
exec "$RUNTIME_JAVA_HOME/bin/java" "${JAVA_ARGS[@]}" -jar "$ROOT/build/libs/Sonar.jar" "$@"
