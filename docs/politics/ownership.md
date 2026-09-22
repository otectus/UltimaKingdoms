# Political ownership

The Capitals-inspired guide is the design reference. The implementation is original code; no Capitals or Townstead source or artwork is incorporated.

Ultima owns settlement identity and a separate political sidecar: capital seats, constitutions, secondary offices, revocable player mandates, petitions, recognition, honors, peaceful agreements, named succession and political house labels. Political actions run on the server thread and modify only Ultima state. The existing faction/reputation integration remains separate; honors do not change its scores.

Townstead/MCA continue to own professions, progression, workstations, schedules, age and life stages, needs, homes, families, personal names, clothing, buildings, calendars, spirit, memories and gossip. Politics reads supported evidence and never adds AI, changes NPC names or schedules, transports residents, creates shipments, or accesses inventories. Loaded population is not treated as total population. Offices appear in the ledger without changing an NPC's name.

A player needs operator permission level 2 to constitute a government. Subsequent authority comes from explicit offices and revocable kingdom mandates; operator status alone does not sign agreements. Settlement-scoped offices cannot administer other settlements. A charter item is not a credential. Petition review and agreement signatures are authenticated again when committed.

Private proposals and petitions are filtered on the server. Public civic identity does not expose political proposals. Ratified terms retain their accepted definitions through datapack changes. Mechanical deadlines use overworld game time, never mutable day time or the Townstead calendar.

Chronicles ingestion and provider-owned commission completion are currently unavailable. No simulated delivery, reward, gossip, or archive replaces those providers. Agreement opportunity entries explain this limitation. Existing Townstead reaction integration is not a Chronicles receipt adapter.

With `mcacapitals` present, political data remains loaded and new native governance mutations are refused. The identity service continues operating. No Capitals configuration or saved data is changed.
