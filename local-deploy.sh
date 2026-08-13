#!/usr/bin/env bash
# Local convenience script: build.sh knows nothing about PrismLauncher (it just wants a
# classpath), so this is what actually finds a Prism install, assembles that classpath from a
# real instance, and drops the resulting jar into that instance's mods/ folder. Not committed --
# it's tied to one machine's instance layout, unlike build.sh which anyone can run.
set -euo pipefail

cd "$(dirname "$0")"

# Locate PrismLauncher's data directory. Override with VOXYMAP_PRISM_DIR if it's installed
# somewhere nonstandard (a portable install, a second launcher, etc).
if [ -n "${VOXYMAP_PRISM_DIR:-}" ]; then
  PL="$VOXYMAP_PRISM_DIR"
elif [ -n "${APPDATA:-}" ]; then
  PL="${APPDATA//\\//}/PrismLauncher"
elif [ "$(uname -s)" = "Darwin" ]; then
  PL="$HOME/Library/Application Support/PrismLauncher"
else
  PL="$HOME/.local/share/PrismLauncher"
fi
[ -d "$PL" ] || { echo "!! PrismLauncher data dir not found: $PL (set VOXYMAP_PRISM_DIR)" >&2; exit 1; }

# Instance to pull the mc client jar and the voxy/xaero/sodium jars from, and to install the
# built jar into. Override with VOXYMAP_INSTANCE if your instance for this isn't named "tectonic".
INSTANCE="${VOXYMAP_INSTANCE:-greencraft-stable}"
MODS="$PL/instances/$INSTANCE/minecraft/mods"
[ -d "$MODS" ] || { echo "!! instance not found: $MODS (set VOXYMAP_INSTANCE)" >&2; exit 1; }

MC="$PL/libraries/com/mojang/minecraft/26.2/minecraft-26.2-client.jar"
[ -f "$MC" ] || { echo "!! minecraft client jar not found: $MC" >&2; exit 1; }
[ -f "$MODS/voxy-0.2.18-beta.jar" ] || { echo "!! voxy jar not found" >&2; exit 1; }
[ -f "$MODS/xaeroworldmap-fabric-26.2-1.44.2.jar" ] || { echo "!! xaero world map jar not found" >&2; exit 1; }

# Prism's managed Java runtimes are named by internal codename (java-runtime-epsilon, -delta, ...),
# not by version number, and that mapping isn't guaranteed stable across Prism releases -- so find
# whichever managed runtime actually reports Java 25, instead of hardcoding a codename.
JAVAC=""
for bin in "$PL"/java/*/bin; do
  for candidate in "$bin/javac" "$bin/javac.exe"; do
    [ -x "$candidate" ] || continue
    if "${candidate%javac*}java" -version 2>&1 | grep -q '"25'; then
      JAVAC="$candidate"
      break 2
    fi
  done
done
[ -n "$JAVAC" ] || { echo "!! no Java 25 toolchain found under $PL/java" >&2; exit 1; }
JAVAC_BIN_DIR="$(dirname "$JAVAC")"
# A Windows-style "C:/..." path breaks once it's put into $PATH -- the drive-letter colon
# collides with PATH's own ':' separator. Convert to POSIX form ("/c/...") if cygpath is around.
command -v cygpath >/dev/null 2>&1 && JAVAC_BIN_DIR="$(cygpath -u "$JAVAC_BIN_DIR")"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac
CP=$(find "$PL/libraries" -name '*.jar' | tr '\n' "$SEP")
CP="$CP$MC$SEP"
CP="$CP$MODS/voxy-0.2.18-beta.jar$SEP"
CP="$CP$MODS/xaeroworldmap-fabric-26.2-1.44.2.jar$SEP"
# Sodium: optional at runtime, but its config API is how the video settings page is added.
CP="$CP$MODS/sodium-fabric-0.9.1+mc26.2.jar$SEP"
# Distant Horizons: the second LOD backend, and the only one that works on Windows-on-ARM. Compiled
# against, never required -- a client with Voxy and no DH never loads a class from it.
for dh in "$MODS"/DistantHorizons-*.jar; do [ -f "$dh" ] && CP="$CP$dh$SEP"; done
# libs/ holds compile-only jars that are already present at runtime. xaerolib is jar-in-jar'd
# inside the world map mod (META-INF/jars/), and holds the config API MapWriter reads its writing
# distance and load/update-chunks flags from -- which XaeroCoverage has to read the same way:
#   unzip -o -j "$MODS/xaeroworldmap-fabric-26.2-1.44.2.jar" 'META-INF/jars/xaerolib-*.jar' -d libs
for j in libs/*.jar; do CP="$CP$j$SEP"; done

VOXYMAP_CLASSPATH="$CP" PATH="$JAVAC_BIN_DIR:$PATH" bash build.sh

MANIFEST="src/main/resources/fabric.mod.json"
VERSION=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)
OUT="dist/voxymap-${VERSION}.jar"

# Fabric loads every jar in mods/, so an old voxymap-*.jar left behind is a load error, not an
# upgrade -- clear out any previous version before installing the new one.
rm -f "$MODS"/voxymap-*.jar
cp "$OUT" "$MODS/"
echo "installed $OUT -> $MODS/"
