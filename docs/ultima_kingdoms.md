# Ultima Kingdoms: Creative and Technical Design Specification

## Design vision and research basis

**Ultima Kingdoms** should be a server-authoritative settlement-identity layer for the Ultima modpack. Its core job is deceptively simple:

> Every recognized village receives a permanent name, belongs to exactly one Ultima kingdom, and exposes that identity consistently to MCA, other addons, commands, datapacks, GUIs, dialogue systems, and future Ultima content.

The important design decision is that a village's **name and kingdom must become persistent world data**, not values that are recomputed every time someone queries the biome. The biome should determine a village's identity when that settlement is first discovered; after that, the assignment survives restarts, mod updates, biome remapping, datapack changes, and manual renaming.

For MCA, this specification treats **Minecraft Comes Alive Reborn**, maintained in the `Luke100000/minecraft-comes-alive` project, as the upstream integration target. Its exact internals should be inspected on the precise Minecraft/MCA branch used by the Ultima pack before implementing hooks; the official repository should be regarded as authoritative over class or method names appearing in examples here. citeturn2view0

The prompt does not identify the exact Minecraft version, loader, MCA version, or the source of the user's other MCA addons. Therefore, **this document deliberately avoids making Ultima Kingdoms depend on undocumented MCA implementation details in its core module**. Version-specific MCA calls belong behind an isolated compatibility adapter compiled against the pack's actual MCA version. This also means an MCA update should require changing `compat/mca`, not rewriting the settlement system. The official MCA source should be consulted whenever implementing those version-specific hooks. citeturn2view0

The mod should separate five concepts that are easy to accidentally conflate:

| Concept | Meaning | Persistence |
|---|---|---|
| **Kingdom** | Serenum, Lunari, Madera, Anemosia, or Yew | Datapack/config definition |
| **Settlement** | A particular village, such as Moonwatch | Permanent world record |
| **Residence** | The settlement an NPC currently lives in | Mutable |
| **Origin** | The settlement/kingdom an NPC originally came from | Normally immutable |
| **Village style** | Plains, snowy, savanna, desert, taiga, or a modded equivalent | Detection metadata |

This distinction is the foundation for good MCA integration. **Do not rename MCA villagers to encode their village.** “Elena” should remain Elena; she can separately be “Elena of Bellmeadow” or “Elena, resident of Bellmeadow, Serenum.” Personal identity belongs to MCA. Civic identity belongs to Ultima Kingdoms.

The preferred dependency architecture is:

```text
Minecraft
    │
    ├── MCA Reborn
    │
    │     └── MCA-specific villager/family/social mechanics
    │
    └── Ultima Kingdoms
          ├── Kingdom registry
          ├── Settlement registry
          ├── Biome assignment
          ├── Naming
          ├── Civic identity
          ├── Public API
          ├── Commands/networking
          │
          └── Compatibility bridges
                ├── MCA
                ├── Ultima MCA addon A
                ├── Ultima MCA addon B
                ├── datapacks/scripts
                └── future integrations
```

The resulting mod is not merely a village-name generator. It becomes the **geopolitical ontology of the Ultima pack**: anything else can ask, “Where is this?”, “What kingdom is this character from?”, or “Who governs this settlement?” without separately reinventing those answers.

A strong definition of success is:

> A coding addon should be able to identify `Serenum`, identify `Bellmeadow`, determine that an MCA villager lives in Bellmeadow, determine that Bellmeadow belongs to Serenum, and react to that information without knowing anything about how Ultima Kingdoms detects villages internally.

That API boundary is more valuable than any individual visual feature.

## Kingdom canon, biome identities, and territorial rules

No pre-existing histories for the five kingdoms were included in the request, so the lore below should be treated as a **proposed Ultima canon framework**, deliberately broad enough to accommodate whatever lore already exists elsewhere in the modpack. The names are used as thematic prompts rather than claims about an existing canon.

A particularly elegant mapping is to give each kingdom one of the five principal village visual/environmental identities:

| Kingdom | Primary village environment | Extended environments | Cultural identity | Naming character |
|---|---|---|---|---|
| **Serenum** | Plains | Sunflower plains, meadows and similar open temperate country | settled heartland, law, farming, civic institutions | soft, pastoral, prosperous |
| **Lunari** | Snowy country | Snowy plains, ice fields, cold highlands; optionally snowy forests | moon, stars, winter, scholarship, remembrance | silver, celestial, quiet |
| **Madera** | Savanna | Savanna plateaus, warm woodland, compatible tropical/open woodland | woodcraft, builders, guilds, caravans | warm, arboreal, amber/red |
| **Anemosia** | Desert | Badlands, dunes, windswept dry country | wind, travel, wells, trade routes, astronomy | airy, sandy, directional |
| **Yew** | Taiga | Old-growth coniferous and temperate forests | wardens, ancient woods, bowyers, customary law | old, forested, guarded |

This is significantly more coherent than attempting to make Yew a desert kingdom merely to force a one-to-one mapping. Anemosia naturally absorbs the arid/wind identity while Yew receives the forest identity implied by its name.

**Serenum — the Realm of the Open Bell.** Serenum is the old agricultural and administrative heartland of Ultima. Its founding myth can revolve around the *Concord of Bells*: isolated farming communities agreeing that a village bell signified sanctuary, market law, and mutual defense. It consequently favors broad fields, roads, bridges, commons, grain, orchards, courthouse-like civic architecture, and prosperous market settlements. Its kingdom is not defined by militarism so much as by the belief that ordered civil life creates peace.

Suggested heraldry: rising sun, bell, wheat sheaf, white bridge. Suggested material vocabulary: oak, stone, copper, pale plaster, gold accents. Villages sound old and welcoming: **Bellmeadow**, **Fairmere**, **Dawnfield**, **Greenford**.

**Lunari — the Realm Beneath the Long Night.** Lunari should be a northern culture formed by communities that survived severe winters through careful planning and observation. The moon becomes practical as well as spiritual: a calendar, a navigational symbol, a keeper of seasons, and eventually a cultural emblem. Scholars, healers, archivists, astronomers, oath-keepers, and winter wardens all fit naturally here.

This makes Lunari mystical without requiring it to be generically “magic kingdom.” Its institutions can feel disciplined and contemplative. Suggested heraldry: crescent moon, eight-pointed star, silver tower. Suggested materials: spruce, dark stone, pale wood, silver-gray metals, blue glass. Settlements sound quiet and celestial: **Moonwatch**, **Silvermere**, **Rimeford**, **Starrest**.

**Madera — the Kingdom of Living Craft.** Madera is the warm-country builder kingdom. Its founding narrative can center on carpenter guilds, bridge-builders, wagon-makers, foresters, masons, and merchants who transformed scattered savanna communities into a connected trade society. Rather than simply being “the nature kingdom,” Madera treats trees as **material culture**: houses, wagons, furniture, instruments, ships where appropriate, bridges and carvings.

That distinguishes it sharply from Yew. Madera shapes wood; Yew protects old woods.

Suggested heraldry: branching tree, carpenter's square, wagon wheel or carved sun. Suggested materials: acacia-like warm woods, terracotta, copper, woven cloth. Names should feel sun-warmed and crafted: **Sunwood**, **Amberbough**, **Redleaf**, **Coppergrove**.

**Anemosia — the Kingdom of Roads Without Walls.** Anemosia is built around wind, movement, and the problem of civilization in dry country. Oasis towns, caravan stations, astronomers, couriers, desert engineers and traders form a decentralized network whose real “walls” are roads, wells and agreements of hospitality.

Its foundational legal tradition could be **the Law of Wells**: water cannot be denied to peaceful travelers. That gives the kingdom a humane identity and produces immediate quest hooks. Suggested heraldry: spiral wind, wing, compass star, stylized dune. Suggested materials: sandstone, terracotta, pale fabric, colored glass. Names emphasize wind and travel: **Zephyrgate**, **Dustwell**, **Dunewatch**, **Crosswind**.

**Yew — the Greenwood Compact.** Yew is older and more decentralized than Serenum. It is a confederation of forest communities governed through wardens, councils, hereditary customs and sacred or protected groves. The yew/bow association can inspire a strong bowyer and ranger tradition without turning Yew into a generic fantasy-elf realm.

Its founding act could be **the Greenwood Compact**, an agreement that no single lord owns the deep forest outright. Communities are custodians rather than absolute owners. Suggested heraldry: yew branch, longbow, stag, raven. Suggested materials: spruce/dark oak-like timber, mossy stone, iron and dark metals. Village names feel old and rooted: **Yewholt**, **Elderbough**, **Mossford**, **Greenwarden**.

The default jurisdiction should be expressed through **data-driven biome rules**, not hard-coded `if biome == ...` statements:

```json
{
  "schema": 1,
  "kingdom": "ultima_kingdoms:serenum",
  "priority": 100,
  "include": [
    "#ultima_kingdoms:kingdom/serenum"
  ],
  "exclude": []
}
```

The actual biome-tag resource path should follow the conventions of the exact target Minecraft version rather than being baked into this specification.

Recommended default environmental ownership:

| Environmental family | Kingdom | Priority | Notes |
|---|---:|---:|---|
| plains/open temperate | Serenum | 100 | Default plains identity |
| meadow/open upland | Serenum | 80 | For modded/generated villages |
| snowy plains | Lunari | 110 | Strongest Lunari signature |
| ice/snow open country | Lunari | 90 | Expansion support |
| ordinary savanna | Madera | 110 | Core warm woodworking culture |
| warm open woodland | Madera | 85 | Useful for modded biomes |
| jungle/bamboo woodland | Madera | 65 | Optional; pack-dependent |
| desert/dunes | Anemosia | 110 | Core arid identity |
| badlands | Anemosia | 100 | Excellent expansion territory |
| windswept dry terrain | Anemosia | 95 | Wind theme beats Madera here |
| ordinary taiga | Yew | 110 | Core Yew identity |
| old-growth coniferous forest | Yew | 100 | Ideal Yew territory |
| dark/temperate forest | Yew | 85 | For village-expansion mods |
| generic unknown biome | resolver fallback | — | Do not silently tag by a brittle hard-coded list |

Overlaps should be expected. A modded `windswept_savanna`-like environment could reasonably belong to Madera or Anemosia. Solve this through explicit **rule priority**, not special-case Java code.

The assignment hierarchy should be:

```text
Admin/manual override
    ↓
Addon-provided explicit kingdom
    ↓
Exact biome rule
    ↓
Biome-tag rule
    ↓
Village/structure-style hint
    ↓
Environmental fallback resolver
    ↓
Configured global fallback
```

For the final fallback, Serenum is a reasonable default only because every settlement must belong somewhere; however, the record should preserve:

```text
assignmentSource = FALLBACK
```

so an admin or compatibility addon can find questionable assignments later.

A better border treatment is to sample more than one block. The settlement classifier can examine the village anchor plus a small sample of surrounding settlement territory:

```text
center biome              60%
settlement-area samples   30%
structure-style hint      10%
```

The exact weights should be configuration values. This prevents a village whose bell happens to sit one block across a biome border from being politically reclassified in a way that visually contradicts most of the settlement.

Most importantly, **classification happens once**.

Suppose Bellmeadow is created in a plains biome and assigned Serenum. Six months later the pack changes the biome tags so that biome would resolve to Yew. Bellmeadow should remain Serenum because that is now world history.

Only `/ultima village reclassify` or an explicit migration operation should change it.

## Settlement naming system and canonical name pools

Village names should combine **hand-authored canonical pools** with a deterministic procedural fallback. Pure procedural naming tends to create technically infinite but forgettable results; a curated pool gives Ultima recognizable places worth referring to in quests.

A village record must separate three identifiers:

```text
settlement UUID
    Permanent identity.
    Never changes.

canonical slug
    Machine-readable alias such as:
    ultima_kingdoms:bellmeadow
    Should normally remain stable after creation.

display name
    "Bellmeadow"
    May be renamed by admins or gameplay.
```

Never use the display name as the database key. Two settlements can theoretically end up with identical player-given names, names can be translated or renamed, and punctuation can change.

Recommended initial canonical pools:

| Serenum | Lunari | Madera | Anemosia | Yew |
|---|---|---|---|---|
| Bellmeadow | Moonwatch | Sunwood | Zephyrgate | Yewholt |
| Fairmere | Silvermere | Amberbough | Dustwell | Elderbough |
| Dawnfield | Frosthollow | Redleaf | Dunewatch | Mossford |
| Greenford | Starrest | Coppergrove | Windrest | Greenwarden |
| Sunvale | Palehaven | Warmshade | Whisperdune | Thornwatch |
| Clearbrook | Rimeford | Goldenbark | Skyreach | Hartwood |
| Goldfield | Nightveil | Emberwood | Sandwatch | Blackbough |
| Summerwell | Crescent Hollow | Saffron Reach | Gale Crossing | Foxhollow |
| Whitebridge | Snowglass | Tallgrass | Stormwell | Ravenholt |
| Rosewick | Wintermere | Honeythorn | Sunscar | Deepgrove |
| Brightmere | Moonfall | Acacia Rest | Crosswind | Pinewatch |
| Stillwater | Starmead | Sunbough | Windward | Stagrest |
| Hearthfield | Argent Watch | Amberrest | Far Dune | Rootmere |
| Crownmead | Frostmere | Redgrove | Highwind | Fernwick |
| Valegate | Moonveil | Goldbough | Dustgate | Yewmere |
| Larkford | Rimewatch | Coppermere | Wanderwell | Oldwood |
| Highmeadow | Winterwatch | Brightwood | Zephyr Rest | Bowyer's Rest |
| Peacecross | Starhollow | Hearthgrove | Skydune | Elderholt |

These should be editable datapack resources. An Ultima quest author should be able to reserve “Moonwatch” for a special generated settlement or blacklist it without rebuilding the mod.

The procedural generator should operate through **weighted morphological templates**, not arbitrary syllable concatenation.

Example Serenum pool:

```json
{
  "schema": 1,
  "id": "ultima_kingdoms:serenum",
  "reserved": [
    "Bellmeadow",
    "Fairmere",
    "Dawnfield",
    "Greenford"
  ],
  "templates": [
    {
      "format": "{prefix}{suffix}",
      "weight": 8
    },
    {
      "format": "{descriptor} {noun}",
      "weight": 2
    }
  ],
  "tokens": {
    "prefix": [
      "Dawn",
      "Fair",
      "Green",
      "Gold",
      "Bright",
      "Summer",
      "Rose",
      "Lark",
      "Vale",
      "Bell"
    ],
    "suffix": [
      "mere",
      "ford",
      "field",
      "meadow",
      "wick",
      "well",
      "bridge",
      "haven"
    ],
    "descriptor": [
      "High",
      "White",
      "Golden",
      "Still"
    ],
    "noun": [
      "Meadow",
      "Bridge",
      "Vale",
      "Cross"
    ]
  }
}
```

Lunari's morphemes should favor:

```text
Moon / Star / Silver / Frost / Rime / Night / Winter / Pale / Crescent
+
watch / mere / hollow / rest / haven / veil / fall / ford / glass
```

Madera:

```text
Sun / Amber / Red / Copper / Gold / Ember / Honey / Warm / Saffron
+
wood / bough / grove / rest / shade / leaf / reach / thorn
```

Anemosia:

```text
Zephyr / Wind / Gale / Dust / Dune / Sand / Sky / Storm / Wander
+
gate / well / watch / rest / reach / crossing / dune / ward / scar
```

Yew:

```text
Yew / Elder / Moss / Thorn / Hart / Raven / Fox / Pine / Root / Fern
+
holt / wood / bough / ford / watch / hollow / grove / mere / wick
```

Generation must be deterministic:

```java
seed = hash(
    worldSeed,
    dimensionKey,
    settlementAnchorChunkX,
    settlementAnchorChunkZ,
    kingdomId
);
```

Then:

```text
Try unused canonical names
        ↓
Try generated names from kingdom grammar
        ↓
Check world-wide normalized uniqueness
        ↓
Retry bounded number of times
        ↓
Use deterministic disambiguator
```

Do **not** regenerate names after restarting the server. Determinism is merely useful for predictable initial generation and testing; persistence remains authoritative.

The uniqueness normalization should be more aggressive than display comparison:

```text
"Moon Watch"
"Moon-Watch"
"moonwatch"
```

can all normalize to roughly the same comparison key. This avoids settlements that look accidentally duplicated.

Recommended naming record:

```java
SettlementName {
    String displayName;
    String normalizedKey;
    String canonicalSlug;
    ResourceId namePool;
    Optional<GeneratedRecipe> recipe;
    List<String> historicalAliases;
}
```

The historical aliases field makes renaming dramatically more useful. A quest or old save referring to “Bellmeadow” can still locate the settlement after a player renames it “New Bellmeadow.”

MCA compatibility imposes an important presentation rule:

```text
MCA personal name:
    "Elena"

Ultima Kingdoms residence:
    "Bellmeadow"

Kingdom:
    "Serenum"

Presentation:
    Elena of Bellmeadow
    Elena — Bellmeadow, Serenum
    Elena, resident of Bellmeadow
```

Never:

```text
CustomName = "Elena of Bellmeadow, Serenum"
```

unless a version-specific MCA API explicitly says this is how its naming system is meant to be extended. Ultima Kingdoms should preserve MCA's own personal/family identity and add civic identity alongside it. Because MCA's internal implementation can vary by Minecraft/MCA branch, that integration point should be verified directly against the exact upstream source used by the pack. citeturn2view0

Player-facing phrases should be localized:

```json
{
  "text.ultima_kingdoms.resident_of":
    "%1$s of %2$s",

  "text.ultima_kingdoms.village_and_kingdom":
    "%1$s, %2$s",

  "text.ultima_kingdoms.kingdom_of":
    "Kingdom of %s",

  "message.ultima_kingdoms.entering":
    "Entering %1$s — %2$s"
}
```

Kingdom labels and UI phrases should be translation keys. Proper village names should ordinarily remain proper nouns rather than changing with client language.

## Runtime architecture, village discovery, and persistent data

The codebase should be built around four registries/services:

```text
KingdomRegistry
SettlementRegistry
SettlementResolver
CivicIdentityService
```

The **kingdom registry** describes abstract political entities.

```java
public record KingdomDefinition(
    ResourceId id,
    TranslationKey displayName,
    ResourceId namePool,
    int rulePriority,
    HeraldryDefinition heraldry,
    Set<ResourceId> metadataTags
) {}
```

The **settlement registry** stores actual places that exist in this world.

A useful persistent record is conceptually:

```java
public record SettlementRecord(
    UUID id,
    DimensionKey dimension,
    BlockPos anchor,
    int effectiveRadius,

    ResourceId kingdomId,

    String displayName,
    String canonicalSlug,

    ResourceId biomeAtCreation,
    Optional<ResourceId> villageStyleAtCreation,

    AssignmentSource assignmentSource,
    DetectionSource detectionSource,

    boolean kingdomLocked,
    boolean nameLocked,

    long createdGameTime,
    long lastObservedGameTime,

    Map<String, String> externalRefs,
    List<String> aliases,

    int schemaVersion
) {}
```

`externalRefs` is particularly valuable:

```json
{
  "mca": "some-upstream-id-if-one-exists",
  "some_ultima_addon": "addon-specific-id"
}
```

Ultima Kingdoms then does not need to force every mod to share the same village identifier.

A settlement should be discovered through multiple **detectors**, ordered from highest quality to lowest:

| Detector | Purpose |
|---|---|
| `ExternalSettlementDetector` | Lets MCA or another Ultima addon explicitly announce a settlement |
| `VillageStructureDetector` | Recognizes generated village structures |
| `PoiClusterDetector` | Recovers villages in old saves or unusual world generation |
| `ManualSettlementDetector` | Lets commands/admin tools establish a village |
| `AddonDetector` | Allows structure mods to identify their own settlement types |

The detector contract should look conceptually like:

```java
public interface SettlementDetector {
    Stream<SettlementCandidate> detect(
        ServerWorldView world,
        ChunkArea area
    );
}
```

A `SettlementCandidate` should not immediately create world data. It goes through a deduplication service first:

```text
Candidate detected
      ↓
Does it match existing external reference?
      ↓ no
Does it substantially overlap an existing village?
      ↓ no
Is anchor within merge distance of existing village?
      ↓ no
Create settlement
```

This prevents a structure detector, POI detector and MCA detector from creating three records for the same physical place.

Do not globally scan every loaded block or every villager every tick.

The preferred lifecycle is:

```text
chunk becomes relevant
    ↓
cheap detector check
    ↓
candidate discovered
    ↓
resolve or create persistent settlement
    ↓
index settlement by chunks
    ↓
future position lookups use spatial index
```

Keep an in-memory chunk index:

```java
Map<ChunkKey, Set<UUID>> settlementsByChunk;
```

Then:

```java
Optional<SettlementRecord> getSettlementAt(world, pos)
```

only checks nearby indexed settlements.

The actual persistent-data implementation should use the world/server persistence facilities of the target Minecraft loader/version. The core requirement is **world-level saved state, schema-versioned, server-authoritative and migratable**; the version-specific wrapper belongs in the loader adapter.

The biome assignment service should be independent of detection:

```java
public interface KingdomResolver {
    KingdomResolution resolve(
        ServerWorldView world,
        SettlementCandidate candidate
    );
}
```

A complete resolution result is more useful than a kingdom ID:

```java
public record KingdomResolution(
    ResourceId kingdom,
    AssignmentSource source,
    ResourceId decisiveBiome,
    int winningPriority,
    double confidence,
    List<RuleTrace> trace
) {}
```

The trace makes `/ultima village debug` extremely valuable:

```text
Village: Moonwatch
Anchor: 312, 71, -884
Biome at anchor: minecraft:snowy_plains

Rules:
  + #ultima_kingdoms:lunari        priority 110
  - #ultima_kingdoms:yew           no match
  - #ultima_kingdoms:serenum       no match

Resolved:
  ultima_kingdoms:lunari

Source:
  BIOME_TAG

Confidence:
  1.00
```

That is far better than asking a developer months later why a village belonged to the “wrong” kingdom.

The civic identity model should deliberately distinguish origin and residence:

```java
public record CivicIdentity(
    Optional<UUID> originSettlement,
    Optional<ResourceId> originKingdom,

    Optional<UUID> residenceSettlement,
    Optional<ResourceId> residenceKingdom,

    CivicIdentitySource source,
    long lastResidenceChange
) {}
```

This enables richer storytelling:

> Elena was born in Moonwatch, Lunari, but now lives in Bellmeadow, Serenum.

An addon can ask either:

```java
identity.originKingdom()
```

or:

```java
identity.residenceKingdom()
```

instead of Ultima Kingdoms imposing one interpretation of “belongs to.”

Suggested lifecycle:

```text
NPC first appears in recognized settlement
    origin settlement = current village
    origin kingdom    = village kingdom
    residence         = same

NPC born through MCA
    origin = parents'/birth location settlement
    residence = household settlement

NPC moves home
    residence changes
    origin does not

NPC temporarily walks outside village
    nothing changes

NPC travels to another village
    nothing changes until residence criteria are met

NPC permanently migrates
    residence changes after positive home evidence

NPC is cured/transformed/reloaded
    preserve civic identity whenever possible
```

Do not classify residence simply as “nearest village every tick.” Villagers walking between POIs or accompanying players would constantly become citizens of somewhere else.

A configurable residence policy can instead require:

```text
home/bed evidence
OR
explicit addon assignment
OR
sustained residence for N game days
```

The record must survive entity serialization and world restart.

For positions that are not NPCs, settlement membership is simpler:

```text
settlementAt(position)
```

should use the village's effective territory or detector-provided bounds.

This also makes future mechanics possible without schema redesign:

```text
Kingdom reputation
Kingdom-specific quests
Kingdom guards
Trade preferences
Village elections
Wars or alliances
Travel documents
Regional dialogue
Regional professions
Village heraldry
Kingdom maps
```

Those features should not be part of the initial identity MVP, but the data model should not prevent them.

## MCA and Ultima-addon integration contract

The most important architectural recommendation is to make MCA a **compatibility provider**, not a type dependency threaded throughout the mod.

Use an interface similar to:

```java
public interface McaBridge {

    boolean isAvailable();

    boolean isMcaVillager(Entity entity);

    Optional<CivicIdentityHint> getIdentityHint(Entity entity);

    void onSettlementResolved(
        Entity entity,
        SettlementView settlement
    );

    void onCivicIdentityChanged(
        Entity entity,
        CivicIdentity oldIdentity,
        CivicIdentity newIdentity
    );
}
```

Then provide:

```text
compat/mca/<target-version>/
```

for the actual implementation.

The coding agent should inspect the selected MCA Reborn branch before binding to villager classes, village managers, networking internals, dialogue data or mixin targets; those details are precisely the sort of integration surface that can change between Minecraft/MCA versions. The official source repository is therefore the source of truth for this adapter. citeturn2view0

The adapter should prefer, in order:

```text
Documented/public MCA extension point
        ↓
MCA event/callback exposed by target build
        ↓
Safe interface access
        ↓
Accessor/mixin bridge
        ↓
Last resort: narrowly scoped injection
```

Avoid invasive replacement of MCA behavior.

In particular, Ultima Kingdoms should **not** take ownership of:

```text
MCA personal names
MCA family relationships
MCA genetics/appearance
MCA personality
MCA relationship values
MCA marriage
MCA dialogue state
```

unless an explicit compatibility feature requires reading one of them.

Instead it adds:

```text
Settlement identity
Kingdom identity
Origin
Residence
Regional dialogue variables
Regional metadata
```

alongside MCA.

A public API should expose **Ultima types only**, never MCA classes:

```java
public interface UltimaKingdomsApi {

    Optional<KingdomView> getKingdom(ResourceId id);

    Optional<SettlementView> getSettlement(UUID id);

    Optional<SettlementView> getSettlementAt(
        ServerWorldView world,
        BlockPos pos
    );

    Optional<SettlementView> getResidence(Entity entity);

    Optional<CivicIdentityView> getCivicIdentity(Entity entity);

    Collection<SettlementView> getSettlements(
        ResourceId kingdom
    );

    Registration registerKingdomResolver(
        ResourceId owner,
        KingdomResolver resolver
    );

    Registration registerSettlementDetector(
        ResourceId owner,
        SettlementDetector detector
    );
}
```

This gives the user's other MCA addons a clean integration point.

Add events:

```java
SettlementCreatedEvent
SettlementDiscoveredEvent
SettlementRenamedEvent
SettlementKingdomChangedEvent

ResidentJoinedEvent
ResidentLeftEvent
CivicIdentityChangedEvent

KingdomDefinitionsReloadedEvent
```

Event payloads should use immutable views.

One especially important event is:

```java
SettlementKingdomChangedEvent {
    UUID settlement;
    ResourceId oldKingdom;
    ResourceId newKingdom;
    ChangeReason reason;
}
```

Otherwise every addon caching kingdom information will become stale when an administrator manually changes a settlement.

For addons that cannot link against the Java API, provide interoperability at additional layers.

**Command interface**

```text
/ultima kingdom list
/ultima kingdom info <kingdom>

/ultima village here
/ultima village info [village]
/ultima village rename <village> <new name>
/ultima village setkingdom <village> <kingdom>
/ultima village reclassify <village>
/ultima village lock <village>
/ultima village unlock <village>
/ultima village discover
/ultima village merge <villageA> <villageB>
/ultima village debug [village]

/ultima citizen info <entity>
/ultima citizen setorigin ...
/ultima citizen setresidence ...

/ultima reload
```

**Optional command-friendly entity tags**

For scripting systems and command blocks:

```text
uk_kingdom_serenum
uk_kingdom_lunari
uk_kingdom_madera
uk_kingdom_anemosia
uk_kingdom_yew
```

These should be a **compatibility mirror**, not canonical storage. If the entity changes residence, Ultima Kingdoms updates the mirror.

Do not encode a village UUID in dozens of persistent entity tags unless another addon specifically needs it.

**Dialogue/context variables**

Ultima Kingdoms should expose a simple context provider:

```text
{ultima.kingdom}
{ultima.kingdom_id}
{ultima.village}
{ultima.village_id}

{ultima.origin_kingdom}
{ultima.origin_village}

{ultima.residence_kingdom}
{ultima.residence_village}
```

When the target MCA version exposes a suitable dialogue-variable system, the MCA bridge should map these into it. Where it does not, Ultima's other dialogue addons can call the public API themselves. Again, the exact MCA-side binding needs to be implemented against the chosen upstream branch rather than guessed. citeturn2view0

This unlocks dialogue such as:

```text
"Welcome to Bellmeadow."

"You don't sound like you're from Serenum."

"I was born in Moonwatch, but Bellmeadow is home now."

"Travel safely if you're crossing into Anemosia."
```

The same API should be consumable by quest addons:

```java
if (kingdoms.getResidence(npc)
            .map(v -> v.kingdomId())
            .filter(SERENUM::equals)
            .isPresent()) {
    // Serenum-specific quest path
}
```

A more advanced dialogue context can distinguish speaker and listener:

```text
speaker.kingdom
listener.current_kingdom
listener.reputation_with_speaker_kingdom
same_kingdom
foreign_visitor
```

Only the first two belong in the initial Ultima Kingdoms release. Reputation is a natural later extension.

**MCA life-cycle integration should cover at least these cases:**

| MCA/Entity event | Ultima response |
|---|---|
| Villager first initialized | Establish civic identity if absent |
| Child born | Record origin; resolve household residence |
| Villager loaded from disk | Restore, do not recalculate origin |
| Villager establishes a new home | Potential residence update |
| Villager migrates | Change residence, preserve origin |
| Villager marries | Do not arbitrarily overwrite civic identity |
| Villager dies | Keep village record; release any transient cache |
| Villager converts/cures | Preserve identity where entity lifecycle permits |
| Village renamed | NPC references resolve by UUID, so remain valid |
| Village changes kingdom | Residence kingdom follows settlement; origin kingdom normally remains historical |
| Datapack reload | Rebuild definitions, but do not rewrite existing settlements |

The subtle distinction in the penultimate row is worth preserving:

```text
Origin:
    historical fact

Residence kingdom:
    derived from current settlement
```

If Bellmeadow changes from Serenum to Yew, someone born there twenty years earlier can remain “Serenum-born” while becoming a resident of a Yew-controlled settlement. That turns a technical data-model decision into potential emergent lore.

For your own MCA addons, establish a tiny dependency-facing module:

```text
ultima-kingdoms-api
```

containing only:

```text
API interfaces
immutable view types
event types
resource identifiers
annotations/contracts
```

No client rendering.  
No world scanner.  
No MCA code.  
No loader-specific implementation unless unavoidable.

Then:

```text
Ultima MCA Addon A ──compileOnly──> ultima-kingdoms-api
Ultima MCA Addon B ──compileOnly──> ultima-kingdoms-api
Ultima Kingdoms ─────────implements─> ultima-kingdoms-api
```

This is the single best long-term compatibility measure in the design.

## Datapack model, presentation, and worldbuilding extensions

Almost everything creative should be data-driven.

A proposed custom kingdom definition:

```json
{
  "schema": 1,
  "id": "ultima_kingdoms:lunari",

  "display_name": {
    "translate": "kingdom.ultima_kingdoms.lunari"
  },

  "name_pool": "ultima_kingdoms:lunari",

  "biome_rules": [
    {
      "tag": "#ultima_kingdoms:kingdom/lunari",
      "priority": 110
    }
  ],

  "style_hints": [
    "snowy"
  ],

  "heraldry": {
    "emblem": "ultima_kingdoms:lunari",
    "banner_pattern": "ultima_kingdoms:lunari",
    "map_icon": "ultima_kingdoms:lunari"
  },

  "metadata": {
    "theme": "moon_winter_scholarship",
    "capital": ""
  }
}
```

The file location itself should be a custom reload-listener resource path chosen for the target Minecraft version. Do not assume a custom `kingdom` object automatically becomes a vanilla dynamic registry unless the code explicitly implements it.

Allow other namespaces to extend biome ownership:

```json
{
  "replace": false,
  "values": [
    "some_biome_mod:frosted_steppe",
    "some_biome_mod:moonlit_tundra"
  ]
}
```

Conceptually this means:

```text
Modpack datapack says:
    Frosted Steppe → Lunari

Ultima Kingdoms core code:
    remains unchanged
```

That is exactly how a modpack-level political system should behave.

World-generation mods should get an even more explicit compatibility path:

```json
{
  "structure_styles": {
    "some_village_mod:red_savanna": "madera",
    "some_village_mod:dune_oasis": "anemosia",
    "some_village_mod:dark_forest": "yew"
  }
}
```

Biome still takes precedence by default because that is the central premise of Ultima Kingdoms, but the structure style is an excellent fallback.

The mod should have a **Village Ledger** or **Kingdom Almanac** UI rather than attempting to communicate everything through chat.

Recommended village screen:

```text
┌────────────────────────────────────────┐
│              MOONWATCH                 │
│               Lunari                   │
│                                        │
│  Founded:   Day 184                    │
│  Region:    Snowy Plains               │
│  Residents: 17                         │
│  Status:    Recognized Settlement      │
│                                        │
│  Origin: biome classification          │
│                                        │
│  [Map] [Residents] [History]           │
└────────────────────────────────────────┘
```

“Founded” should really mean **first recognized by the mod** unless the pack supplies deeper historical simulation. Labeling detection time as literal historical founding would be misleading.

An optional enter/leave overlay would make the kingdoms immediately tangible:

```text
                 MOONWATCH
          Kingdom of Lunari
```

or:

```text
              BELLM EADOW
               SERENUM
```

This should be client-configurable to avoid HUD fatigue.

A boundary message could appear only when crossing kingdom borders:

```text
Entering the Kingdom of Yew
```

That makes political geography perceptible even when individual villages are far apart.

Heraldry should also be data:

| Kingdom | Emblem concept | Palette concept | Architectural cue |
|---|---|---|---|
| Serenum | rising sun / bell | gold, cream, green | roads, civic squares |
| Lunari | crescent and star | silver, dark blue, pale gray | observatories, lamps |
| Madera | branching tree / wheel | amber, red, green | carved wood, workshops |
| Anemosia | spiral wind / compass | sand, white, sky tones | shade cloth, towers, wells |
| Yew | yew branch / bow | deep green, brown, charcoal | woodland halls, stone markers |

These should be presentation metadata, not encoded assumptions inside kingdom logic.

A `HeraldryDefinition` might be:

```java
public record HeraldryDefinition(
    ResourceId icon,
    Optional<ResourceId> mapMarker,
    Optional<ResourceId> bannerPattern,
    int uiThemeIndex
) {}
```

Avoid hardcoding RGB colors into logic. Rendering resources can decide exact presentation.

An optional **kingdom banner placement** feature should be conservative. Do not automatically replace village blocks or edit player-modified settlements merely because a village was detected. Safer options are:

```text
display heraldry in UI
show heraldry on maps
provide craftable kingdom banners
let structure/worldgen datapacks place banners
allow an admin command to install a marker
```

rather than mutating every existing village.

A useful craftable item would be the **Surveyor's Ledger**:

```text
Right-click within a village:
    Bellmeadow
    Kingdom of Serenum
    Settlement ID ...
    Classification: Plains
```

For operators it could additionally expose debug data while ordinary players see only lore-friendly information.

The system could later support roads or maps by treating settlements as a graph:

```text
Settlement A
    ├── 820 blocks → Settlement B
    ├── 1440 blocks → Settlement C
    └── border → Yew
```

That creates a natural substrate for:

```text
caravan quests
postal quests
MCA migration
regional trade
kingdom capitals
fast travel
road generation
diplomatic missions
bandit encounters
pilgrimages
```

without changing the settlement identity schema.

A particularly strong future enhancement is **kingdom reputation that is independent of MCA personal relationships**:

```text
MCA:
    Elena likes player +42

Ultima Kingdoms:
    Serenum regards player +10

Village:
    Bellmeadow regards player +25
```

Do not derive one directly from the other. A person can like a player whose political reputation is poor, which is dramatically more interesting.

Another future layer is **settlement history**:

```java
record SettlementHistoryEntry(
    long gameTime,
    HistoryType type,
    JsonObject payload
) {}
```

Possible history:

```text
Created / discovered
Renamed
Kingdom changed
Raid survived
Population milestone
Capital designation
Addon-defined historical event
```

Then the UI can say:

> Bellmeadow joined Serenum on Day 422.

This should remain opt-in because unlimited event logs can inflate save data.

## Implementation blueprint, testing, and acceptance criteria

The codebase should strongly separate common logic from integration logic:

```text
ultima-kingdoms/
├── api/
│   ├── UltimaKingdomsApi
│   ├── KingdomView
│   ├── SettlementView
│   ├── CivicIdentityView
│   └── events/
│
├── core/
│   ├── KingdomRegistry
│   ├── SettlementRegistry
│   ├── CivicIdentityService
│   └── migration/
│
├── settlement/
│   ├── SettlementDetector
│   ├── VillageStructureDetector
│   ├── PoiClusterDetector
│   ├── SettlementResolver
│   └── SpatialSettlementIndex
│
├── kingdom/
│   ├── KingdomDefinition
│   ├── KingdomResolver
│   ├── BiomeKingdomResolver
│   └── KingdomRuleTrace
│
├── naming/
│   ├── VillageNameService
│   ├── NamePool
│   ├── NameTemplate
│   └── NameUniquenessIndex
│
├── citizen/
│   ├── CivicIdentity
│   ├── ResidenceResolver
│   └── CivicIdentityPersistence
│
├── compat/
│   ├── mca/
│   │   └── McaBridge
│   └── addons/
│
├── data/
│   ├── KingdomDataLoader
│   ├── NamePoolDataLoader
│   └── BiomeRuleDataLoader
│
├── command/
├── network/
├── client/
└── platform/
```

If the selected MCA branch uses a multi-loader architecture, align Ultima Kingdoms' platform split with whatever loaders are actually in the Ultima pack rather than attempting to maintain unnecessary variants. The exact build and version arrangement should be checked against the pack's MCA source branch before implementation. citeturn2view0

The core creation flow should look approximately like:

```java
void onSettlementCandidate(
    ServerWorld world,
    SettlementCandidate candidate
) {
    Optional<SettlementRecord> existing =
        settlementRegistry.match(candidate);

    if (existing.isPresent()) {
        settlementRegistry.observe(existing.get(), candidate);
        return;
    }

    KingdomResolution resolution =
        kingdomResolver.resolve(world, candidate);

    SettlementName generatedName =
        villageNameService.createUniqueName(
            world,
            candidate,
            resolution.kingdom()
        );

    SettlementRecord settlement =
        SettlementRecord.create(
            UUID.randomUUID(),
            candidate,
            resolution,
            generatedName
        );

    settlementRegistry.add(settlement);
    spatialIndex.add(settlement);

    events.post(new SettlementCreatedEvent(settlement.view()));

    civicIdentityService.discoverResidents(settlement);
}
```

Critically, normal lookup must **not** do this:

```java
// WRONG
Kingdom kingdomAt(BlockPos pos) {
    return lookupBiome(pos).kingdom();
}
```

It should do:

```java
// RIGHT
Optional<Kingdom> kingdomAt(BlockPos pos) {
    return settlementRegistry
        .getSettlementAt(pos)
        .map(SettlementRecord::kingdom);
}
```

Biome determines **initial political identity**. Persistent settlement records determine ongoing political identity.

A rename similarly changes only presentation:

```java
void renameVillage(UUID settlementId, String newName) {
    SettlementRecord record = get(settlementId);

    validate(newName);

    record.aliases().add(record.displayName());
    record.setDisplayName(newName);

    save();
    events.post(...);
}
```

It does not change the UUID.

Kingdom reassignment should be explicit and auditable:

```java
void changeKingdom(
    UUID settlementId,
    ResourceId kingdom,
    ChangeReason reason
) {
    // validate kingdom
    // update record
    // refresh resident derived residence kingdom
    // preserve resident historical origin
    // refresh compatibility tags
    // invalidate client cache
    // publish event
}
```

Networking should remain server-authoritative.

Clients need only:

```text
Kingdom definitions needed for UI
Nearby/relevant settlement summaries
Civic identity needed for displayed entities
Registry revision number
```

Do not synchronize the entire world's village database every time someone joins a server.

Use revisioned delta synchronization:

```text
server registry revision = 83
client revision          = 82

send:
  SettlementUpdated(Bellmeadow)
```

The client cache is disposable; server persistence is canonical.

Save data needs an explicit schema version from the first release:

```json
{
  "schema": 1,
  "settlements": [...]
}
```

Then:

```java
switch (schema) {
    case 1 -> loadV1(data);
    case 2 -> migrateV2(data);
    default -> ...
}
```

Never postpone migration architecture until a second release.

**Minimum automated unit tests:**

| Test | Expected result |
|---|---|
| Plains classification | Serenum |
| Snow classification | Lunari |
| Savanna classification | Madera |
| Desert classification | Anemosia |
| Taiga classification | Yew |
| Overlapping biome tags | Highest-priority rule wins |
| Equal priorities | Stable documented tie-break |
| Name generation same seed | Same initial output |
| Duplicate canonical name | Generator chooses another |
| Rename | UUID unchanged |
| Kingdom change | settlement UUID/name unchanged |
| Definition reload | Existing assignment unchanged |
| Unknown biome | documented fallback path |
| Slug collision | unique machine identity retained |
| Missing kingdom definition | safe failure, no corrupt save |

**World integration tests:**

| Scenario | Requirement |
|---|---|
| New village generation | One settlement record only |
| Chunk unload/reload | Same UUID/name/kingdom |
| Server restart | Same UUID/name/kingdom |
| Existing world receives mod | Village discovered lazily |
| Village straddles biome boundary | Stable classification |
| Two nearby villages | Not incorrectly merged |
| Modded village | Addon detector can register it |
| Manual village | Admin can create/claim |
| Datapack biome rule changes | Existing village unchanged |
| Explicit reclassification | Village changes as requested |
| Rename | MCA residents continue resolving village |
| MCA addon absent | Core does not crash if designed as optional |
| Compatibility addon absent | No classloading crash |

**MCA-focused tests:**

```text
MCA villager spawns inside village
    → residence assigned

MCA villager is born
    → origin recorded

Villager walks outside boundary
    → residence retained

Villager visits another village briefly
    → residence retained

Villager establishes home in another village
    → residence eventually updates

Villager reloads
    → civic identity unchanged

Village renamed
    → villager still resolves by UUID

Village kingdom changed
    → residence kingdom changes
    → historical origin remains unchanged

Personal MCA name changes
    → civic identity unaffected

Ultima settlement changes
    → MCA family/social data unaffected
```

Because these hooks depend on the exact MCA release used in Ultima, the coding agent should write the integration tests against that branch's real entities and lifecycle behavior rather than assuming internal signatures from another MCA release. citeturn2view0

Performance acceptance criteria should emphasize architecture rather than an arbitrary millisecond target:

```text
No whole-world scan on tick
No iteration over every known settlement for ordinary position lookup
No biome classification every tick for established settlements
No disk write every tick
No packet broadcast for unchanged state
No repeated MCA reflection in hot paths
```

Cache compatibility lookups where relevant, use chunk-indexed settlement queries, and mark persistent state dirty only when the state has actually changed.

Logging should have separate categories:

```text
ultima_kingdoms/settlement
ultima_kingdoms/kingdom
ultima_kingdoms/naming
ultima_kingdoms/mca
ultima_kingdoms/data
ultima_kingdoms/network
```

Useful debug messages:

```text
[Ultima Kingdoms] Discovered settlement candidate at [31,-55]
[Ultima Kingdoms] Classified biome minecraft:savanna -> Madera
[Ultima Kingdoms] Created settlement "Amberbough" [UUID ...]
[Ultima Kingdoms] Attached MCA resident ... -> Amberbough
```

Ordinary production logs should not spam one line per villager load.

The initial release should be scoped as follows:

| Capability | Initial release |
|---|---|
| Five kingdoms | **Required** |
| Data-driven kingdom definitions | **Required** |
| Biome-based classification | **Required** |
| Persistent named settlements | **Required** |
| Curated + procedural names | **Required** |
| Existing-world discovery | **Required** |
| MCA civic identity bridge | **Required** |
| Public API | **Required** |
| Addon events | **Required** |
| Commands/debugging | **Required** |
| Client settlement overlay | Recommended |
| Village ledger | Recommended |
| Heraldry | Recommended |
| Origin vs residence | **Strongly recommended** |
| Reputation | Later |
| Diplomacy | Later |
| Kingdom warfare | Later |
| Automatic road generation | Later |
| Village-history simulation | Later |
| Capital-city mechanics | Later |

A coding agent should treat the implementation as complete only when all of the following are true:

> **Identity persistence:** Generate Bellmeadow once, restart the server, alter biome configuration, and Bellmeadow is still Bellmeadow of Serenum.

> **MCA separation:** Changing Bellmeadow's name does not overwrite or corrupt an MCA villager's personal name, family data, or relationship state.

> **Civic resolution:** Given an MCA villager, the API can identify their current settlement and kingdom without another addon understanding the detector implementation.

> **Historical identity:** Moving an NPC from Moonwatch to Bellmeadow can produce `originKingdom=Lunari` and `residenceKingdom=Serenum`.

> **Modded-biome extensibility:** A pack datapack can assign a newly added biome to Yew without modifying Java code.

> **Modded-village extensibility:** Another addon can register a village candidate through an API/detector rather than forcing Ultima Kingdoms to know its structure internals.

> **Safe reconfiguration:** Reloading biome rules does not silently rewrite established political geography.

> **Stable references:** Renaming or reassigning a village does not invalidate its UUID or break addons holding a settlement reference.

> **Debuggability:** An operator can inspect why a village belongs to a kingdom and determine whether its assignment came from biome, structure style, another addon, manual action or fallback.

> **Version isolation:** MCA-specific implementation code is confined to an adapter that can be replaced for another MCA/Minecraft branch. The exact hook implementations must be verified against the official MCA source for the selected Ultima pack version. citeturn2view0

The strongest final shape for **Ultima Kingdoms** is therefore not “a mod that puts fantasy names on villages.” It is a persistent, data-driven civic-geography framework:

```text
                         ULTIMA
                            │
              ┌─────────────┴─────────────┐
              │                           │
          KINGDOMS                   SETTLEMENTS
              │                           │
    ┌─────────┼─────────┐           Bellmeadow
    │         │         │                 │
 Serenum    Lunari   ... Yew              ├── kingdom → Serenum
                                        │
                                        ├── biome origin
                                        │
                                        ├── stable UUID
                                        │
                                        ├── aliases/history
                                        │
                                        └── residents
                                                │
                                 ┌──────────────┴──────────────┐
                                 │                             │
                              MCA NPCs                    Other NPCs
                                 │                             │
                       origin / residence             same public API
```

That architecture gives the Ultima modpack something much more reusable than village cosmetics. **MCA provides people; Minecraft provides places; Ultima Kingdoms gives those people and places a political homeland.**

## Open questions and version limitations

The major unresolved implementation variable is the exact **Minecraft version, mod loader, MCA Reborn version, and identities/source code of the other MCA addons** in the Ultima pack. Those details determine the concrete event hooks, mixins, entity classes, networking APIs, build dependencies and any MCA-specific dialogue integration. The official MCA Reborn repository researched for this specification should be consulted on the exact pack branch before the compatibility adapter is implemented. citeturn2view0

Accordingly, identifiers such as `ServerWorldView`, `DimensionKey`, `ResourceId`, `TranslationKey`, and event-interface names in this document are **architecture-level pseudocode**, not promises of exact mapped class names. A coding agent should translate them into the mappings and loader APIs used by the target project rather than copying them literally.

The other significant unknown is the modpack's biome and village-generation set. The five default pairings—**Serenum/open plains, Lunari/snow, Madera/savanna, Anemosia/desert, Yew/taiga and forests**—are intentionally designed so the base system remains coherent while modded biome ownership is supplied by datapacks. This is preferable to baking speculative third-party biome IDs into the core mod.

Finally, “full compatibility with my MCA addons” cannot honestly mean direct source-level adapters to unspecified addons. The robust solution is the compatibility contract described above: stable settlement UUIDs, a small public API artifact, lifecycle events, commands, optional command-visible kingdom tags, datapack-driven geography, origin/residence metadata, and an isolated MCA bridge. Once individual Ultima addons integrate against that contract, **Ultima Kingdoms becomes the common authority for kingdom and village identity rather than requiring fragile pairwise integrations between every mod in the pack**.