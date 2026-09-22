# Ultima Kingdoms: Courts, Charters, and Concords

## Coding-agent implementation guide

**Target:** `otectus/UltimaKingdoms`, Minecraft Forge 1.20.1, Java 17.  
**Research date:** 20 September 2026.  
**Purpose:** Adapt the strongest ideas from MCA: Capitals into Ultima Kingdoms while preserving Townstead's systems and giving its daily village gameplay a wider political purpose.

Build a political layer above existing settlements: governments, capital seats, secondary court offices, peaceful agreements, petitions, and civic honors. Townstead continues to determine how villagers live, work, develop, remember, and socialize. Ultima Kingdoms determines which realm a settlement belongs to and what its political institutions have formally decided.

This is an implementation specification, not a claim that the proposed features already exist. Source observations below are separated from proposed behavior. Source review did not include launching Minecraft, testing the mod combination, or verifying the contents of published JARs against source commits.

## 1. Evidence, versions, and implementation baseline

### 1.1 Sources actually inspected

| Project/source | Snapshot inspected | What it establishes |
|---|---|---|
| Ultima Kingdoms | `main`, commit `fe05c7c952367d38e699e280f019cc7321ecfc64` | Existing persistent settlement identity, civic identity, Java API, ledger, networking, and exact MCA adapter. |
| MCA: Capitals public description | CurseForge page, accessed 2026-09-20 | Advertised monarchy, noble houses, courts, succession, diplomacy, war, petitions, decrees, and chronicle. The page listed Forge 1.3.7 as its main release. |
| MCA: Capitals older default branch | `master`, commit `89101881ea3ed683f03b8d4d3c8e5d21f0522908` | Earlier implementation and README; its properties say 1.1.0. Do not mistake this branch for current advertised functionality. |
| MCA: Capitals newer Forge source | `forge-1.3.8`, commit `63358cb4f5dcc715f3edc038f3ed41979f3b790f` | Source implementation of political services, agreements, generated trade shipments, succession, titles, asylum, and mourning. This is a source snapshot, not a verified 1.3.7 binary or a claim that 1.3.8 is publicly released. |
| Townstead public description | CurseForge page, accessed 2026-09-20 | Released 0.7.6 features and an explicit MCA compatibility warning. |
| Townstead main source | `main`, commit `4d6206cdf8b9d0f558694d7b35b223f4f6ace61e` | Properties say 0.7.7; active Stonecutter target is NeoForge 1.21.1. Includes read-only API snapshots for needs, schedules, age, buildings, and calendar. This does not prove those APIs exist in released Forge 0.7.6. |
| Townstead Chronicles development source | `early-chronicles`, commit `f93dc271125a750c0f15741ed384282fe0531881` | Event archive, village history, memories, sentiment, gossip, and community-spirit code. Treat as an overlap to preserve and an integration candidate, not a stable released API. |

Pinned links appear in §14. Recheck the repository heads before implementation and record any material differences. Do not silently substitute an unrelated loader branch.

### 1.2 Existing Ultima Kingdoms is the foundation

The inspected release is a settlement-identity mod. Its README explicitly excludes implemented reputation, diplomacy, warfare, roads, capitals, and settlement-history simulation. Those are new development, not configuration changes. [U1]

Preserve these existing contracts:

- Kingdom IDs are namespaced resources: Serenum, Lunari, Madera, Anemosia, and Yew are already represented by definitions.
- Each settlement has a persistent UUID, one kingdom assignment, name, dimension, bounds, aliases, external references, and revision.
- `KingdomsService` is server-scoped and server-thread-only. It exposes settlement queries, civic origin/residence, context, controlled mutations, and provider registrations.
- `SettlementSavedData` stores records and merge redirects in overworld saved data. Its current schema is 1 and it refuses unsupported schemas.
- `CivicIdentityView` distinguishes historical origin from current residence.
- `VillageLedgerScreen` and the existing ledger packets provide the natural UI entry point. `NetworkHandler` already uses protocol versioning, request IDs, rate limits, and paginated settlement results.
- The public API artifact excludes MCA types. Keep all optional-mod classes outside it. [U2–U5]

Two concrete gaps matter for this expansion:

1. `KingdomsServiceImpl.merge` redirects settlement records but does not currently publish a dedicated settlement-merge event. Add one before attaching political sidecars that reference settlement UUIDs.
2. `getContext(Entity)` only exposes civic identity. It has no viewer parameter and must not become a channel for private diplomatic proposals or NPC memories. Add a separately authorized political-context query instead. [U3]

### 1.3 Version alignment is the first gate

Ultima Kingdoms currently targets **exactly MCA `7.6.26+1.20.1`**; `McaAccess` resolves the packaged `forge.net.mca` namespace. The inspected Capitals 1.3.8 source targets **MCA `7.7.1-alpha.3+1.20.1`**. Townstead's current source imports `net.conczin.mca`; its public 0.7.6 page says the Forge release is incompatible with MCA versions beyond `7.6.28 beta 10`. These are different compatibility surfaces. [U1, U6, C2, T1, T2]

Before implementing an adapter:

1. Select and document one actual Forge 1.20.1 dependency tuple: Forge, MCA, Architectury, Townstead, and this mod. Record artifact versions and hashes.
2. Start by testing the existing Ultima MCA pin with the intended Townstead release. The public warning is an upper bound, not proof that every lower MCA build works.
3. If upgrading MCA is necessary, port the isolated MCA adapter and validate civic identity first. Do not widen the dependency range merely to let the game start.
4. Probe the exact Townstead JAR for the required public snapshots. Main-source availability is not release availability.
5. Support a proven snapshot adapter, a separately proven legacy read adapter if needed, and an explicit unsupported state. Missing capabilities must remain unknown, not become fabricated population, age, or building data.
6. Keep development Chronicles support behind an exact-version capability check. Do not require an unreleased Townstead branch for the core political feature set.

This guide adapts ideas and architecture; it does not require installing MCA: Capitals.

## 2. Feature selection and ownership

### 2.1 What to take from Capitals

| Capitals feature | How the inspected source implements it | Decision for Ultima Kingdoms |
|---|---|---|
| Capital founding and charter | Population scanner offers a charter; foundation and appointment services establish a capital and sovereign. | **Highest priority:** a deliberate capital-seat charter within an existing kingdom. Never turn every qualifying village into a separate kingdom. |
| Court offices and titles | Court builders derive household/rank membership; office services and title resolvers expose appointments and titles. | **Highest priority:** useful secondary civic offices. Never replace professions, workstations, schedules, or family names. |
| Diplomatic agreements | Separate proposal, validation, authority, agreement, and processing services; canonical capital-pair keys and persistent relationship records. | **High priority:** explicit peaceful agreements between existing kingdom governments; no automatic war escalation. |
| Petitions and decrees | Audience/standing checks route title and sovereign actions through services. | **High priority:** scoped requests and decisions for honors, charters, diplomatic introductions, and commissions. |
| Capital Chronicle and herald | Semantic event identifiers, arguments, identity snapshots, stored entries, translated rendering, and herald announcements. | **Adapt the event model only:** political transactions emit events; Townstead owns the narrative archive and gossip when supported. |
| Dynastic succession and abdication | Persistent family-node checks, named-heir priority, ordered royal candidates, noble fallbacks, and interregnum handling. | **Second milestone:** explicit office succession with a deterministic preview, confirmed death evidence, and Townstead-aware life stages. |
| Noble houses and mottos | Separate identity services also manipulate surnames and marriage-related identity. | **Later, limited:** political house membership, emblem, and motto only. No surname inheritance or family simulation. |
| Trade agreements and shipments | Trade service periodically creates shipments from weighted biome/profession/building profiles and deposits them into storage. | **Redesign:** charters unlock real player/quest commissions. Exclude automatic generation or extraction of village goods. |
| Mourning | Stores original clothes, applies mourning clothing, and later restores it. | **Limited adaptation:** formal memorial notice and political vacancy. Townstead/MCA retain grief, emotions, clothing, and calendar behavior. |
| Friends/enemies of the Crown | Crown-standing and justice services track statuses and consequences. | **Positive recognition only:** honors and formal standing. Avoid a second reputation, criminality, or punishment system. |
| Exile, asylum, wardship, arranged betrothal | Refugee/justice/family systems coordinate legal and household changes. | **Exclude forced moves and family changes.** A later hospitality agreement may provide dialogue/quest eligibility without relocating anyone. |
| War, deposition, campaigns, army response | Dedicated military/campaign services and political outcomes. | **Exclude from this project.** They would require a separate design around NPC control, safety, defense, and other mods. |

Implementation evidence: founding/courts [C3–C5], agreements [C6–C8], chronicle [C9], succession [C10], generated trade [C11–C12], mourning [C13], identity/justice/asylum [C14–C16].

### 2.2 Non-negotiable authority boundaries

| Domain | Authority | Ultima Kingdoms may do | Ultima Kingdoms must not do |
|---|---|---|---|
| Settlement UUID, kingdom, civic origin/residence | Ultima Kingdoms | Add political references to the existing identity. | Create a second detector or silently reclassify established settlements. |
| Personal names, family bonds, marriage, parenthood | MCA and its configured extensions | Read authoritative relationships for optional succession. | Rename people, arrange marriages, rewrite parentage, or impose surnames. |
| Hunger, thirst, fatigue, eating, rest | Townstead | Display relevant availability; explain why an NPC cannot hold an audience now. | Feed/heal/refill meters through political abstractions or cancel urgent needs. |
| Professions, progression, shifts, work AI | Townstead/MCA | Attach a secondary civic office; read verified profession/tier data. | Change profession, XP, station, shift, job priorities, pathfinding, or worker inventory. |
| Age, life stage, immortality, roots, genetics | Townstead/MCA | Read adulthood and confirmed lifecycle facts through an adapter. | Age an heir faster, kill an immortal ruler, or create competing life stages. |
| Building recognition and local management | MCA/Townstead | Reference an already recognized building for an institution. | Rescan structures under a second building system or take over the Blueprint workflow. |
| Calendar, dates, stamps | Townstead | Format political dates through an adapter; optionally request a supported stamp integration later. | Create a rival calendar, change time, or directly mutate shared/private stamps. |
| Community spirit | Townstead | Use a proven read-only signal as optional flavor. | Introduce competing town happiness/prosperity/spirit meters or set spirit values. |
| Memories, sentiment, gossip, village history | Townstead Chronicles where available | Submit bounded political facts through a verified bridge; let its knowledge system govern who learns them. | Duplicate its archive, diffusion, mood, or personal-story simulation. |
| Quests and deliveries | Existing quest provider when integrated | Offer contextual templates and eligibility; consume verified completion receipts. | Add a competing objective tracker or claim a commission delivered without provider evidence. |
| Reputation and faction standing | Installed, explicitly selected reputation/faction provider | Query specific standing or publish one identified outcome event. | Convert MCA hearts to a new global kingdom score or blindly mirror scores bidirectionally. |
| Crime, arrest, guards, imprisonment | Existing MCA/Crime/guard systems | Leave untouched; expose relevant political facts for future adapters. | Add arrests, exile enforcement, bounties, confiscation, conscription, or immunity. |

Chronicles and community-spirit development ownership is intentionally reserved even when a particular released Townstead build lacks it. This prevents the expansion from occupying a feature area Townstead is already implementing. [T1–T7]

### 2.3 Explicit exclusions

Do not implement taxation, passive treasury income, global price manipulation, worker levies, automatic caravans, remote chest access, new professions, a new guard AI, land protection, settlement claim ownership, forced celebrations, military power ratings, simulated hunger, or ordinary birth/marriage/death chronicles.

Do not globally replace MCA dialogue JSON or Townstead GUI files. Political interactions must be additive and optional. The Village Ledger remains a complete fallback interface.

## 3. The intended experience

A player discovers Bellmeadow in Serenum. Its existing name, kingdom, and residents remain intact. The ledger shows that Serenum has no capital seat yet. An authorized founding action designates Bellmeadow as the seat and records a civic charter; it does not seize houses or reassign villagers.

An eligible adult librarian is appointed **Keeper of Records** while retaining their librarian profession, Townstead tier, workstation, and weekly schedule. The player can submit a charter petition through the ledger. A physical audience is available when the librarian is not working, resting, collapsed, or in danger; the office does not drag the NPC away from their job.

Serenum and Lunari establish a scholarly agreement. It unlocks a kingdom-specific commission through a verified quest provider, or a clear contract opportunity entry if no provider is installed. Existing librarians, cooks, farmers, and craftsmen continue to produce and work normally. Ultima contributes the reason to visit another settlement, the political decision, and recognition for fulfilling the agreement.

When the contract is completed, the provider sends one stable completion receipt. Ultima records the political fulfillment once and can award an honor. A supported Chronicles adapter publishes the announcement in Townstead's system. Villagers learn and discuss it according to Townstead's rules rather than all knowing immediately.

This first complete loop should exist before optional noble houses or hereditary succession.

## 4. Government, capital seats, and founding charters

### 4.1 One government per existing kingdom

Use the existing namespaced kingdom ID as the key for a new `GovernmentRecord`. There is at most one active government and one capital seat per kingdom per server save in the initial implementation. Kingdoms remain shared across dimensions; the capital is a dimension-aware settlement reference.

Do not create a new polity UUID for every village. Do not make a biome, house, or office into a new kingdom. A settlement's `kingdomId` is unchanged by appointment, abdication, treaty, or capital designation.

Government lifecycle:

| State | Meaning | Allowed behavior |
|---|---|---|
| `UNORGANIZED` | Kingdom identity exists but no recognized government has been constituted. | Read identity and offer eligible founding information. |
| `ACTIVE` | Charter, office rules, and authorized leadership exist. | Normal petitions, appointments, honors, and agreements. |
| `INTERREGNUM` | A required leadership office is vacant following confirmed removal/death/abdication. | Existing agreements persist; restricted caretaker decisions are allowed. |
| `DORMANT` | A referenced kingdom definition or required provider is unavailable. | Preserve data; disable affected mutations; allow operator inspection. |

Keep provider health separate from constitutional state. One broken Townstead snapshot must not depose a ruler or invalidate a treaty.

### 4.2 Capital designation

Create a **Seat Charter** action in the ledger. Its authority comes from the server-side government service, not possession of an item.

Eligibility rules:

1. The settlement exists and resolves through merge redirects.
2. It belongs to the target kingdom and is not already another kingdom's active seat.
3. The actor has explicit founding authority from an operator/admin-configured scenario, or approval from the existing government if moving a seat.
4. Optional pack-authored building/residency conditions have known, current evidence. Unknown data yields a pending/unavailable requirement, not an arbitrary pass or permanent failure.
5. The action preserves all existing MCA/Townstead state.

The default configuration should **not** award sovereignty to the first player who opens a ledger, reaches a population threshold, or holds a crafted charter. In multiplayer this would let exploration become an accidental takeover. Operator initialization can be done once; subsequent governance uses delegated permissions.

Allow an unorganized kingdom to remain so indefinitely. Its villages still participate in ordinary gameplay. Provide an operator bootstrap command and an optional data-defined scenario setup for curated worlds. No forced founding popups.

Population can be an optional founding condition, but only against a verified resident index. The number of currently loaded NPCs is not the settlement's population. Never depose a government because residents unloaded or a town temporarily fell below a threshold.

A capital badge appears beside the existing settlement identity. Capital status grants political interactions only; it does not provide additional building-management rights.

### 4.3 Kingdom-specific styles

These are proposed defaults inspired by the repository's design document, which itself labels the lore as proposed. Preserve the existing five identities and make this layer replaceable by datapacks. Do not hard-code ethnicity, species, genetics, or personality requirements. [U7]

| Kingdom | Proposed government style | Leadership display | Signature civic interest | Townstead complement |
|---|---|---|---|---|
| Serenum | Crown with a civic council | Sovereign / Chancellor | Public charters, reciprocal aid, agricultural patronage | Gives existing food production and communal buildings political recognition. |
| Lunari | Court with a scholarly council | Sovereign / Keeper of the Seal | Scholarship, remembrance, archives | Recognizes existing librarians and provides scholarly exchanges. |
| Madera | Guild assembly | First Speaker | Craftsmanship, institutions, exchange | Honors actual profession progression and completed commissions. |
| Anemosia | Concord of settlements | First Envoy | Hospitality, diplomatic introductions, travel | Makes existing inns and cafes meaningful destinations without running them. |
| Yew | Warden council | High Warden | Stewardship, local autonomy, sworn service | Supports local institutions without imposing royal household mechanics. |

Implement these first as shared constitutional rules plus titles, role lists, eligibility, and content preferences. Do not build five independent government engines. Hereditary succession is optional for crown profiles; council profiles use appointment/election rules.

## 5. Court offices and civic honors

### 5.1 Offices are a second role, never a profession

Store an office assignment using actor UUID, office ID, kingdom ID, optional settlement scope, appointment time, mandate, appointing authority, and record revision. Use a `PersonRef` containing both UUID and kind (`PLAYER` or `NPC`). Entity IDs and display names are unsuitable persistent identifiers.

An NPC can remain a Townstead cook and hold a civic office. Render it as:

> Mira — Cook, Steward of Bellmeadow

Build this from components at display time. Do not overwrite `CustomName`, MCA names, surname fields, or the profession translation.

Initial office set:

| Office | Concrete function | Explicit limit |
|---|---|---|
| Sovereign / First Speaker / High Warden | Ratifies charter changes and delegates political permissions. | No implicit permission to control all village systems. |
| Chancellor | Presents pending petitions; performs delegated political administration. | No job reassignment or resident control. |
| Envoy | Introduces counterpart governments and prepares agreement proposals. | Proposing is separate from ratifying; no teleporting NPC couriers. |
| Herald | Offers public political notices and current office information. | No new gossip diffusion or global chat spam. |
| Keeper of Records | Explains ratified agreements, succession eligibility, and charter terms. | Does not create a competing history archive. |
| Local Steward | Sponsors a settlement's institution petitions and commission nominations. | No production, hunger, scheduling, or building authority. |

Suggested defaults: one holder per office/scope; at most two substantive offices per actor; vacancies allowed. Make limits data-driven. Reject incompatible assignments and self-approval where a petition explicitly requires an independent approver.

Candidate selection uses confirmed civic residence, adulthood, life status, and optional profession evidence. Distinguish Townstead profession progression from vanilla trading level. Do not require every office to map to a profession.

Only explicit appointment grants an office. A heuristic may recommend a candidate but must not silently convert a working villager into a courtier.

### 5.2 Preserve availability and autonomy

Initial offices add **no AI tasks**. NPC interaction is an optional view into the same server-side services used by the ledger.

When a supported Townstead snapshot exists, use current activity and needs to choose an availability message. Treat rest, collapse, urgent needs, combat, and dangerous conditions as higher priority than a ceremony or audience. Planned shifts alone do not prove the NPC is currently free.

Do not freeze, teleport, move, or force-face an NPC from the governance code. A conversation mod may own its own interaction lifecycle. Do not open a competing screen over an active conversation. The ledger supports asynchronous petitions when an NPC is unavailable.

### 5.3 Honors without a parallel reputation system

Implement honors as facts: recipient, award definition, awarding government, evidence receipt, and award/revocation status. Examples:

- **Friend of the Open Bell:** fulfilled a Serenum civic commission.
- **Keeper of the Winter Record:** completed a Lunari scholarly exchange.
- **Companion of the Living Craft:** recognized Madera service or a verified mastery milestone.
- **Guest of the Open Road:** earned diplomatic recognition in Anemosia.
- **Sworn Friend of the Greenwood:** fulfilled a Yew stewardship commission.

The names and thresholds are proposed content, not existing canon. Honors may unlock an audience, title, or specific quest condition. They must not create blanket merchant discounts, combat buffs, diplomatic immunity, universal friendship, or access to other mods' private inventories.

Award once per configured evidence scope. A forgeable item, client packet, self-written book, or repeated chunk load is not proof of service. Manual awards require named political permission and are visibly recorded as discretionary awards.

If MCA Reputation or an Ultima Factions integration is later added, implement a provider adapter against its actual code. This review does not establish those providers' APIs. Do not invent class names or assume that kingdom honor, villager opinion, and faction allegiance are equivalent.

## 6. Peaceful diplomacy and real commissions

### 6.1 Borrow the separation of concerns

Capitals separates agreement validation, authority, proposals, persistence, and processing. Preserve that general design. Its pair key canonicalizes the two political parties; its relation record separates score from diplomatic state. Ultima should likewise keep agreement state independent from personal opinion. A new numeric inter-kingdom reputation simulation is unnecessary for the first release. [C6–C8]

Use a canonical `KingdomPairKey` from two distinct, existing `ResourceLocation` IDs. Canonicalize storage order, but retain `proposer`, `recipient`, and separate obligations explicitly. Sorting parties must not reverse who promised what.

Agreement state machine:

`DRAFT → PROPOSED → RATIFIED → ACTIVE → EXPIRED or TERMINATED`

`PROPOSED` can also become `DECLINED` or `WITHDRAWN`. A ratified agreement can be `SUSPENDED` if a specific required capability disappears. Suspension does not erase its parties, signatures, or past fulfillment.

Every proposal freezes its terms, definition revision, parties, authorized signatories, obligations, expiry, and proposal revision. Both sides authorize the **same terms hash**. Editing terms invalidates previous signatures.

### 6.2 Initial agreement types

| Agreement | Playable consequence | Why it complements Townstead |
|---|---|---|
| Diplomatic Recognition | Opens envoy interactions and political information exchange between the two governments. | Gives travel and existing settlements wider context. |
| Hospitality Accord | Unlocks guest-related dialogue and provider-backed hospitality commissions at participating settlements. | Uses existing inns/cafes as destinations; provides no free beds, food, or needs bypass. |
| Scholarly Exchange | Unlocks record-delivery, research, or institution-recognition content through a quest provider. | Recognizes librarians and buildings already maintained by MCA/Townstead. |
| Craft and Market Charter | Makes a bounded set of real supply commissions available. | Rewards real production without generating village resources. |
| Civic Aid Compact | Permits voluntary, explicitly accepted relief commissions. | Lets players support villages without introducing a replacement needs simulation. |

Do not add a non-aggression label that promises to stop combat the mod does not control. A political peace pledge can be flavor only if labeled that way; combat enforcement belongs in a separately designed integration.

NPC governments can ratify through deterministic, data-defined policy checks run by the server. Begin with objective conditions such as recognition, office authority, agreement limits, and available commission providers. Show acceptance/rejection reasons. Do not repeatedly roll random acceptance by reopening a menu, depend on secretly fabricated reputation, or force a player to wait for an unloaded NPC to tick.

Player-led governments require the appropriate player or delegated council ratification. One player cannot sign on behalf of another government merely by speaking to its envoy. Allow treaty proposals to remain pending while a player is offline.

### 6.3 Trade must not bypass production

In the inspected Capitals source, `CapitalTradeProfileService.createShipment` creates new `ItemStack`s from weighted resources. `CapitalTradeExchangeService` deposits those shipments on a recurring interval. That implementation supplies resources independently of Townstead's actual worker production. Do not port it. [C11–C12]

The initial **Craft and Market Charter** changes eligibility, not inventory:

1. A ratified charter enables a defined commission pool between participating settlements.
2. A supported quest provider owns the offer, acceptance, objectives, item checks, delivery, and rewards.
3. Ultima records a provider-qualified quest-instance ID and a stable completion receipt.
4. Completion can satisfy a charter obligation or justify an honor once.
5. Townstead remains responsible for producing and consuming village goods.

If no quest provider exists, show the charter's political terms and available opportunities, but label mechanical commissions unavailable. Never display a working Accept/Deliver button whose behavior is unimplemented.

Do not create passive shipments, fabricate inventory, remove supplies from kitchen storage, or infer delivery from a chest's contents. A later physical logistics project would need separate authorization and a design for inventory ownership, reserved quantities, crash recovery, and Townstead's consumption claims; it is outside this instruction set.

### 6.4 Petitions and patronage

A petition is a bounded request to a political institution, not a quest engine.

Implement four petition kinds first:

- Recognize a civic institution in an existing building.
- Nominate a resident or player for an honor.
- Request an introduction or agreement proposal.
- Sponsor an available provider-backed commission.

Lifecycle: `SUBMITTED → UNDER_REVIEW → APPROVED / DECLINED / WITHDRAWN / EXPIRED`.

Store petitioner, intended authority, kingdom/settlement, request type, typed evidence references, submitted terms, and decision reason. A petition's approval cannot make an unfinished building complete or a missing delivery real. For work-bearing petitions, approval opens the commission; fulfillment is a separate verified event.

Suggested institution recognition:

| Existing institution | Political recognition | What changes |
|---|---|---|
| Library | Charter of Learning | Enables relevant scholarly commissions and a civic badge. |
| Inn | House of Hospitality | Eligible destination for hospitality content. |
| Established workshop | Guild Charter | Eligible source/host for craft-related content. |
| Existing communal kitchen | Civic Patronage | Optional recognition and relief-commission context. |

Use Townstead/MCA's recognized building reference, dimension, village identity, and type. No new block scanner or replacement kitchen/inn/library. If an exact provider lacks building enumeration, let the user nominate a loaded location and resolve it with a supported query. `TownsteadAPI.buildingAt` is a point query, not a whole-settlement institution index. [T2]

If the building disappears, suspend the recognition's dependent content after verified evidence. Unloaded is not demolished. A rebuilt building can regain recognition through explicit revalidation; never transfer recognition to a nearby unrelated building solely because it occupies a similar position.

## 7. Succession, regency, and political houses

### 7.1 Succession is a later, isolated milestone

Capitals' source uses persistent family-node existence/deceased state and ordered candidates instead of relying only on a loaded entity. That is a valuable pattern. Its automatic candidate validation is not a Townstead-aware adulthood policy; implement that distinction deliberately. [C10]

Add succession after appointments, authority, save/load, and political events are reliable.

Supported mechanisms:

| Constitution | Default transition |
|---|---|
| Appointed office | Named successor, then an authorized appointing body. |
| Elective council leadership | Restricted election among eligible nominees; existing voters are defined by the charter. |
| Optional hereditary crown | Explicitly designated heir, then a deterministic list from authoritative family facts, then interregnum. |

An in-game **Succession Preview** lists candidates and explains inclusion, exclusion, unknown status, and tie-breaks. Use stable ordering: constitutional priority, nomination/appointment sequence, then UUID. Never depend on hash-map iteration or entity load order.

### 7.2 Lifecycle rules

- Trigger on confirmed NPC death, validated abdication/removal, or a completed constitutional decision.
- Absence, chunk unloading, dimension travel, disconnects, stale evidence, and missing family nodes are not proof of death.
- Survival-player death does not vacate office by default. Logout never does. Hardcore handling is an explicit server rule.
- Adulthood comes from the selected provider's semantic life stage. Do not assume a fixed number of days or parse a localized age label.
- A child may be an acknowledged heir, but cannot perform adult office actions. A regent has explicit limited authority; the original heir identity is retained. If no adult regent is available, use interregnum.
- Immortal/ageless rulers remain valid until a real transition occurs. Never impose aging or automatic death to make the system advance.
- A revived former ruler does not silently replace their successor. Restoration is an explicit political decision.
- Unknown eligibility pauses the affected automatic decision; it does not skip a legitimate candidate as if dead.
- Commit the new holder, revoke old mandates, and record the transition as one political transaction. Replaying the same death/abdication input must not repeat succession.

Existing treaties belong to the government, not its current actor. They normally survive succession. A new ruler can terminate them only through their terms and authorization rules.

### 7.3 Houses without rewriting personal identity

Optional political houses contain a stable house ID, display label, motto, heraldic reference, founder, membership, and honorary relationships to offices. They do not own kinship.

Default membership changes are explicit. A suggested relative can be displayed by reading MCA family data, but automatic surname/marriage rewriting is excluded. House membership confers neither a profession nor land ownership, and should not default to hereditary authority in Madera or Yew.

Do not copy Capitals' marriage surname services. Render `House Ashbell` as a separate political affiliation rather than changing a person's actual MCA name. [C14]

## 8. Political events, Townstead Chronicles, and dialogue

### 8.1 Keep facts, narrative, and permissions separate

Ultima must persist the mechanical state it owns: office holders, charter terms, signatures, honors, and current agreements. It also needs a bounded transaction record for reconciliation and event delivery. This is not a new village-history simulation.

Townstead's development `Chronicles` class explicitly separates recorded facts from remembered/believed accounts; its comments prohibit using memories and sentiment as mechanical unlocks. Preserve that rule. A rumor about a coronation cannot grant a title, and a rumor about betrayal cannot cancel a treaty. [T4–T6]

New political event types:

- `government_constituted`
- `capital_designated` / `capital_relocated`
- `office_appointed` / `office_vacated`
- `succession_completed` / `interregnum_started`
- `agreement_proposed` / `agreement_ratified` / `agreement_terminated`
- `institution_recognized` / `recognition_suspended`
- `commission_fulfilled`
- `honor_awarded`

Publish only political consequences. Townstead already observes ordinary life events in its development system. If a ruler dies, Ultima emits the office vacancy and succession, not a second generic villager death.

Event envelope fields:

| Field | Purpose |
|---|---|
| `eventId` | Stable UUID assigned once at political commit. |
| `transactionId` | Correlates state change and provider receipts. |
| `type` and `schema` | Namespaced event type and payload version. |
| `kingdomIds`, `settlementIds` | Political scope using canonical references. |
| `actorRefs` | Typed UUID identities; no reliance on display names. |
| `occurredGameTime` | Monotonic mechanical timestamp. |
| `calendarSnapshot` | Optional provider ID/date for human-facing historical display. |
| `translationKey` and typed arguments | Localized presentation; historical names can be snapshotted without changing identity. |
| `visibility` | Public, parties-only, office-restricted, or private. |
| `causationId`, `producer` | Stops feedback loops and duplicate processing. |

### 8.2 Chronicles bridge: capability-based, not assumed

The inspected development `ChronicleEmitter.emit` requires a live actor, resolves the actor's MCA village, performs template selection/cooldown, and gathers witnesses. `emitTemplate` is documented as a direct/debug path that skips cooldown. `Chronicles.record` assigns an event ID and queues persistence. None of these observations proves a supported external API with idempotent delivery. [T4–T5]

Therefore:

1. Define a small Ultima-owned `PoliticalEventSink` interface.
2. Implement the Townstead sink only against a pinned and tested capability surface. Keep Townstead classes out of core and public APIs.
3. Do not pass a dummy actor or force-load a ruler to manufacture a witness context.
4. Do not use a dead ruler as a live `emit` actor for an offline succession.
5. For political events without a suitable live actor, require a supported external fact-ingestion path that can represent the actual settlement, participants, and visibility. If it is absent, retain the bounded pending event and show current political state without falsely claiming narrative delivery.
6. Use stable source event IDs and receiver-side deduplication where supported. The current inspected methods do not by themselves guarantee crash-safe exactly-once ingestion.
7. Persist sink acknowledgments with pending deliveries. If the sink may have accepted an event before a crash but cannot confirm it by source ID, flag delivery as uncertain and require reconciliation; do not automatically replay it indefinitely.
8. Let Townstead apply witness, diffusion, sentiment, and mood rules. Do not grant all villagers instant knowledge.
9. Do not relay Townstead's own political echo back into the same sink. Keep producer/causation information and an explicit one-way ownership rule.

The coding agent may implement a version-specific bridge in this repository, but must not silently modify Townstead or assume an upstream API change has been accepted. A missing safe ingestion path blocks that integration feature, not core governments and agreements.

### 8.3 Older or unsupported Townstead versions

With Townstead installed but no supported Chronicles bridge, display current political facts and pending decisions in the ledger. Keep only the bounded operational records needed to explain recent actions and reconcile deliveries. Do not introduce a general-purpose Chronicle book, village biography archive, rumor engine, or mood simulation as a fallback.

When Chronicles later becomes available, do not dump unlimited historical events into villagers' memories. Backfill only explicitly approved public political facts, preserve their original times, and mark historical imports appropriately if the provider supports that distinction.

### 8.4 Dialogue and privacy

Expose public political context for optional conversation/quest adapters:

- `ultima.politics.government_type`
- `ultima.politics.capital_settlement_id`
- `ultima.politics.office_ids`
- `ultima.politics.public_honor_ids`
- Public ratified agreement IDs relevant to the current settlement.

These are proposed fields, not existing API keys. Preserve the existing `ultima.kingdom_id`, residence, and origin context.

Add `getPoliticalContext(viewer, subject, purpose)` or an equivalent typed query so private information can be filtered. An NPC's knowledge of a past event must be obtained through Townstead's knowledge capability when available; a government fact lookup alone is not permission to narrate secret news through that NPC.

Examples of additive topics: “Who represents this settlement?”, “What agreements have been announced?”, “How can I petition the council?”, and “What does this honor recognize?” Do not replace everyday conversation, give every NPC perfect geopolitical knowledge, or hijack Townstead's interaction menu.

## 9. Technical architecture and file-level instructions

All new class names and signatures below are proposed. Existing names explicitly identified in §1 are verified against the inspected repository.

### 9.1 Extend the project without enlarging the core service indefinitely

Use packages under `com.ultimakingdoms`:

| Proposed area | Responsibilities |
|---|---|
| `politics/GovernmentService`, `GovernmentRecord` | Charter and government lifecycle, capital seat, constitutional rules. |
| `politics/OfficeService`, `OfficeAssignment`, `AuthorityService` | Appointments, delegated mandates, scope, authorization. |
| `politics/SuccessionService`, `SuccessionPreview` | Deterministic candidate evaluation and explicit transitions. |
| `diplomacy/AgreementService`, `AgreementRecord`, `KingdomPairKey` | Proposals, signatures, terms, active agreements, expiration. |
| `petition/PetitionService`, `PetitionRecord` | Request validation, evidence, review, decisions. |
| `institution/InstitutionRecognitionService` | Sidecar recognition of provider-owned buildings. |
| `honor/HonorService`, `HonorAward` | Evidence-backed and discretionary recognition. |
| `politics/data/PoliticalSavedData` | Versioned political state, deduplication, bounded outbox and receipts. |
| `politics/event/PoliticalEventDispatcher` | Post-commit events and provider delivery. |
| `compat/townstead/` | Exact-version read-only snapshots, calendar, optional Chronicles adapter. |
| `compat/quest/`, `compat/reputation/` | Optional provider adapters; no invented external API assumptions. |
| `api/politics/` | Immutable queries, command requests/results, versioned event contracts. |
| `client/politics/`, `network/politics/` | Ledger tabs, bounded payloads, server-authorized actions. |

Keep `KingdomsServiceImpl` responsible for settlement/civic identity. Political services depend on `KingdomsService`; the core must not import Townstead, Capitals, quest, or reputation classes.

Modify the existing project at these concrete integration points; the paths below are relative to `src/main/java/com/ultimakingdoms/` unless stated otherwise:

| Existing file/area | Required change |
|---|---|
| `UltimaKingdoms.java` and `core/ApiBootstrap.java` | Inspect the current lifecycle wiring, then attach and close server-scoped political services alongside the identity service. Never initialize a world-backed singleton during static class loading. |
| `core/KingdomsServiceImpl.java` | Add narrow merge/reassignment preflight and post-commit notifications. Keep political rules outside the identity implementation. |
| `api/event/` | Add merge lifecycle contracts with immutable snapshots. Use a separate political API/service instead of forcing new abstract methods onto every existing `KingdomsService` implementation. |
| `data/DefinitionRegistry.java` and `data/DefinitionSnapshot.java` | Integrate a separately validated political-definition snapshot into reload lifecycle; retain atomic publication and current identity definitions. |
| `config/UltimaKingdomsConfig.java` | Add bounded political settings or delegate to a dedicated political config class. Preserve existing discovery and civic defaults. |
| `command/UltimaCommands.java` | Register political inspection and explicit operator bootstrap/repair commands. Route mutations through the same authorization service as packets. |
| `network/NetworkHandler.java` | Register bounded political requests/results, bump protocol when needed, and clear request caches at lifecycle boundaries. |
| `client/VillageLedgerScreen.java` and `client/ClientPresentationState.java` | Extend ledger navigation and disposable client caches without duplicating settlement identity or overlay ownership. |
| `src/main/resources/assets/ultima_kingdoms/lang/en_us.json` | Add translated titles, states, permission failures, and missing-capability explanations. |
| `build.gradle`, existing test source sets, and `tools/test/` | Preserve API-artifact isolation and packaged-runtime verification; add the meaningful political and compatibility tests in §12. |

Suggested new commands, all routed through services:

```text
/ultima politics inspect <kingdom>
/ultima politics bootstrap <kingdom> <settlement> <profile>
/ultima politics appoint <kingdom> <office> <actor>
/ultima politics succession preview <kingdom>
/ultima politics diagnose
/ultima politics reconcile <record>
```

Inspection output is visibility-filtered; bootstrap and repair require operator permission level 2. Player-level office actions require the appropriate mandate even when a command is used. Provide dry-run output for reconciliation; never make a diagnostic command silently mutate political state.

### 9.2 Minimal sidecar model

Store political state in a separate overworld SavedData entry, for example `ultima_kingdoms_politics`, schema 1. Do not repurpose settlement schema 1 fields to mean political ownership.

```java
// Proposed domain sketches; adapt to the project's actual coding style.
record PersonRef(UUID id, PersonKind kind) {}
record GovernmentKey(ResourceLocation kingdomId) {}
record BuildingRef(ResourceLocation provider, ResourceLocation dimension,
                   String providerVillageId, String providerBuildingId) {}
record EvidenceRef(ResourceLocation provider, String instanceId,
                   String receiptId, long verifiedAt) {}
record PoliticalCommand(UUID requestId, long expectedRevision,
                        ResourceLocation action, PoliticalPayload payload) {}
```

The sender is obtained from the server context, never trusted from `PoliticalCommand`. `PoliticalPayload` is a closed set of bounded typed records, not arbitrary NBT or Java serialization.

Required records include:

- Government: kingdom ID, charter definition/revision, constitution snapshot, capital UUID, state, offices, mandates, and revision.
- Agreement: UUID, canonical parties, directed obligations, terms snapshot/hash, signatures, lifecycle, mechanical deadlines, and revision.
- Petition: UUID, requester, authority scope, type, evidence, status, decision, and revision.
- Recognition/honor: UUID, target, scope, definition revision, evidence, lifecycle, and revision.
- Transaction/outbox: durable event UUIDs, causation, delivery state, bounded receipts, and acknowledgment status.

Store UUID references and human-readable snapshots for different purposes. Renames update current UI labels but do not rewrite historical wording or change record keys.

### 9.3 Townstead capability contract

Define an adapter exposing independent capabilities such as:

```java
enum TownsteadCapability {
    VILLAGER_SNAPSHOT, LIFE_STAGE, SCHEDULE, NEEDS,
    BUILDING_AT_POSITION, CALENDAR, POLITICAL_EVENT_INGESTION,
    KNOWN_POLITICAL_NEWS
}
```

Return a result with value, source/version, observation time, and status (`KNOWN`, `UNKNOWN`, `UNSUPPORTED`, `STALE`). Do not collapse all four into an empty list or zero.

The inspected main-source API provides `TownsteadAPI.entity`, `villager`, `calendar`, and `buildingAt`, plus read-only record types. Prefer the broad Minecraft `Entity` entry point inside the adapter where appropriate. Do not directly link these classes from core. The player snapshot contains placeholder fields for various villager-specific values; do not interpret those zero values as a starving/collapsed player or valid villager profession evidence. [T2–T3]

If reflection is required to preserve optional loading, resolve and validate handles once at startup. Disable only the failing capability with one useful diagnostic. Do not repeatedly search for method names on every entity tick, use arbitrary reflective field writes, or swallow all errors as empty success.

### 9.4 Settlement mapping, merges, and reclassification

1. Resolve provider village identity using existing MCA external references and dimension. Do not assume an integer MCA village ID is globally unique.
2. Add an explicit way to expose all relevant external aliases when merged settlements have multiple provider references; `SettlementView.externalRefs()` alone may not expose the full internal merged reference collection.
3. Add a post-commit `SettlementMergedEvent` with source UUID, target UUID, and relevant snapshots. Provide a narrowly scoped preflight check for sidecar conflicts before applying a merge.
4. Canonicalize all settlement references on load and query. Update capital seats, recognition, open petitions, and commission contexts through redirects.
5. When merging two independently recognized institutions or political seats creates a conflict, block the destructive merge pending an explicit resolution. Do not silently select whichever record is iterated first.
6. Kingdom reassignment of a capital settlement requires a preflight decision: designate a replacement capital or explicitly leave the old government without a seat. It must not transfer that entire kingdom's government into the destination kingdom.
7. Revalidate settlement-scoped recognition and open commission eligibility after reassignment. Preserve original award evidence and historical civic origin.
8. Agreement parties are kingdom IDs, so moving or renaming a capital does not create a new treaty party.

Publish post-commit merge/reassignment events only after the new state is coherent. Listeners must receive a stable snapshot and cannot observe half-updated references.

### 9.5 Reload, removals, and serialization

- Definitions reload atomically from a fully validated snapshot.
- Existing offices and agreements retain the accepted definition/terms revision. A datapack change must not retroactively alter a signed obligation or appoint a new ruler.
- Removed definitions make affected records dormant/unavailable and retain their original references. Restoring the definition permits explicit revalidation.
- Never rewrite missing kingdoms to Serenum as a political migration fallback.
- Refuse unsupported future political schemas without overwriting the original file, following the existing settlement persistence approach.
- For malformed entries, retain diagnostic/quarantine information and avoid destructive blanket reinitialization.
- Keep migrations explicit, versioned, and idempotent. Test old settlement-only worlds: adding politics creates empty political state while preserving all settlement UUIDs and identities.
- Political mutations are applied on the server thread to one authoritative state graph. Mark dirty once per logical transaction and dispatch external effects afterward.

Normal Minecraft SavedData is not a cross-mod ACID database. Do not claim crash-atomic exactly-once reward delivery merely because two records were marked dirty. Use provider-side stable completion receipts and idempotent reward ownership; where unavailable, make that integration unavailable or require explicit reconciliation.

### 9.6 Time semantics

Use server game time for mechanical cooldowns, protocol deadlines, and lease durations. Do not use mutable `/time set` day time for authorization or treaty expiration. Each timer records its clock semantics; avoid mixing Townstead day counts with game ticks.

Use Townstead calendar snapshots for human-facing dates when supported. Default treaty durations are expressed in server simulation ticks/days with a clear UI description. Calendar-based ceremonial dates are presentation-only until a separately tested calendar scheduling adapter exists. Sleeping, changing calendar profiles, or removing a calendar integration must not silently expire every agreement.

Unloaded NPCs do not pause government records. Paused single-player worlds and stopped servers do pause simulation-time deadlines. Offline real-world expiration is not part of the default design.

## 10. Data definitions, configuration, and UI

### 10.1 Data-driven definitions

Add resource families beneath `data/<namespace>/ultima_kingdoms/`:

| Family | Defines |
|---|---|
| `governments/` | Constitution, title labels, allowed offices, succession policy. |
| `offices/` | Scope, permissions, occupancy limits, eligibility. |
| `agreements/` | Typed terms, prerequisites, durations, commission-pool links. |
| `petitions/` | Request schema, authority, required evidence, decision rules. |
| `honors/` | Display, evidence requirements, uniqueness, optional content access. |
| `institution_charters/` | Supported provider building types and resulting political eligibility. |

Example **proposed** government schema:

```json
{
  "schema": 1,
  "kingdom": "ultima_kingdoms:yew",
  "constitution": "ultima_kingdoms:warden_council",
  "leader_title": "ultima_kingdoms.title.high_warden",
  "capital_policy": "explicit_designation",
  "succession": "council_appointment",
  "offices": [
    "ultima_kingdoms:high_warden",
    "ultima_kingdoms:envoy",
    "ultima_kingdoms:herald"
  ],
  "founding_authority": "operator_or_scenario"
}
```

Example **proposed** agreement schema:

```json
{
  "schema": 1,
  "type": "ultima_kingdoms:scholarly_exchange",
  "requires": ["ultima_kingdoms:diplomatic_recognition"],
  "ratification": "both_governments",
  "duration": {"clock": "game_time", "ticks": 720000},
  "commission_pool": "ultima_kingdoms:scholarly_exchange",
  "inventory_effects": "none",
  "visibility": "public_after_ratification"
}
```

These examples are implementation targets, not files already accepted by the current mod. Validate all references and forbid arbitrary commands/scripts in definitions. Missing optional quest content disables its commission link without invalidating the political agreement itself.

Eligibility conditions must use stable IDs and a documented typed predicate registry. Use provider-qualified item tags and profession/building IDs; do not guess by English name substrings as a general compatibility strategy.

### 10.2 Configuration defaults

| Setting | Proposed default | Rationale |
|---|---|---|
| Government mode | `native`, unless conflict detected | The expansion works without Capitals. |
| Player founding | Explicit operator/scenario authority | Avoids first-visitor sovereignty. |
| Capital auto-promotion | Off | Population alone must not reorganize the world. |
| Automatic hereditary succession | Off until profile enables it | Respects council and appointed institutions. |
| Office AI/forced attendance | Not implemented | Preserves Townstead scheduling and needs. |
| Trade inventory automation | Not implemented | Preserves production and inventory ownership. |
| Chronicles integration | Auto-detect supported exact capability | No dependency on an unreleased branch. |
| Political chat announcements | Off by default; ledger/NPC notices available | Avoids interrupting normal village play. |
| Concurrent petitions | 3 per player per kingdom | Limits spam while retaining useful choice. |
| Agreement proposals | 1 per type and kingdom pair | Prevents duplicate pending negotiations. |
| Operational journal | 256 recent entries per kingdom | Bounded diagnostics; active obligations are stored separately. |
| Pending event deliveries | 512 per server, persisted | Bounded backlog; report overflow and pause affected exports. |

The numbers above are initial tuning choices, not sourced balance constants. Required deduplication for still-active agreements, honors, and commissions lives on those records; pruning the recent journal must never permit rewards to repeat.

Do not silently discard failed critical deliveries on overflow. Core state remains authoritative; surface the export problem to operators and coalesce only explicitly nonessential notifications.

### 10.3 Extend the existing ledger

Add a Kingdom view with restrained tabs:

- **Overview:** existing identity, capital seat, constitution, public leader/offices.
- **Council:** appointments and permission-appropriate actions.
- **Agreements:** public active agreements; private proposals visible only to authorized parties.
- **Petitions:** the viewer's requests and authorized review queue.

Display institutional recognition on the existing settlement detail panel. Show honors on relevant person/kingdom panels. Link to Townstead's archive only when an actual supported UI-opening capability exists; do not promise a nonexistent deep link.

Prefer one existing ledger over a collection of new management items. A later physical charter/seal may be a presentation prop or receipt; stealing/crafting it does not grant authority.

Every disabled action explains the concrete reason: “Requires an envoy mandate,” “Awaiting the other government's signature,” or “Building status unavailable.” Distinguish no evidence from a failed condition.

Use localized components, long-name truncation/tooltips, keyboard navigation, and scrollable lists. Verify normal and small GUI scales. Titles should support neutral labels and configurable styles without assuming a binary display policy in the domain model.

### 10.4 Server actions and network validation

Reuse the existing `SimpleChannel` approach and increment the protocol when packet contracts change. Register fixed packet directions and keep handlers on the main server thread. Do not import newer-loader payload examples directly into Forge 1.20.1. [U5]

For each mutation verify:

1. Authenticated sender and connection state.
2. Requested action is known and rate-limited.
3. Kingdom, settlement, actor, and institution references exist and canonicalize correctly.
4. Permission and delegation scope at the moment of commit.
5. Expected record revision; stale UI receives a refreshable conflict result.
6. Definition availability, life status, typed evidence, and relevant capability health.
7. Proposal terms hash and the counterpart signature when applicable.
8. Request idempotency: a replay returns the prior result without creating another award or event.

Ledger petitions can be remote by design. NPC audience actions additionally require a living, loaded, appropriate nearby NPC in the sender's dimension and a valid interaction context. Do not apply a proximity requirement to an intentionally asynchronous ledger queue.

Suggested payload limits: 20 records per page, 64-character short labels, 512-character petition explanations, bounded actor/evidence lists, and a conservative total packet byte cap. Truncate or reject user text before serialization. Do not synchronize private proposals, full family trees, all NPC needs, or the entire world political database on login.

### 10.5 Optional coexistence with MCA: Capitals

Co-installation is not required. If `mcacapitals` is detected, do not silently run two authorities for the same ruler, titles, succession, and diplomacy.

Until a separately tested provider adapter exists, preserve loaded Ultima political data but disable new conflicting native governance mutations and show a clear diagnostic. Settlement identity continues functioning. Do not alter Capitals config, import its state, or delete either mod's data automatically.

A future explicit provider mode could read Capitals' government facts while retaining Ultima settlement identity. It requires an administrator-approved mapping from Capitals' capital IDs to Ultima kingdom/settlement IDs and reconciliation of multiple Capitals within one Ultima kingdom. That is a separate integration, not a hidden assumption of this guide.

## 11. Implementation sequence and reviewable milestones

### Phase 0 — dependency contract and extension points

Deliver:

- `docs/politics/compatibility-matrix.md` with exact versions, hashes, proven capabilities, and unsupported combinations.
- A short `docs/politics/ownership.md` recording the boundaries in §2.
- A verified Townstead read adapter or a deliberately unsupported capability result for the selected tuple.
- Merge/reassignment preflight and post-commit lifecycle hooks in Ultima core.
- Versioned political persistence and a test fixture for an existing settlement-only save.

**Gate:** core settlement behavior still works with no optional mods; a selected MCA/Townstead tuple boots and civic identity remains stable. An unverified tuple cannot be advertised as supported.

### Phase 1 — capital charter, offices, and ledger

Implement government definitions, explicit bootstrap, capital designation, office appointments/mandates, read-only NPC titles, and ledger views. Add public political state queries and a bounded operational journal/outbox.

**Playable result:** an authorized player can constitute Serenum's government at an existing settlement, appoint a librarian as Keeper of Records, restart, and see the same office while that NPC continues its ordinary Townstead work.

**Gate:** office changes modify only Ultima state. No Townstead professions, needs, family data, or schedules change.

### Phase 2 — petitions, recognition, and peaceful agreements

Implement typed petition requests, building recognition through verified references, two-sided treaty ratification, permissions, expiration, and public/private views. Add honors with evidence and discretionary-award distinctions.

**Playable result:** two existing governments establish recognition and a scholarly/hospitality agreement; players submit and resolve meaningful civic petitions. No resources are generated.

**Gate:** two simultaneous ratifications, stale screens, duplicate requests, offline parties, and missing capabilities produce deterministic safe results.

### Phase 3 — Townstead narrative and optional quest adapters

Implement only adapters whose actual target APIs and artifacts have been inspected. Add a political event sink, tested source-ID deduplication/reconciliation, relevant Townstead event definitions where supported, and optional provider-backed commissions.

**Playable result:** a real completed commission produces one honor and one acknowledged political narrative event; Townstead controls who learns it.

**Gate:** no duplicate quest rewards, event echo loops, fabricated knowledge, or unsupported external API claims. If Chronicles lacks a safe ingestion contract, ship current political-state views and document the unavailable bridge rather than replacing Townstead's system.

### Phase 4 — succession and constitutional continuity

Add previewable named succession, council appointment/election, optional hereditary policy, regency, confirmed lifecycle input, and interregnum permissions.

**Playable result:** a confirmed ruler death or abdication transitions government once, preserves the kingdom/capital identity and active treaties, and respects Townstead life stages.

**Gate:** unload, restart, absence, immortality, and ordinary player respawn cannot falsely trigger a transition.

### Phase 5 — optional houses and content refinement

Add political houses/mottos, additional honor sets, kingdom-specific petition writing, and art/text polish. Keep mechanics shared and data-driven.

Do not add military campaigns, forced family systems, automated logistics, or a parallel chronicle under this phase. Those remain explicitly outside scope.

## 12. Verification and acceptance criteria

### 12.1 Required automated coverage

Use focused domain tests and meaningful integration tests. Extend the existing build/test setup rather than constructing an unrelated framework. The repository notes that MCA development GameTests have an upstream mixin issue and that its exact adapter was tested in a packaged production runtime; keep a packaged-runtime verification route. [U1]

| Scenario | Required result |
|---|---|
| Old settlement-only save gains politics | Existing settlement UUIDs, names, kingdoms, bounds, aliases, and civic origins are identical. |
| Rename or datapack biome-rule change | No government/treaty identity changes; established settlement assignments persist. |
| Two concurrent capital designations | Exactly one valid seat; losing action returns a revision conflict. |
| Forge C2S packet names another player as actor | Authority remains the authenticated sender; unauthorized action rejected. |
| Replayed award/ratification/completion receipt | One result, one state transition, no extra reward. |
| Proposal terms edited after a signature | Prior signature invalidated; both governments must approve identical terms. |
| Unloaded office holder | Appointment retained; physical audience unavailable; ledger still usable. |
| Sleeping or collapsed Townstead NPC | No forced audience, pathing, wakeup, schedule change, or needs mutation. |
| Profession tier differs from trade level | Eligibility uses the documented provider field, not a guessed substitute. |
| Unknown adulthood/family status | Automatic succession pauses with a clear reason. |
| Confirmed ruler death delivered twice | One vacancy/succession transaction. |
| Player logs out or respawns | No unintended vacancy or succession. |
| Immortal ruler | No artificial aging/death timer. |
| Capital is renamed, merged, or reassigned | References reconcile or operation is blocked with a concrete conflict; no orphaned government. |
| Same MCA village number in two dimensions | Distinct provider references remain distinct. |
| Townstead version lacks snapshot API | Unsupported capability; no classloading crash or fake zero-valued evidence. |
| Chronicles source event accepted before crash | Confirmed dedupe or explicit uncertain-delivery reconciliation, never blind repeated injection. |
| Chronicles unavailable/full | Political state remains valid; export backlog is bounded and diagnosed. |
| Private agreement proposal | Never appears to unrelated players or public NPC dialogue. |
| Rumor falsely alleges political change | No mechanical office/treaty/permission change. |
| Definition removed/reloaded | Existing terms retained; affected actions become dormant, not rewritten. |
| `/time set`, sleep, calendar switch | Mechanical deadlines follow their documented clock; no accidental mass expiry. |
| Simulated trade agreement cycle | No new `ItemStack`s, chest changes, or passive resource deposits. |
| Simultaneous Capitals install | Conflict mode preserves data and avoids duplicate native political mutations. |

### 12.2 Pack verification matrix

Test the exact declared versions in these configurations:

1. Ultima only.
2. Ultima + supported MCA.
3. Ultima + supported MCA + selected released Townstead.
4. Exact Chronicles-capable Townstead build, only if supported.
5. Supported quest provider present and absent.
6. Supported conversation/reputation adapter present and absent, if those integrations are implemented.
7. Dedicated server with two clients, including one unauthorized player.
8. Capitals co-install detection, if the version combination itself can load.

Do not claim full compatibility from compilation. Record server startup, world load/save/restart, UI interaction, packet authorization, NPC behavior, and adapter failure evidence separately.

For the Townstead preservation test, compare relevant NPC state before and after office/charter actions: profession, progress, schedule, birth/life stage, home, family, clothing, and needs. Needs naturally change over time, so distinguish ordinary simulation from changes caused by Ultima. Observe a complete work/rest cycle with political UI actions in progress.

### 12.3 Performance requirements

- No whole-world NPC or settlement scan per tick.
- No chunk tickets or forced loading for audiences, succession, or narrative delivery.
- Reconcile relevant records on events plus a bounded queue; use the existing spatial/identity infrastructure.
- Cache read snapshots briefly with explicit staleness; refresh on interaction when necessary.
- Keep archive queries off the tick thread where the provider supplies asynchronous access. Return to the server thread before mutating state or sending results tied to live world context.
- Bound packet pages, journal size, proposals, petitions, and retries. Keep active mechanical obligations independent from journal retention.
- Clear server-scoped caches, queues, and player request state on disconnect/server shutdown.
- Emit one useful incompatibility diagnostic per failing capability, not a log line on every NPC tick.

### 12.4 Completion criteria

The first release is complete when a player can establish an authorized capital seat, appoint useful civic offices, submit and decide petitions, recognize an existing institution, ratify a peaceful agreement with another government, and retain all of it across restarts without altering Townstead's daily simulation.

If optional narrative/quest adapters are included, they must demonstrate actual delivery and deduplication with the declared versions. Unavailable adapters are explicitly labeled, not simulated through placeholder success.

Finish with a change summary that lists implemented features, exact supported versions, tests actually run, and remaining gated integrations. Do not call succession, houses, Chronicles ingestion, or commissions complete merely because their interfaces exist.

## 13. Instructions to the implementing agent

1. Read the current repository instructions and compare the inspected snapshot with current code before editing.
2. Preserve the existing settlement and civic API contracts. Implement the political layer as new services and sidecar data.
3. Start with Phase 0, then complete each playable milestone before expanding scope.
4. Keep Townstead as the owner of needs, work, age, buildings, calendar, spirit, memories, and gossip. Read through supported adapters; do not take over those systems.
5. Reimplement the selected concepts for Ultima's model. Do not transplant Capitals' capital-per-village assumptions, generated trade goods, surname changes, clothing swaps, justice, or campaign code.
6. Preserve the public API boundary and optional-dependency classloading safety.
7. Make political authority explicit, server-validated, revocable, and scope-limited. A title item is never a credential.
8. Persist accepted terms and identities; make transitions idempotent and migration-safe.
9. Verify the relevant source and artifacts before naming any optional integration supported. Record a missing capability honestly and keep unaffected features working.
10. Deliver implementation, documentation, data examples, and evidence from the required tests. Keep future features clearly separated from shipped behavior.

The current Ultima repository declares All Rights Reserved; Capitals source declares GPL-3.0-or-later and Townstead declares GPL-3.0. For this task, write original implementation and original prose/assets inspired by the reviewed behavior. Do not copy source or artwork into Ultima under an assumption that the existing project licensing already permits redistribution. If direct reuse becomes necessary, resolve that specific licensing decision before incorporating it. [U1, C2, T1]

## 14. Source ledger

Repository links below are pinned to inspected commits. File paths describe verified source, while new classes and resource schemas elsewhere in this guide are proposed.

### Ultima Kingdoms

- **[U1]** [README: current features, exact MCA pin, API/build boundaries, testing notes, license](https://github.com/otectus/UltimaKingdoms/blob/fe05c7c952367d38e699e280f019cc7321ecfc64/README.md)
- **[U2]** [KingdomsService: existing public service contract](https://github.com/otectus/UltimaKingdoms/blob/fe05c7c952367d38e699e280f019cc7321ecfc64/src/main/java/com/ultimakingdoms/api/KingdomsService.java)
- **[U3]** [KingdomsServiceImpl: civic context, lifecycle events, merge behavior](https://github.com/otectus/UltimaKingdoms/blob/fe05c7c952367d38e699e280f019cc7321ecfc64/src/main/java/com/ultimakingdoms/core/KingdomsServiceImpl.java)
- **[U4]** [SettlementSavedData: schema and redirects](https://github.com/otectus/UltimaKingdoms/blob/fe05c7c952367d38e699e280f019cc7321ecfc64/src/main/java/com/ultimakingdoms/core/SettlementSavedData.java)
- **[U5]** [NetworkHandler: channel protocol, paging, request validation](https://github.com/otectus/UltimaKingdoms/blob/fe05c7c952367d38e699e280f019cc7321ecfc64/src/main/java/com/ultimakingdoms/network/NetworkHandler.java)
- **[U6]** [McaAccess: exact version and relocated package surface](https://github.com/otectus/UltimaKingdoms/blob/fe05c7c952367d38e699e280f019cc7321ecfc64/src/main/java/com/ultimakingdoms/compat/mca/McaAccess.java)
- **[U7]** [Design specification: proposed five-kingdom themes](https://github.com/otectus/UltimaKingdoms/blob/fe05c7c952367d38e699e280f019cc7321ecfc64/docs/ultima_kingdoms.md)

### MCA: Capitals

- **[C1]** [CurseForge: advertised features and published-file listing](https://www.curseforge.com/minecraft/mc-mods/mca-capitals)
- **[C2]** [Forge 1.3.8 source properties: target versions and license identifier](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/gradle.properties)
- **[C3]** [CapitalPopulationScanner: population and charter workflow](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalPopulationScanner.java)
- **[C4]** [CapitalCourtBuilder: deriving court membership](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalCourtBuilder.java)
- **[C5]** [CapitalCourtApplier: applying computed roles and heir](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalCourtApplier.java)
- **[C6]** [CapitalDiplomaticAgreementService: agreement service boundaries](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalDiplomaticAgreementService.java)
- **[C7]** [CapitalDiplomaticAgreementValidation: audience, authority, and prerequisite checks](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalDiplomaticAgreementValidation.java)
- **[C8]** [CapitalRelationRecord: persistent score/state/history](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/data/CapitalRelationRecord.java) and [CapitalRelationKey: canonical pair](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/data/CapitalRelationKey.java)
- **[C9]** [CapitalChronicleService: semantic entries and herald delivery](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalChronicleService.java) and [CapitalChronicleEntry: serialized arguments](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalChronicleEntry.java)
- **[C10]** [CapitalSuccessionService: persistent family checks and candidate ordering](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalSuccessionService.java)
- **[C11]** [CapitalTradeProfileService: generated weighted shipments](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalTradeProfileService.java)
- **[C12]** [CapitalTradeExchangeService: recurring storage deposits and relationship effects](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalTradeExchangeService.java)
- **[C13]** [CapitalMourningService: clothing application/restoration](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalMourningService.java)
- **[C14]** [MarriageSurnameService: personal-identity behavior deliberately excluded](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/identity/MarriageSurnameService.java)
- **[C15]** [CapitalCrownJusticeService: justice domain deliberately excluded](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalCrownJusticeService.java)
- **[C16]** [CapitalAsylumService: asylum domain reviewed but not transplanted](https://github.com/MajesttyX/mca_capitals_addon/blob/63358cb4f5dcc715f3edc038f3ed41979f3b790f/src/main/java/com/majesttyx/mcacapitals/capital/CapitalAsylumService.java)

### Townstead

- **[T1]** [CurseForge: released feature boundaries and MCA compatibility warning](https://www.curseforge.com/minecraft/mc-mods/townstead)
- **[T2]** [TownsteadAPI: read-only snapshots and point-based building lookup](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/api/TownsteadAPI.java)
- **[T3]** [TownsteadVillagerSnapshot: profession, age, schedule, and needs fields](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/src/main/java/com/aetherianartificer/townstead/api/TownsteadVillagerSnapshot.java)
- **[T4]** [Development Chronicles: fact/knowledge separation, archive, and queries](https://github.com/AetherianArtificer/Townstead/blob/f93dc271125a750c0f15741ed384282fe0531881/src/main/java/com/aetherianartificer/townstead/chronicle/Chronicles.java)
- **[T5]** [Development ChronicleEmitter: actor-dependent templates and witnesses](https://github.com/AetherianArtificer/Townstead/blob/f93dc271125a750c0f15741ed384282fe0531881/src/main/java/com/aetherianartificer/townstead/chronicle/emit/ChronicleEmitter.java)
- **[T6]** [Development GossipTicker: knowledge spread and village digest](https://github.com/AetherianArtificer/Townstead/blob/f93dc271125a750c0f15741ed384282fe0531881/src/main/java/com/aetherianartificer/townstead/chronicle/knowledge/GossipTicker.java)
- **[T7]** [Development SpiritReadout: Townstead's spirit domain](https://github.com/AetherianArtificer/Townstead/blob/f93dc271125a750c0f15741ed384282fe0531881/src/main/java/com/aetherianartificer/townstead/spirit/SpiritReadout.java)
- **[T8]** [Main-source version properties](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/gradle.properties) and [Stonecutter active target](https://github.com/AetherianArtificer/Townstead/blob/4d6206cdf8b9d0f558694d7b35b223f4f6ace61e/stonecutter.gradle.kts)
