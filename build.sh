#!/usr/bin/env bash
# Build script for voxymap. Plain javac + jar -- no Loom.
#
# Intermediary for 26.2 is the identity mapping (mappings.tiny is a 25-byte
# header only), so the runtime namespace IS official and no remap step exists.
# We compile straight against the runtime jars and mark the output
# Fabric-Mapping-Namespace: official so Loader skips remapping.
set -euo pipefail

cd "$(dirname "$0")"

# javac wants ';' between classpath entries on Windows (including under Git Bash), ':' everywhere
# else -- this is the JVM host platform's separator, not the shell's.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac

# Dependency jars: the Minecraft 26.2 client jar and its libraries, voxy, xaeroworldmap, sodium,
# the Fabric API submodule jars, and xaerolib (see README for exact versions and where to get
# each one). Either point VOXYMAP_CLASSPATH at an already-assembled classpath, or drop every jar
# into one directory (default ./deps, override with VOXYMAP_DEPS_DIR) and this globs it.
if [ -n "${VOXYMAP_CLASSPATH:-}" ]; then
  CP="$VOXYMAP_CLASSPATH"
else
  DEPS="${VOXYMAP_DEPS_DIR:-deps}"
  [ -d "$DEPS" ] || {
    echo "!! dependency jar directory not found: $DEPS" >&2
    echo "   put the Minecraft 26.2 client jar + its libraries, voxy-0.2.18-beta.jar," >&2
    echo "   xaeroworldmap-fabric-26.2-1.44.2.jar, sodium-fabric-0.9.1+mc26.2.jar, the Fabric" >&2
    echo "   API submodule jars, and xaerolib (see README) in $DEPS/, or set VOXYMAP_DEPS_DIR" >&2
    exit 1
  }
  CP=$(find "$DEPS" -name '*.jar' | tr '\n' "$SEP")
  [ -n "$CP" ] || { echo "!! no jars found in $DEPS" >&2; exit 1; }
fi

# Any JDK 25 on PATH or pointed to by JAVA_HOME works -- --release 25 below is what actually
# enforces the version, so there's no separate version check to duplicate here.
JAVAC="$(command -v javac || true)"
JAR="$(command -v jar || true)"
if [ -n "${JAVA_HOME:-}" ]; then
  [ -x "$JAVA_HOME/bin/javac" ] && JAVAC="$JAVA_HOME/bin/javac"
  [ -x "$JAVA_HOME/bin/jar" ] && JAR="$JAVA_HOME/bin/jar"
fi
[ -n "$JAVAC" ] && [ -n "$JAR" ] || {
  echo "!! javac/jar not found on PATH and JAVA_HOME not set to a JDK 25 install" >&2
  exit 1
}

# Read from fabric.mod.json rather than declared here. That file is the version
# the game reports -- SodiumOptions reads it back off the mod container for the
# settings page -- so a second copy in this script is a copy that can disagree
# with it, and the disagreement would show up as a jar whose filename and
# in-game version are different builds.
MANIFEST="src/main/resources/fabric.mod.json"
VERSION=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)
[ -n "$VERSION" ] || { echo "!! could not read version from $MANIFEST" >&2; exit 1; }
OUT="dist/voxymap-${VERSION}.jar"

# Overwriting a jar that has already been deployed somewhere is how "0.6.0" came
# to name about ten different builds, including a protocol change. Refuse, and
# say what to do about it -- benchmark results record the version and silently
# comparing two different jars under one name is worse than a failed build.
if [ -f "$OUT" ]; then
   echo "!! $OUT already exists -- bump \"version\" in $MANIFEST before rebuilding" >&2
   echo "   (or delete it deliberately if this build is a retry of the same source)" >&2
   exit 1
fi

# Intermediates go to a scratch directory on local disk, not next to the sources.
#
# This tree is often a network share (it is on the machine this was written on), and javac
# creating a few hundred package directories on one is both slow and unreliable: after the old
# output is deleted, the directory it is about to recreate can still be mid-teardown, and javac
# fails with "error while writing <Class>" naming whichever class lost the race. That reads as a
# compile error and is not one. A retry papered over it and still lost roughly one build in three.
#
# mktemp -d lands on local disk on every platform this runs on, including Git Bash on Windows,
# and the trap takes it away again. Only the finished jar touches the share.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CLASSES="$WORK/classes"
mkdir -p "$CLASSES" dist

find src/main/java -name '*.java' > "$WORK/sources.txt"
echo "compiling $(wc -l < "$WORK/sources.txt") sources"

"$JAVAC" \
  --release 25 \
  -encoding UTF-8 \
  -Xlint:-options \
  -nowarn \
  -cp "$CP" \
  -d "$CLASSES" \
  @"$WORK/sources.txt"

cp -r src/main/resources/. "$CLASSES/"

# Fabric-Mapping-Namespace says which namespace the classes in here are written against, and it
# has to say "intermediary" -- the namespace Loader runs in -- even though these classes are
# compiled against official names.
#
# That is not a contradiction in 26.2: intermediary IS the identity mapping (mappings.tiny is a
# 25-byte header and nothing else), so official and intermediary name every member identically.
# Both values describe the same bytes.
#
# They do not produce the same behaviour, though. Declaring "official" tells Loader the mod is in
# a namespace other than the one it runs in, which switches on the runtime remapper -- and that
# changes how Knot builds its class path. Under Loader 0.19.3 the result was a second copy of the
# mixin library inside Knot, so every OTHER mod's mixin plugin then failed to cast to the app
# copy of IMixinConfigPlugin, and the client died during mod init with ~80 ClassCastExceptions
# naming mods that have nothing to do with this one. Dropping this jar into an instance was enough
# to break it; taking it out fixed it. Declaring the namespace Loader is already in means no
# remap, no second class path, no split.
cat > "$WORK/manifest.mf" <<'EOF'
Manifest-Version: 1.0
Fabric-Mapping-Namespace: intermediary
EOF

rm -f "$OUT"
"$JAR" --create --file "$OUT" --manifest "$WORK/manifest.mf" -C "$CLASSES" .

echo "built $OUT"
ls -la "$OUT"
