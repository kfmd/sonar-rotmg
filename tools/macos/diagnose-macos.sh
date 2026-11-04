#!/bin/bash
set -u

printf 'Sonar macOS diagnostics\n'
printf '%s\n' '-----------------------'
printf 'macOS: '; sw_vers -productVersion 2>/dev/null || true
printf 'Build: '; sw_vers -buildVersion 2>/dev/null || true
printf 'Architecture: '; uname -m
resolve_jdk() {
  local version="$1"
  local home="$(/usr/libexec/java_home -v "$version" 2>/dev/null || true)"
  if [[ -z "$home" ]] && command -v brew >/dev/null 2>&1; then
    local prefix="$(brew --prefix "openjdk@$version" 2>/dev/null || true)"
    if [[ -n "$prefix" && -x "$prefix/libexec/openjdk.jdk/Contents/Home/bin/java" ]]; then
      home="$prefix/libexec/openjdk.jdk/Contents/Home"
    elif [[ -n "$prefix" && -x "$prefix/bin/java" ]]; then
      home="$prefix"
    fi
  fi
  if [[ -n "$home" && -x "$home/bin/java" ]]; then printf '%s' "$home"; fi
}

for version in 17 21; do
  home="$(resolve_jdk "$version")"
  if [[ -n "$home" ]]; then
    printf 'JDK %s: ' "$version"
    "$home/bin/java" -version 2>&1 | head -n 1
    printf '  Home: %s\n' "$home"
  else
    printf 'JDK %s: not found\n' "$version"
  fi
done
printf 'Recommended: build with JDK 17 and launch with JDK 21.\n'
printf '\nPacket capture:\n'
if [[ -e /usr/lib/libpcap.A.dylib ]]; then
  echo '  System libpcap: present (/usr/lib/libpcap.A.dylib)'
elif [[ -x /usr/sbin/tcpdump ]] && command -v otool >/dev/null 2>&1 && otool -L /usr/sbin/tcpdump 2>/dev/null | grep -q 'libpcap'; then
  echo '  System libpcap: linked through the macOS dyld shared cache'
else
  echo '  System libpcap: unable to confirm (check with: otool -L /usr/sbin/tcpdump)'
fi
if compgen -G '/dev/bpf*' >/dev/null; then
  echo '  BPF devices: present'
  if [[ -r /dev/bpf0 ]]; then echo '  /dev/bpf0 readable: yes'; else echo '  /dev/bpf0 readable: no'; fi
  if [[ -w /dev/bpf0 ]]; then echo '  /dev/bpf0 writable: yes'; else echo '  /dev/bpf0 writable: no'; fi
else
  echo '  BPF devices: not found'
fi
printf '\nLikely Exalt asset locations:\n'
HOME_DIR="$HOME"
for f in \
  "$HOME_DIR/.local/share/RealmOfTheMadGod/Production/RotMGExalt.app/Contents/Resources/Data/resources.assets" \
  "$HOME_DIR/Library/Application Support/Steam/steamapps/common/Realm of the Mad God/RotMG Exalt Launcher.app/Contents/Resources/Data/resources.assets" \
  "$HOME_DIR/Library/Application Support/Steam/steamapps/common/Realm of the Mad God Exalt/RotMG Exalt.app/Contents/Resources/Data/resources.assets" \
  "$HOME_DIR/Library/Application Support/Steam/steamapps/common/Realm of the Mad God Exalt/RotMGExalt.app/Contents/Resources/Data/resources.assets" \
  "/Applications/RotMG Exalt.app/Contents/Resources/Data/resources.assets" \
  "/Applications/RotMGExalt.app/Contents/Resources/Data/resources.assets"; do
  [[ -f "$f" ]] && echo "  FOUND: $f"
done
printf '\nExalt Extractor output:\n'
if [[ -n "${SONAR_EXALT_OUTPUT_DIR:-}" && -d "$SONAR_EXALT_OUTPUT_DIR/xml" ]]; then
  echo "  Configured: $SONAR_EXALT_OUTPUT_DIR"
elif [[ -d "$HOME/Library/Application Support/Sonar/exalt-extractor-output/xml" ]]; then
  echo "  Found default: $HOME/Library/Application Support/Sonar/exalt-extractor-output"
else
  echo '  Not configured. Set SONAR_EXALT_OUTPUT_DIR to the output from rotmg-network/exalt-extractor.'
fi

printf '\nPermissions are granted in System Settings > Privacy & Security:\n'
echo '  Input Monitoring: Sonar (or Terminal when running run-macos.sh)'
echo '  Screen & System Audio Recording: Sonar (or Terminal when running run-macos.sh)'
