# Political expansion validation — 2026-09-20

The first-release loop is implemented in the existing mod: authorized founding and capital moves, secondary offices and revocable mandates, petitions and decisions, provider-verified institution recognition, discretionary honors, two-party peaceful agreements, six ledger tabs, separate persistence and public API. Named succession/abdication/interregnum and a political house label/motto are also playable. Elections, regencies, automatic hereditary policy, multi-house institutions, Chronicles delivery and provider-backed commissions remain gated; they are not counted as completed features.

Target: Java 17, Minecraft 1.20.1, Forge 47.4.23. Optional artifacts and hashes are in [compatibility-matrix.md](compatibility-matrix.md).

## Build and domain checks

Commands were run from the repository; the Gradle helper uses its absolute repository argument.

```sh
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/UltimaKingdoms build productionTestJar productionClientTestJar -I tools/test/production-tests.gradle -I tools/client-test/client-test.gradle
python3 tools/test/run_gametests.py -PrunDir=build/politics-gametest-20260920-release
python3 tools/test/verify_artifacts.py
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py /home/otectus/Projects/UltimaKingdoms --json
git diff --check
```

- Build passed: `/tmp/gradle-UltimaKingdoms-build-20260920-091544.log`.
- JUnit: 32 discovered, 31 passed, one pre-existing optional Townstead binding test skipped, no failures/errors. All four political persistence tests passed. XML reports: `build/test-results/test/`.
- GameTests: all 35 required tests passed, including political authorization/privacy/replay/stale revisions, hash validation, revoked signatory authority, named succession, duplicate/canceled NPC death, settlement-only migration, capital reassignment, merge redirect and conflicting local offices. Log: `/tmp/gradle-UltimaKingdoms-runGameTestServer-20260920-091904.log`.
- Artifact checks passed for main/API/sources: no bundled optional dependencies or test fixtures; API contains only API classes. Content helper returned no findings; whitespace check passed.

Political milestone main jar (before the subsequent Shimaguni addition; `build/libs` may contain a newer build): `build/libs/ultima_kingdoms-0.1.0.jar`, SHA-256 `a5359f9ed0cf42a24ae68e2d2ddb78004f186bceed8f3973b69e081ff366a68b`.
API jar SHA-256: `f448706a91aeb26206bd033b030004caaf5416e819a766bfaf1b5197c5d858aa`.
Sources jar SHA-256: `6e3f642d9ca35283af33c88acd68b6878c98823fe647894d44547b0875f2261b`.

## Packaged servers

```sh
python3 tools/test/integration_runtime.py --work-dir build/politics-production-standalone-20260920-release --phase politics
python3 tools/test/integration_runtime.py --work-dir build/politics-production-mca-20260920-release --phase politics --mod build/integration-validation-20260919/townstead-pair-02/mods/minecraft-comes-alive-7.6.26+1.20.1-universal.jar --mod build/integration-validation-20260919/townstead-pair-02/mods/architectury-9.2.14-forge.jar
python3 tools/test/integration_runtime.py --work-dir build/politics-production-townstead-20260920-release-b --phase politics --mod build/integration-validation-20260919/townstead-pair-02/mods/minecraft-comes-alive-7.6.26+1.20.1-universal.jar --mod build/integration-validation-20260919/townstead-pair-02/mods/architectury-9.2.14-forge.jar --mod build/integration-validation-20260919/townstead-pair-02/mods/townstead-0.7.6+1.20.1.jar --mod build/integration-validation-20260919/townstead-pair-02/mods/Patchouli-1.20.1-85-FORGE.jar
```

Standalone and MCA-only passed creation, process restart, retained receipt replay and byte-identical future-schema preservation. Each directory contains `politics.log`, `politics-restart.log`, `politics-future.log` and `artifacts.sha256`. The exact MCA/Townstead tuple also passed all three phases, including live provider adulthood/building reads and retained institutional recognition.

The fixture initializes an adult librarian through normal spawn initialization. Political appointment compares complete NPC NBT before/after commit, including native provider data. Institution recognition uses the actual Townstead point-query API against a fixture library; test setup alone constructs the provider fixture. This does not claim the library was built organically by an NPC.

One immediate-shutdown Townstead repetition hit an upstream MCA entity-load/shutdown wait: server thread in `EntityStorage`, IO worker loading MCA age state and waiting for chunk access. Thread dump: `/tmp/ultima-political-restart-threads.txt`; failed attempt: `build/politics-production-townstead-20260920-release`. The test harness now permits 80 normal ticks before restart/future-test shutdown, allowing asynchronous loading to settle. No provider behavior was patched.

## Full Townstead day

```sh
python3 tools/test/integration_runtime.py --work-dir build/politics-production-cycle-20260920 --phase politics-cycle --mod build/integration-validation-20260919/townstead-pair-02/mods/minecraft-comes-alive-7.6.26+1.20.1-universal.jar --mod build/integration-validation-20260919/townstead-pair-02/mods/architectury-9.2.14-forge.jar --mod build/integration-validation-20260919/townstead-pair-02/mods/townstead-0.7.6+1.20.1.jar --mod build/integration-validation-20260919/townstead-pair-02/mods/Patchouli-1.20.1-85-FORGE.jar
```

Passed 24,000 actual simulation ticks with repeated political council queries. Observed activities: idle, meet, rest and work. NPC remained alive; librarian profession and configured shifts remained identical. The test did not accelerate time or rewrite needs. Hunger/fatigue observations are in the log; they are not evidence of a simulated resource economy. Fixture chunk loading, bed/workstation setup and peaceful difficulty belong to the test harness only.

Log: `build/politics-production-cycle-20260920/politics-cycle.log`; artifact manifest in the same directory. This long observation used the earlier main-jar hash `1158b861d626ff03ba291fe9db1e2f8cd28fc2fdd55a50e38971d5160aae0c13`. Its NPC appointment/preservation behavior is unchanged in the final jar; later changes concern scope validation, diagnostics, saved-record validation and moving the verified provider version into build configuration. Final-jar commit-time NPC preservation and provider evidence were separately rerun above.

## Client checks

```sh
env -u WAYLAND_DISPLAY DISPLAY=:97 XDG_SESSION_TYPE=x11 LIBGL_ALWAYS_SOFTWARE=1 /home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/UltimaKingdoms runClientTest -I tools/client-test/client-test.gradle -PclientTestPolitics=true
env -u WAYLAND_DISPLAY DISPLAY=:97 XDG_SESSION_TYPE=x11 LIBGL_ALWAYS_SOFTWARE=1 python3 tools/client-test/politics_multiplayer.py --work-dir build/politics-multiplayer-20260920-release-c
```

Integrated client passed actual packets, capital founding, council display, action clicks, compact-window form, tabs and ledger return. Log: `/tmp/gradle-UltimaKingdoms-runClientTest-20260920-092004.log`; marker `build/client-validation/POLITICS_PASS.txt`. Screenshots: `build/client-validation/screenshots/politics-{overview,council,form-compact,petitions-compact}.png`.

Two real clients against the final packaged dedicated server passed: the authorized player receives and signs the private proposal; the unrelated player receives no proposal and is refused even when given its record ID/hash. Markers: `build/politics-multiplayer-20260920-release-c/{leader,stranger}-PASS.txt`; client/server logs and `artifacts.sha256` are in that directory.

Two earlier repetitions timed out because the minimal harness did not retry a request dropped by the four-server-tick rate limit. Client ticks can catch up faster than server ticks during login. The harness now retries the identical request after one second, exercising persisted receipt semantics; the production ledger already has bounded identical-request retries. This was a harness correction, not a relaxation of authorization or throttling. Failed attempts remain in `build/politics-multiplayer-20260920-release` and `-release-b`.

Test-only harness rebuild logs: `/tmp/gradle-UltimaKingdoms-productionTestJar-20260920-092150.log` and `/tmp/gradle-UltimaKingdoms-productionClientTestJar-20260920-092626.log`. These do not change the final main jar.

## Scope of evidence

The Capitals conflict guard is exercised with an injected detection result; actual Capitals co-installation is not claimed. No political Chronicles/quest/reputation/conversation adapter is advertised, so no provider delivery/reward claim is inferred from existing unrelated integration tests. Simulation observation uses API ledger queries; live mouse interaction is tested separately. Runtime outputs under `build/` and logs under `/tmp/` are local evidence, not committed release assets. Existing unrelated integration changes in the working tree were preserved.
