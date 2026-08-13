# VoxyMap

Fabric mod for Minecraft 26.2. Projects stored LOD voxel data onto
[Xaero's World Map](https://modrinth.com/mod/xaeros-world-map), so the map fills in with terrain
you've seen from a distance but never actually walked through. It never touches a tile Xaero
scanned for real. If Xaero later re-scans ground this mod filled in, Xaero's version wins.

The LOD comes from [Voxy](https://modrinth.com/mod/voxy), or from
[Distant Horizons](https://modrinth.com/mod/distanthorizons) where Voxy cannot run. That is the
point as of 0.8.0: Voxy has no working driver path on Windows-on-ARM, so those clients get the
DH backend instead. One jar, backend chosen at startup from what is installed.

Not affiliated with any of them. Xaero's World Map plus one of Voxy or Distant Horizons needs to be
installed for this to do anything; see [Requirements](#requirements).

![icon](src/main/resources/assets/voxymap/icon.png)

## Why

Voxy keeps a full-detail voxel database of everything the client has ever streamed, well past
Xaero's own draw distance. Xaero only draws what its writer has physically walked over. This mod
reads the former and writes the latter, using Xaero's own colour/classification logic so the
seam between "actually scanned" and "filled in" tiles doesn't show.

On a server, the client's Voxy database only holds what render distance already streamed to it.
That is not much to work with. The optional server half fixes that by pregenerating chunks ahead of
players and streaming the resulting voxel data down, so there's something for the client side to
draw from.

## Requirements

- Fabric Loader, Fabric API, Java 25
- A LOD backend on the client: [Voxy](https://modrinth.com/mod/voxy) 0.2.18-beta **or**
  [Distant Horizons](https://modrinth.com/mod/distanthorizons) 3.2.x. Where both are installed Voxy
  is used; `lodBackend` in the config pins it either way.
- [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.44.x for the map half. Without
  it the mod still streams LOD into your backend, it just doesn't fill a map.
- None of these are hard dependencies. The same jar loads on a dedicated server where none of them
  exist.
- One jar goes on both client and server. The server-side pregen/streaming half needs Voxy
  server-side and is unchanged by 0.8.0: a DH client talks the same protocol to the same server.

### On Windows-on-ARM

Voxy does not work there. Install Distant Horizons instead and leave Voxy out of `mods/` entirely.
The mod detects that and runs its whole client half against DH: streamed LOD goes into DH's
database and gets rendered, and the Xaero map is filled from what DH has stored. `/voxymap status`
names the backend it picked, and `/voxymap dh` says what DH can see.

Since 0.9.0 the jar also carries the **ARM buffer-upload fix** that used to be the separate
`gcarmfix` mod, because it is the same machine's problem. Snapdragon X / Adreno drivers fault inside
`glBufferSubData` when blaze3d writes to an immutable, persistently-mapped buffer, and Xaero's map
tile uploads hit it. The fix makes blaze3d use its own mutable-buffer path instead. It detects the
driver at runtime and does nothing at all on anything else, including every x86 client and any
dedicated server. Settings live in `config/voxymap-armfix.properties`; you should not need to touch
them.

**If you had `gcarmfix` installed, delete it.** Two copies of the same patch is a mixin conflict at
startup, not a harmless duplicate.

## Installing

Drop the jar in `mods/` alongside Voxy and Xaero's World Map. That's it. It starts sweeping about
15 seconds after you join a world and keeps going in the background. Nothing shows on the HUD;
progress goes to the log and to `/voxymap status`.

With Sodium installed, **Video Settings → VoxyMap** has a speed preset (Background / Balanced /
Fast) and a framerate floor below which filling pauses.

That floor (`pauseWhenFpsBelow`) defaults to **20**. It has to sit below the framerate the machine
actually runs at or the apply stage never gets a tick and the map silently never fills. A laptop
holding 30 fps in a heavy modpack is a normal machine, not a struggling one. The settings row says
`never fills` if you set it above your framerate cap.

## Commands

| Command | Does |
| --- | --- |
| `/voxymap status` | What the bridge can see: Voxy's database, Xaero's write gates. Start here. |
| `/voxymap here [chunks]` | Author one tile chunk ahead of where you're facing. Cheap first test. |
| `/voxymap region <x> <z>` | Sweep one Xaero region on demand. |
| `/voxymap start` / `stop` | Full sweep of the current dimension / suspend it. |
| `/voxymap probe [x z]` | Dump Xaero's live in-memory state for a region. |
| `/voxymap dh [cx cz]` | Distant Horizons only: what DH can see, and the runs it has stored for a chunk. |
| `/voxymap wipe` | Delete this mod's + Voxy's data for the current world. Requires `wipe confirm`. |
| `/voxymapserver pregen` | Server-side chunk pregen status and live tuning (op only). |
| `/voxymapserver resend` | Force a full re-send of streamed LOD to every connected player. |

Config lives in `config/voxymap.json`.

## Known limitations

- Overworld and End only by default. The Nether uses a different column model this mod doesn't
  implement yet (`--force` will try anyway; it'll look wrong). Which dimensions those are is
  decided by the dimension itself, not by how you are looking at it: turning Xaero's **cave mode**
  on no longer stops the surface being filled, because tiles are always written to the surface
  layer and are simply waiting for you when you switch back.
- Waterlogged blocks lose their water overlay. Open water, lakes, and lava render fine.
- On the Distant Horizons backend the map cannot tell "stored data stops short" from "stored data
  has a hole in it". DH answers with a run list and there is no index behind it to check against.
  Both come out as an unresolved column, which is drawn as nothing rather than as a guess.
- `/voxymap wipe` on a DH client does not delete DH's terrain database. That is DH's data and it
  has its own delete control. It does delete this mod's record of what the server already sent, so
  the map refills from what DH and Xaero still hold rather than from nothing.
- Lighting reflects conditions when the backend ingested that ground, not the current time of day.
  Ground streamed in at night is drawn dark, and stays dark until it is re-authored in daylight.
  That is very visible if a whole sweep happens after dusk.
- Pre-alpha. Debug logging is on by default and commands/config may still change.

## Building

```bash
bash build.sh
```

Plain `javac` + `jar`, no Gradle or Loom. 26.2's intermediary mapping is the identity mapping, so
there's no remap step. `build.sh` needs a JDK 25 on `PATH` (or `JAVA_HOME`) and a set of
dependency jars to compile against, all dropped into a `deps/` folder next to `build.sh` (or
pointed to with `VOXYMAP_DEPS_DIR`):

- The Minecraft 26.2 client jar and its libraries
- `voxy-0.2.18-beta.jar`
- `DistantHorizons-3.2.0-b-26.2-fabric-neoforge.jar`
- `xaeroworldmap-fabric-26.2-1.44.2.jar`
- `sodium-fabric-0.9.1+mc26.2.jar`
- The Fabric API submodules: `fabric-api-base-2.0.4+ece063239e.jar`,
  `fabric-command-api-v2-3.1.0+00cb03469e.jar`, `fabric-lifecycle-events-v1-4.1.3+4575b05f9e.jar`,
  `fabric-networking-api-v1-6.3.3+72073ef09e.jar`, `fabric-rendering-v1-25.3.1+6988455e9e.jar`
- `xaerolib-fabric-26.2-1.7.1.jar`, jar-in-jar'd inside Xaero's World Map. Pull it out with
  `unzip -o -j xaeroworldmap-fabric-26.2-1.44.2.jar 'META-INF/jars/xaerolib-*.jar' -d deps`
- `sponge-mixin-0.17.3+mixin.0.8.7.jar` and LWJGL's `lwjgl-opengl`. Only the ARM fix's two mixins
  need these, and both ship with any Fabric install (Prism keeps them under `libraries/`). No
  annotation processor and no refmap: the mixins target `com.mojang.blaze3d`, which Mojang does not
  obfuscate, so the names are the same in every namespace.

Intermediates are compiled to a scratch directory on local disk rather than beside the sources.
This tree is often a network share, and javac creating package directories on one immediately after
deleting the last build fails intermittently with `error while writing <Class>`. That reads as a
compile error and is not one. Only the finished jar is written to the source tree.

Output goes to `dist/voxymap-<version>.jar`.
