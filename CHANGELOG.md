# Changelog

## 0.1.1 — unreleased

Implementation of the findings in [docs/UltimaKingdoms-Audit.md](docs/UltimaKingdoms-Audit.md).

### Changed

- The optional companion ranges in `gradle.properties` are lower bounds only: `mcaquests [1.6.5,)`,
  `mcacrime [0.7.5,)`, `mcareputation [0.6.0,)`, and Townstead `[0.7.6,0.9)` to match MCA: Quests
  (0.8 adds `api.v1`; 0.9 has not been seen). Forge enforces an optional range when the mod is
  present, so the old upper bounds would have refused to launch with the next Crime, Reputation or
  Townstead release even though every one of those bridges already fails closed on API drift.
  Recruits is `[1.15.2,)` on the same grounds: the integration is audited for exactly 1.15.2, and that is
  enforced at runtime rather than by refusing to launch. `RecruitsMixinPlugin` applies the Recruits
  mixins only to 1.15.2 and every Recruits adapter refuses any other version, so a newer Recruits now
  launches with the integration off and one warning naming the installed version.
  `ModsTomlRangesTest` fails the build if an optional dependency is pinned to one exact version.

### Fixed

- Only villagers and entities tagged `ultima_kingdoms:civic_residents` receive automatic civic residence; hostile mobs and livestock no longer become residents.
- Recruit transfer confirmation flushes entity and overworld data instead of saving every dimension, and repeated verification is throttled.
- Political request receipts expire after a game day and are pruned during deadline maintenance, so the receipt store no longer fills permanently.
- Kingdom Tasks results name people and places instead of printing identifiers, and the protocol no longer rewrites result text by pattern matching.
- An elected successor or leader cannot be displaced by appointment authority; election winners are recorded as elected mandates.
- Evolving-world scenarios find institutions beyond the first page; withdrawing a non-receipt contribution allows contributing again.
- Campaign declaration and accord signature report a pending native application instead of an error after the record was committed; withdrawal restores the relation that stood before the declaration.
- The ledger and settlement selectors explain when settlement discovery is read-only; an unwritable discovery file no longer stops the server.
- Village point-of-interest clusters are grouped by proximity so large villages register once.
- The legacy government screen channel is read-only; all mutations go through reviewed Kingdom Tasks.

### Changed

- Kingdom Tasks text is translatable (`task.ultima_kingdoms.*`, `interaction.ultima_kingdoms.*` keys with English fallbacks); the interactions protocol is version 2 and the main channel is version 4.
- Non-consequential tasks run as soon as their fields are complete; opening screens prefill the settlement they show; list rows show two lines; results offer "Same task again" and "Related tasks".
- Constitutional transition rules may choose a council-only or council-plus-residents electorate.
- Declaring a campaign, signing an accord and fulfilling or refusing a protectorate obligation adjust the actor's standing with the affected kingdom.
- Counterparts receive a chat notice when a campaign, accord, election, transfer, drama or protectorate awaits them, and new opportunities are announced to the government.
- A kingdom's own government may map and bind its native claims; a capital resident may found the first government (`politics.openFounding`); governed settlements are evolution regions by default (`autoEligibleRegions`).
- Every `/ultima-<domain>` command root is also available as `/ultima <domain>`.
- First login shows a welcome line and the Book of Kingdoms opens the task each topic describes.

### Compatibility

- Checked against the current companion trees. MCA Quests 1.7.0 and MCA Conversations 1.8.0 ship their R1–R3 provider halves natively, and every reflective binding in both directions matches.
- MCA Crime 0.7.5 now ships the workshop-legality (`InstitutionalServiceApi`) and jurisdiction (`JurisdictionPolicyApi`) providers. Without them, workshop commissions and native law enforcement stayed unavailable.
- Live faction synchronization requires MCA Reputation 0.6.1, the first release with the standing journal. With 0.6.0, local standing is read-only.
- Faction synchronization recovers on its own when MCA Reputation's standing journal lapses this server's cursor (`GAP`), which happens when synchronization was off, stalled or uninstalled for longer than the journal's retention bound (`standingJournalMaxEntries`). The skipped changes cannot be replayed: the cursor moves durably to where the journal's kept entries begin, one warning names how many were skipped, and the faction status line counts them (`source_gaps`, `source_skipped`). Until now a `GAP` stopped synchronization for good. Faction standing is deliberately not re-derived from MCA Reputation's standing baselines at that point, because a baseline already includes every change applied before the gap and importing it would count them twice.
- The Book of Kingdoms names these provider versions; "Check installed integrations" also lists MCA Conversations.

## 0.1.0

Initial release for Minecraft 1.20.1 and Forge.

### Added

- Persistent, server-authoritative settlement records with stable UUIDs, names, slugs, kingdom assignments, territory bounds, aliases, locks, and discovery provenance.
- Automatic village recognition from tagged village structures and configurable village-POI clusters, plus manual creation, discovery, merge, rename, locking, and reclassification commands.
- Six data-driven kingdoms: Serenum, Lunari, Madera, Anemosia, Shimaguni, and Yew, with biome rules, structure-style rules, name pools, heraldry, and Serenum as the default fallback.
- Schema 1 datapack definitions with validation and reload support.
- Village Ledger item and server-synchronized settlement browser with kingdom filtering and detail views.
- Configurable settlement-entry overlay with kingdom heraldry.
- Persistent NPC origin and residence identity, administrative citizen commands, dialogue-context values, and lifecycle events.
- Optional, exact-version MCA Reborn `7.6.26+1.20.1` integration for MCA village discovery and villager home evidence, isolated from MCA personal and family data.
- Public server API for settlement and civic queries, mutations, extension registrations, and Forge lifecycle events.
- Separate API and sources artifacts alongside the main mod jar.

### Compatibility

- Minecraft `1.20.1`
- Forge `47.x`; built with `47.4.23`
- Java `17`
- Optional MCA setup tested with MCA Reborn `7.6.26+1.20.1` and Architectury API `9.2.14`
