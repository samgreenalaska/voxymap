# VoxyMap

VoxyMap is a **compatibility bridge**, not a standalone map or renderer. It reads the voxel data
that [Voxy](https://modrinth.com/mod/voxy) has already stored for a world and writes it into
[Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) as ordinary map tiles, so Xaero
shows terrain you have *seen from a distance* (through Voxy's long-range rendering) even if you
have never walked there.

**This project is unaffiliated with, and not endorsed by, the authors of Voxy or Xaero's World
Map.** It is a third-party addon that depends on both of their mods being installed and hooks into
publicly-visible members of their code; it does not modify, redistribute, or include either mod.

## What it actually changes

- On the client, once both Voxy and Xaero's World Map are present, VoxyMap periodically checks
  which parts of Voxy's database are new and writes matching tiles into Xaero's map. Nothing is
  drawn that Xaero didn't already have a slot for, and nothing is shown on the HUD beyond the
  normal map.
- **It will never overwrite a tile Xaero itself scanned by walking through it.** If Xaero later
  re-scans ground VoxyMap filled in, Xaero's version silently takes over.
- On a dedicated server, an optional half of the same mod generates chunks slightly ahead of
  players and streams the resulting voxel data to their client, so there is more for the bridge to
  draw with. This is ordinary chunk generation done earlier than a player would otherwise trigger
  it.

## Requirements

- Fabric Loader, Fabric API, Java 25, Minecraft 26.2.
- **Voxy** and **Xaero's World Map** are *not* hard dependencies, but the mod does nothing useful
  without both installed on the client. The server-side chunk pregeneration works with just Voxy
  present on the server.

## Limitations

- Overworld and End only by default; the Nether and any dimension Xaero maps from world-save files
  are intentionally skipped (see the README for why).
- Waterlogged blocks lose their water overlay; open water, lakes, and lava render correctly.
- Lighting reflects conditions at the moment Voxy ingested that ground, not the current time of
  day. Cosmetic only.
- This is pre-alpha software: debug logging is on by default, commands and config keys may
  still change, and it has not been tested at scale across many server configurations.
