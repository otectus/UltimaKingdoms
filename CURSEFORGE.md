# Ultima Kingdoms

Give every village a lasting place in the world.

Ultima Kingdoms recognizes settlements, assigns each one a permanent name and kingdom, and keeps that identity stable across restarts and datapack changes. Villages become part of Serenum, Lunari, Madera, Anemosia, or Yew according to their biome and structure style when first discovered.

## Features

- Persistent settlement names, kingdom membership, territory, and discovery records
- Five kingdoms with distinct name pools, biome rules, colors, and heraldry
- Automatic detection of village structures and village point-of-interest clusters
- Craftable Village Ledger with settlement browsing, kingdom filters, and detail pages
- Configurable settlement-entry titles and heraldry
- Administrator commands for discovery, creation, renaming, kingdom assignment, locking, merging, and diagnosis
- Datapack-defined kingdoms, name pools, biome rules, and structure-style rules
- Public API for Ultima addons and other integrations
- Optional Minecraft Comes Alive Reborn integration

This initial release focuses on durable village and civic identity. Reputation, diplomacy, warfare, roads, capitals, and settlement-history simulation are outside its current scope.

## Getting started

Place the main Ultima Kingdoms jar in the `mods` directory on both the client and server. The API and sources jars are development artifacts and should not be installed. Explore loaded villages normally, or use `/ultima village discover` as an operator to scan nearby chunks.

Craft a Village Ledger with five paper (three across the top plus one at middle-left and middle-right), a book in the center, and a compass at bottom-middle. Use the ledger to browse every known settlement.

Players can inspect the world with:

```text
/ultima kingdom list
/ultima kingdom info <kingdom>
/ultima village here
/ultima village info [village]
```

Administrative commands require permission level 2. Common and client configuration files control discovery limits, civic-evidence checks, and the entry overlay.

## Requirements

- Minecraft 1.20.1
- Forge 47.x (built with Forge 47.4.23)
- Java 17
- Required on both client and server

## Minecraft Comes Alive Reborn

MCA Reborn is optional. Ultima Kingdoms works as a standalone settlement system when MCA is absent.

The compatibility adapter targets exactly MCA Reborn `7.6.26+1.20.1`. The tested MCA setup uses Architectury API `9.2.14`. With that setup, Ultima Kingdoms can recognize MCA villages and record an MCA villager's civic origin and residence from positive home evidence.

Ultima civic identity is stored separately from MCA identity. The integration does not overwrite a villager's personal name, family relationships, home, genetics, personality, dialogue state, or relationship values. A resident can remain the same MCA character while their settlement is renamed or administered through Ultima Kingdoms.

Different MCA versions are not supported by this release and should not be installed alongside it.

## For modpack authors

All geography and naming definitions are datapack resources. Packs can add or replace kingdom definitions, name pools, biome and biome-tag rules, and structure-style rules without rebuilding the mod. Existing settlements retain their saved identities after a reload unless an administrator explicitly changes them.

The release also includes a classes-only API artifact for addons that need settlement lookup, civic identity, context values, extension registrations, or lifecycle events without depending on MCA classes.

Ultima Kingdoms is All Rights Reserved.
