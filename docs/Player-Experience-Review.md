# Player experience review

Ultima Kingdoms now has two complementary in-game interfaces. The **Book of Kingdoms** explains rules, prerequisites, consequences, and recovery paths. **Kingdom Tasks** is the live, server-owned interface for reading records and taking action.

## The normal player path

Press **K** while playing to open Kingdom Tasks. Search for a goal such as “join guild,” “read election,” “browse routes,” or “transfer recruit.” The server returns only tasks the player may see; permission-level-two tasks are absent from an ordinary player's catalogue.

Each task asks for one value at a time. Saved records appear as readable, filtered choices: settlement names, player names, NPC names, governments, agreements, scenarios, protection duties, transfers, organizations, or another domain-specific label. Record identity, revisions, terms hashes, and reconciliation fingerprints are retained by the server and are not editable fields.

Consequential tasks end on a review page. It repeats the selected people and places, relevant terms, choice details, and consequences. **Apply reviewed action** performs the operation once. Backing out changes nothing. The server rejects a selection whose record or task version changed during review, and repeated apply messages return the completed session result rather than running the operation twice.

The form protocol also rejects choices copied from another player or session, expired sessions, stale page nonces, permission lost while a form is open, and records that are no longer in the viewer-filtered choice set. A delayed result can be checked without submitting the action again. These checks support the interface; they do not replace the owning service's authority, provider, durability, or prerequisite checks.

Kingdom Tasks can also be opened from **Tasks** in the Village Ledger, **Actions** on a Kingdom page, and task buttons in the War Room. Those buttons open the real server task. They do not prepare a chat command or pass a hidden client assertion to a service.

## Coverage

The live catalogue covers the current player-facing systems:

- **Settlements and identity:** read the current or a known settlement, browse kingdoms, and inspect civic identity. Operators can register, rename, reclassify, lock, discover, merge, and diagnose settlements; set confirmed origin or residence; and reload definitions.
- **Government:** browse government pages, offices, agreements, petitions, institutions, honors, and history. Dedicated tasks cover government setup, seats, appointments, mandates, agreements, petitions, recognition, honors, succession, abdication, houses, election rules, elections, and regency.
- **Civic service:** inspect guild progress, join or leave, explain eligibility, find introductions and commissions, browse chapters and institutions, establish chapters, manage chapter contacts, and check personal qualifications.
- **Warfare and exploration:** inspect military control, campaigns, sovereignty accords, mobilization, civilian contracts, sites, routes, encounters, and resource access. Authorized tasks declare or recover campaigns, negotiate accords, issue temporary orders, start routes, request access, and author bounded world context.
- **Evolving world:** inspect scenarios and digests, contribute or withdraw, resolve and recover authored outcomes, perform scoped service, configure opt-in regions, and inspect or act on voluntary protection duties.
- **Families and transfers:** request a private family introduction; propose, inspect, consent to, cancel, apply, confirm, or restore a recruit transfer; and perform guarded operator reconciliation.
- **Organizations and drama:** found a chartered organization; negotiate merger, member opt-out, accepted-job novation, finalization, or dissolution; and inspect, propose, consent to, negotiate, exit, execute, or recover authored drama.
- **Standing and administration:** operators receive separate tasks for diagnostics, standing corrections, local effects, integration status, migration preview and application, evolution settings, regions, world authoring, and recovery.

The catalogue does not grant authority. A visible task can still be blocked by current office or mandate, missing bilateral consent, an undiscovered place, an offline participant, a changed record, an unfinished obligation, a disabled world feature, or an unavailable native provider. Read and inspect tasks return the current explanation without exposing private records merely to explain a refusal.

## Government workflows

Government no longer relies on one cycling action form with reused fields. Agreements, petitions, recognition, honors, offices, succession, houses, elections, and regency have distinct tasks and labels. Private petitions are available only to their authorized viewers. Ballot choices are authenticated and private; public election inspection reports candidates, participation count, deadlines, and outcome without exposing how another player voted.

Election and regency actions are dedicated tasks. A leader first adopts a constitutional transition rule. Eligible users then open, inspect, vote in, and close elections through named choices. Regency has separate appointment and ending tasks and shows the configured responsibility set and expiry. Neither workflow treats logout, chunk unload, or NPC absence as death or incapacity.

The political History task shows durable facts according to the viewer's visibility. It remains a read interface: corrections such as superseded or expired source state are history, not an invitation to replay the original transaction.

## Recruit transfer privacy and consent

A recipient first opens **Share a recruit transfer destination**. They select one group they own and one named online sender. Only that sender may then see that named group as a destination when proposing a transfer to that recipient. The share reveals no troop count and no unrelated group. It is connection-scoped, can be revoked by the recipient, and clears when either participant logs out or the server stops.

The source owner then chooses a loaded self-owned recruit, the recipient, the shared group, and any family requirement. Review names the recruit, source owner, recipient, destination group, notice, deadline, equipment evidence, and family terms. The recipient separately reviews and consents. Sharing a group is permission to select a destination; it is not consent to transfer a recruit.

Revoking a share prevents new proposals. It does not erase a durable proposal that both services already accepted. Participants use the proposal's neutral cancel path while cancellation is allowed. After notice, the source owner may apply; confirm and restore remain guarded recovery operations. Civic identity, residence, family, buildings, inventory, and political standing are not moved by the native recruit ownership operation.

## Organization and evolving-world choices

Organization merger review identifies both organizations, both required consents, the member opt-out period, and accepted obligations that block finalization. An affected member can keep their membership from moving. Accepted civic or native jobs remain tied to their historical organization until completed, explicitly cancelled, or novated with the required authority and consent. Merger does not copy standing, qualifications, or quest identity blindly; old identities remain historical redirects.

Scenario, protection, and drama choices use viewer-filtered records. A player sees opportunities connected to known settlements and their own authorized participation. Incoming bilateral consent can be shown to the opposing commander or eligible source member with its terms while unrelated private scenarios and transfers remain hidden. Offline pauses, deadlines, global concurrency limits, and factual provider acknowledgments are enforced by the owning services.

Peaceful schism is an organization workflow. It requires real membership, charter authority, civic settlement evidence, and voluntary source-member consent. It does not require a military claim and does not rewrite a deity or erase the source organization. Campaign, rebellion, and invasion workflows remain bounded by their own authored evidence and native authority.

## Book and optional commands

Craft the Book of Kingdoms from one ordinary book and one paper. Its searchable chapters retain the full explanations for settlements, civic service, government, warfare, evolving-world systems, operators, optional providers, and recovery. Reading the Book never sends a command or changes the world.

Commands remain an optional equivalent interface for console automation and players who prefer chat. Record selectors use quoted readable names, such as `"Old Bellmeadow"`, `"Election for Serenum"`, or `"Relief at Greyhaven"`. The new name at the end of settlement create or rename consumes the remaining text and is not quoted. Revision and reconciliation fingerprint arguments shown in advanced command references are carried automatically by the GUI review; no task is command-only.

## Remaining experience gaps

The GUI is intentionally text-based. It does not render maps, relationship graphs, election charts, or a timeline visualization. Long read results use paged task output and the Book remains the fuller explanation.

Choice lists only contain records the current provider can identify and the viewer may know. Optional MCA, MCA Quests, MCA Crime, Recruits, and atlas features still depend on compatible installed companions. The task menu reports absence or refusal; it does not emulate those providers.

The task menu covers current read, action, recovery, reconciliation, and operator operations. Commands provide an equivalent automation path. Datapacks and resource packs remain the external authoring path for definitions and Book text, while unsupported or future schemas still require diagnosis before writes resume. Diverged native recruit accounting is retained for the reviewed reconciliation task rather than silently normalized. Townstead has no fabricated archive receipt path.

The interface uses current English names from the server and bundled language resources. Resource packs can replace Book topics, but all server choices still obey current discovery and privacy filters. Players must be online for interactions whose authority or consent is deliberately authenticated in the current session.

## Validation of this implementation

- `gradlew-quiet.sh /home/otectus/Projects/UltimaKingdoms build -I tools/client-test/client-test.gradle` passed: 125 unit tests passed, one skipped; API packaging checks passed. Final build log: `/tmp/gradle-UltimaKingdoms-build-20260921-000538.log`.
- `python3 tools/test/run_gametests.py -PrunDir=build/interaction-gametest-delivery` passed all 44 required dedicated-server GameTests. This includes session isolation, stale selections, repeat apply, private selectors, permission loss, stable named aliases, bounded replies, and NPC movement. Log: `/tmp/gradle-UltimaKingdoms-runGameTestServer-20260921-000405.log`.
- `python3 tools/client-test/interaction_client.py --work-dir /tmp/kingdom-actions-client-final --display :96` passed real K-key opening, search, settlement creation/reading/renaming, compact paging, and War Room campaign handoff without opening chat. Screenshots and tested artifact hashes are in that directory.
- `python3 tools/client-test/interaction_client.py --work-dir /tmp/kingdom-politics-client-1 --suite politics` passed government establishment through the new form, council and petition pages, compact named appointment choices, and return to the ledger.
- `python3 tools/test/integration_runtime.py --work-dir build/interaction-native-military-1 --phase military --mod build/r3-military-runtime-final/mods/recruits-1.20.1-1.15.2.jar` passed provider-backed campaign declaration and named recruit deployment through the form handlers, duplicate-result checks, native persistence, and restart recovery. These service-handler checks complement the packaged client tests; they do not simulate every provider workflow through mouse clicks.
- `python3 tools/client-test/book_client.py --work-dir /tmp/kingdom-book-gui-final` passed against the final JAR: recipe, actual item use, packaged guide resources, chapter/search/navigation controls, compact layout, scrolling, and reopening the last topic. Screenshots and exact artifact hashes are retained in that directory.
- `check_mod.py /home/otectus/Projects/UltimaKingdoms` reported zero errors or warnings; `git diff --check` passed.

Final local mod: `build/libs/ultima_kingdoms-0.1.0.jar`, SHA-256 `d536a7fd2da71ad54a00632f86b6854b8b91a8a3157e0d1b35bf94c61ce5db3c`. The final package includes the subsequent guide/help corrections and nearby-person location hints; the final dedicated-server suite covers the location-hint change. Client test directories retain the exact hashes they tested.
