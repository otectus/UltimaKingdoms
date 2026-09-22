# Political compatibility and evidence

Target: Minecraft 1.20.1, Forge 47.4.23, Java 17, Ultima Kingdoms development version 0.1.0. Dependency declarations remain in `gradle.properties`; an accepted version range is not a compatibility claim.

On 2026-09-20, GitHub commit endpoints still returned the guide's exact heads:

| Repository / branch | Commit |
| --- | --- |
| UltimaKingdoms / main | `fe05c7c952367d38e699e280f019cc7321ecfc64` |
| mca_capitals_addon / forge-1.3.8 | `63358cb4f5dcc715f3edc038f3ed41979f3b790f` |
| Townstead / main | `4d6206cdf8b9d0f558694d7b35b223f4f6ace61e` |
| Townstead / early-chronicles | `f93dc271125a750c0f15741ed384282fe0531881` |

The local tree already contained additional integration work when political development began. That work was preserved. In particular MCA capability probing, faction standing, quest conditions, Townstead snapshots and external-reference indexing predate this change.

## Selected released tuple

These local artifacts were used by the pre-existing packaged server test in `build/integration-validation-20260919/townstead-pair-02`. The political evidence below is recorded separately.

| Artifact | SHA-256 |
| --- | --- |
| `minecraft-comes-alive-7.6.26+1.20.1-universal.jar` | `5dc3ee1f2f74221b3b7b73456451bf9a3f0cc87c8378a64233680f051dbc3d19` |
| `architectury-9.2.14-forge.jar` | `218b471d0b8a1f6cda14cfc1beb9eeb0df54304500acc6c5613d9b88ec65d9af` |
| `townstead-0.7.6+1.20.1.jar` | `78ab52d551dfaf2ca3366da565ccb501918f73affe7b48f339ce63b17de1513e` |
| `Patchouli-1.20.1-85-FORGE.jar` | `05f7b5d52f6b8f0fd7f8b4822fa07a192d1394d83a264309c9d221d6d4fd21c5` |

The original Townstead attempt without Patchouli failed its declared dependency check. The corrected tuple passed startup, calendar reads, building enumeration, civic reassignment and historical origin checks. Political checks subsequently passed standalone, MCA-only and the exact complete tuple, including NPC appointment, provider building recognition, restart and future-schema preservation. Ledger interaction, two-client authorization and full-day observations are tracked separately in `validation.md`.

Political NPC adulthood and institution evidence require this exact Townstead version plus healthy read capabilities. Unavailable, unsupported, unloaded or unconfirmed evidence rejects the dependent action with a reason. Without Townstead, adult vanilla villagers can hold offices. Unsupported NPC providers do not acquire fabricated adulthood evidence. Players are validated against server identities, not villager snapshot placeholder fields.

Chronicles ingestion, known-news queries, provider completion receipts, automated hereditary succession and elections are not advertised as supported. No compatibility is claimed for NeoForge, MCA 7.7, other Townstead releases, or actual Capitals co-installation. The conflict guard can be exercised independently of whether those mods can load together.

## Current validation

See `validation.md` for commands, actual results and remaining checks. Runtime artifacts and logs under `build/` are intentionally untracked. Their hashes identify the tested builds; compilation alone is not a packaged-runtime result.
