# Ultima Kingdoms

Ultima Kingdoms is a server-authoritative settlement identity mod for Minecraft 1.20.1 and Forge. It recognizes villages, gives each one a persistent name and one of six kingdoms, and exposes that civic identity through in-game tasks, a ledger, entry overlays, optional commands, datapacks, and a Java API.

The initial release includes Serenum, Lunari, Madera, Anemosia, Yew, and Shimaguni. A settlement's biome and structure style influence its first kingdom assignment. After creation, its stable identity, name, kingdom, bounds, aliases, and discovery information are saved with the world rather than recalculated on every lookup.

Shimaguni (`ultima_kingdoms:shimaguni`) follows the pack's seven Shimaguni histories: an island empire, the Island Covenant, and the Tide Archive at Akatsura. New jungle, sparse-jungle and bamboo-jungle settlements default to Shimaguni; datapacks can extend its biome tag. Existing saved settlements keep their assignments. Its red heraldry uses a moon above waves, and its political profile is `ultima_kingdoms:shimaguni_charter`. Akatsura is reserved from random naming so it can be designated deliberately; no capital or historical alliance is created automatically. Generated village names and the civic honor are new thematic content inspired by those histories.

## Requirements

- Minecraft 1.20.1
- Forge 47.x (built with Forge 47.4.23)
- Java 17
- Ultima Kingdoms must be installed on both the client and server

Minecraft Comes Alive Reborn is optional. The dependency metadata accepts MCA Reborn versions from `7.6` inclusive to `8.0` exclusive (`[7.6,8)`), while the adapter capability-probes four known MCA 7.x package roots. Runtime validation used exactly MCA Reborn `7.6.26+1.20.1` with Architectury API `9.2.14`; other versions in the accepted range have not been runtime-validated. Ultima Kingdoms runs without either mod.

## Installation

Place `ultima_kingdoms-0.1.0.jar` in the `mods` directory of every client and server. The `-api.jar` is a compile-time artifact for addon developers, and the `-sources.jar` contains source attachments; neither belongs in a game instance's `mods` directory.

For MCA integration, also install MCA Reborn `7.6.26+1.20.1` and its Architectury dependency on both client and server. The validated combination uses Architectury API `9.2.14`.

## Playing

Ultima Kingdoms examines loaded chunks for structures in Minecraft's village structure tag and for clusters of village points of interest. A newly recognized settlement receives a stable identity and a kingdom based on the supplied biome and structure-style definitions. Administrators can also create or discover settlements explicitly.

Craft a **Book of Kingdoms** from one ordinary book and one paper in any crafting grid. Use it for searchable chapters covering every system, from first steps to advanced government, warfare, world evolution and server administration. Chapter contents, search, and previous/next controls navigate the guide; scroll or focus the article and use the arrow/Page Up/Page Down keys to read. Command examples are optional reference text and do not execute.

Press **K** while playing to open **Kingdom Tasks**. Search for what you want to do, then follow the server's named choices. Consequential tasks show a review page with the selected people, places, terms, and effects before **Apply reviewed action**. The server carries record identity, revision, and evidence metadata with each choice, so players select readable names instead of copying technical values into chat. The Village Ledger, Kingdom pages, and War Room also open relevant tasks. See the [player experience guide](docs/Player-Experience-Review.md).

Craft the Village Ledger and use it to browse known settlements, filter them by kingdom, and inspect settlement details. Its recipe is:

```text
Paper  Paper    Paper
Paper  Book     Paper
       Compass
```

When a player enters a recognized settlement, the client shows its name, kingdom, and heraldry. This overlay is enabled by default and can be adjusted in `config/ultima-kingdoms-client.toml`.

The initial release provides civic identity and presentation plus persistent kingdom/faction standing, with optional MCA Reputation integration. Reputation synchronization defaults to `SHADOW`. The current development tree also adds a political layer with explicit capital charters, secondary offices, petitions, institution recognition, honors, and peaceful agreements. R3 adds temporary native military orders and discovered route guidance. R4 adds opt-in evolving-world scenarios, political history, constitutional transitions, voluntary protection duties, organization lifecycle, family introductions, and negotiated native recruit transfers. See [political usage](docs/politics/usage.md), [ownership](docs/politics/ownership.md), and [compatibility evidence](docs/politics/compatibility-matrix.md).

The R1 civic network adds voluntary Lamplighters membership, verified quest service, independent qualification, workshop contacts, private regional introductions and native curated commissions. See [R1 implementation, provider requirements and validation](docs/R1-Civic-Foundation.md); the complete delivery includes companion-provider changes and twelve pack quest overlays.

R2 adds paid recognized-workshop commissions, receipt-backed civic honors, private Crime service suspension and restitution recovery, and signed hospitality introductions. See [R2 implementation and delivery](docs/R2-Institutions-and-Agreements.md) for the matching provider changes and configuration. R2 institutional services default to enabled.

R3 adds enabled-by-default campaigns, native diplomacy acknowledgment, occupation and settlement decisions, temporary mobilization, civilian recovery contracts, local jurisdiction, discovered world sites/routes and atlas/ledger views. See [R3 setup, commands, ownership and recovery](docs/R3-Native-Control.md).

R4 world evolution defaults off. Its authored scenarios and campaigns require explicit world opt-in, factual prerequisites, consent, and bounded outcomes. See [R4 setup, commands, provider extensions and recovery](docs/R4-Evolving-World.md).

## MCA Reborn integration

With the supported MCA version installed, Ultima Kingdoms reads MCA's village records to recognize settlements and reads an MCA villager's current home as positive residence evidence. An MCA villager's first observed civic residence becomes their origin if no origin is already recorded. Newborns inside an already recognized settlement can also receive that settlement as their initial civic identity.

The integration adds separate Ultima civic data to entities. It does not write MCA personal names, family relationships, homes, genetics, personalities, dialogue state, or relationship values. Renaming an Ultima settlement therefore changes its civic label without renaming its residents or changing their MCA family and home data.

## Optional command reference

Kingdom Tasks is the normal player interface. The commands below remain useful for console automation and players who prefer chat. Record selectors use readable names; quote a name when it contains spaces. Kingdom arguments in this basic command tree use namespaced definition names such as `ultima_kingdoms:serenum`. To supply a new settlement name, provide the radius first. The new name for `create` or `rename` consumes the rest of the command as plain text, so do not quote it; quotes there become part of the name. For example:

```text
/ultima village rename "Old Bellmeadow" New Bellmeadow
/ultima village info "Old Bellmeadow"
/ultima village create 64 New Bellmeadow
```

The following inspection commands are available to players:

```text
/ultima kingdom list
/ultima kingdom info <kingdom-definition-id>
/ultima village here
/ultima village info ["settlement name"]
```

The remaining commands require permission level 2:

```text
/ultima village create [radius] [name]
/ultima village rename "Old Bellmeadow" <new name...>
/ultima village setkingdom "Old Bellmeadow" <kingdom-definition-id>
/ultima village reclassify "Old Bellmeadow"
/ultima village lock "Old Bellmeadow"
/ultima village unlock "Old Bellmeadow"
/ultima village discover [radiusChunks]
/ultima village merge "Old Bellmeadow" "New Bellmeadow"
/ultima village debug ["settlement name"]
/ultima citizen info <entity>
/ultima citizen setorigin <entity> "Old Bellmeadow"
/ultima citizen setresidence <entity> "Old Bellmeadow"
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

Optional integrations use separate common configs. `config/ultima_kingdoms-integrations-common.toml` enables the optional `ultima_kingdoms:kingdom` condition registered with MCA Quests by default, and `config/ultima_kingdoms-townstead-common.toml` enables the Townstead adapter by default. Quest definitions use `kingdom_lifecycle`; conversation topics use `kingdom_gate`. A quest lifecycle defaults to `offer_only`, so authors must select `mode: "live"` explicitly when an accepted quest should follow current political state. MCA Reputation synchronization is configured in `config/ultima_kingdoms-factions-common.toml` and defaults to `reputationSync.mode = SHADOW`.

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
