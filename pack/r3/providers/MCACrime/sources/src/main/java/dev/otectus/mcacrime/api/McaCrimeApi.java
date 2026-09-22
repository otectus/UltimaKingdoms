package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.api.model.CrimePlayerSnapshot;
import dev.otectus.mcacrime.api.model.CrimePublicView;
import dev.otectus.mcacrime.api.model.CrimeRecordQuery;
import dev.otectus.mcacrime.api.model.CrimeRecordSelector;
import dev.otectus.mcacrime.api.model.CrimeRecordView;
import dev.otectus.mcacrime.api.model.CivicContractView;
import dev.otectus.mcacrime.api.model.ServiceRefusalView;
import dev.otectus.mcacrime.api.model.CustodyView;
import dev.otectus.mcacrime.api.model.JailSentenceView;
import dev.otectus.mcacrime.api.model.OutlawStatusView;
import dev.otectus.mcacrime.api.jurisdiction.JurisdictionPolicies;
import dev.otectus.mcacrime.api.jurisdiction.JurisdictionPolicyProvider;
import dev.otectus.mcacrime.api.jurisdiction.JurisdictionSnapshot;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.enforcement.LegalTarget;
import dev.otectus.mcacrime.enforcement.OutlawResolver;
import dev.otectus.mcacrime.enforcement.OutlawStatus;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.state.CrimeCapabilities;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stable public surface for MCA: Crime (spec §16). Other mods can read a player's standing and case
 * history without touching internals; mutation stays server-internal through {@link CrimeState} and
 * the case service, with the two named exceptions of {@link #commuteCapitalSentence} and
 * {@link #pardonCapitalSentence} -- clemency over a capital sentence, which 0.7.5 §3.19 requires to be
 * reachable as an explicit privileged transaction rather than only from an operator's keyboard. To
 * react to changes, subscribe to the events in {@code dev.otectus.mcacrime.api.event} on the Forge
 * event bus.
 *
 * <h2>Contracts every method here honours</h2>
 *
 * <ul>
 *   <li><b>Nothing throws at an integration.</b> A dialogue evaluation or a quest condition must never
 *       crash because of this mod; failures come back as an empty result or a neutral value.</li>
 *   <li><b>Only immutable views cross the boundary.</b> Never a {@code CrimeRecord}, never a
 *       capability, never the {@code SavedData}.</li>
 *   <li><b>Reads are scoped to the player asked about.</b> There is no "any player" query, which is
 *       what structurally prevents one player enumerating another's history.</li>
 * </ul>
 *
 * <h2>Versioning</h2>
 *
 * <p>{@link #getApiVersion()} lets a bridge refuse an incompatible future version instead of dying on
 * a {@code NoSuchMethodError}. The version is deliberately <b>not</b> a public constant: {@code javac}
 * copies a {@code public static final int} straight into the consumer's own constant pool, so a
 * companion compiled against v1 would keep reading 1 forever, and the handshake it was written to
 * perform would silently never fire.
 */
public final class McaCrimeApi {

    /**
     * Incremented only on a breaking change to this class's signatures.
     *
     * <p>Two in 0.7.5: the physical-restraint surface, the capital-sentence surface and their events
     * are added, and {@code CustodyView.custodyId} finally carries a real value. Every addition is
     * additive — no existing method changed shape — so a v1 consumer keeps working, which is why this
     * is a version bump rather than a deprecation.
     */
    private static final int API_VERSION = 2;

    private McaCrimeApi() {
    }

    /** The binary API generation. Bridges should refuse anything they were not written against. */
    public static int getApiVersion() {
        return API_VERSION;
    }

    /** Memory-sensitive dialogue and quest context for this villager/player pair only. */
    public static List<dev.otectus.mcacrime.api.model.VictimMemoryView> victimMemories(
            MinecraftServer server, UUID villager, UUID player) {
        try { return dev.otectus.mcacrime.memory.VictimMemoryService.memories(server, villager, player); }
        catch (RuntimeException e) { return List.of(); }
    }

    /**
     * Registers a currency MCA: Crime can charge fines, bail, ransom, theft and bounties in.
     *
     * <p>Call during common setup, <b>before</b> the first config reload: the active currency is
     * resolved from {@code integrations.currencyId} at that point, and an id registered afterwards is
     * only picked up by the next reload.
     *
     * <p>An implementation whose balance is virtual — a bank account, a purse, a database row — must
     * return {@code hasItemForm() == false}, so nothing tries to drop it on the ground and lose it.
     *
     * <p>Not a version bump: this class versions on breaking changes to existing signatures, and an
     * added static method is not one. A companion written for v1 keeps working untouched.
     */
    public static void registerCurrency(dev.otectus.mcacrime.economy.Currency currency) {
        try { dev.otectus.mcacrime.economy.Currencies.register(currency); }
        catch (RuntimeException ignored) { /* nothing here throws at an integration */ }
    }

    // ------------------------------------------------------------------ existing surface (unchanged)

    public static long getKarma(ServerPlayer player) {
        return CrimeState.getKarma(player);
    }

    public static long getHeat(ServerPlayer player) {
        return CrimeState.getHeat(player);
    }

    public static Band getBand(ServerPlayer player) {
        return CrimeState.getBand(player);
    }

    public static boolean isWanted(ServerPlayer player) {
        return CrimeState.isWanted(player);
    }

    // ------------------------------------------------------------------ jurisdiction policy extension

    /** Installs one server-scoped source of immutable sovereignty, control and treaty evidence. */
    public static void registerJurisdictionPolicyProvider(MinecraftServer server,
                                                           JurisdictionPolicyProvider provider) {
        JurisdictionPolicies.attach(server, provider);
    }

    public static void unregisterJurisdictionPolicyProvider(MinecraftServer server,
                                                             JurisdictionPolicyProvider provider) {
        JurisdictionPolicies.detach(server, provider);
    }

    /** Explainable policy snapshot only; it exposes no private case, report, mask or Heat state. */
    public static Optional<JurisdictionSnapshot> jurisdictionPolicy(MinecraftServer server,
                                                                    CrimeCommunityKey community,
                                                                    @Nullable UUID subject) {
        return JurisdictionPolicies.resolve(server, community, subject);
    }

    /**
     * Whether this responder has a Crime-owned local basis to use force against this target.
     * Occupation, political hostility and a global Heat value alone are insufficient. Callers must
     * still apply Minecraft/server protection, friendly-fire and attack-capability rules.
     */
    public static boolean mayEnforce(LivingEntity responder, LivingEntity target) {
        if (responder == null || target == null || responder == target || !responder.isAlive()
                || !target.isAlive() || responder.level() != target.level()
                || !(responder.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            return false;
        try {
            CrimeCommunityKey community = JurisdictionPolicies.jurisdictionOf(level, responder).orElse(null);
            if (community == null) return false;
            Optional<JurisdictionSnapshot> evidence = JurisdictionPolicies.resolve(
                    level.getServer(), community, target.getUUID());
            if (!JurisdictionPolicies.civilianLawContinues(evidence)) return false;
            var configured = dev.otectus.mcacrime.justice.JusticeService.Settings.fromConfig();
            boolean cooperation = evidence.filter(e -> e.availability() == JurisdictionSnapshot.Availability.AVAILABLE
                    && e.cooperation() == JurisdictionSnapshot.Cooperation.CONFIGURED).isPresent();
            var settings = new dev.otectus.mcacrime.justice.JusticeService.Settings(
                    configured.observations(), configured.globalPropagation() && cooperation, configured.confidence());
            boolean escaped = target instanceof ServerPlayer player && LegalTarget.isEscapedPrisoner(player);
            boolean captive = target instanceof ServerPlayer player && LegalTarget.isHoldingCaptive(player);
            boolean resisting = target instanceof ServerPlayer player && LegalTarget.isResistingArrest(player);
            boolean wanted = target instanceof ServerPlayer player && cooperation
                    && JurisdictionPolicies.allowsStandaloneWanted(evidence) && CrimeState.isWanted(player);
            var decision = dev.otectus.mcacrime.justice.JusticeService.evaluate(
                    CrimeWorldData.get(level.getServer()), target.getUUID(), community, level.getGameTime(),
                    settings, escaped, captive, wanted, resisting, false);
            // Wearing a mask is grounds to challenge, never force. An active witnessed masked pursuit is different.
            return decision.mayChallenge() || target instanceof ServerPlayer player
                    && LegalTarget.isMaskedPursuit(player);
        } catch (RuntimeException | LinkageError failure) {
            McaCrime.LOGGER.debug("MCA: Crime local enforcement query failed closed", failure);
            return false;
        }
    }

    // ------------------------------------------------------------------ snapshots

    /**
     * Everything about a player's legal standing at one instant.
     *
     * <p>One object rather than six calls, so a caller cannot end up holding a karma reading from
     * before a change and a jail state from after it.
     */
    public static Optional<CrimePlayerSnapshot> snapshot(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            MinecraftServer server = player.getServer();
            long outstandingFines = 0L;
            int unresolved = 0;
            if (server != null) {
                for (CrimeRecord record : CrimeWorldData.get(server).actionableFor(player.getUUID())) {
                    unresolved++;
                    outstandingFines += Math.max(0L, record.fineAmount());
                }
            }
            return Optional.of(new CrimePlayerSnapshot(
                    player.getUUID(),
                    CrimeState.getKarma(player),
                    CrimeState.getHeat(player),
                    CrimeState.getBand(player),
                    CrimeState.isWanted(player),
                    OutlawResolver.resolve(player).lawfulCombatTarget(),
                    JailService.isJailed(player),
                    JailService.remainingTicks(player),
                    CustodyService.isCaptive(player),
                    LegalTarget.isHoldingCaptive(player),
                    unresolved,
                    outstandingFines));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — snapshot failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * Whether force against this player is lawful right now, and why (0.5.1).
     *
     * <p>Separate from {@link #snapshot} rather than folded into it: a companion that only wants to
     * know whether its own NPC may attack somebody should not have to pay for a ledger walk, and
     * {@code CrimePlayerSnapshot} is a published shape that widening would break.
     */
    public static Optional<OutlawStatusView> outlawStatus(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            OutlawStatus status = OutlawResolver.resolve(player);
            return Optional.of(new OutlawStatusView(
                    status.lawfulCombatTarget(),
                    status.lethalForceLawful(),
                    status.bountyEligible(),
                    status.basis().reasonKey(),
                    status.heat(),
                    status.karma(),
                    status.band()));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — outlaw status failed; returning empty", t);
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ records

    /** One case by its exact id, or empty. */
    public static Optional<CrimeRecordView> record(MinecraftServer server, UUID recordId) {
        try {
            return CrimeCaseService.view(server, recordId);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — record lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * This player's cases matching {@code query}, bounded and deterministically ordered.
     *
     * <p>Scoped to {@code player} as the offender, with no way to ask about anyone else. Administrative
     * cross-player reads go through {@code /crime ledger}, which has its own permission check.
     */
    public static List<CrimeRecordView> selectRecords(ServerPlayer player, CrimeRecordQuery query) {
        if (player == null || player.getServer() == null) {
            return List.of();
        }
        try {
            CrimeRecordQuery effective = query == null ? CrimeRecordQuery.ANY : query;
            MinecraftServer server = player.getServer();
            List<CrimeRecord> records = CrimeWorldData.get(server).recordsForOffender(player.getUUID());
            List<CrimeRecordView> views = new ArrayList<>(records.size());
            records.forEach(record -> views.add(record.view()));
            return CrimeRecordSelector.select(views, effective, server.overworld().getGameTime());
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — record selection failed; returning empty", t);
            return List.of();
        }
    }

    /** How many of this player's cases are still actionable, without materialising them. */
    public static int unresolvedCaseCount(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return 0;
        }
        try {
            return CrimeWorldData.get(player.getServer()).actionableFor(player.getUUID()).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ custody and sentences

    /** Who is holding this entity, if anyone. Works for players and villagers alike. */
    public static Optional<CustodyView> custody(MinecraftServer server, UUID entityId) {
        if (server == null || entityId == null) {
            return Optional.empty();
        }
        try {
            CustodyRecord record = CrimeWorldData.get(server).getCustody(entityId);
            if (record == null) {
                return Optional.empty();
            }
            return Optional.of(new CustodyView(
                    record.getCaptive(),
                    record.isCaptivePlayer(),
                    record.isLawful(),
                    record.getOwner() == null ? Optional.empty() : record.getOwner().ownerUuid(),
                    // Projected from the gear actually on them: the record stopped holding a restraint
                    // in 0.7.5, and this deprecated field is answered from the physical state instead.
                    dev.otectus.mcacrime.restraint.LegacyRestraintProjection.of(
                            CrimeWorldData.get(server).physicalRestraint(entityId)),
                    record.getRealTicksHeld(),
                    record.getRemainingJailTicks(),
                    Optional.ofNullable(record.getHoldDim()),
                    // Real since 0.7.5: the custody identity a companion needs to tell two successive
                    // captures of one subject apart. The linked case stays empty -- a custody record
                    // names a sentence, and a sentence is not a case.
                    Optional.ofNullable(record.getCustodyId()),
                    Optional.empty()));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — custody lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    /** This player's current sentence, or empty when they are not serving one. */
    public static Optional<JailSentenceView> sentence(ServerPlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        try {
            return CrimeCapabilities.get(player)
                    .map(PlayerCrimeData::getJail)
                    .filter(jail -> jail != null)
                    .map(jail -> toView(jail, CrimeWorldData.get(player.getServer()), player.getUUID()));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — sentence lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    static JailSentenceView toView(JailState jail, CrimeWorldData world, UUID offender) {
        return new JailSentenceView(
                Optional.of(jail.getSentenceId()),
                jail.getRemainingOnlineTicks(),
                jail.getRealOnlineTicksServed(),
                Optional.ofNullable(jail.getJailDim()),
                jail.isEscaped(),
                jail.getModeSnapshot(),
                world.casesForSentence(offender, jail.getSentenceId()).stream()
                        .map(dev.otectus.mcacrime.ledger.CrimeRecord::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                // Additive since API version 2 (§3.19): what kind of sentence this is.
                world.sentenceKind(jail.getSentenceId()).id());
    }

    // ------------------------------------------------------------------ communities

    /**
     * This player's standing with a community, from MCA: Crime's own store.
     *
     * <p>Named for what it actually reads, and deliberately left that way. It has always answered from
     * this mod's fallback table, and a companion that treated it as "the reputation mod's number" was
     * reading something else than it thought (reference §11.6). Changing it to consult MCA: Reputation
     * would silently move the ground under every existing caller, so the companion-aware answer is a
     * second method instead — see {@link #effectiveStanding}.
     */
    public static int communityStanding(MinecraftServer server, UUID playerId, CrimeCommunityKey community) {
        if (server == null || playerId == null || community == null) {
            return 0;
        }
        try {
            return CrimeWorldData.get(server).reputation(community, playerId);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The standing a settlement should actually act on: MCA: Reputation's, when it is keeping it.
     *
     * <p>The two stores are not redundant copies, they are two different claims. When MCA: Reputation is
     * installed and MCA: Crime has handed it the authority for these deeds, <em>its</em> number is the
     * one the deeds were filed against and this mod's fallback table has stopped being updated for
     * them; reading the fallback then would report standing frozen at whenever the companion arrived.
     * With no companion, the fallback is the only number there is and is exactly right.
     *
     * <p>Falls back rather than failing on every uncertainty — companion absent, integration switched
     * off, a score it does not hold — because a neutral-but-stale answer is a village that is slightly
     * behind, and a thrown exception is a dialogue that does not open.
     */
    public static int effectiveStanding(MinecraftServer server, UUID playerId, CrimeCommunityKey community) {
        if (server == null || playerId == null || community == null) {
            return 0;
        }
        try {
            java.util.OptionalInt companion = dev.otectus.mcacrime.compat.ReputationBridge.ops()
                    .map(ops -> ops.score(server, playerId, community))
                    .orElse(java.util.OptionalInt.empty());
            if (companion.isPresent()) {
                return companion.getAsInt();
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — effective standing lookup failed; using the local store", t);
        }
        return communityStanding(server, playerId, community);
    }

    // ------------------------------------------------------------------ public projections

    /**
     * What one community may know about one person.
     *
     * <p>The method to call from a settlement reaction, a dialogue condition or anything that draws a
     * status where other people can see it. {@link #selectRecords} is the player's own file and is
     * scoped to them for exactly that reason; this is the village's knowledge, and the difference is
     * every unwitnessed crime, every crime in another village, and every crime nobody has yet reported
     * (reference §11.1). A companion that populated a village alert from the record list would be
     * broadcasting things nobody saw.
     *
     * <p>The knowledge rule lives in {@link CrimePublicView#isPublic} and is the same one the civic
     * incident filing uses, so what a village reacts to and what MCA: Reputation recorded cannot drift
     * apart.
     */
    public static Optional<CrimePublicView> publicView(MinecraftServer server, CrimeCommunityKey community,
                                                       UUID subject) {
        if (server == null || community == null || subject == null) {
            return Optional.empty();
        }
        try {
            CrimeWorldData data = CrimeWorldData.get(server);
            List<CrimeRecordView> views = new ArrayList<>();
            data.recordsForOffender(subject).forEach(record -> views.add(record.view()));

            boolean observations = dev.otectus.mcacrime.McaCrimeConfig.COMMON.enableObservations.get();
            double confidence =
                    dev.otectus.mcacrime.McaCrimeConfig.COMMON.reportConfidenceThreshold.get();
            // Built once from the report index rather than re-scanned per case: the projection is asked
            // for on a dialogue open and on a reaction, and a scan per case would be quadratic in a
            // long-running world.
            Set<UUID> reported = new java.util.HashSet<>();
            data.reportsAgainst(subject).stream()
                    .filter(report -> report.supportsArrest(confidence))
                    .forEach(report -> reported.add(report.incidentId()));

            ServerPlayer online = server.getPlayerList().getPlayer(subject);
            Band band = online == null
                    ? Band.fromKarma(0L)
                    : CrimeState.getBand(online);
            boolean wanted = online != null
                    ? CrimeState.isWanted(online)
                    : data.warrant(subject) != null;
            long bounty = wanted ? dev.otectus.mcacrime.bounty.BountyService.price(server, subject) : 0L;

            return Optional.of(CrimePublicView.of(community, subject, band, wanted,
                    effectiveStanding(server, subject, community), bounty, views, observations,
                    reported::contains));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — public view failed; returning empty", t);
            return Optional.empty();
        }
    }

    /** The same, for a player who is online, where band and wanted status are read directly. */
    public static Optional<CrimePublicView> publicView(ServerPlayer player, CrimeCommunityKey community) {
        return player == null || player.getServer() == null
                ? Optional.empty()
                : publicView(player.getServer(), community, player.getUUID());
    }

    // ------------------------------------------------------------------ civic layer

    /**
     * Whether one villager will serve one person, and what they say if not (reference §11.5).
     *
     * <p>Published so a settlement companion can ask MCA: Crime's question instead of building its own
     * answer out of a band and a wanted flag. The rule has one exception that must never be got wrong —
     * food, shelter and care are never refused — and a second implementation of it is a second chance
     * to strand a player with no route back.
     *
     * <p>Answers "served" for everything when {@code townstead.serviceRestrictions} is off, which is
     * the default, and for any service kind it does not recognise. Never throws.
     *
     * @param provider    the villager being asked; their own memory is what makes a refusal personal
     * @param subject     who is asking
     * @param serviceKind one of {@code essential_food}, {@code essential_shelter}, {@code trade},
     *                    {@code luxury}, {@code fence}
     */
    public static ServiceRefusalView serviceRefusal(net.minecraft.server.level.ServerLevel level,
                                                    net.minecraft.world.entity.Entity provider,
                                                    UUID subject, String serviceKind) {
        try {
            dev.otectus.mcacrime.civic.ServiceKind kind =
                    dev.otectus.mcacrime.civic.ServiceKind.parse(serviceKind).orElse(null);
            if (kind == null) {
                return ServiceRefusalView.allowed(serviceKind == null ? "" : serviceKind);
            }
            dev.otectus.mcacrime.civic.ServiceRestrictionPolicy.Decision decision =
                    dev.otectus.mcacrime.civic.ServiceRestrictions.decide(level, provider, subject, kind);
            return new ServiceRefusalView(decision.refused(), kind.id(), decision.reasonKey(),
                    decision.repairKey());
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — service refusal query failed; serving", t);
            return ServiceRefusalView.allowed(serviceKind == null ? "" : serviceKind);
        }
    }

    /**
     * Every civic service contract this offender has, newest last (reference §12.1).
     *
     * <p>Read-only, and there is deliberately no companion method to accept or advance one. Work is
     * credited only from transitions MCA: Crime observed itself, so a presentation layer can show a
     * contract and cannot complete one — which is what "Crime retains the case and completion
     * authority" has to mean in code rather than in a comment.
     */
    public static List<CivicContractView> civicContracts(MinecraftServer server, UUID offender) {
        if (server == null || offender == null) {
            return List.of();
        }
        try {
            List<CivicContractView> views = new ArrayList<>();
            for (dev.otectus.mcacrime.civic.ServiceContract contract
                    : CrimeWorldData.get(server).serviceContractsFor(offender)) {
                views.add(new CivicContractView(contract.contractId(), contract.caseId(),
                        contract.offender(), contract.community().asString(), contract.task().id(),
                        contract.requiredUnits(), contract.completedUnits(), contract.deadline(),
                        contract.state().id()));
            }
            return List.copyOf(views);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — civic contract query failed; returning empty", t);
            return List.of();
        }
    }

    // ------------------------------------------------------------------ the physical engine (0.7.5)

    /**
     * What is physically on one subject: worn restraints, the hold on them, the device holding them.
     *
     * <p>The physical half of the §1.4 split. {@link #custody} answers the legal half, and the two
     * are deliberately separate calls because they are separate facts: a villager can be in
     * handcuffs and under no sentence, or serving a sentence in an open cell wearing nothing.
     */
    public static Optional<dev.otectus.mcacrime.api.model.RestraintView> restraints(
            MinecraftServer server, UUID subjectId) {
        if (server == null || subjectId == null) {
            return Optional.empty();
        }
        try {
            dev.otectus.mcacrime.restraint.PhysicalRestraintState state =
                    CrimeWorldData.get(server).physicalRestraint(subjectId);
            if (state == null) {
                return Optional.empty();
            }
            List<dev.otectus.mcacrime.api.model.RestraintSlotView> slots = new ArrayList<>(3);
            for (dev.otectus.mcacrime.restraint.RestraintSlot slot
                    : dev.otectus.mcacrime.restraint.RestraintSlot.values()) {
                state.slot(slot).ifPresent(worn -> slots.add(
                        dev.otectus.mcacrime.restraint.PhysicalApiEvents.view(slot, worn)));
            }
            return Optional.of(new dev.otectus.mcacrime.api.model.RestraintView(subjectId,
                    state.generation(), state.revision(), slots,
                    Optional.ofNullable(state.tetherId()), Optional.ofNullable(state.detentionId()),
                    Optional.ofNullable(state.dimension())));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — restraint lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    /** The one hold on this subject — chain, anchor or escort — if there is one. */
    public static Optional<dev.otectus.mcacrime.api.model.TransportView> transport(
            MinecraftServer server, UUID subjectId) {
        if (server == null || subjectId == null) {
            return Optional.empty();
        }
        try {
            CrimeWorldData data = CrimeWorldData.get(server);
            return dev.otectus.mcacrime.tether.TetherService.forSubject(data, subjectId).stream()
                    .findFirst()
                    .map(tether -> new dev.otectus.mcacrime.api.model.TransportView(tether.id(),
                            tether.subject(), tether.kind().name().toLowerCase(java.util.Locale.ROOT),
                            tether.holderId(),
                            tether.anchorPos() == null ? Optional.empty()
                                    : Optional.of(new long[] {tether.anchorPos().getX(),
                                            tether.anchorPos().getY(), tether.anchorPos().getZ()}),
                            Optional.ofNullable(tether.dimension()), tether.lengthBlocks(),
                            tether.kind() == dev.otectus.mcacrime.tether.TetherKind.ESCORT));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — transport lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    /** The device holding this subject, if one is. */
    public static Optional<dev.otectus.mcacrime.api.model.DetentionView> detention(
            MinecraftServer server, UUID subjectId) {
        if (server == null || subjectId == null) {
            return Optional.empty();
        }
        try {
            CrimeWorldData data = CrimeWorldData.get(server);
            return dev.otectus.mcacrime.detention.DetentionService.forSubject(data, subjectId)
                    .map(record -> new dev.otectus.mcacrime.api.model.DetentionView(record.id(),
                            record.subject(), record.kind().id(),
                            Optional.ofNullable(record.dimension()),
                            record.devicePos() == null ? new long[] {0L, 0L, 0L}
                                    : new long[] {record.devicePos().getX(), record.devicePos().getY(),
                                            record.devicePos().getZ()},
                            record.occupantGeneration(),
                            dev.otectus.mcacrime.detention.ExecutionAuthorization.condemned(server,
                                    subjectId)));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — detention lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * The capital sentence against this subject, if there is one (0.7.5 §3.19).
     *
     * <p>Read-only in the strongest sense: there is no API method to assign, carry out or clear one.
     * Clemency is an operator's explicit privileged transaction and an execution is a deliberate act
     * at a device, and neither is something a companion mod should be able to do by calling a method.
     */
    public static Optional<dev.otectus.mcacrime.api.model.CapitalSentenceView> capitalSentence(
            MinecraftServer server, UUID subjectId) {
        if (server == null || subjectId == null) {
            return Optional.empty();
        }
        try {
            CrimeWorldData data = CrimeWorldData.get(server);
            CustodyRecord held = data == null ? null : data.getCustody(subjectId);
            if (held == null || held.getSentenceId() == null
                    || !data.sentenceKind(held.getSentenceId()).capital()) {
                return Optional.empty();
            }
            long now = server.overworld().getGameTime();
            var pending = dev.otectus.mcacrime.detention.ExecutionAuthorization.pending(subjectId, now);
            return Optional.of(new dev.otectus.mcacrime.api.model.CapitalSentenceView(subjectId,
                    held.isCaptivePlayer(), held.getSentenceId(), held.getRemainingJailTicks(),
                    pending.isPresent(),
                    pending.map(order -> new long[] {order.device().getX(), order.device().getY(),
                            order.device().getZ()}),
                    pending.map(order -> order.expiresAt()).orElse(0L)));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — capital sentence lookup failed; returning empty", t);
            return Optional.empty();
        }
    }

    /**
     * Commutes a capital sentence to a custodial one (0.7.5 §3.19, M6.8).
     *
     * <p>One of the two clemency paths, and the only mutators this facade offers over the capital
     * model. Nothing here can assign a capital sentence or carry one out: assignment is the legal
     * system's own answer to killing a guard, and an execution is a deliberate act at a device. What
     * a companion mod may do is show mercy, and the plan requires that to be reachable by a running
     * mod as well as by an operator's command -- a courthouse mod, a quest reward for a rescued
     * family, a governor plugin.
     *
     * <p>The same service path the {@code /crime capital commute} command uses, so the holding term is
     * kept, any pending execution is cleared, the condemned escort is called off, and
     * {@code SentenceCommutedEvent} fires exactly once whichever route asked for it.
     *
     * @param authority who is granting it, for the audit line; {@code null} for the server itself.
     *                  Commutation records no actor on the cases, because it closes none -- it
     *                  rewrites the sentence kind and leaves every case exactly where it was.
     * @return what happened, never {@code null}: {@code NOT_CONDEMNED} when there is no capital
     *         sentence, {@code REFUSED} when the store is read-only
     */
    public static dev.otectus.mcacrime.ledger.CapitalSentenceService.Clemency commuteCapitalSentence(
            MinecraftServer server, UUID subjectId, @Nullable UUID authority) {
        if (server == null || subjectId == null) {
            return dev.otectus.mcacrime.ledger.CapitalSentenceService.Clemency.REFUSED;
        }
        try {
            if (authority != null) {
                McaCrime.LOGGER.info("MCA: Crime - API commutation of the capital sentence against {} "
                        + "granted by {}", subjectId, authority);
            }
            return dev.otectus.mcacrime.ledger.CapitalSentenceService.commute(server, subjectId);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime - capital commutation failed; refusing", t);
            return dev.otectus.mcacrime.ledger.CapitalSentenceService.Clemency.REFUSED;
        }
    }

    /**
     * Pardons the cases a capital sentence was for, and clears the sentence with them (M6.8).
     *
     * <p>A privileged transaction, and it stays one through this facade: {@code CaseTransitions}
     * requires privilege of any pardon, and this method is a named, audited entry point rather than a
     * back door round it. It never resurrects anybody and never shortens an ordinary sentence; the
     * prisoner is left custodial with the term they had.
     *
     * @param authority who is granting it. Recorded as the resolving actor on every case closed, so a
     *                  pardon can always be traced to whoever asked for it; {@code null} attributes it
     *                  to the subject's own record, as the command does for a console source.
     */
    public static dev.otectus.mcacrime.ledger.CapitalSentenceService.Clemency pardonCapitalSentence(
            MinecraftServer server, UUID subjectId, @Nullable UUID authority) {
        if (server == null || subjectId == null) {
            return dev.otectus.mcacrime.ledger.CapitalSentenceService.Clemency.REFUSED;
        }
        try {
            return dev.otectus.mcacrime.ledger.CapitalSentenceService.pardon(server, subjectId, authority);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime - capital pardon failed; refusing", t);
            return dev.otectus.mcacrime.ledger.CapitalSentenceService.Clemency.REFUSED;
        }
    }

    /** One lock, by its id. Carries state and never a binding: a view cannot mint a key. */
    public static Optional<dev.otectus.mcacrime.api.model.LockView> lock(MinecraftServer server,
                                                                        UUID lockId) {
        if (server == null || lockId == null) {
            return Optional.empty();
        }
        try {
            dev.otectus.mcacrime.locks.LockRecord record = CrimeWorldData.get(server).lock(lockId);
            if (record == null) {
                return Optional.empty();
            }
            dev.otectus.mcacrime.locks.LockTarget target = record.target();
            return Optional.of(new dev.otectus.mcacrime.api.model.LockView(record.lockId(),
                    record.locked(), record.reinforced(),
                    Optional.ofNullable(target.dimension()),
                    target.pos() == null ? Optional.empty()
                            : Optional.of(new long[] {target.pos().getX(), target.pos().getY(),
                                    target.pos().getZ()})));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime — lock lookup failed; returning empty", t);
            return Optional.empty();
        }
    }
}
