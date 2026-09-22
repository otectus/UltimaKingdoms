# R1 — regional civic network

Implementation and acceptance record for Phases 0–2 of the [integration roadmap](Ultima-Factions-Integration-Audit-and-Roadmap.md). **R1 regional civic network is implemented and validated; staged delivery is separate from live installation.** This document supersedes the earlier foundation-preview description; the older preview's hashes and runtime results are not evidence for the new receipt bridge.

## Gameplay and ownership

The Lamplighters provide the first peaceful civic network. They are a voluntary guild, separate from kingdom citizenship, government office, personal affection, sovereign standing, military allegiance, race and worship.

1. Complete an existing Lamplighters service quest. MCA Quests still owns offers, objectives, inventory consumption, native rewards, cooldowns and completion history.
2. After a verified player-file save, its durable completion outbox supplies the authoritative receipt. Ultima acknowledges separate guild service once per player, guild and quest template. Repeatable native quests retain their ordinary rewards without farming guild standing.
3. Existing authored Lamplighters quest givers become prospective guild contacts. An adult resident needs a real, loaded Townstead workshop to activate chapter services. Ordinary professions alone never enroll NPCs. Family, hearts, genetics, home and personal opinion remain with their providers.
4. Earn introductions and curated commissions through membership or the independent service route. An introduction discloses one existing neighboring chapter to the requesting player. A commission menu selects existing, ordinarily eligible quests; it does not duplicate quests or replace their native acceptance checks.
5. Departure preserves service and private history. A sufficiently accomplished independent contributor retains neutral access. Destroying/changing the workshop or moving out of interaction range suspends services; restoring the provider-owned building recovers them.

The network does not generate undiscovered settlements, force-load distant chunks, create military territory, or pretend every village already hosts a chapter. Chapters must exist through authored contacts or authorized institutional appointments. A regional introduction requires another existing chapter within the configured radius. Existing map mods retain their own discovery rules; Ultima supplies private known coordinates rather than writing their files or revealing their maps.

### Qualifications

| Service | Member route | Independent route |
|---|---|---|
| Regional introduction | 20 standing, 1 verified deed, volunteer rank | 40 standing, 2 verified deeds |
| Curated commissions | 60 standing, 3 verified deeds, Lamplighter rank | 100 standing, 5 verified deeds |

Member ranks are volunteer / Lamplighter / route warden at 0 / 60 / 100 standing. Unaffiliated contributors do not acquire an organizational rank. The eight peaceful Lamplighters templates provide 110 possible standing, sufficient for both independent services; four existing arcane/expedition templates add 80. No ordinary magic, race, deity, crafting or exploration content is membership-locked.

## Player and administrator entry points

- Village Ledger → **Guilds**: own membership, standing, verified deeds, rank progress, qualification reasons, nearby contact, provider health, recent evidence and private history; Join, Leave, Introduction, Commissions, Refresh.
- `/ultima guild`, `join <organization>`, `leave <organization>`, and `explain <organization> <permission>`.
- `/ultima guild introduction <organization>` and `commissions <organization>` resolve the actual nearest eligible contact, then revalidate the actor, speaker, current institution and current qualification.
- `/ultima guild chapters` and `institutions` list bounded pages of discovered places. Unknown coordinates are not returned.
- `/ultima guild charter <organization> <institution UUID>` binds a chapter to an existing recognized political institution. `/ultima guild appoint <chapter UUID> <NPC selector>` appoints a nearby adult resident. `/ultima guild dismiss <NPC UUID>` revokes the role durably, including automatic reactivation from old authored evidence. These writes require institutional recognition authority or operator permission; moving a contact between chapters also requires authority over its former chapter.
- `/ultima guild qualify <skill_level|deity|race|lore_collected> <subject> <minimum>` performs an actor-private provider read. Skill subjects use provider names such as `building`; deity/race subjects use their namespaced IDs; lore subjects use actual RPG Lore book IDs. A missing/unsupported provider is **unavailable**, not a successful qualification. This diagnostic never awards progress.
- `/ultima military here` observes the current Overworld claim through exact Recruits 1.15.2. It never changes claims, teams, relations or troops.
- Operator `/ultima integrations` reports installed versions and civic/discovery health. Installation alone is not proof that a capability bound successfully.

Native MCA Conversations integration is implemented in its provider patch: a private guild-contact topic, current qualification/workshop branches and authenticated introduction/commission actions. It uses the actual speaker and requester; it does not broadcast standing, private worship or destinations into public NPC chat. See provider delivery and final acceptance below.

## Source and provider boundaries

| Area | Source | Contract |
|---|---|---|
| Guild identity and progression | `api/factions/organization/`, `factions/organization/` | Voluntary memberships, independent standing, strict definitions, evidence and explanations |
| Civic roles and services | `api/civic/`, `civic/` | Sparse appointments, live provider checks, private introductions, native commission invocation |
| Political reads | `api/politics/InstitutionView`, `GovernmentService` | Typed institution status and scoped recognition authority; political owner remains authoritative |
| Discovery | `knowledge/SettlementKnowledge` | Per-player knowledge, legacy adoption, merge redirects, filtered reads |
| Quest effects | `compat/quests/receipts/` | Persisted policy intent, proof-bound delivery, durable acknowledgment and retry |
| Progression reads | `api/progression/`, `compat/progression/` | Optional private Runic Skills / Gods / Races / RPG Lore predicates; no writeback |
| UI and networking | `client/GuildScreen`, `civic/CivicNetwork` | Actor-only packets, bounded responses, separate read/mutation throttles, server revalidation |
| MCA Quests provider | isolated provider checkout and patch | Completion receipt outbox, targeted player-save fence, acknowledgment and scoped native offers |
| MCA Conversations provider | isolated provider checkout and patch | Optional reflection-only civic context, fail-closed topic gate, private native actions |

The existing mod ID remains `ultima_kingdoms`. Existing sovereign faction APIs, standing records, settlement IDs and political saves retain their meanings. There is no score-to-membership migration. The classes-only API artifact does not expose optional provider implementation types.

### Read predicates

Exact inspected installed surfaces:

- Runic Skills **2.2.1**: `SkillCapability.get(Player)`, `RegistrySkills.getSkill(String)`, `getSkillLevel(Skill)`. Reads committed levels rather than consuming the cancellable pre-write level event.
- Runic Gods **0.2.1**: `RunicGodsAPI.getPlayerGodId(ServerPlayer)`. Does not bind a deity or copy favor into faction standing.
- Runic Races **1.7.1**: `RaceHelper.getRaceId(Player)`. No race selection, root migration, genetics or automatic allegiance writes.
- RPG Lore **2.2.0**: `CodexTrackingData.getInstance()` and `hasBook(UUID,String)`. Collection is knowledge only, not proof of an office, deed or artifact provenance.

Different provider versions remain unsupported until their surfaces are checked. Reads are lazy, server-thread-only and private to the authenticated actor. There is no per-NPC progression polling or irreversible milestone award in R1. The existing 54-entry race/root selection bridge remains owned by its pack scripts.

## Datapack and pack content

Guild definitions: `data/<namespace>/ultima_factions/factions/<path>.json`, schema 1. ID must match the resource path. Supported fields include kind, localized purpose, optional membership conflicts/exclusive group, ordered ranks and permissions, service thresholds, neutral thresholds, and exact whitelisted quest/credit pairs. At most 128 guild definitions; each quest can fan out to at most 128 effects. Credit is positive and bounded to 10,000. Invalid batches retain the previous committed generation.

Service rules add optional paired `neutral_minimum_standing` and `neutral_required_deeds`, never lower than member requirements. Omitting them preserves membership-only behavior. Missing definitions preserve records and history, suspend new membership/services, and permit resignation.

Civic rules: `data/<namespace>/ultima_factions/civic_network/<path>.json`, schema 1:

```json
{
  "schema": 1,
  "organization": "ultima_kingdoms:lamplighters",
  "sponsor_quests": ["ultima:civic/lamplighters/fire_in_poor_hands"],
  "building_families": ["workshop"],
  "commissions": ["ultima:civic/lamplighters/fire_in_poor_hands"]
}
```

Sponsor rules designate authored guild context, not universal NPC profession membership. The bundled version covers eight existing Lamplighters givers and twelve existing commission IDs. Rules are bounded, immutable and transactionally reloaded within their domain. Invalid data does not replace the last valid civic set.

`pack/r1/kubejs/` contains twelve overlays with the original quest IDs. Only offer/completion dialogue receives civic context. Objectives, giver restrictions, prerequisites, chain sequencing, repeats, rewards and native actions remain intact. `quest-manifest.json` records original and overlay hashes. Verify with:

```sh
python3 tools/test/verify_r1_pack.py
```

The first completion is explicitly identified as guild-credit eligible. Commissions are convenient filtered access to the existing catalog, **not exclusive political content**. Once an ordinary offer is issued, resignation does not revoke the native right to accept it; native giver/eligibility/cooldown checks still run. Future institution-exclusive quests require a new acceptance-time permission contract.

## Durability, reload and history

### Producer

MCA Quests supplies immutable receipts containing provider epoch, unique quest-instance receipt ID, player/giver, quest ID, committed revision/time, accepted dimension/village and available frozen kingdom/building context.

`readCompletionReceipts(player, consumerId, limit)` subscribes the consumer with a renewable 6,000-tick lease. New receipts freeze the active consumer cohort; later consumers do not acquire retroactive rewards. No active consumer means ordinary quest completion does not create receipt obligations. Unacknowledged obligations remain preserved after consumer expiry. Unreadable/future state with possible obligations fails closed.

The completion path stages its receipt with native completion history, active-quest removal and inventory changes, invokes **targeted** vanilla `PlayerList.save(ServerPlayer)`, then rereads that player's on-disk capability and confirms the completed snapshot before exposing evidence. A live completion event only wakes delivery; it is not evidence itself. Acknowledgment is also saved and reread before returning success. This verifies a player-file fence; it does **not** make another mod's save or the giver's inventory atomically commit with that file.

The provider queue holds at most 256 live receipts per player. Fully acknowledged receipts can retire under pressure into bounded acknowledgment tombstones; healthy consumers can complete more than 256 quests without waiting seven days. Pending obligations are never silently evicted. One expired consumer can still impede a second consumer sharing the same full queue; R1 registers one consumer. Do not promise arbitrary multi-consumer throughput without queue partitioning or an explicit obligation-retirement design.

### Recipient

1. Read only durable provider receipts; verify the player and successful outcome.
2. Atomically persist a recipient intent before effects, including zero-effect dispositions. Freeze guild IDs, exact credit and authored-contact decisions at this point.
3. Apply a guild effect using its persisted intent identity. The internal runtime re-reads its proof; callers cannot substitute an arbitrary quest or credit. Definition removal or changed credit during retry cannot rewrite a committed award.
4. Persist evidence and effect identity before returning success. Replays validate payload fingerprints; another receipt for the same quest cannot award a second guild deed.
5. Persist contact seeds independently. Missing settlement mappings retain their frozen dimension/village reference for later activation, without blocking the completed guild award indefinitely.
6. Persist the delivered marker, acknowledge the producer durably, and remove the finished recipient intent. Any interruption retries the same identities.

The bridge wakes on provider notices/login and renews at most 32 online players each 20 ticks, with bounded eight-receipt reads. It does not load offline players or scan chunks. Failed delivery logs are transition-limited. Disabled civic integration retains pending data.

Membership, departure and deed commits append bounded actor-private history and post `OrganizationCommittedEvent` **after** durable commit. History retains the latest 8,192 entries globally; it is presentation history, not an effect journal. Evidence receipts remain the exactly-once authority. Explanations include organization policy generation and evidence revision; each service action independently re-evaluates live institutional and social conditions.

## Privacy and migration

New world data is additive:

- `ultima_kingdoms_organizations.dat`: memberships, evidence and private history, schema 1.
- `ultima_kingdoms_knowledge.dat`: discovered settlement UUIDs and frozen legacy-public adoption.
- `ultima_kingdoms_civic_network.dat`: chapter references, appointments, pending authored roles, dismissal tombstones and introduction receipts, schema 1.
- `ultima_kingdoms_quest_receipts.dat`: frozen delivery intents, schema 1.
- MCA Quests completion outbox: lives in the existing player capability and has its own producer schema/migration rules.

Existing schema-1 guild saves default missing history to empty. Earlier civic seeds with a settlement UUID do not need the new unresolved-community fields. Unknown or malformed payloads preserve their raw data read-only. These files must be backed up with the world and playerdata; do not roll individual ledgers back independently or delete evidence to resolve a capacity alarm.

The first knowledge adoption records settlements previously present in the old globally visible ledger as public, and durably saves the adoption marker before new discovery. Subsequent discoveries remain per-player, including after restart and settlement merges. Operators intentionally retain administrative visibility. Trusted server APIs can read global state; player-facing endpoints filter it.

An introduction commits its chosen destination and cooldown before revealing it. Retrying after a crash grants the same knowledge rather than selecting another place. Only its recipient receives coordinates. Unknown places are not sent by guild listing/command suggestions or typed player-authorized political reads. No universal fog-of-war retrofit of external map mods is claimed.

## Configuration and operational limits

`ultima-kingdoms-civic-common.toml`:

| Setting | Default | Range / behavior |
|---|---:|---|
| `enabled` | true | Turns off receipt consumption and chapter services; preserves records |
| `introductionRadius` | 4096 | 128–32768 blocks, same dimension |
| `contactRange` | 8 | 2–16 blocks; live line of sight required |
| `introductionCooldown` | 24000 | 20–168000 server game ticks per player/guild |

All writes are server-authoritative. No client packet can choose another player, grant standing, appoint arbitrary contacts, write provider reputation or claim territory. Institution operations honor current authority. Civil/guild offices grant no Recruits troop authority.

Bounded storage refuses new effects rather than deleting replay guards: 32,768 membership records, 16,384 guild evidence receipts, 16,384 recipient intents, 4,096 chapters, 16,384 contacts/pending roles/dismissal tombstones, 100,000 introductions and 100,000 private discovery records. At sustained capacity, pending delivery can backpressure the producer. Administrators should monitor health, back up the complete world, and resolve capacity through a reviewed migration/increased bound; there is intentionally no unsafe “clear receipts” command. These limits suit the initial civic network, not indefinite high-population operation.

## Delivery and validation

Provider changes were built in isolated copies under `build/r1-provider-work/` and applied to the MCAQuests and MCAConversations sibling repositories after verifying all 58 source baseline hashes. Prior source files are backed up at `/tmp/ultima-r1-provider-source-backup-20260920-131912`. The delivery includes their exact source files, patches and hashes alongside the main mod and twelve pack overlays. A classes-only main API jar accompanies the implementation. No live world or live instance installation is part of these acceptance runs.

Validation commands and final artifact hashes are recorded in `build/r1-completion-validation.json` (also included in the delivery). Completed coverage includes:

- Main `check build productionTestJar productionClientTestJar` with both runtime init scripts; content/artifact checks.
- MCA Quests full build, outbox/schema/pressure/replay regressions, API/reobfuscation checks.
- MCA Conversations generator drift, context/gate/action tests and provider-free linkage checks.
- Real native pack quest craft/delivery → durable provider receipt → guild effect → chapter activation, neutral route, introduction privacy, commission menu, stale location/building refusal and recovery; process restart.
- Two real clients: GuildScreen join/leave/rejoin, independent profiles, localized policy explanations, compact/normal screenshots.
- Current pack tuple copied to an isolated client directory; copied old world load/save; actual twelve registry IDs and installed progression reads; original world hashes unchanged.
- Provider absence remains supported, invalid/future schemas preserve data, and old globally visible settlements are not re-adopted on restart.

Do not substitute earlier preview logs for these checks. Later warfare, crime mutations, treaties, economic transfers, taxation, vassalage and dynamic political crises remain R2–R4 work, as in the roadmap.

### Final acceptance evidence (2026-09-20)

| Check | Result / evidence |
|---|---|
| Main check/build and packaged test artifacts | PASS; 56 tests, 1 skipped, no failures/errors; `/tmp/gradle-UltimaKingdoms-check-20260920-131714.log` |
| MCA Quests build | PASS; 1,330 tests, 16 skipped, no failures/errors; `/tmp/gradle-MCAQuests-build-20260920-125650.log` |
| MCA Conversations build and generated content | PASS; 1,604 tests, 6 skipped, no failures/errors; `/tmp/gradle-MCAConversations-build-20260920-131545.log` |
| Native quest, conversation, replay and restart | PASS; `build/r1-civic-loop-h/civic-loop.log`, `civic-loop-restart.log`; actual receipt survives policy removal, fabricated delivery proof rejected, private native actions revalidate the requester |
| Companions without Ultima | PASS startup and clean shutdown; `build/r1-provider-absence-final/initial.log` |
| Real clients | PASS; `build/r1-civic-clients-delivery/leader-PASS.txt`, `peer-PASS.txt`; two normal/compact screenshots per client (four total) under their `screenshots/` directories |
| Full current pack, copied existing world | PASS load/save/clean exit; 404 loaded mod entries, twelve quest IDs, civic service and all four progression read adapters; `build/r1-full-pack-final-b/R1_PACK_PASS.txt` |
| Original world integrity | PASS; full before/after file hash maps match in `build/r1-full-pack-final-b/source-world-{before,after}.json` |
| Core without companion providers | PASS initial/restart; `build/r1-core-final/r1.log`, `r1-restart.log` |
| Content | `python3 tools/test/verify_r1_pack.py`: twelve stable IDs, unchanged mechanics and sufficient peaceful neutral credit; `check_mod.py`: zero errors/warnings/notes |

Full-pack acceptance verifies installed registry/API binding and old-world loading, not every unrelated mod's gameplay. Its log retains existing pack warnings. The native service fixture completes one actual craft/delivery quest; additional standing is explicit fixture setup, not a claim that seven complete questlines were played. The two-client fixture verifies actual UI/network privacy separately from the native server quest/action fixture.

The first full-pack launch was blocked by sandbox display sockets and was rerun with approved display access. An earlier native restart run (`r1-civic-loop-g`) passed its assertions but timed out during shutdown while waiting for asynchronous entity loading. The test fixture now allows 80 startup ticks before stopping; the fresh `r1-civic-loop-h` run completed both processes cleanly. No production workaround or weakened assertion was introduced.

Build and runtime invocations:

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/UltimaKingdoms check build productionTestJar productionClientTestJar -I tools/test/production-tests.gradle -I tools/client-test/client-test.gradle
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/UltimaKingdoms productionTestJar -I tools/test/production-tests.gradle
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/UltimaKingdoms/build/r1-provider-work/MCAQuests build -PmcaReputationClasses=/home/otectus/Projects/MCAReputation/build/classes/java/main
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/UltimaKingdoms/build/r1-provider-work/MCAConversations build
python3 tools/client-test/civic_multiplayer.py --work-dir build/r1-civic-clients-delivery
python3 tools/client-test/r1_pack.py --work-dir build/r1-full-pack-final-b
python3 tools/test/integration_runtime.py --work-dir build/r1-provider-absence-final --startup-only --mod build/r1-provider-work/MCAQuests/build/libs/mcaquests-1.6.6.jar --mod build/r1-provider-work/MCAConversations/build/libs/mcaconversations-1.7.2.jar --mod build/politics-production-mca-20260920-release/mods/minecraft-comes-alive-7.6.26+1.20.1-universal.jar --mod /home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods/architectury-9.2.14-forge.jar
python3 tools/test/integration_runtime.py --work-dir build/r1-civic-loop-h --phase civic-loop --mod build/r1-provider-work/MCAQuests/build/libs/mcaquests-1.6.6.jar --mod build/r1-provider-work/MCAConversations/build/libs/mcaconversations-1.7.2.jar --mod build/politics-production-mca-20260920-release/mods/minecraft-comes-alive-7.6.26+1.20.1-universal.jar --mod /home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods/architectury-9.2.14-forge.jar --mod /home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods/townstead-0.7.6+1.20.1.jar --mod '/home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods/FireSticks+V.1.5+[1.20.1][FORGE].jar' --mod /home/otectus/Documents/curseforge/minecraft/Instances/Ultima/mods/Patchouli-1.20.1-85-FORGE.jar
python3 tools/test/verify_r1_pack.py
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py /home/otectus/Projects/UltimaKingdoms
python3 pack/r1/providers/apply_sources.py --apply
```

Use a new work directory when repeating the isolated runtime commands; the runners intentionally refuse to overwrite evidence.
