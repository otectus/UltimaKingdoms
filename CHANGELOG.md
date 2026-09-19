# Changelog

## 0.1.0

Initial release for Minecraft 1.20.1 and Forge.

### Added

- Persistent, server-authoritative settlement records with stable UUIDs, names, slugs, kingdom assignments, territory bounds, aliases, locks, and discovery provenance.
- Automatic village recognition from tagged village structures and configurable village-POI clusters, plus manual creation, discovery, merge, rename, locking, and reclassification commands.
- Five data-driven kingdoms: Serenum, Lunari, Madera, Anemosia, and Yew, with biome rules, structure-style rules, name pools, heraldry, and Serenum as the default fallback.
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
