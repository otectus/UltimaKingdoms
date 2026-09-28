# Ultima Kingdoms audit

Audit of commit `ad0fbad5b99712ac7fb9fadfd4c595b757fe9ad7` (branch `main`), performed 2026-09-21 as a combined engineering and player-experience review. No production code was changed; this document is the only repository change. Line references are to the working tree at that commit.

## 1. Scope and method

The audit covered the Forge 1.20.1 mod source under `src/main/java/com/ultimakingdoms` (298 files, 23,112 lines), bundled resources and datapack definitions, the unit and GameTest suites, the packaged-server acceptance harness under `tools/test`, and the documentation set (README, CHANGELOG, CURSEFORGE, `docs/R1`–`R4`, `docs/Player-Experience-Review.md`, `docs/politics/*`, Book of Kingdoms guide JSON). Provider patches under `pack/r*/providers` were treated as integration contracts and not audited line by line.

Method, in order:

1. Baseline checks: compile, unit tests from a clean state, the dedicated-server GameTest suite, resource lint, and five packaged-server acceptance phases (section 7).
2. Documentation-versus-code reconciliation for every player-facing claim (section 2).
3. Source traces of each major workflow from input through validation, persistence, provider effect, and displayed result: settlement discovery and identity, the Kingdom Tasks form protocol, civic guilds and commissions, government and elections, warfare and mobilization, evolving-world scenarios, protection pacts, recruit transfers, drama, and organization lifecycle.
4. Lifecycle and scale review: static state, logout and stop cleanup, restart recovery, schema refusal, provider absence, per-tick and per-request costs.
5. A designer pass over every screen and the command surface.
6. Throwaway JUnit repro tests run from the scratchpad through a Gradle init script, without adding files to the repository (section 7).

Severity uses Critical, High, Medium, Low. Confidence uses Confirmed (executed repro or unambiguous code path), High, Medium, Low. Every finding names the affected workflow and the evidence.

## 2. Implementation status matrix

Each documented capability, with what the source actually does.

| Claim (source document) | Status | Evidence |
| --- | --- | --- |
| Settlement recognition from village structures and POI clusters, persistent identity, aliases, locks, merge, reclassify (README) | Implemented | `core/KingdomsServiceImpl.java`, `settlement/*`, GameTests `KingdomGameTests` (15 tests) |
| Six kingdoms with datapack definitions and reload (README) | Implemented | `data/DefinitionRegistry.java`, six kingdom JSON files |
| "Five data-driven kingdoms" (CHANGELOG.md:11, CURSEFORGE.md:10) | Contradicted | Six kingdoms ship; Shimaguni is missing from both texts |
| Village Ledger, entry overlay, Book of Kingdoms, Kingdom Tasks on K (README, Player-Experience-Review) | Implemented | `client/*`, `interaction/*`, 65 guide entries |
| Per-player settlement discovery with legacy public adoption (R1) | Implemented | `knowledge/SettlementKnowledge.java` |
| Form protocol: session isolation, stale selection refusal, repeat apply returns the same result, permission re-check (Player-Experience-Review) | Implemented | `interaction/InteractionNetwork.java`; GameTests `InteractionGameTests` (5) |
| Guild membership, deeds through durable MCA Quests receipts, introductions, commissions, chapters (R1) | Implemented; provider-dependent | `factions/organization/*`, `civic/*`, `compat/quests/receipts/*` |
| Paid workshop commission, receipt-backed honor, Crime suspension, hospitality (R2) | Implemented; provider-dependent | `civic/CivicService.java`, `compat/crime/*` |
| Campaigns, accords, mobilization, civilian contracts, sites, routes, encounters (R3) | Implemented; Recruits 1.15.2 exact | `warfare/*`, `worldcontext/*`, 8 mixins |
| Scenarios, digest, protection pacts, family introductions, recruit transfers, drama, organization lifecycle, elections, regency (R4) | Implemented | `evolution/*`, `politics/GovernmentService.java` |
| "Elections, regencies … are not implemented or advertised" (docs/politics/usage.md:52; validation.md:3) | Contradicted by R4 and code | `GovernmentService.openElection` at line 94 |
| "There is no automatic receipt pruning or destructive repair command" for political receipts (docs/politics/usage.md:60) | Accurate, and the root of defect D-4 | `GovernmentService.java:146,180` |
| README command reference (README.md:68–93) | Partial | Only `/ultima kingdom`, `village`, `citizen`, `reload` are listed; `/ultima guild`, `/ultima politics`, `/ultima warfare`, `/ultima faction` and the seven `/ultima-*` roots exist only in R1–R4 documents |
| Optional dependency metadata (`META-INF/mods.toml`) | Partial | Declares `mca`, `mcaquests`, `townstead` only; Recruits (mixins), MCA Crime, MCA Reputation and the four progression mods are probed at runtime without a declared range |
| MCA Quests, Crime, Conversations behaviour described in R1–R3 | Not verifiable here | Requires the patched provider jars; contracts audited only from this side of the reflection boundary |
| "44 required dedicated-server GameTests" (Player-Experience-Review.md:75) | Accurate | Re-run in this audit: 44 completed, all passed |

## 3. Confirmed defects

Ordered by player impact.

### D-1 Every living entity inside a settlement is recorded as a resident

- Severity: High. Confidence: Confirmed by code path.
- Workflow: civic identity, resident events, Townstead reactions, election eligibility of NPCs.
- Evidence: `core/KingdomsServiceImpl.java:506–518` (`observeEntity` falls through to `getSettlementAt` and `setResidence` for any entity without identity); `UltimaKingdoms.java:183` and `:194` call it for every non-player `LivingEntity` on join and every `civicIdentity.evidenceInterval` ticks; `core/KingdomsServiceImpl.java:452` stamps the automatic write with `CivicIdentitySource.COMMAND`.
- Expected: only villager-like NPCs (vanilla villagers, MCA villagers, provider-recognised residents) acquire civic origin and residence, labelled as automatic evidence.
- Actual: zombies, animals, bosses and summoned mobs that stand in a settlement receive a persistent `ultima_kingdoms:civic_identity` tag, `ResidentJoinedEvent` and `CivicIdentityChangedEvent` fire for them, and `/ultima citizen info` reports the source as `COMMAND`. Every such entity later costs an identity read plus redirect check each interval. Townstead reaction dispatch (`compat/townstead/TownsteadPoliticalReactions.java`) filters by `townstead.villager()`, so it is not affected, but the political NPC checks and any addon consuming the events are.
- Failure path: spawn a zombie inside a recognised village, wait 200 ticks, run `/ultima citizen info @e[type=zombie,limit=1]`.
- Fix: gate the fallback on an entity-type allow list (vanilla `Villager`, provider-recognised villagers via `CivicEvidenceProvider`, or a `ultima_kingdoms:civic_residents` entity tag) and write the fallback with `CivicIdentitySource.AUTOMATIC`.
- Acceptance: GameTest that a zombie and a cow inside a settlement have no civic identity after 400 ticks while a villager has one with source `AUTOMATIC`; an existing world with mob identities loads unchanged.

### D-2 Recruit transfers force a full world save on every step, including a repeatable one

- Severity: High. Confidence: Confirmed by code path.
- Workflow: recruit transfer apply, confirm, restore, reconcile.
- Evidence: `compat/recruits/RecruitsTransfer.java:179–201` (`saveAndConfirm` calls `server.saveEverything(false, true, true)` and then `worker.synchronize(true).join()` on the server thread); `evolution/RecruitTransferService.java:96` (`confirm`) is callable by either party on any non-terminal transfer with no throttle beyond vanilla command spam.
- Expected: persistence verification limited to the recruit's entity chunk and the Recruits data files.
- Actual: `apply` performs two forced saves of every loaded chunk in every dimension; `confirm` performs one per call. On a populated server each call stalls the tick for the duration of a full save. A player with one pending transfer can repeat `/ultima-transfer confirm` at the vanilla command rate (about one per second sustained) and keep the server in continuous forced saves.
- Fix: replace `saveEverything` with a targeted flush (the recruit's level entity storage plus the three Recruits `SavedData` files, which are already saved explicitly on the lines above), and rate-limit `confirm` and `restore` per transfer (for example one attempt per 200 ticks, returning the retained detail otherwise).
- Acceptance: a unit test on the throttle; a packaged-server check that `confirm` on a `COMPLETE` transfer performs no save; tick-time measurement before and after with the `military` phase fixture.

### D-3 Political mutations stop permanently after 8,192 requests

- Severity: High (progression dead end on any long-lived server). Confidence: Confirmed by code path, acknowledged in `docs/politics/usage.md:60`.
- Workflow: every government action, petition, agreement, ballot, election, regency.
- Evidence: `politics/GovernmentService.java:180` (`execute`) and `:146` (`transition`) refuse when `receipts.size()` reaches 8,192; the comment at `:179` says receipts are never evicted; `politics/PoliticalSavedData.java:36` validates the same cap on load. No command or task prunes them (grep for receipt pruning finds only `factions/FactionSavedData.pruneReceipts`, which is a different store).
- Expected: replay protection bounded by time or per-actor window, so a server can run indefinitely.
- Actual: each successful mutation consumes one slot forever. Every ballot cast is a mutation. A modestly active server (say four kingdoms, elections every few days, petitions and agreements) reaches the cap in months; after that every political task fails with "Political request receipt capacity reached; operator maintenance required", and no maintenance exists. The facts journal is already bounded per kingdom (`GovernmentService.java:272`), so only receipts grow without bound.
- Fix: retire receipts whose revision is older than a retention window (mirror `FactionSavedData.pruneReceipts`), or keep a bounded ring per actor; retain the replay guarantee for the window in which a client can still resend. Add an operator diagnostic that reports receipt usage.
- Acceptance: unit test that the 8,193rd request succeeds after retention elapses and that a resend inside the window still replays; save/load round trip keeps the pruned store valid.

### D-4 Task result text is rewritten by regular expressions and loses information

- Severity: Medium. Confidence: Confirmed by repro test (`PlayerWordsAuditTest`, section 7).
- Workflow: every Kingdom Tasks result and the War Room intelligence panel.
- Evidence: `interaction/PlayerWords.java:16` replaces `/ultima` and everything after it on the line with "the Kingdoms task menu"; `:18` replaces every UUID with "saved record". Producers of such text: `warfare/WarfareRuntime.java:139,150`, `warfare/CampaignService.java:94`, `warfare/mobilization/MobilizationService.java:148`, `civic/CivicViews.java:84`; the War Room applies the same rewrite at `warfare/WarfareNetwork.java:52`.
- Expected: results explain what happened; players never need identifiers because the interface carries them.
- Actual (repro output): "Peace/autonomy: /ultima warfare campaigns 1234. Civilian contracts: /ultima-contract. Local civilian law remains in force." becomes "Peace/autonomy: the Kingdoms task menu". A campaign declaration reads "Campaign saved record: NOTICE". A settlement renamed to "Fort /ultima-crossing" is reported as "Renamed to Fort the Kingdoms task menu". Command users who read the same services through chat receive the raw UUIDs, so the two interfaces disagree about what a record is called.
- Fix: stop post-processing free text. Have services return structured results (label plus optional record reference) and let the interaction layer decide what to show; drop the command-line and UUID regexes entirely.
- Acceptance: the repro test inverted (text preserved verbatim); a GameTest that a declaration result names the settlement and phase.

### D-5 Evolving-world scenarios only consider the first 32 recognised institutions

- Severity: Medium (silent dead end as institutions accumulate). Confidence: Confirmed by code path.
- Workflow: scenario generation for institution, occupation, demand and grievance triggers.
- Evidence: `evolution/EvolutionService.java:254` calls `politics.knownInstitutions(viewer, 0, 32)` once and filters that page for the sampled settlement; compare `warfare/contracts/CivilianContractService.institutionAt`, which pages through all institutions for the same lookup.
- Expected: any settlement with an operational recognised institution can trigger scenarios.
- Actual: once a server has more than 32 recognitions, settlements whose institution sorts after the 32nd UUID never generate institution-based scenarios; nothing reports why.
- Fix: page like `institutionAt`, or add a `knownInstitutions(viewer, settlement)` query.
- Acceptance: GameTest with 33 recognitions where the 33rd settlement still produces a scenario.

### D-6 Election results are not protected and the electorate is only the council

- Severity: Medium (design and correctness versus the R4 text "legitimate leader"). Confidence: Confirmed by code path; election arithmetic verified in `ElectionMathAuditTest`.
- Workflow: elections and succession.
- Evidence: `politics/GovernmentService.java:493` builds the electorate from mandate holders and player office holders only; `:511` installs a winner of an active government merely as `successor`; `:463–465` lets the leader run `NAME_SUCCESSOR` at any time except during an active regency, replacing the elected successor; `:112` lets a voter overwrite their ballot, including during grace (`:110`).
- Expected: an election outcome is durable until the next election, and the electorate matches what players expect from "election".
- Actual: with the default charter the leader is often the whole electorate (repro: a one-voter electorate resolves immediately). A leader who dislikes the result names someone else the next tick and the election record still shows the original winner. The R4 document (docs/R4-Evolving-World.md:174) describes the mechanics accurately but the Book calls the winner "legitimate leader during interregnum", which is only true for the interregnum branch.
- Fix: record `electedSuccessor` on the government and refuse `NAME_SUCCESSOR` and `REMOVE_OFFICE` on it until a new election or abdication; define the electorate in the transition rule (council, office holders plus residents, or players above a standing tier) so worlds can choose; either forbid re-voting or document it.
- Acceptance: GameTest that `NAME_SUCCESSOR` after a resolved election is refused; unit test on electorate rules.

### D-7 Discovery storage failure hides every settlement without telling players

- Severity: Medium. Confidence: Confirmed by repro test (`SettlementKnowledgeReadOnlyAuditTest`).
- Workflow: ledger, every task choice list, commands that resolve settlements by name.
- Evidence: `knowledge/SettlementKnowledge.java:34–35` (`adopt` returns silently when the file is unreadable), `:72–99` (`page`, `count`, `find` return nothing for non-operators), `:143` (only a server log). The diagnostic reaches players only through the operator-only integration task (`interaction/StandingTasks.java:22`) and `/ultima integrations`.
- Expected: a read-only discovery file is surfaced where players look: the ledger empty state and the task catalogue.
- Actual: the ledger shows "No recognized settlements", every settlement selector is empty, and operators still see everything, so the problem is easy to misdiagnose as "nothing discovered yet".
- Fix: include `SettlementKnowledge.diagnostic()` in the `LedgerPagePacket` (a bounded string) and in the catalogue reply when non-empty; log at ERROR once on startup.
- Acceptance: repro test extended to assert the ledger empty message contains the diagnostic.

### D-8 Large villages without a structure start can be recorded twice

- Severity: Medium. Confidence: Medium (geometry confirmed by `PoiOverlapAuditTest`; in-world reproduction not executed).
- Workflow: settlement discovery of player-built or MCA-only villages.
- Evidence: `settlement/PoiClusterDetector.java:45–48` derives the candidate from the centroid of all village POIs within 48 blocks of the scanned chunk and keys it by that centroid's chunk; `core/KingdomsServiceImpl.java:624–640` merges a POI candidate into an existing record only when the anchors are within 32 blocks or the 128×128 bounds overlap at least 65 % (`:750`). Two centroids 50 blocks apart overlap 61 % (repro output), so a village whose POIs spread over roughly 100 blocks yields two records depending on where the player first loads it.
- Expected: one settlement per contiguous village.
- Actual: duplicate records with generated names; operators must notice and run merge. The structure detector runs first and absorbs POI candidates for generated villages, so this affects hand-built and MCA villages without a village structure start.
- Fix: cluster POIs by connectivity (union of 48-block neighbourhoods) and key the candidate by the cluster's minimum chunk, or treat any bounds intersection with an existing POI-detected record as a match.
- Acceptance: GameTest that a 150-block spread of village POIs loaded from two directions creates exactly one record.

### D-9 Warfare commits a signature or declaration and then reports an error

- Severity: Medium (consistency and trust). Confidence: Confirmed by code path.
- Workflow: accord signing, campaign declaration.
- Evidence: `warfare/CampaignService.java:143` (`sign` saves the second signature, then calls `applyAccord`, which throws "Both native leaders must be online…" when the first signer is offline); `:93` (`declare` saves the campaign, then `applyDeclaration` can throw on a changed binding).
- Expected: a saved state change is reported as success with a pending status.
- Actual: the review page shows "Could not complete: Both native leaders must be online…" while the signature is durably recorded; the player has to discover `accord_apply` or `retry_campaign` on their own.
- Fix: catch the follow-up failure and return "Signature recorded; native application pending: <reason>".
- Acceptance: GameTest signing with the counterpart offline returns success and the accord phase is `SIGNED`.

### D-10 Family introduction scenarios die after a withdrawal

- Severity: Low. Confidence: Confirmed by code path.
- Workflow: evolving-world family introduction.
- Evidence: `evolution/EvolutionService.java:146` keys the family proof by scenario and player only; `:161` consumes the key; `:163–166` (`withdraw`) keeps it consumed.
- Expected: withdrawing an authority-backed or family-backed choice lets the same player choose again; only receipt-backed service proofs should be single use.
- Actual: after one withdrawal the only eligible player is told "This service receipt already supports another choice" until the scenario expires, and the family cooldown blocks a new one.
- Fix: consume only `mcaquests` receipts; for other proofs, remove the key on withdrawal.
- Acceptance: unit test on `contribute` after `withdraw` for the family scenario.

### D-11 Withdrawal always restores neutrality, not the prior relation

- Severity: Low. Confidence: Confirmed by code path.
- Evidence: `warfare/CampaignService.java:181` applies `ENEMY → NEUTRAL` regardless of the frozen `before` relation captured at declaration.
- Actual: allied factions that declared a campaign and withdrew end neutral.
- Fix: pass `c.before()` as the desired relation.

### D-12 The whole Kingdom Tasks interface is untranslatable

- Severity: Medium (localisation, resource packs). Confidence: Confirmed by code path.
- Evidence: task titles and help are literals in `interaction/ActionRegistry.java:47–48` and all `*Tasks.java`; client literals in `client/KingdomActionsScreen.java` ("Apply reviewed action" at `:56`, search, paging, close), `client/VillageLedgerScreen.java:66,74`, `client/WarRoomScreen.java:34`; every service result string in `warfare/*`, `evolution/*`, `politics/GovernmentService.java`. The lang file (308 keys) covers only the older screens.
- Fix: return `Component` JSON in `Reply.detail` and options (the channel already carries JSON), key the strings, and let `PlayerWords` disappear.

### D-13 Documentation drift

- Severity: Low each; Medium in aggregate for a mod whose selling point is explained systems. Confidence: Confirmed.
- CHANGELOG.md:11 and CURSEFORGE.md:10 say five kingdoms; CURSEFORGE.md also says reputation, diplomacy and warfare are out of scope.
- docs/politics/usage.md:52 and validation.md:3 say elections and regencies are not implemented.
- README.md:68–93 omits nine command roots.
- `META-INF/mods.toml:41` ends the optional dependency list at Townstead although Recruits (mixin targets), MCA Crime and MCA Reputation are integrated; declaring them with the exact tested versions makes the load-order and version contract visible to pack authors.
- Player-Experience-Review.md:13 says "stale page nonces"; the implementation uses a per-page `state` UUID, which is the same thing under another name (accurate, only terminology).

### D-14 Raw record identifiers and Java record text in player-facing output

- Severity: Low. Confidence: Confirmed by code path.
- `politics/PoliticalCommands.java:73` prints `ElectionView.toString()`.
- `politics/GovernmentService.java:836` shows the honor recipient as a UUID; `:862` appends the NPC UUID to council rows; `:821` shows kingdom ids and raw tick deadlines in agreement rows.
- `/ultima guild chapters`, `/ultima-world sites|routes|encounters`, `/ultima-protection list`, `/ultima-drama list`, `/ultima-evolution list`, `/ultima-mobilization status` all print UUIDs, while the GUI removes them (D-4), so neither surface gives a stable handle.

## 4. Suspected risks requiring validation

### R-1 JSON round-trip snapshots on hot paths

- Severity: Medium at scale. Confidence: Medium (pattern confirmed; cost not measured).
- Evidence: reads copy whole stores through Gson: `evolution/EvolutionSavedData.java:36` (`snapshot()` used by `settings` at `EvolutionService.java:26` and `scenarios` at `:28`, called for every form step), `worldcontext/WorldContextSavedData.java:93` (used by `observeStructure` at `WorldContextService.java:27` and `recordEncounter` at `:127`, so every discovered structure and every credited kill serialises up to 32 MB), `evolution/ProtectionService.java:21` (a new service and snapshot per call, see `interaction/EvolutionTasks.java:21–22`), `politics/PoliticalSavedData.java:118` (every political mutation deep-copies 32,768 facts and 8,192 receipts, then serialises again for the durable write).
- Validation: benchmark `EvolutionService.scenarios` and `GovernmentService.execute` with stores filled to their caps; target under 5 ms per call. Fix direction: immutable snapshots with structural sharing, or copy-on-write of only the touched collection.

### R-2 Quadratic choice building and per-chunk linear scans

- Severity: Medium at 5,000+ settlements. Confidence: Medium.
- Evidence: `interaction/BasicTargets.java:14` pages through `SettlementKnowledge.page`, which rebuilds and sorts the player's whole known set per page (`knowledge/SettlementKnowledge.java:72–82`), so one form step costs pages × N log N; `core/KingdomsServiceImpl.java:624` (`match`) and `:692` (`normalizedNameTaken`) scan all records per candidate, twice per tick (`:487`); `core/KingdomsServiceImpl.java:106` scans all records for an undefined kingdom id on every ledger request.
- Validation: seed 10,000 settlements and profile a ledger page and a chunk-scan tick.

### R-3 Double apply across retained sessions depends on every task bumping its version

- Severity: Low. Confidence: High for the mechanism, Low for real impact.
- Evidence: `interaction/InteractionNetwork.java:60` retains up to 16 live sessions per player; `:90` is the only guard, comparing the task's `version()` string. Every mutating task I traced bumps a revision that its version includes, with one exception: `civic.workshop` creates an offer in `InstitutionalCommissionData`, which is not part of the civic version string, so two review pages can create two offers and reserve two honor slots for 1,200 ticks. No GameTest covers two concurrent review pages.
- Validation: GameTest opening the same consequential task twice and applying both.

### R-4 Commands accept any UUID and bypass viewer-filtered choice lists

- Severity: Low (services re-check), but a fragile pattern. Confidence: High.
- Evidence: `interaction/NamedTargets.java:19` returns a raw UUID before consulting the choice list. Services checked (`known`, `visible`, `own`, `authority`) all re-validate, but the recruit-transfer "share a destination" privacy step can be skipped by typing a group UUID (`evolution/RecruitTransferService.java:47` only requires the group to be recipient-owned); consent is still required.
- Validation: a negative test per command that a UUID outside the viewer's list is refused.

### R-5 Siege hooks fail closed on any reflection error

- Severity: Low. Confidence: High.
- Evidence: `compat/recruits/RecruitsEvents.java:39–43` cancels native siege Start and Tick events on any exception; `warfare/CampaignService.java:246` returns false for bound claims when the campaign store is read-only. Intentional, but a Recruits point release that changes a getter name would silently stop all sieges server-wide.
- Validation: log at ERROR once when the hook cancels for a reason other than policy.

### R-6 Legacy political packet path is still a live mutation surface

- Severity: Low. Confidence: High.
- Evidence: `politics/PoliticalNetwork.java:38` still executes a client-supplied `Request`; the client only sends `null` mutations now (`client/politics/KingdomScreen.java:53`). Authority is checked server-side, but the path has no review page and swallows failures (`:41`), so a modified client gets no error text.
- Validation: remove the mutation field or refuse non-null mutations.

### R-7 Startup fails hard when the discovery file cannot be written

- Severity: Low. Confidence: High.
- Evidence: `knowledge/SettlementKnowledge.adopt` throws from `ServerStartingEvent` when the initial save is not durable; `UltimaKingdoms.serverStarting` rethrows, so a read-only `world/data` directory prevents the server from starting rather than running with discovery disabled.
- Validation: decide whether fail-closed at startup is the intended trade-off and document it.

### R-8 Encounter receipts under mob farms

- Severity: Low with defaults (only elder guardians and evokers), Medium with the R3 pack overlays. Confidence: Medium.
- Evidence: `worldcontext/WorldContextService.java:127` snapshots and durably writes on every credited death; the repeat budget (3 per week per player and site) limits receipts but not the snapshot cost.

## 5. UI and UX findings

### Kingdom Tasks (K)

- U-1 Flat catalogue. About 129 tasks are presented as a single searchable list of 20 per page; categories exist in the data but there is no category filter or grouping in the screen. A new player searching "vote" or "join" copes; a player who does not know the vocabulary ("accord", "mandate", "scoped service") does not. Recommendation: category tabs from `Task.category`, recently used tasks first, and one-line "what you can do here" hints from the current settlement context.
- U-2 Read tasks require the full wizard. "Read this settlement" is K → search → task → review page ("Ready. Continue…") → apply: four clicks for a read. Recommendation: run field-less, non-consequential tasks immediately.
- U-3 Details hidden in tooltips. Choice rows show only the label; phase, reason and location are in the hover tooltip (`client/KingdomActionsScreen.java:42`). Recommendation: two-line rows with the detail in secondary colour.
- U-4 No keyboard selection. Enter submits search or text only (`client/KingdomActionsScreen.java:74`); options need the mouse. Tab reaches buttons, so it is usable, but arrow-key selection and number keys would make repeated tasks fast.
- U-5 Context is dropped between screens. The War Room opens tasks without pre-selecting the settlement the player was looking at; the Kingdom page's "Actions" opens the catalogue with the search "Government" and forgets the kingdom (`client/politics/KingdomScreen.java:72`); the ledger's "Kingdom" button silently opens Serenum when nothing is selected (`client/VillageLedgerScreen.java:71`). Recommendation: let `InteractionClient.open` accept prefilled field values so the first field is skipped when the origin screen already knows it.
- U-6 Result pages are terminal. After "Apply reviewed action" the only paths are Back (disabled), All tasks (search reset) and Close. Recommendation: "Do this again", "Inspect the record", and related tasks.
- U-7 Timeouts and errors. A 200-tick timeout produces "No reply yet. Inspect current status before repeating an action." with a "Check this action" button (`client/KingdomActionsScreen.java:66`); good. Error text from services is precise but dense with internal terms (fingerprint, epoch, sidecar, native, provider). A glossary exists in the Book but the UI never links to it.
- U-8 Empty states are generic. "No eligible known choices. Check your location, permissions, prerequisites and installed integrations." appears for every empty selector. Most services already have explain APIs (`explainOwn`, `actionDenials`, `diagnostic`); the task could show the specific reason.

### Village Ledger, Kingdom page, War Room, Guilds

- U-9 Three generations of UI coexist: the ledger and Guilds screen (translated, custom layout), the Kingdom page with seven tabs and a legacy channel, and Tasks. A player sees government records in the Kingdom page but acts in Tasks, with different labels for the same objects ("Kingdom" tab vs "Government" category, "Agreements" vs "Propose an agreement" vs warfare "control agreement").
- U-10 Identifier presentation. The Kingdom page shows UUIDs for honor recipients and NPC office holders, tick values for deadlines and kingdom ids in agreement rows (D-14); Tasks show "saved record" (D-4); commands show UUIDs. Recommendation: a single formatter for names, relative deadlines ("in 2 days"), and kingdom display names.
- U-11 War Room intelligence lines are service strings with revision numbers filtered out and identifiers replaced; the six buttons are static and do not reflect what is possible here (a non-leader sees "Declare campaign").

### Book of Kingdoms

- U-12 The book is thorough (65 entries) and its claims matched the code in every chapter I compared (elections, transfers, campaign declaration, government founding, blocked-action guidance). Two gaps: chapters state requirements in prose but never link to the task that satisfies them, and there is no way to reach the book from a task result.

### Onboarding and discoverability

- U-13 The K key is announced only in the Book, README and ledger button. The Book recipe advancement exists (`advancements/recipes/book_of_kingdoms.json`), but there is no first-join hint or toast. Recommendation: a one-time chat message on first settlement entry ("Press K for Kingdom Tasks; craft a Book of Kingdoms to learn more") and an advancement for entering a settlement.
- U-14 Operator-gated first steps. Governments need an operator to bootstrap; warfare needs an operator to map and bind every claim; evolution needs an operator to enable the world and each region. A player on a server without an engaged operator can craft the book, read about everything, and do nothing beyond guild membership.

### Localisation, scaling, accessibility

- U-15 See D-12: the main interface cannot be translated. The overlay, ledger, guild and kingdom screens are keyed.
- U-16 GUI scale. The task screen shows `(height-166)/23` rows: 4 rows at 270 px GUI height (scale 4 on 1080p), so a 20-option server page becomes 5 local pages. The ledger caps its panel at 360 px and adapts. MenuTextPanel supports mouse wheel, drag, arrow and Page keys, and provides narration; the option lists provide button narration only.

## 6. Design assessment and enhancement proposals

### Assessment

The safeguards are the strongest part of the mod: durable intents before provider calls, revision-checked mutations, read-only preservation of unknown schemas, per-player discovery, and the review page. The weakness is that most systems produce records rather than consequences:

- Scenario outcomes `AID`, `MEDIATE` and `NEGOTIATE` end in a sentence (`evolution/EvolutionService.java:206`); only `INTRODUCE` and `SUCCEED` change political state.
- Kingdom standing (`factions/*`) is consumed only by the MCA reputation bridge, the Townstead header and operator commands; no campaign, scenario, honor, protection duty or guild deed changes it.
- Guild ranks unlock two services (introductions, commissions); civic honors are cosmetic by ownership rule.
- Elections choose a successor the leader can overwrite (D-6).
- Scenario generation needs a government plus a recognised institution known to the sampled visitor; without Townstead and an active steward most worlds never see a scenario except the interregnum one.

Pacing constants are internally consistent but expressed in ticks everywhere: campaign notice 1,200 ticks (1 real minute) before a 168,000-tick campaign (2.3 real hours); petitions and agreements expire after 720,000 ticks (10 real hours) regardless of how many players are online; scenario deadlines pause when no visitor is present (good). Elections default to 60 game minutes.

### Proposals

- E-1 Consequence table. Define, in data, what each outcome does: scenario `AID` → standing with the beneficiary kingdom for contributors; `MEDIATE` → a temporary `CIVIC_AID` clause between the two governments; campaign resolution → standing changes for the commander's kingdom; protection duty fulfilled → standing with the protector; workshop honor → a faction overlay bonus. The faction service, clauses and honors already exist; only the wiring is missing.
- E-2 Make elections matter. Electorate options in the transition rule (council, residents, standing tier); an elected successor takes office at the end of grace unless the leader abdicates first, or at minimum is immune to `NAME_SUCCESSOR`.
- E-3 Reduce operator gating. A native faction leader with a `PROPOSE` mandate can bind their own claim to the settlement they stand in (the same checks as `bindHere` minus the operator requirement); regions become eligible automatically when a government exists; a kingdom with no government accepts a founding petition signed by the players with the highest standing.
- E-4 One government surface. Fold the Kingdom page tabs into read tasks with context prefilled from the ledger, and retire the legacy political channel.
- E-5 Humanised time and names everywhere: "in 2 days 4 hours", "Bellmeadow", "Serenum"; keep ticks only in operator diagnostics.
- E-6 Event notifications to parties: agreement proposed to your kingdom, election opened, transfer proposed to you, obligation requested. `PoliticalCommittedEvent` and the per-service commit points make this cheap; today only the evolution digest and a few system messages exist.
- E-7 Settlement growth. Bounds are frozen at creation and only replaced by a higher-ranked detector (`core/KingdomsServiceImpl.java:658–669`); a player-built expansion beyond the original radius is outside the settlement for overlay, control and residence purposes. Grow bounds from repeated POI observations within a configured cap.
- E-8 Scenario triggers that do not need institutions: settlement newly discovered, campaign ended, agreement expired, first government founded.
- E-9 Fold the seven `/ultima-*` roots into `/ultima <domain>` and print names instead of UUIDs in chat, sharing the `NamedTargets` catalogue.

## 7. Coverage summary and checks

Commands were run from the repository root on 2026-09-21. Exact logs are listed; results are quoted from them.

| Check | Command | Result |
| --- | --- | --- |
| Build and unit tests (cached) | `gradlew-quiet.sh <repo> build productionTestJar -I tools/test/production-tests.gradle` | PASS, `/tmp/gradle-UltimaKingdoms-build-20260921-221033.log` (tasks were up to date, so re-run below) |
| Unit tests from clean, API jar boundary | `gradlew-quiet.sh <repo> cleanTest test verifyApiJar` | PASS, `/tmp/gradle-UltimaKingdoms-cleanTest-20260921-221202.log`; JUnit XML: 126 tests, 0 failures, 0 errors, 1 skipped (Townstead binding test) |
| Dedicated-server GameTests | `python3 tools/test/run_gametests.py -PrunDir=build/audit-gametest-1` | PASS, "44 tests completed; all required tests passed", `/tmp/gradle-UltimaKingdoms-runGameTestServer-20260921-221209.log` |
| Resource lint | `python3 /home/otectus/Projects/.mcmod-tools/check_mod.py <repo>` | 0 errors, 0 warnings, 0 notes |
| Packaged server, core only | `python3 tools/test/integration_runtime.py --work-dir build/audit-rt-initial --phase initial` | PASS integration standalone civic gates, receipts, and dry-run |
| Packaged server, politics | `… --work-dir build/audit-rt-politics --phase politics` | PASS create, restart with receipt replay, and byte-identical future-schema preservation (three logs in that directory) |
| Packaged server, military with Recruits 1.15.2 | `… --work-dir build/audit-rt-military --phase military --mod build/gui-ux-final/mods/recruits-1.20.1-1.15.2.jar` | PASS military defaults, authority, native acknowledgments, offline policy, bilateral autonomy, divergence; PASS restart |
| Packaged server, R4 extensions (elections, regency, merges, schism, campaign) with Recruits, MCA, Architectury | `… --work-dir build/audit-rt-r4-extensions --phase r4-extensions --mod recruits… --mod minecraft-comes-alive… --mod architectury…` | PASS |
| Packaged server, R4 core (scenarios, protection, transfers, provider absence, future schema) | first run `build/audit-rt-r4` without MCA Quests; second run `build/audit-rt-r4b` with the full tuple (section 7.1) | first run FAIL by design ("Native civilian contract provider must be enabled"); second run PASS, five boots |
| Audit repro tests (scratchpad, not in repository) | `gradlew-quiet.sh <repo> auditTest -I <scratchpad>/audit-init.gradle -DauditTestDir=<scratchpad>/audit-tests` | PASS, 10 tests, 0 failures, `/tmp/gradle-UltimaKingdoms-auditTest-20260921-221944.log`; printed evidence quoted in D-4, D-7, D-8, D-6 |

Repro test sources (kept outside the repository under the session scratchpad `audit-tests/java/com/ultimakingdoms/`): `interaction/PlayerWordsAuditTest.java` (4 tests), `knowledge/SettlementKnowledgeReadOnlyAuditTest.java` (2), `core/PoiOverlapAuditTest.java` (2), `politics/ElectionMathAuditTest.java` (2). The init script `audit-init.gradle` adds an `auditTest` source set from that directory; it does not modify build files.

### 7.1 Packaged R4 core phase, full provider tuple

Command: `python3 tools/test/integration_runtime.py --work-dir build/audit-rt-r4b --phase r4 --mod build/gui-ux-final/mods/recruits-1.20.1-1.15.2.jar --mod build/gui-ux-final/mods/minecraft-comes-alive-7.6.26+1.20.1-universal.jar --mod build/gui-ux-final/mods/architectury-9.2.14-forge.jar --mod build/gui-ux-final/mods/mcaquests-1.6.6.jar --mod build/gui-ux-final/mods/mcacrime-0.7.5.jar --mod build/gui-ux-final/mods/townstead-0.7.6+1.20.1.jar --mod build/gui-ux-final/mods/Patchouli-1.20.1-85-FORGE.jar`

Result: PASS for all five boots (`r4.log`, `r4-restart.log`, `r4-absent.log`, `r4-reinstalled.log`, `r4-future.log` in `build/audit-rt-r4b`): political history, lawful succession, competing outcomes, protectorate consent/refusal/exit, native transfer veto recovery, durable ownership across restart and provider absence, and byte-identical future-schema sidecars. Tested jar hashes are in `build/audit-rt-r4b/artifacts.sha256` (main jar `d536a7fd…3ce3db3c`, matching the build above). The first attempt without MCA Quests failed as designed, which confirms the fail-closed behaviour of `ProtectionService.provider()` rather than a defect.

### 7.2 Not run or blocked

- Client GUI harness (`tools/client-test/*.py`, `runClientTest`): not run. It expects an Xvfb binary at `/tmp/jewelcraft-xvfb` and the CurseForge client install; neither was verified, and a real display session was not used for automation. GUI findings are from source reading of the screens.
- Packaged phases `r2`, `r2-legacy`, `civic-loop`, `r3`, `r3-civilian`, `r4-service`, `r4-family`, `r4-crash`, `politics-cycle`, `named`, `reactions`, `quests`: not run in this audit. Historical results in `docs/R1`–`R4` were not treated as verification.
- MCA-dependent GameTest `McaGameTests.exactMcaHomeLifecycle` runs inside the 44 with MCA absent; its MCA branch was therefore not exercised here.
- Provider-side behaviour of the patched MCA Quests, Crime and Conversations builds: outside scope.
- Performance figures for R-1 and R-2: not measured.

## 8. Roadmap

Ordered by player impact, then risk, effort and dependencies.

1. D-3 political receipt pruning (High impact, low effort, no dependencies). Unblocks long-lived servers.
2. D-2 targeted saves and throttling for recruit transfers (High impact, medium effort). Prevents a player-triggered server stall.
3. D-1 restrict automatic residence to villager-like entities and label it `AUTOMATIC` (High impact, low effort). Existing saves keep their tags; add a one-time cleanup only if events prove noisy.
4. D-4 plus D-12 plus D-14 together: structured, translatable results and a single name/time formatter (Medium impact, medium effort). Removes `PlayerWords`, fixes mangled text, makes chat and GUI agree, and enables resource-pack translation. Do this before U-5 and U-9 because they reuse the result model.
5. D-6 and E-2 election semantics (Medium impact, medium effort; depends on a decision about the electorate).
6. D-5, D-9, D-10, D-11: local fixes with GameTests (Low effort each).
7. U-2, U-3, U-5, U-8: task screen usability (Medium impact, medium effort; depends on item 4 for context passing).
8. D-7 and R-7: surface discovery diagnostics and decide the startup policy (Low effort).
9. D-8 POI clustering (Medium impact for MCA and player-built villages, medium effort, needs an in-world GameTest first).
10. R-1 and R-2 measurement, then structural sharing for the JSON-backed stores (Medium effort, do after the stores' shapes stabilise).
11. E-1, E-3, E-6, E-8: consequence wiring, reduced operator gating, notifications and institution-free triggers (High impact on engagement, higher effort; each is data plus one service change).
12. D-13 documentation corrections (trivial; batch with the next release notes), R-3, R-4, R-5, R-6 hardening.

## 9. Implementation status (2026-09-22)

Every finding above was implemented after the audit unless a row says otherwise. Product decisions taken with conservative defaults are marked "decision". Verification commands and logs are in section 9.1.

| Finding | Status | Where |
| --- | --- | --- |
| D-1 | Done. Automatic residence only for villagers and the `ultima_kingdoms:civic_residents` entity tag, recorded as `AUTOMATIC`. | `core/KingdomsServiceImpl.observeEntity`, `data/ultima_kingdoms/tags/entity_types/civic_residents.json` |
| D-2 | Done. Transfer steps flush the level's entity storage and the overworld data storage instead of saving every dimension; verification is throttled to once per 200 ticks per transfer. | `compat/recruits/RecruitsTransfer.flushEntityAndOverworldData`, `evolution/RecruitTransferService.throttleVerification` |
| D-3 | Done. Receipts carry the commit game time, expire after 24,000 ticks and are pruned 256 at a time during deadline maintenance; legacy receipts (time 0) retire first. | `api/politics/Politics.Receipt`, `politics/GovernmentService.expiredReceipts` |
| D-4 | Done. `PlayerWords.safe` no longer rewrites identifiers or command text; every producer named in the finding now emits names, and results that are bundled language keys become translatable. | `interaction/PlayerWords`, `interaction/Names`, the services listed under D-14 |
| D-5 | Done. Scenario generation pages through all recognised institutions. | `evolution/EvolutionService.institutionAt` |
| D-6, E-2 | Done. A resolved election records an elected mandate; `NAME_SUCCESSOR` cannot replace an elected successor and `REMOVE_OFFICE` cannot remove an elected leader; abdication releases the mandate. The transition rule gains an electorate option (`COUNCIL`, the default and the reading of rules saved before the option, or `RESIDENTS`, which adds every connected player whose civic residence is in the kingdom). Decision: a ballot may be changed until the election closes, as before. | `politics/GovernmentService` (`electorate`, `elected`, `closeElection`), `politics/PoliticalSavedData.Records.electedMandates`, `api/politics/PoliticalTransition.Electorate`, GameTest `electedMandateOutranksAppointment`, unit test `rulesSavedBeforeTheElectorateOptionDefaultToTheCouncil` |
| D-7 | Done. The ledger page packet carries the discovery diagnostic (main protocol 4), the ledger's empty state prints it, and the settlement selector's empty hint prints it. | `network/LedgerPagePacket.diagnostic`, `client/VillageLedgerScreen`, `interaction/InteractionTasks.hints` |
| D-8 | Done. Village POIs are grouped by proximity (40 blocks, union-find) and each cluster becomes one candidate whose radius follows its real extent, so overlapping scans merge. | `settlement/PoiClusterDetector.clusters`, unit test `PoiClusterDetectorTest` |
| D-9 | Done. `declare` and `sign` return a "recorded, native application pending" result when the follow-up native call refuses, instead of an error after the commit. | `warfare/CampaignService.declare`, `sign` |
| D-10 | Done. Withdrawing a family or authority contribution releases its proof; only quest receipts stay consumed. | `evolution/EvolutionService.withdraw` |
| D-11 | Done. Withdrawal restores the relation recorded before the declaration. | `warfare/CampaignService.withdraw` |
| D-12 | Done. Reply titles, details and option text are serialized components; task titles, help, categories and field labels use `task.ultima_kingdoms.*` keys, protocol and client text use `interaction.ultima_kingdoms.*` keys, all with English fallbacks and listed in `en_us.json`; the interactions protocol is version 2. Untranslated: free-text field help written per task and service result sentences. | `interaction/InteractionNetwork`, `interaction/ActionRegistry`, `client/KingdomActionsScreen`, `assets/ultima_kingdoms/lang/en_us.json` |
| D-13 | Done. Six kingdoms in CHANGELOG and CURSEFORGE; scope sentence updated; elections and regencies documented in `docs/politics/usage.md`; README lists every command root; `mods.toml` declares Recruits, MCA Crime and MCA Reputation with the tested ranges from `gradle.properties`. | `CHANGELOG.md`, `CURSEFORGE.md`, `README.md`, `docs/politics/usage.md`, `docs/Player-Experience-Review.md`, `META-INF/mods.toml`, `gradle.properties`, `build.gradle` |
| D-14, E-5 | Done. `Names` gives settlement, kingdom, player, entity, duration and remaining-time readings; election inspection prints a sentence; council rows and personLabel no longer append UUIDs; mobilization, drama, protection and campaign results name their subjects. | `interaction/Names`, `politics/PoliticalCommands.describe`, `politics/GovernmentService.personLabel` |
| R-1 | Partial. `revision()` reads no longer JSON-copy the store for protection, evolution and drama; view methods still snapshot. Decision: keep snapshots on views until the stores' shapes stabilise. | `evolution/ProtectionSavedData`, `evolution/EvolutionSavedData`, `evolution/drama/DramaSavedData` |
| R-2 | Done for the settlement selector: one sorted pass instead of a sort per 64-row page. Other selectors are bounded by their own limits. | `knowledge/SettlementKnowledge.all`, `interaction/BasicTargets` |
| R-3 | Done. Civic task versions include a fingerprint of the commission contracts. | `civic/CivicService.commissionFingerprint`, `interaction/CivicTasks` |
| R-4 | Verified: `NamedTargets.resolve` only accepts values present in the actor-filtered catalogue; covered by `NamedTargetsTest`. Request ids remain client-chosen nonces by design. | `src/test/.../interaction/NamedTargetsTest.java` |
| R-5 | Done. Each failure kind of the siege hooks is logged once with the exception, while still failing closed. | `compat/recruits/RecruitsEvents.failedClosed` |
| R-6, E-4 | Done. The legacy political channel refuses mutations; the Kingdom page stays as the read-only government browser and its Actions button opens Kingdom Tasks with the shown settlement prefilled. | `politics/PoliticalNetwork`, `client/politics/KingdomScreen` |
| R-7 | Done. Decision: an unwritable discovery file leaves discovery read-only with an error log and a visible diagnostic; the server keeps running and the file is untouched. | `knowledge/SettlementKnowledge.adopt` |
| R-8 | Done. Encounter credit is evaluated before any snapshot; deaths that earn no receipt cost no copy or write. | `worldcontext/WorldContextService.recordEncounter` |
| U-1 | Partial. The catalogue is sorted by category and each row shows its category on a second line; no category filter chips. | `interaction/InteractionNetwork.catalogue`, `client/KingdomActionsScreen` |
| U-2 | Done. Non-consequential tasks run as soon as their fields are complete (one click for a field-less read, two with a selection). Consequential tasks keep the review page. | `interaction/InteractionNetwork.handle` |
| U-3 | Done. Two-line rows (name, then summary) with the full text still in the tooltip. | `client/KingdomActionsScreen.OptionRow` |
| U-4 | Done. Down arrow moves from the search box to the first row, Up returns; rows are focusable buttons so Tab, arrows and Enter work. | `client/KingdomActionsScreen.keyPressed` |
| U-5 | Done. Requests carry a prefill map; the War Room, the ledger's Tasks button and the Kingdom page's Actions button pass the settlement they show, and matching selectors are skipped. | `InteractionNetwork.Request.prefill`, `client/InteractionClient.prefill`, `WarRoomScreen`, `VillageLedgerScreen`, `KingdomScreen` |
| U-6 | Done. Result pages offer "Check this action", "Same task again" and "Related tasks". | `client/KingdomActionsScreen` |
| U-7 | Unchanged (timeout copy is now a language key). | |
| U-8 | Done. 39 selector kinds have a specific empty-state explanation; the settlement hint includes the discovery diagnostic. | `interaction/InteractionTasks.hints` |
| U-9 | Partial. The legacy channel is read-only (E-4) and the Kingdom page hands off to Tasks; the three screens still coexist. | |
| U-10 | Done through D-14 and E-5. | |
| U-11 | Partial. War Room buttons prefill the settlement and open the named task; the button list is still static. | `client/WarRoomScreen` |
| U-12 | Done. Guide topics carry a `task` link and the book shows an "Open task" button (38 topics linked). | `guide/KingdomGuide.Entry.task`, `client/KingdomGuideScreen`, `assets/ultima_kingdoms/guide/en_us/*.json` |
| U-13 | Done. A welcome line on first login names the menu key (as the bound key) and the two items; a "Kingdoms Await" advancement toast appears on first tick. | `UltimaKingdoms.playerLoggedIn`, `data/ultima_kingdoms/advancements/welcome.json` |
| U-14, E-3 | Done. Decisions: a kingdom's government with proposal authority may map and bind its own native claims; a player whose civic residence is the chosen capital may found that kingdom's first government with themselves as leader (`politics.openFounding`, default on); every settlement of a governed kingdom is an evolution region unless paused (`autoEligibleRegions`, default on, after the world opt-in). | `warfare/WarfareRuntime.authority`, `politics/GovernmentService.residentFounder`, `evolution/EvolutionService.eligible`, `config/UltimaKingdomsConfig`, `evolution/EvolutionConfig` |
| U-15 | Done through D-12. | |
| U-16 | Changed: rows are 26 px tall and the row count is `(height-166)/26`; compact-viewport behaviour is covered only by the client harness, which was not run (section 7.2). | `client/KingdomActionsScreen` |
| E-1 | Done. Standing with the affected kingdom changes on campaign declaration (−40), full accord signature (+25), obligation fulfilled (+15) and refused (−15); each is keyed by the request id so replays never double-apply and failures never block the act. | `factions/Consequences`, `warfare/CampaignService`, `evolution/ProtectionService` |
| E-6 | Done. Chat notices to the government or person a proposal awaits: campaign declared, accord proposed, election opened, transfer proposed, drama proposed, protectorate proposed, new opportunity opened. | `interaction/Notify` and the services above |
| E-7 | Done. A record observed beyond its recorded edge by an equal-rank detector grows to the union of the bounds, up to eight times the default radius. | `core/KingdomsServiceImpl.observe` |
| E-8 | Done for occupation, demand and grievance triggers, which no longer require a recognised institution; the four new trigger kinds proposed (discovery, campaign end, agreement expiry, first government) are not added. | `evolution/EvolutionService.generate` |
| E-9 | Done. `/ultima contract`, `transfer`, `mobilization`, `evolution`, `drama`, `world` and `protection` redirect to the existing trees; the hyphenated roots remain. | the seven command classes |

### 9.1 Verification

| Check | Command | Result |
| --- | --- | --- |
| Compile | `gradlew-quiet.sh <repo> compileJava` | PASS (`/tmp/gradle-UltimaKingdoms-compileJava-20260922-000315.log`) |
| Unit tests | `gradlew-quiet.sh <repo> test` | PASS, JUnit XML: 130 tests, 0 failures (`/tmp/gradle-UltimaKingdoms-test-20260922-000343.log`) |
| GameTests | `python3 tools/test/run_gametests.py -PrunDir=build/impl-gametest-4` | PASS, "All 45 required tests passed" (`/tmp/gradle-UltimaKingdoms-runGameTestServer-20260922-000706.log`); one earlier run exposed an order-dependent rename test, now fixed to search by name |
| Resource lint | `check_mod.py <repo>` | 0 errors, 0 warnings, 0 notes |
| Client harness sources | `gradlew-quiet.sh <repo> compileClientTestJava -I tools/client-test/client-test.gradle` | PASS (compile only; the harness itself needs a display, section 7.2) |
| Packaged build | `gradlew-quiet.sh <repo> build productionTestJar -I tools/test/production-tests.gradle` | PASS (`/tmp/gradle-UltimaKingdoms-build-20260922-001627.log`); main jar sha256 begins `767126b7cc926865` |
| Packaged server, core only | `python3 tools/test/integration_runtime.py --work-dir build/impl-rt-initial-2 --phase initial` | PASS |
| Packaged server, politics | `… --work-dir build/impl-rt-politics-2 --phase politics` | PASS (create, restart with receipt replay, future schema) |
| Packaged server, military with Recruits 1.15.2 | `… --work-dir build/impl-rt-military-5 --phase military --mod build/gui-ux-final/mods/recruits-1.20.1-1.15.2.jar` | PASS (military and military-restart) |
| Packaged server, R3 with Recruits | `… --work-dir build/impl-rt-r3 --phase r3 --mod …recruits…` | PASS (five boots including provider absence and future schema) |
| Packaged server, R4 core, full provider tuple | `… --work-dir build/impl-rt-r4-3 --phase r4 --mod …` (same seven jars as section 7.1) | PASS (five boots) |
| Packaged server, R4 extensions | `… --work-dir build/impl-rt-r4-ext-2 --phase r4-extensions --mod …recruits… --mod …minecraft-comes-alive… --mod …architectury…` | PASS |

The packaged acceptance tests under `tools/test/runtime` were updated where they had parsed record ids or enum names out of player-facing result text (`MilitaryScenario`, `WarfareScenario`, `EvolutionScenario`, `R4ServiceScenario`, `R4FamilyScenario`); they now read ids from the services' view lists and match condition words. Earlier packaged runs in `build/impl-rt-military`, `build/impl-rt-military-2` to `-4`, `build/impl-rt-r4`, `build/impl-rt-r4-2` failed only on those test-side assumptions and are superseded by the runs above.
