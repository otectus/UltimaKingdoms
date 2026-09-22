# R4 evolving-world operations

Press **K** for the current **Kingdom Tasks** interface. In-mod player and operator actions have a GUI path with named choices and a review step. Saved-record command selectors also accept readable names; legacy UUID examples remain compatible but are optional. See [the current player interface guide](Player-Experience-Review.md) and the in-game Book of Kingdoms.

R4 adds opt-in political opportunities around the existing settlement, politics, organization, MCA Quests, Recruits, and warfare owners. It does not infer control, family relationships, deaths, victories, or completed work. A provider must return the relevant fact or receipt before R4 records a consequence.

All durations and deadlines below use game ticks. Commands that take a revision use optimistic concurrency: inspect the current record, use the revision it reports, and inspect again after any successful mutation or stale-revision refusal.

## Enable a world and its regions

Evolution starts paused. An operator must enable the world and then opt individual settlements in by UUID:

```text
/ultima-evolution configure <enabled:true|false> <drama:true|false>
/ultima-evolution region <settlement UUID> <enabled:true|false>
```

The first flag controls ordinary authored scenarios. The second flag separately permits dramatic scenarios and the negotiated Recruits transfer extension. Changing the world enabled flag clears the currently active sampling set; it does not delete saved scenarios. Disabling a region removes it from eligibility and pauses its active sampling.

Only an online visitor standing in an eligible settlement makes that region active. Evaluation never loads chunks. The common configuration bounds evaluation with `interval` (default 1,200 ticks), `regionBudget` (default 4 online players/regions per pass), and `concurrentScenarios` (default 8). Ordinary scenarios and authored dramas share that concurrency budget. Stored scenarios, causes, contributions, terms, and receipts remain bounded as well.

Players manage their own digest subscription and browse only scenarios visible through their settlement knowledge:

```text
/ultima-evolution digest <enabled:true|false>
/ultima-evolution list [offset]
/ultima-evolution inspect <scenario UUID>
```

`offset` must be a multiple of 10. Digest cursors are saved before a notification is sent, so restart does not intentionally repeat the same digest interval.

An ordinary scenario lists its authored outcomes and current revision. The mutation commands are:

```text
/ultima-evolution contribute <aid|mediate|introduce|negotiate|succeed> <scenario UUID> <revision>
/ultima-evolution withdraw <scenario UUID> <revision>
/ultima-evolution resolve <aid|mediate|introduce|negotiate|succeed|decline> <scenario UUID> <revision> [counterpart kingdom]
/ultima-evolution retry <scenario UUID>
/ultima-evolution release_rejected <scenario UUID>
```

Only outcomes present in that scenario's frozen template are accepted. Aid, mediation, and autonomy negotiation contributions require a completed scoped MCA Quests contract. Introduction and succession revalidate their owner evidence. Resolution records a durable intent before calling politics; `retry` uses the same actor, request ID, and frozen political revision after an interrupted acknowledgment. `release_rejected` reopens only an uncommitted owner request. Withdrawing a contribution leaves its service receipt consumed so it cannot support a second choice. `decline` ends an opportunity peacefully.

Scenario deadlines pause while their settlement is outside the sampled active set. There is no offline catch-up. Authored drama uses remaining online time and advances only while all recorded participants are online; each evaluation deducts at most 1,200 ticks.

## Political history

The kingdom screen includes a **History** tab. The equivalent command reads its first viewer-filtered page:

```text
/ultima politics inspect <kingdom> history
```

History facts have a stable source receipt ID, actor attribution, affected kingdoms and players, a game-tick/revision order, visibility, and a current correction state. Superseded agreement and petition lifecycle entries are returned as `SUPERSEDED`; elapsed entries are returned as `EXPIRED`. Public facts are visible to everyone. Party facts require an affected player or current `PROPOSE`/`RATIFY` authority in an affected kingdom. Private petitions are limited to affected players and current `REVIEW` authority in their kingdom. A private ballot is visible only to its voter. A foreign viewer does not receive these facts.

The command page is limited to 20 rows. The server API supports viewer-filtered history pages of up to 50 facts. Political mutations are durably committed before their notices are published, and deadline/NPC-death facts use stable source IDs. NPC office vacancy requires a confirmed death event; unload, absence, player respawn, and restart are not treated as death.

This history is the mod's bounded operational ledger. Townstead 0.7.6 has no supported Chronicles/archive receipt adapter. R4 does not fabricate a Townstead archive entry, gossip event, or deep link.

## Scoped native service and voluntary protection

A protection pact enumerates the only duties it may request. Valid duty tokens are `civic_aid`, `defense_assistance`, and `mediation` and may be comma-separated:

```text
/ultima-protection propose <protector kingdom> <subordinate kingdom> <beneficiary settlement UUID> <duties> <duration> <notice> <terms...>
/ultima-protection sign <pact UUID> <kingdom> <revision>
/ultima-protection list
/ultima-protection inspect <pact UUID>
/ultima-protection request <pact UUID> <duty> <revision>
/ultima-protection fulfill <obligation UUID> <revision>
/ultima-protection refuse <obligation UUID> <revision> <reason...>
/ultima-protection exit <pact UUID> <kingdom> <revision>
```

Both governments must sign frozen terms with current `RATIFY` authority. On the second signature, both signing authorities must be online and still authorized. The hierarchy is checked and limited to depth four. A request outside the pact's enumerated duties is refused. Refusal is an explicit saved result that may support later negotiation; it does not declare war or transfer property.

An open scenario or protection obligation can authorize exactly one of the scoped native quest kinds `aid`, `mediation`, `autonomy`, or `defense`:

```text
/ultima-evolution contract <scenario-or-obligation UUID> <giver UUID> <aid|mediation|autonomy|defense>
```

The giver must be a nearby, loaded, living, visible institutional contact. The scope, settlement, authored outcome/duty, deadline, institution, player, and provider binding must all match. MCA Quests owns acceptance, objectives, item consumption, and payment. After native completion, the volunteer uses `fulfill`; R4 consumes the durable provider epoch/receipt once. Replays, arbitrary scopes, closed obligations, and repeat reward attempts are refused.

Either signatory can record an exit after current ratification authority is checked. Duties end after the pact's configured notice period. Exit does not confiscate troops, offices, buildings, property, or standing.

## MCA family introductions

The explicit family entry point is:

```text
/ultima-evolution family_introduction
```

It requires a reciprocal, non-deceased MCA marriage record, a loaded spouse, verified residences in different kingdoms, a known counterpart government, and an enabled current region. It creates a private scenario for the requesting player. No chunk is loaded to find the spouse, and marriage grants no office, ownership, or political authority.

For the family scenario, contribute `introduce`, then resolve `introduce` with the exact counterpart kingdom shown by the verified family context:

```text
/ultima-evolution contribute introduce <scenario UUID> <revision>
/ultima-evolution resolve introduce <scenario UUID> <revision> <counterpart kingdom>
```

The relationship is revalidated at contribution, resolution, and recovery. A successful resolution submits a normal introduction petition through the political owner. Changed or unavailable native family evidence pauses the operation rather than inventing continuity.

## Bilateral Recruits transfers

Negotiated transfer is enabled only when world drama is enabled and the audited Recruits provider is ready:

```text
/ultima-transfer propose <recruit UUID> <recipient player UUID> <recipient group UUID> <family:true|false>
/ultima-transfer inspect <transfer UUID>
/ultima-transfer consent <transfer UUID> <revision>
/ultima-transfer cancel <transfer UUID> <revision>
/ultima-transfer apply <transfer UUID> <revision>
/ultima-transfer confirm <transfer UUID>
/ultima-transfer restore <transfer UUID>
```

Proposal freezes the loaded recruit's identity, equipment digest, source owner, and recipient/group. Application captures and saves the current source group, remaining native wage timer, original orders, and native counts before any handoff. The recipient must be online to preview and consent. `family=true` additionally requires a reciprocal MCA marriage between the two players every time the agreement is used. Consent alone changes nothing. The source owner may apply only after 1,200 ticks of notice and before the 72,000-tick deadline, with both the recipient and recruit loaded.

R4 saves intent before releasing/hiring through Recruits, then verifies the entity, equipment, groups, owner counts, scoreboard state, and native disk state. A nonterminal transfer blocks mobilization of that recruit. `confirm` retries disk acknowledgment. `restore` requires both original parties online and requests guarded restoration of the original snapshot. `cancel` is the neutral exit before native application.

A transfer may be marked `accountingDiverged` if unrelated native recruit accounting changes while its recovery snapshot is pending. That intent and snapshot are deliberately retained. Automatic confirm/restore cannot clear the divergence; an operator must reconcile the native owner counts and retained transfer state. R4 does not silently rewrite the counts or discard the receipt.

### Operator reconciliation

If unrelated native recruitment, deaths, or recounts occur during a pending transfer, the saved `accountingDiverged` flag prevents automatic count repair, even if totals later return to their previous values. An operator can independently reconcile the native state and then acknowledge it:

```text
/ultima-transfer reconciliation_preview <transfer UUID>
/ultima-transfer reconcile <transfer UUID> <revision> <fingerprint> <reason>
```

The preview reports the current owner, group, equipment fingerprint, and both native counts. The acknowledgment requires permission level 2, the current revision and fingerprint, a reason, and successful native disk verification. It performs no ownership or count writes. The distinct terminal `RECONCILED` receipt retains the original snapshot and divergence evidence; it does not claim the original transfer completed.

## Dynamic organization lifecycle

Lifecycle commands are under `/ultima guild lifecycle`:

```text
/ultima guild lifecycle status <organization id>
/ultima guild lifecycle found <organization id> <template id> <sponsor kingdom> <revision> <display name...>
/ultima guild lifecycle merge propose <source id> <target id> <revision>
/ultima guild lifecycle merge consent <merge UUID> <revision>
/ultima guild lifecycle merge opt_out <merge UUID> <revision>
/ultima guild lifecycle merge novate <merge UUID> <obligation UUID> <revision>
/ultima guild lifecycle merge finalize <merge UUID> <revision>
/ultima guild lifecycle dissolve <organization id> <revision>
```

Founding copies a bounded installed template into a durable dynamic definition. It requires current `RECOGNIZE` authority in the sponsor kingdom, reserves a new resource ID, and enrolls only the founder. Reloaded or removed source templates do not mutate the frozen definition.

A merge starts with source authority and remains pending until independently authorized target consent. Active source members may opt out. Finalization closes each source membership, enrolls only non-opted-out compatible members in the target, and does not copy source standing into the target. The source remains a historical `MERGED` tombstone with a stable redirect for current lookups, while frozen historical quest and receipt IDs remain unchanged.

Accepted civic commissions and scoped civilian contracts are enumerated blockers. A merge or dissolution is refused until each is completed, explicitly cancelled, or, where supported, confirmed through `merge novate`. Dissolution closes memberships and leaves a `DISSOLVED` tombstone; the removed organization's benefits stop. It does not erase deed evidence or historical standing.

## Elections and regency

A government keeps its existing succession behavior until its current leader explicitly adopts a constitutional rule:

```text
/ultima politics transition_rule <kingdom> <elections:true|false> <regency:true|false> <election ticks> <grace ticks> <regency ticks> <comma-separated permissions>
/ultima politics election_open <kingdom> <first player> <second player>
/ultima politics election_inspect <election UUID>
/ultima politics election_vote <election UUID> <candidate player>
/ultima politics election_close <election UUID>
/ultima politics appoint_regent <kingdom> <player>
/ultima politics end_regency <kingdom>
```

Rule durations are bounded by the command: election and regency durations are 1,200–2,419,200 ticks, and grace is 200–172,800 ticks. Permissions use political enum names such as `APPOINT,SEAT`. The command surface opens a two-candidate ballot; the authenticated API supports the rule's frozen maximum, currently up to eight.

Opening freezes the candidates and electorate from current player office holders and mandates, plus an active unexpired regent. Votes are private and deterministic. An authorized close is allowed when all eligible ballots have arrived or the deadline passes. A unique winner becomes the named successor of an active government, or the legitimate leader during interregnum. A tie enters neutral grace. Once grace expires, the election expires without installing a winner even if the last stored tally is unique.

Regency requires explicit rule opt-in. The appointment freezes the named successor, grants only the enumerated regent permissions, and expires at its recorded deadline. A regent cannot replace the preserved successor. End or expiry removes interim authority and keeps that successor. NPC absence is never a reason to open or resolve an election or regency.

## Authored drama

Installed drama template IDs are `ultima_kingdoms:border_campaign`, `ultima_kingdoms:occupied_rebellion`, `ultima_kingdoms:frontier_invasion`, and `ultima_kingdoms:concord_schism`.

```text
/ultima-drama list [offset]
/ultima-drama inspect <drama UUID>
/ultima-drama propose <request UUID> <global drama revision> <template id> <settlement UUID> [subject]
/ultima-drama consent <drama UUID> <record revision>
/ultima-drama execute <drama UUID> <record revision>
/ultima-drama negotiate <drama UUID> <record revision>
/ultima-drama exit <drama UUID> <record revision>
/ultima-drama recover <drama UUID> <record revision>
```

Military proposals require a known civic settlement, a provider-saved native claim/control binding, an explicitly mapped source faction commanded by the proposer, and an independently commanded opposing faction. Rebellion additionally requires a current saved occupation grievance. Invasion uses the authored target and transfer objective, but still requires both involved native leaders to opt in. Execution calls the existing authority-checked campaign declaration, accord, or withdrawal owner. It never invents a siege, victory, control transition, or town destruction.

For `concord_schism`, `subject` is the source organization ID. The proposer must be an active member and hold current charter recognition authority; another active member supplies independent consent. Execution founds a new charter through the organization lifecycle owner. Nobody is moved automatically, source standing is retained, and each participant chooses any new membership separately. It does not rewrite deity, faith, residence, or civic identity.

Every drama freezes authored evidence and terms, has a bounded operation budget, and permits a nonviolent exit. Negotiation and recovery call provider operations only after saving intent. Provider refusal or missing acknowledgment leaves a suspended/recoverable record. `exit` preserves control, civic identity, residents, buildings, assets, and organization standing.

## Saved data, compatibility, and recovery

R4 writes independent sidecars under `<world>/data`:

| File | Owner state | Recovery behavior |
| --- | --- | --- |
| `ultima_kingdoms_evolution.dat` | World/region opt-in, scenarios, contributions, digest cursors, receipts | Unsupported or partial schema is preserved read-only; `retry` or `release_rejected` handles saved resolution intent. |
| `ultima_kingdoms_protection.dat` | Pacts, obligations, refusals, completion receipts | Unsupported schema is preserved read-only; no new obligation is inferred. |
| `ultima_kingdoms_recruit_transfers.dat` | Bilateral terms and native before/after recovery snapshots | Unsupported schema is preserved read-only; `confirm` and `restore` operate only on retained, authorized intent. |
| `ultima_kingdoms_evolution_drama.dat` | Frozen drama terms/evidence, participant consent, provider intent | Unsupported schema is preserved read-only; `recover` retries a bounded saved provider operation. |
| `ultima_kingdoms_organizations.dat` | Membership, deeds, dynamic definitions, redirects, tombstones, merge consent | Unsupported/future schema is preserved read-only; unsettled obligations block destructive lifecycle changes. |
| `ultima_kingdoms_politics.dat` | Governments, agreements, petitions, typed history, receipts, elections, regencies | Unsupported/future or partial transition payload is preserved read-only; request receipts make owner acknowledgment recoverable. |
| `ultima_kingdoms_civilian_contracts.dat` | Native quest offers, accepted bindings, terminal provider proofs, R4 scopes | Accepted identities and completion proofs remain durable; replay cannot issue another reward or satisfy another duty. |

These owners use bounded payloads and durable writes for state transitions that precede external provider calls. Do not delete a sidecar to clear a pending operation; that discards the evidence needed to decide whether the provider already changed state. Back up the world, inspect the relevant record, restore provider availability/version, then use its explicit retry, confirm, restore, release, exit, or novation path.

Run provider-absence checks only on an isolated copy of the world. Loading that copy without the provider lets vanilla remove entities whose types are now unknown. A reinstall/recovery check must start from the retained provider-world snapshot, not from the copy that vanilla already loaded without those entity definitions.

The current provider contracts are:

- Minecraft Comes Alive Reborn `7.6.26+1.20.1` (declared compatible range `[7.6,8)`) for reciprocal family facts and residences.
- MCA Quests range `[1.6.5,1.7)` for native objectives, acceptance, payment, and durable completion receipts.
- Townstead `0.7.6+1.20.1` for the exact political life-stage/building evidence adapter (declared range `[0.7.6,0.8)`). There is no released Chronicles archive adapter.
- Recruits `1.15.2` for the audited faction, campaign, persistence, scheduler, and bilateral transfer extension. Version-bound extensions fail closed when that exact provider is unavailable.

## Validation evidence

- `gradlew-quiet.sh <repository> build productionTestJar productionClientTestJar -I tools/test/production-tests.gradle -I tools/client-test/client-test.gradle`: passed. Log: `/tmp/gradle-UltimaKingdoms-build-20260920-222623.log`. Includes API-jar verification and 123 unit tests: 122 passed, one existing Townstead binding test skipped.
- `python3 tools/test/run_gametests.py -PrunDir=build/r4-gametest-final`: all 39 required tests passed. Log: `/tmp/gradle-UltimaKingdoms-runGameTestServer-20260920-222703.log`.
- `python3 tools/test/integration_runtime.py --work-dir build/r4-native-g --phase r4 --mod …`: passed native consent, veto/listener recovery, counter-divergence detection, operator reconciliation, history privacy, lawful succession, competing outcomes, protection refusal/exit, restart, provider absence, retained-snapshot reinstall, and future-schema byte preservation. Logs and exact tested artifact hashes: `build/r4-native-g/`.
- The same runtime driver with `--phase r4-service` passed real MCA Quests scoped delivery, native payment, voluntary duty fulfillment and replay refusal: `build/r4-service-a/r4-service.log`.
- `--phase r4-extensions` passed elections, regency expiry, merger blockers/opt-outs, peaceful schism and provider-confirmed campaign declaration: `build/r4-extensions-b/r4-extensions.log`.
- `--phase r4-family` passed native MCA reciprocal marriage, read-only evidence, private introduction, relationship invalidation and unloaded-spouse refusal: `build/r4-family-a/r4-family.log`.
- `python3 tools/client-test/evolution_multiplayer.py --work-dir build/r4-multiplayer-e`: both real clients passed history packet privacy and opposing outcome commands; dedicated-server audit verified one terminal result and matching government state.
- `check_mod.py <repository>`: zero errors, warnings or notes. `git diff --check` passed.

Runtime provider inputs are recorded in each directory's `artifacts.sha256`; the native runtime uses the audited R3 provider jars from `build/r3-civilian-runtime-a/mods/`. Earlier runtime evidence remains applicable to unchanged feature slices. Exact commands, tested artifact hashes and final-artifact matches are recorded in `build/reports/r4-validation.json`.

- `python3 tools/test/integration_runtime.py --work-dir build/r4-crash-a --phase r4-crash --mod …`: the driver killed the process after the native handoff was saved but before R4 completion acknowledgment. Restart retained `APPLYING`, confirmed the saved outcome, and refused repeat mutation. Logs: `build/r4-crash-a/r4-crash.log` and `r4-crash-restart.log`.
- `python3 tools/client-test/r1_pack.py --r4 --work-dir build/r4-full-pack-d`: passed in the 404-mod pack using a copied existing world. Original-world hashes remained identical. The real History tab received and rendered its authorized page; screenshot: `build/r4-full-pack-d/screenshots/r4-political-history.png`.

The final packaged JAR hash matches the crash-recovery, two-client, and full-pack runs. The build artifact is `build/libs/ultima_kingdoms-0.1.0.jar`; world evolution remains opt-in.
