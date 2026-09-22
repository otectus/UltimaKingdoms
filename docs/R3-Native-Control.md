# R3 warfare, territory and world integration

Press **K** for the current **Kingdom Tasks** interface. In-mod player and operator actions have a GUI path with named choices and a review step. Saved-record command selectors also accept readable names; legacy UUID examples remain compatible but are optional. See [the current player interface guide](Player-Experience-Review.md) and the in-game Book of Kingdoms.

R3 implements political campaigns around native Recruits warfare, negotiated settlement decisions, temporary mobilization, civilian recovery contracts, local Crime jurisdiction, discovered world sites, institution routes, encounter receipts and atlas/ledger views. All R3 feature switches default to **enabled**. The matching configuration overlay is `pack/r3/config/ultima-kingdoms-warfare-common.toml`.

## Ownership and requirements

Recruits 1.15.2 owns military factions, directed battle relations, soldiers, equipment, claims and siege progress. Ultima owns explicit mappings, campaign intent, mandates, notice, recognized sovereignty and civilian service proofs. Native capture first produces occupation; it does not rename a settlement, change its civic kingdom/culture, rewrite families/buildings or erase reputation and legal cases.

Military commands require both the actual native faction leader and a current political PROPOSE or RATIFY mandate. Being an operator alone does not grant those player mandates. Administrative mapping and retirement require operator level 2. The exact native version receives server packet authorization and cancelled-siege patches; absent/unsupported providers leave military records retained and unavailable. No native classes are bundled.

The audited Recruits delayed executor also receives server-thread dispatch and shutdown cleanup. Delayed tasks are tied to their originating server session, preventing a disconnected world's callbacks from running in a later integrated-server world.

MCA Quests and MCA Crime require the R3 provider builds for the contract, jurisdiction and external atlas interfaces. MCA Quests uses protocol 17: clients and server must use the same build. Provider source exports and before/after hashes are under `pack/r3/providers`; these layer on the R1/R2 provider changes. Core startup remains supported without these providers. Installed Map Atlases remains the rendering owner, and Recruits keeps its native territorial map.

## Setup and player actions

1. Stand in each participating native claim and run `/ultima warfare map_here <kingdom id>` to register its native faction mapping.
2. Stand inside both the recognized overworld settlement and its already-saved native claim, then run `/ultima warfare bind_here <settlement UUID>`. A claim binds to one settlement. Mapping never assigns a player or NPC to a team.
3. Inspect `/ultima warfare here`, or open **War room** from the settlement ledger. Ordinary viewers need discovery and current local presence to see military history and negotiations. Operators may inspect remotely.

Commands use the revision displayed by `/ultima warfare campaigns <settlement UUID>`:

| Action | Command |
| --- | --- |
| Declare | `/ultima warfare declare <defend\|relief_access\|withdrawal\|autonomy\|transfer> <settlement> <revision> <public reason>` |
| Retry native declaration | `/ultima warfare retry_campaign <campaign UUID>` |
| Withdraw own declaration | `/ultima warfare withdraw <campaign UUID>` |
| Propose settlement | `/ultima warfare accord <recognized\|autonomous\|independent> <settlement> <other native faction> <recognized kingdom> <revision> <terms>` |
| Sign or reject | `/ultima warfare accord <sign\|reject> <accord UUID> <revision>` |
| Retry native accord | `/ultima warfare accord apply <accord UUID>` |
| Temporary orders | `/ultima-mobilization muster <unit UUID\|nearby> <defense\|escort\|scout>` |
| Restore orders | `/ultima-mobilization <dismiss\|recover> <lease UUID>` |
| View leases | `/ultima-mobilization status` |
| Civilian work | `/ultima-contract <relief\|reconnaissance\|mediation\|autonomy\|defense\|escort> <giver UUID>` |

Default policy protects capitals, requires the defending native leader online for bound sieges, and requires 1,200 ticks of notice. Campaigns last 168,000 ticks after notice. Native troop detection and native damage determine siege outcomes. Cancellation cannot create a hidden active siege or force civilian hiding. Offline defenders pause permitted damage; no automatic relation write-back occurs when a native player changes diplomacy.

Accords freeze both native relation directions, signing governments, physical claim/controller and settlement terms. Both current leaders must be online and retain ratification authority. An accord becomes operational only after both native directions are acknowledged in provider saves. Partial application remains pending for explicit retry; divergent native state requires fresh negotiation. Recognized, autonomous and independent status are separate from civic identity and native control. A later breach ends operational safe conduct while retaining the ratified historical sovereignty decision.

Peaceful neutral visitors may receive safe conduct; a visitor hostile in either native direction to either party does not inherit it. Native civilian law continues during occupation. Crime owns evidence, enforcement, custody and service restrictions; public warfare context does not disclose private suspicion. Enemy household teams do not authorize attacks on civilian NPCs. Target attribution follows loaded projectile, cloud, summon, tame and mount ownership, and ally protection applies to target acquisition and attributed damage.

## Mobilization and civilian service

Mustering uses at most 16 self-owned, loaded recruits within 32 blocks, for 6,000 ticks by default. Defense holds position; escort follows/protects the commander; scout uses a nearby movement order. These compose native orders without free equipment, generic damage bonuses, conscription or ownership/group changes. Recruitment costs, equipment, morale and wages remain native-owned.

The lease freezes original and applied orders before mutation. Expiry and dismissal restore only matching identity and orders. Divergent native commands require explicit recovery. Former commanders retain recovery for their own leases; operators can recover through the same frozen identity checks. Restoration remains pending until entity reload confirms persisted original orders, so a crash cannot silently discard an unacknowledged restore. Completed leases cannot overwrite a replacement lease. Recovery looks up loaded entity IDs and never loads chunks.

Six authored once-only MCA Quests contracts provide relief, reconnaissance, mediation, autonomy consultation, defense service and escort. Native quests own objectives, consumed items and payment. Ultima records completion only from the durable provider receipt, before acknowledgment. Accepted work retains its original giver, institution and control context. Relief remains available to eligible neutral workers without guild membership; mediation/autonomy do not require an existing peace accord. Escort requires safe conduct. Completing a civilian consultation does not unilaterally change military relations or sovereignty.

## World sites, routes and maps

Datapacks select structure and encounter IDs under `warfare/world`. Vanilla defaults are bundled; the R3 pack overlay adds verified Cataclysm, Mowzie's Mobs, Dungeons Arise and Seven Seas sites. A discovered structure is evidence of a site, not a grant of territorial ownership. Encounter receipts require attributable participation, known nearby context and supported spawn evidence. Tamed, owned, summoned/unproven and repeated encounters are excluded or capped. These receipts never annex land or duplicate provider loot.

`/ultima-world sites`, `routes` and `encounters` show filtered pages. Operators can author a site at their position, routes between discovered operational institutions, and a neutral resource site with commodity exclusions and a discovered return site. `/ultima-world route start <UUID>` begins a checkpoint contract; access is rechecked while travelling. Revoked access removes live route guidance. `/ultima-world resource request <site> <commodity>` evaluates that site's access policy; it grants no ore ownership and touches no player storage or personal loot.

World tick work uses spatial, per-player journey and encounter-history indexes with bounded candidate windows. Route progress batches one saved transaction. Atlas publication sends bounded authorized points through the provider's automatic external layer, preserving manual pins. The war room is read-only; its buttons prepare editable commands whose execution is authorized again on the server.

## Persistence and recovery

Sidecars are `ultima_kingdoms_control`, `ultima_kingdoms_campaigns`, `ultima_kingdoms_civilian_contracts`, `ultima_kingdoms_mobilization` and `ultima_kingdoms_world_context`. Unknown or malformed schemas remain read-only and preserve their payload. Durable intent precedes native writes. Observation requires saved native claim identity, owner, siege state and registered chunk to agree with current native evidence.

A bound settlement cannot silently merge or change civic assignment, even with R3 disabled. `/ultima warfare retire <settlement UUID> <control revision>` explicitly releases its binding while preserving a tombstone and leaving native control untouched. The old claim UUID cannot be reused. Provider absence never invents completed peace, service or restoration. Restore a compatible backup to recover unreadable future-schema records.

## Validation and delivery

R3 validation is recorded in `build/r3-validation.json` when the complete delivery checks pass. Packaged scenarios cover native claims, authority, diplomacy acknowledgment, native siege detection/damage, offline policy, occupation, civilian law/contracts, restart and provider gaps. Separate real-client checks exercise war-room privacy and spoofed native diplomacy packets. Full-pack checks use an isolated copy of an existing world and verify the source world remains unchanged.

A staged delivery contains matching runtime jars, layered content/configuration, provider source exports and validation evidence. It does not install anything into the live instance or publish a release.


Validated on 2026-09-20: core and provider unit suites passed (90 core, 1,345 MCA Quests and 2,632 MCA Crime cases; 33 total skips, no failures), all 39 required GameTests passed, and packaged military/civilian restart plus control provider-gap/future-schema checks passed. Two real clients passed privacy and native diplomacy authorization checks with clean shutdown. The final isolated 404-mod pack resolved all 38 structure and 10 encounter selectors and rendered the network-backed war room. Its first-login MCA character introduction was skipped only in the test copy to permit active-world measurement; the live pack and source world were unchanged. Tick samples and methodology are included in the validation evidence.
