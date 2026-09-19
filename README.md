# Ultima Kingdoms

Ultima Kingdoms is a server-authoritative settlement identity mod for Minecraft 1.20.1 and Forge. It recognizes villages, gives each one a persistent name and one of five kingdoms, and exposes that civic identity through a ledger, entry overlays, commands, datapacks, and a Java API.

The initial release includes Serenum, Lunari, Madera, Anemosia, and Yew. A settlement's biome and structure style influence its first kingdom assignment. After creation, its UUID, name, kingdom, bounds, aliases, and discovery information are saved with the world rather than recalculated on every lookup.

## Requirements

- Minecraft 1.20.1
- Forge 47.x (built with Forge 47.4.23)
- Java 17
- Ultima Kingdoms must be installed on both the client and server

Minecraft Comes Alive Reborn is optional. The included adapter and dependency metadata target exactly MCA Reborn `7.6.26+1.20.1`; the tested MCA setup also uses Architectury API `9.2.14`. Ultima Kingdoms runs without either mod. Do not install a different MCA version alongside this release.

## Installation

Place `ultima_kingdoms-0.1.0.jar` in the `mods` directory of every client and server. The `-api.jar` is a compile-time artifact for addon developers, and the `-sources.jar` contains source attachments; neither belongs in a game instance's `mods` directory.

For MCA integration, also install MCA Reborn `7.6.26+1.20.1` and its Architectury dependency on both client and server. The validated combination uses Architectury API `9.2.14`.

## Playing

Ultima Kingdoms examines loaded chunks for structures in Minecraft's village structure tag and for clusters of village points of interest. A newly recognized settlement receives a stable identity and a kingdom based on the supplied biome and structure-style definitions. Administrators can also create or discover settlements explicitly.

Craft the Village Ledger and use it to browse known settlements, filter them by kingdom, and inspect settlement details. Its recipe is:

```text
Paper  Paper    Paper
Paper  Book     Paper
       Compass
```

When a player enters a recognized settlement, the client shows its name, kingdom, and heraldry. This overlay is enabled by default and can be adjusted in `config/ultima-kingdoms-client.toml`.

The initial release provides civic identity and presentation. It does not implement reputation, diplomacy, warfare, roads, capitals, or settlement-history simulation.

## MCA Reborn integration

With the supported MCA version installed, Ultima Kingdoms reads MCA's village records to recognize settlements and reads an MCA villager's current home as positive residence evidence. An MCA villager's first observed civic residence becomes their origin if no origin is already recorded. Newborns inside an already recognized settlement can also receive that settlement as their initial civic identity.

The integration adds separate Ultima civic data to entities. It does not write MCA personal names, family relationships, homes, genetics, personalities, dialogue state, or relationship values. Renaming an Ultima settlement therefore changes its civic label without renaming its residents or changing their MCA family and home data.

## Commands

Settlement arguments accept a UUID, slug, or quoted display name. Kingdom arguments use namespaced IDs such as `ultima_kingdoms:serenum`. To supply a name when creating a settlement, provide the radius first. The new name for `create` or `rename` consumes the rest of the command as plain text, so do not quote it; quotes there become part of the name. For example:

```text
/ultima village create 64 New Bellmeadow
/ultima village rename "Old Bellmeadow" New Bellmeadow
```

The following inspection commands are available to players:

```text
/ultima kingdom list
/ultima kingdom info <kingdom>
/ultima village here
/ultima village info [village]
```

The remaining commands require permission level 2:

```text
/ultima village create [radius] [name]
/ultima village rename <village> <name>
/ultima village setkingdom <village> <kingdom>
/ultima village reclassify <village>
/ultima village lock <village>
/ultima village unlock <village>
/ultima village discover [radiusChunks]
/ultima village merge <source> <target>
/ultima village debug [village]
/ultima citizen info <entity>
/ultima citizen setorigin <entity> <village>
/ultima citizen setresidence <entity> <village>
/ultima reload
```

Manual settlement radii range from 16 to 512 blocks and default to 64. Discovery radii range from 1 to 32 chunks and default to 8.

## Configuration

The common config is written to `config/ultima_kingdoms-common.toml`:

| Key | Default | Range | Purpose |
| --- | ---: | ---: | --- |
| `settlementDiscovery.defaultSettlementRadius` | 64 | 16–512 | Horizontal radius for detected settlements |
| `settlementDiscovery.poiSearchRadius` | 48 | 16–256 | Radius searched for village POIs |
| `settlementDiscovery.poiMinimumCount` | 3 | 1–64 | POIs required to recognize a cluster |
| `settlementDiscovery.chunkScansPerTick` | 2 | 1–64 | Loaded chunks inspected per server tick |
| `settlementDiscovery.candidateMergeDistance` | 32 | 0–256 | Match distance for weak candidates |
| `civicIdentity.evidenceInterval` | 200 | 20–24000 | Ticks between NPC civic-evidence checks |

The client config is `config/ultima-kingdoms-client.toml`:

| Key | Default | Range | Purpose |
| --- | ---: | ---: | --- |
| `entryOverlay.enabled` | `true` | — | Show the settlement-entry title |
| `entryOverlay.kingdomBordersOnly` | `false` | — | Show it only when the kingdom changes |
| `entryOverlay.durationTicks` | 80 | 20–400 | Display duration in client ticks |
| `entryOverlay.y` | 54 | 0–1000 | Vertical screen position in pixels |

## Datapacks

Definitions use schema `1` JSON files below `data/<namespace>/ultima_kingdoms/`:

```text
kingdoms/<id>.json
name_pools/<id>.json
biome_rules/<id>.json
structure_styles/<id>.json
```

Kingdom definitions select a translation key, name pool, heraldry icon and color, optional metadata and style hints, and whether the kingdom is the single fallback. Name pools provide canonical names, reserved and blacklisted names, weighted templates, and tokens. Biome rules accept biome IDs and `#tag` selectors with priorities and exclusions. Structure-style rules map structure style IDs to kingdoms by priority.

The [built-in resources](src/main/resources/data/ultima_kingdoms/ultima_kingdoms/) are working examples. `/ultima reload` reloads datapack definitions. Existing settlement identities are retained; use the explicit reclassification or administration commands when a saved settlement should change.

## Java API

Addons can compile against `ultima_kingdoms-0.1.0-api.jar`. Obtain the server-scoped service after Ultima Kingdoms attaches it during server startup:

```java
KingdomsService kingdoms = UltimaKingdomsApi.get(server);
```

Calling `UltimaKingdomsApi.get` before the service is ready throws `IllegalStateException`. Call all `KingdomsService` methods on the Minecraft server thread; off-thread calls also throw `IllegalStateException`.

`KingdomsService` exposes kingdom, settlement, residence, civic-identity, and dialogue-context queries; controlled settlement and civic mutations; and registrations for settlement detectors, kingdom resolvers, and civic-evidence providers. Forge events under `com.ultimakingdoms.api.event` report definition reloads and settlement or resident lifecycle changes. The API artifact contains API classes only and does not expose MCA types.

## Development

The project uses the included Gradle wrapper and a Java 17 toolchain.

```text
./gradlew build
./gradlew runClient
./gradlew runServer
./gradlew runData
```

`build` runs the unit checks, verifies the API jar boundary, and produces:

```text
build/libs/ultima_kingdoms-0.1.0.jar
build/libs/ultima_kingdoms-0.1.0-api.jar
build/libs/ultima_kingdoms-0.1.0-sources.jar
```

MCA remains a runtime-only integration and is not placed on the compile classpath or linked into the public API artifact. Development GameTests with MCA are currently blocked by an upstream SRG mixin incompatibility; the exact MCA integration was instead validated with the unchanged packaged mod in a production Forge server runtime.

See the [changelog](CHANGELOG.md) for this release and the [design specification](docs/ultima_kingdoms.md) for the wider architecture and future design context.

## License

All Rights Reserved.
