package com.ultimakingdoms.factions.organization;

import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.api.politics.Politics;
import com.ultimakingdoms.api.politics.UltimaPoliticsApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class OrganizationServiceImpl implements OrganizationService {
    static final int API_VERSION = 2;
    private static final int SNAPSHOT_EVIDENCE_LIMIT = 32;
    private final MinecraftServer server;
    private final OrganizationDefinitions definitions;
    private final OrganizationSavedData data;

    OrganizationServiceImpl(MinecraftServer server, KingdomsService kingdoms, OrganizationDefinitions definitions) {
        this.server = Objects.requireNonNull(server, "server");
        Objects.requireNonNull(kingdoms, "kingdoms");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.data = OrganizationSavedData.get(server);
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public List<OrganizationDefinitionView> definitions() {
        requireServerThread();
        OrganizationDefinitions.Snapshot snapshot = definitions.snapshot();
        List<OrganizationDefinitionView> result=new ArrayList<>(snapshot.ordered().stream().map(value -> value.view(snapshot.generation())).toList());
        data.records().lifecycles.values().stream().filter(value->value.state.equals("ACTIVE")).map(this::dynamicDefinition)
                .map(value->value.view(data.records().revision)).forEach(result::add);
        return result.stream().sorted(Comparator.comparing(value->value.id().toString())).toList();
    }

    @Override public List<OrganizationDefinitionView> foundingTemplates() {
        requireServerThread();var snapshot=definitions.snapshot();return snapshot.ordered().stream().map(value->value.view(snapshot.generation())).toList();
    }

    @Override public List<OrganizationLifecycleView> lifecycles(ServerPlayer viewer) {
        requireActor(viewer);return data.records().lifecycles.values().stream().sorted(Comparator.comparing(value->value.id)).map(this::lifecycleView).toList();
    }

    @Override public List<OrganizationMergeView> merges(ServerPlayer viewer) {
        requireActor(viewer);UUID actor=viewer.getUUID();return data.records().merges.values().stream().filter(value->{
            var source=data.records().lifecycles.get(value.source);var target=data.records().lifecycles.get(value.target);
            var member=data.records().memberships.get(OrganizationSavedData.key(actor,new ResourceLocation(value.source)));
            return value.proposer.equals(actor)||(member!=null&&member.active)||(source!=null&&authorized(viewer,source))||(target!=null&&authorized(viewer,target));
        }).sorted(Comparator.comparingLong((OrganizationSavedData.MergeRecord value)->value.revision).reversed()).map(this::mergeView).toList();
    }

    @Override public List<OrganizationObligation> mergeObligations(ServerPlayer viewer,UUID mergeId) {
        requireActor(viewer);var merge=data.records().merges.get(Objects.requireNonNull(mergeId));if(merge==null||!merge.sourceConsent||!merge.targetConsent||!merge.state.equals("CONSENTED"))return List.of();
        var source=data.records().lifecycles.get(merge.source);var target=data.records().lifecycles.get(merge.target);
        if(source==null||target==null||(!authorized(viewer,source)&&!authorized(viewer,target)))return List.of();
        return obligations(new ResourceLocation(merge.source));
    }

    @Override
    public Optional<OrganizationDefinitionView> definition(ResourceLocation organizationId) {
        requireServerThread();
        ResourceLocation resolved=resolve(Objects.requireNonNull(organizationId,"organizationId")).orElse(organizationId);
        return definitionOf(resolved).map(value->value.view(data.records().revision));
    }

    @Override
    public OrganizationPlayerSnapshot ownSnapshot(ServerPlayer player) {
        requireActor(player);
        UUID playerId = player.getUUID();
        List<OrganizationMembershipSnapshot> memberships = data.records().memberships.values().stream()
                .filter(value -> playerId.equals(value.player))
                .sorted(Comparator.comparing(value -> value.organization))
                .map(this::membershipSnapshot)
                .toList();
        return new OrganizationPlayerSnapshot(playerId, data.records().revision, memberships);
    }

    @Override
    public OrganizationExplanation explainOwn(ServerPlayer player, ResourceLocation organizationId,
                                              ResourceLocation servicePermission) {
        requireActor(player);
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(servicePermission, "servicePermission");
        if (!data.writable()) {
            return new OrganizationExplanation(organizationId, servicePermission,
                    OrganizationExplanation.Decision.UNAVAILABLE, Optional.empty(),
                    List.of(data.diagnostic()), data.records().revision);
        }
        ResourceLocation canonical=resolve(organizationId).orElse(organizationId);
        if(!benefitsActive(canonical))return new OrganizationExplanation(canonical,servicePermission,
                OrganizationExplanation.Decision.UNAVAILABLE,Optional.empty(),List.of("organization.lifecycle_inactive"),data.records().revision);
        Optional<OrganizationDefinition> foundDefinition = definitionOf(canonical);
        if (foundDefinition.isEmpty()) {
            return new OrganizationExplanation(organizationId, servicePermission,
                    OrganizationExplanation.Decision.UNAVAILABLE, Optional.empty(),
                    List.of("organization.definition_unavailable"), data.records().revision);
        }
        OrganizationDefinition definition = foundDefinition.get();
        OrganizationSavedData.MembershipRecord member = data.records().memberships.get(
                OrganizationSavedData.key(player.getUUID(), canonical));
        return OrganizationPolicy.explain(definition, member, servicePermission,
                member == null ? data.records().revision : member.revision).withPolicyRevision(data.records().revision);
    }

    @Override
    public OrganizationOperationResult join(ServerPlayer actor, ResourceLocation organizationId) {
        requireActor(actor);
        Objects.requireNonNull(organizationId, "organizationId");
        organizationId=resolve(organizationId).orElse(organizationId);
        ResourceLocation joinedOrganization=organizationId;
        OrganizationDefinitions.Snapshot snapshot = definitions.snapshot();
        Optional<OrganizationDefinition> found = definitionOf(organizationId);
        if(!benefitsActive(organizationId))return operation(OrganizationOperationResult.Status.UNKNOWN_DEFINITION,
                organizationId,null,"organization.lifecycle_inactive");
        if (found.isEmpty()) return operation(OrganizationOperationResult.Status.UNKNOWN_DEFINITION,
                organizationId, null, "organization.definition_unavailable");
        if (!data.writable()) return operation(OrganizationOperationResult.Status.READ_ONLY,
                organizationId, null, data.diagnostic());

        OrganizationSavedData.MembershipRecord current = data.records().memberships.get(
                OrganizationSavedData.key(actor.getUUID(), organizationId));
        if (current != null && current.active) {
            return operation(OrganizationOperationResult.Status.NO_CHANGE, organizationId, current,
                    "organization.already_member");
        }
        String conflict = incompatible(actor.getUUID(), found.get(), snapshot);
        if (conflict != null) return operation(OrganizationOperationResult.Status.INCOMPATIBLE_MEMBERSHIP,
                organizationId, current, conflict);
        if (current == null && data.records().memberships.size() >= OrganizationSavedData.MAX_MEMBERSHIPS) {
            return operation(OrganizationOperationResult.Status.LIMIT_REACHED, organizationId, null,
                    "organization.membership_capacity_reached");
        }

        OrganizationSavedData.Records next = data.transaction();
        OrganizationSavedData.MembershipRecord member = next.memberships.computeIfAbsent(
                OrganizationSavedData.key(actor.getUUID(), organizationId),
                ignored -> new OrganizationSavedData.MembershipRecord(actor.getUUID(), joinedOrganization));
        member.active = true;
        member.everJoined = true;
        member.joinedAt = now();
        member.leftAt = -1L;
        member.revision = ++next.revision;
        var history=appendHistory(next,actor.getUUID(),organizationId,member.active?"joined":"left");
        if (!commitDurably(next)) return operation(OrganizationOperationResult.Status.DURABILITY_FAILED,
                organizationId, current, "organization.durable_save_failed");
        publish(history);
        return operation(OrganizationOperationResult.Status.APPLIED, organizationId, member,
                "organization.joined");
    }

    @Override
    public OrganizationOperationResult leave(ServerPlayer actor, ResourceLocation organizationId) {
        requireActor(actor);
        Objects.requireNonNull(organizationId, "organizationId");
        organizationId=resolve(organizationId).orElse(organizationId);
        if (!data.writable()) return operation(OrganizationOperationResult.Status.READ_ONLY,
                organizationId, null, data.diagnostic());
        OrganizationSavedData.MembershipRecord current = data.records().memberships.get(
                OrganizationSavedData.key(actor.getUUID(), organizationId));
        if (current == null || !current.active) return operation(OrganizationOperationResult.Status.NO_CHANGE,
                organizationId, current, "organization.not_a_member");
        OrganizationSavedData.Records next = data.transaction();
        OrganizationSavedData.MembershipRecord member = next.memberships.get(
                OrganizationSavedData.key(actor.getUUID(), organizationId));
        member.active = false;
        member.leftAt = now();
        member.revision = ++next.revision;
        var history=appendHistory(next,actor.getUUID(),organizationId,member.active?"joined":"left");
        if (!commitDurably(next)) return operation(OrganizationOperationResult.Status.DURABILITY_FAILED,
                organizationId, current, "organization.durable_save_failed");
        publish(history);
        return operation(OrganizationOperationResult.Status.APPLIED, organizationId, member,
                "organization.left");
    }

    @Override
    public OrganizationDeedResult recordDeed(UUID playerId, ResourceLocation organizationId, UUID receiptId,
                                             String questId, int credit, Optional<UUID> settlementId) {
        return recordDeedInternal(playerId,organizationId,receiptId,questId,credit,settlementId,false);
    }
    OrganizationDeedResult recordCommittedDeed(UUID playerId,ResourceLocation organizationId,UUID receiptId,
            String questId,int credit,Optional<UUID> settlementId) {
        return recordDeedInternal(playerId,organizationId,receiptId,questId,credit,settlementId,true);
    }
    private OrganizationDeedResult recordDeedInternal(UUID playerId,ResourceLocation organizationId,UUID receiptId,
            String questId,int credit,Optional<UUID> settlementId,boolean frozenPolicy) {
        requireServerThread();
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(receiptId, "receiptId");
        settlementId = Objects.requireNonNull(settlementId, "settlementId");
        if(!frozenPolicy)organizationId=resolve(organizationId).orElse(organizationId);
        ResourceLocation appliedOrganization=organizationId;
        String safeQuest = questId == null ? "" : questId;
        UUID effectId = effectId(playerId, organizationId, receiptId, safeQuest);
        if (!data.writable()) return deed(OrganizationDeedResult.Status.READ_ONLY, organizationId, effectId,
                credit, 0, null, null, data.diagnostic());

        String fingerprint = fingerprint(playerId, organizationId, receiptId, safeQuest, credit, settlementId);
        OrganizationEvidenceLedger.Replay replay = OrganizationEvidenceLedger.replay(
                data.records(), effectId, fingerprint);
        if (replay.status() == OrganizationEvidenceLedger.Status.CONFLICT) {
            return deed(OrganizationDeedResult.Status.CONFLICTING_REPLAY, organizationId, effectId,
                    credit, 0, member(playerId, organizationId), evidence(replay.stored()),
                    "organization.receipt_payload_conflict");
        }
        if (replay.status() == OrganizationEvidenceLedger.Status.MATCH) {
            if (!flushDurable()) return deed(OrganizationDeedResult.Status.DURABILITY_FAILED, organizationId,
                    effectId, credit, 0, member(playerId, organizationId), evidence(replay.stored()),
                    "organization.durable_save_failed");
            return deed(OrganizationDeedResult.Status.REPLAYED, organizationId, effectId, credit, 0,
                    member(playerId, organizationId), evidence(replay.stored()), "organization.deed_replayed");
        }
        OrganizationSavedData.EvidenceRecord boundSource = OrganizationEvidenceLedger.sourceReceipt(
                data.records(), playerId, organizationId, receiptId);
        if (boundSource != null) {
            return deed(OrganizationDeedResult.Status.CONFLICTING_REPLAY, organizationId, effectId,
                    credit, 0, member(playerId, organizationId), evidence(boundSource),
                    "organization.receipt_payload_conflict");
        }
        ResourceLocation parsedQuest = safeQuest.length() <= 128 ? ResourceLocation.tryParse(safeQuest) : null;
        OrganizationDefinition definition = definitionOf(organizationId).orElse(null);
        if(!frozenPolicy&&!benefitsActive(organizationId))definition=null;
        if (!frozenPolicy && definition == null) return deed(OrganizationDeedResult.Status.UNKNOWN_DEFINITION, organizationId,
                effectId, credit, 0, null, null, "organization.definition_unavailable");
        if (parsedQuest == null || credit < 1 || credit > 10000 || (!frozenPolicy && (definition.deed(parsedQuest).isEmpty()
                || definition.deed(parsedQuest).get().credit() != credit))) {
            return deed(OrganizationDeedResult.Status.INVALID_EVIDENCE, organizationId, effectId,
                    credit, 0, null, null, "organization.deed_not_whitelisted");
        }
        boolean questAlreadyCredited = OrganizationEvidenceLedger.alreadyCredited(
                data.records(), playerId, organizationId, safeQuest);
        if (questAlreadyCredited) return deed(OrganizationDeedResult.Status.INVALID_EVIDENCE, organizationId,
                effectId, credit, 0, member(playerId, organizationId), null,
                "organization.quest_already_credited");
        if (data.records().receipts.size() >= OrganizationSavedData.MAX_RECEIPTS) {
            return deed(OrganizationDeedResult.Status.EVIDENCE_CAPACITY_REACHED, organizationId, effectId,
                    credit, 0, member(playerId, organizationId), null,
                    "organization.evidence_capacity_reached");
        }
        String memberKey = OrganizationSavedData.key(playerId, organizationId);
        if (!data.records().memberships.containsKey(memberKey)
                && data.records().memberships.size() >= OrganizationSavedData.MAX_MEMBERSHIPS) {
            return deed(OrganizationDeedResult.Status.EVIDENCE_CAPACITY_REACHED, organizationId, effectId,
                    credit, 0, null, null, "organization.membership_capacity_reached");
        }

        OrganizationSavedData.Records next = data.transaction();
        OrganizationSavedData.MembershipRecord member = next.memberships.computeIfAbsent(memberKey,
                ignored -> new OrganizationSavedData.MembershipRecord(playerId, appliedOrganization));
        try {
            member.standing = Math.addExact(member.standing, credit);
            member.deedCount = Math.addExact(member.deedCount, 1);
        } catch (ArithmeticException overflow) {
            return deed(OrganizationDeedResult.Status.INVALID_EVIDENCE, organizationId, effectId,
                    credit, 0, Optional.of(membershipSnapshot(member)), null, "organization.credit_limit_reached");
        }
        member.revision = ++next.revision;
        OrganizationSavedData.EvidenceRecord stored = new OrganizationSavedData.EvidenceRecord();
        stored.effectId = effectId;
        stored.sourceReceiptId = receiptId;
        stored.player = playerId;
        stored.organization = organizationId.toString();
        stored.questId = safeQuest;
        stored.requestedCredit = credit;
        stored.appliedCredit = credit;
        stored.settlement = settlementId.orElse(null);
        stored.gameTime = now();
        stored.revision = member.revision;
        stored.fingerprint = fingerprint;
        next.receipts.put(effectId, stored);
        var history=appendHistory(next,playerId,organizationId,"deed_recorded");
        if (!commitDurably(next)) return deed(OrganizationDeedResult.Status.DURABILITY_FAILED, organizationId,
                effectId, credit, 0, member(playerId, organizationId), null,
                "organization.durable_save_failed");
        publish(history);
        return deed(OrganizationDeedResult.Status.APPLIED, organizationId, effectId, credit, credit,
                Optional.of(membershipSnapshot(member)), evidence(stored), "organization.deed_recorded");
    }

    private OrganizationSavedData.HistoryRecord appendHistory(OrganizationSavedData.Records next,UUID player,ResourceLocation organization,String action) {
        var entry=new OrganizationSavedData.HistoryRecord();entry.id=UUID.randomUUID();entry.player=player;
        entry.organization=organization.toString();entry.action=action;entry.gameTime=now();entry.revision=next.revision;
        next.history.add(entry);if(next.history.size()>8192)next.history.remove(0);return entry;
    }
    private com.ultimakingdoms.api.factions.organization.OrganizationHistoryEntry historyView(OrganizationSavedData.HistoryRecord entry) {
        return new com.ultimakingdoms.api.factions.organization.OrganizationHistoryEntry(entry.id,entry.player,new ResourceLocation(entry.organization),entry.action,entry.gameTime,entry.revision);
    }
    private void publish(OrganizationSavedData.HistoryRecord entry) {
        try { net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new com.ultimakingdoms.api.factions.organization.OrganizationCommittedEvent(historyView(entry))); }
        catch(RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().error("Organization observer failed after durable commit",failure); }
    }
    @Override public List<com.ultimakingdoms.api.factions.organization.OrganizationHistoryEntry> ownHistory(ServerPlayer player,int limit) {
        requireActor(player);if(limit<1||limit>32)throw new IllegalArgumentException("Invalid history limit");
        return data.records().history.stream().filter(e->e.player.equals(player.getUUID()))
                .sorted(Comparator.comparingLong((OrganizationSavedData.HistoryRecord e)->e.revision).reversed()).limit(limit).map(this::historyView).toList();
    }

    @Override public Optional<ResourceLocation> resolve(ResourceLocation organizationId) {
        requireServerThread();Objects.requireNonNull(organizationId);
        String resolved=resolveId(data.records(),organizationId.toString());
        ResourceLocation value=ResourceLocation.tryParse(resolved);
        return value!=null&&(definitions.snapshot().get(value).isPresent()||data.records().lifecycles.containsKey(resolved))?Optional.of(value):Optional.empty();
    }
    @Override public Optional<OrganizationLifecycleView> lifecycle(ResourceLocation organizationId) {
        requireServerThread();Objects.requireNonNull(organizationId);
        return Optional.ofNullable(data.records().lifecycles.get(organizationId.toString())).map(this::lifecycleView);
    }
    @Override public Optional<OrganizationMergeView> merge(UUID mergeId) {
        requireServerThread();return Optional.ofNullable(data.records().merges.get(Objects.requireNonNull(mergeId))).map(this::mergeView);
    }
    @Override public List<OrganizationObligation> obligations(ResourceLocation organizationId) {
        requireServerThread();Objects.requireNonNull(organizationId);
        return com.ultimakingdoms.civic.CivicRuntime.get(server).organizationObligations(organizationId);
    }

    @Override public OrganizationLifecycleResult found(ServerPlayer actor,ResourceLocation organizationId,ResourceLocation templateId,
                                                        String sponsorKingdom,String displayName,long expectedRevision) {
        requireActor(actor);Objects.requireNonNull(organizationId);Objects.requireNonNull(templateId);Objects.requireNonNull(sponsorKingdom);Objects.requireNonNull(displayName);
        var rejected=preflight(expectedRevision);if(rejected!=null)return rejected;
        OrganizationDefinition template=definitions.snapshot().get(templateId).orElse(null);
        if(template==null)return lifecycleResult(OrganizationLifecycleResult.Status.UNKNOWN,"organization.template_unavailable",null,null,List.of());
        if(organizationId.toString().length()>128||displayName.isBlank()||displayName.length()>64||displayName.chars().anyMatch(Character::isISOControl)
                ||ResourceLocation.tryParse(sponsorKingdom)==null)return lifecycleResult(OrganizationLifecycleResult.Status.CONFLICT,"organization.invalid_charter",null,null,List.of());
        if(definitionOf(organizationId).isPresent()||data.records().redirects.containsKey(organizationId.toString()))
            return lifecycleResult(OrganizationLifecycleResult.Status.CONFLICT,"organization.id_reserved",null,null,List.of());
        if(data.records().lifecycles.size()>=128)return lifecycleResult(OrganizationLifecycleResult.Status.LIMIT_REACHED,"organization.lifecycle_capacity",null,null,List.of());
        if(data.records().memberships.size()>=OrganizationSavedData.MAX_MEMBERSHIPS)return lifecycleResult(OrganizationLifecycleResult.Status.LIMIT_REACHED,"organization.membership_capacity_reached",null,null,List.of());
        String membershipConflict=incompatible(actor.getUUID(),reidentify(template,organizationId),definitions.snapshot());
        if(membershipConflict!=null)return lifecycleResult(OrganizationLifecycleResult.Status.CONFLICT,membershipConflict,null,null,List.of());
        if(!UltimaPoliticsApi.get(server).authorized(actor,sponsorKingdom, Politics.Permission.RECOGNIZE))
            return lifecycleResult(OrganizationLifecycleResult.Status.UNAUTHORIZED,"organization.charter_authority_required",null,null,List.of());
        var next=data.transaction();next.revision++;
        var record=new OrganizationSavedData.LifecycleRecord();record.id=organizationId.toString();record.template=templateId.toString();
        record.sponsorKingdom=sponsorKingdom;record.displayName=displayName;record.state="ACTIVE";record.founder=actor.getUUID();
        record.createdAt=now();record.revision=next.revision;record.definition=freeze(template);next.lifecycles.put(record.id,record);
        var member=new OrganizationSavedData.MembershipRecord(actor.getUUID(),organizationId);member.active=true;member.everJoined=true;
        member.joinedAt=now();member.revision=next.revision;next.memberships.put(OrganizationSavedData.key(actor.getUUID(),organizationId),member);
        var history=appendHistory(next,actor.getUUID(),organizationId,"founded");
        if(!commitDurably(next))return lifecycleResult(OrganizationLifecycleResult.Status.DURABILITY_FAILED,"organization.durable_save_failed",null,null,List.of());
        publish(history);return lifecycleResult(OrganizationLifecycleResult.Status.APPLIED,"organization.founded",record,null,List.of());
    }

    @Override public OrganizationLifecycleResult proposeMerge(ServerPlayer actor,ResourceLocation source,ResourceLocation target,long expectedRevision) {
        requireActor(actor);Objects.requireNonNull(source);Objects.requireNonNull(target);var rejected=preflight(expectedRevision);if(rejected!=null)return rejected;
        var first=activeLifecycle(source);var second=activeLifecycle(target);
        if(first==null||second==null||source.equals(target))return lifecycleResult(OrganizationLifecycleResult.Status.UNKNOWN,"organization.merge_party_unavailable",null,null,List.of());
        if(!authorized(actor,first))return lifecycleResult(OrganizationLifecycleResult.Status.UNAUTHORIZED,"organization.merge_authority_required",first,null,List.of());
        boolean busy=data.records().merges.values().stream().anyMatch(value->!Set.of("APPLIED","CANCELLED").contains(value.state)
                &&(value.source.equals(source.toString())||value.target.equals(source.toString())||value.source.equals(target.toString())||value.target.equals(target.toString())));
        if(busy)return lifecycleResult(OrganizationLifecycleResult.Status.CONFLICT,"organization.merge_already_pending",first,null,List.of());
        var next=data.transaction();next.revision++;var proposal=new OrganizationSavedData.MergeRecord();proposal.id=UUID.randomUUID();
        proposal.source=source.toString();proposal.target=target.toString();proposal.proposer=actor.getUUID();proposal.sourceConsent=true;
        proposal.targetConsent=false;proposal.state="PENDING";proposal.revision=next.revision;next.merges.put(proposal.id,proposal);
        var history=appendHistory(next,actor.getUUID(),source,"merge_proposed");
        if(!commitDurably(next))return lifecycleResult(OrganizationLifecycleResult.Status.DURABILITY_FAILED,"organization.durable_save_failed",first,null,List.of());
        publish(history);return lifecycleResult(OrganizationLifecycleResult.Status.PENDING_CONSENT,"organization.merge_pending_consent",first,proposal,List.of());
    }

    @Override public OrganizationLifecycleResult consentMerge(ServerPlayer actor,UUID mergeId,long expectedRevision) {
        requireActor(actor);Objects.requireNonNull(mergeId);var rejected=preflight(expectedRevision);if(rejected!=null)return rejected;
        var current=data.records().merges.get(mergeId);if(current==null||!current.state.equals("PENDING"))
            return lifecycleResult(OrganizationLifecycleResult.Status.NO_CHANGE,"organization.merge_not_pending",null,current,List.of());
        var target=activeLifecycle(new ResourceLocation(current.target));if(target==null)return lifecycleResult(OrganizationLifecycleResult.Status.UNKNOWN,"organization.merge_party_unavailable",null,current,List.of());
        if(!authorized(actor,target))return lifecycleResult(OrganizationLifecycleResult.Status.UNAUTHORIZED,"organization.merge_authority_required",target,current,List.of());
        var next=data.transaction();next.revision++;var proposal=next.merges.get(mergeId);proposal.targetConsent=true;proposal.state="CONSENTED";proposal.revision=next.revision;
        var history=appendHistory(next,actor.getUUID(),new ResourceLocation(proposal.target),"merge_consented");
        if(!commitDurably(next))return lifecycleResult(OrganizationLifecycleResult.Status.DURABILITY_FAILED,"organization.durable_save_failed",target,current,List.of());
        publish(history);return lifecycleResult(OrganizationLifecycleResult.Status.APPLIED,"organization.merge_consented",target,proposal,obligations(new ResourceLocation(proposal.source)));
    }

    @Override public OrganizationLifecycleResult optOutMerge(ServerPlayer actor,UUID mergeId,long expectedRevision) {
        requireActor(actor);Objects.requireNonNull(mergeId);var rejected=preflight(expectedRevision);if(rejected!=null)return rejected;
        var current=data.records().merges.get(mergeId);if(current==null||Set.of("APPLIED","CANCELLED").contains(current.state))
            return lifecycleResult(OrganizationLifecycleResult.Status.NO_CHANGE,"organization.merge_closed",null,current,List.of());
        var source=new ResourceLocation(current.source);var member=data.records().memberships.get(OrganizationSavedData.key(actor.getUUID(),source));
        if(member==null||!member.active)return lifecycleResult(OrganizationLifecycleResult.Status.UNAUTHORIZED,"organization.active_source_membership_required",null,current,List.of());
        if(current.optOuts.contains(actor.getUUID()))return lifecycleResult(OrganizationLifecycleResult.Status.NO_CHANGE,"organization.merge_already_opted_out",null,current,List.of());
        var next=data.transaction();next.revision++;var proposal=next.merges.get(mergeId);proposal.optOuts.add(actor.getUUID());proposal.revision=next.revision;
        var history=appendHistory(next,actor.getUUID(),source,"merge_opted_out");
        if(!commitDurably(next))return lifecycleResult(OrganizationLifecycleResult.Status.DURABILITY_FAILED,"organization.durable_save_failed",null,current,List.of());
        publish(history);return lifecycleResult(OrganizationLifecycleResult.Status.APPLIED,"organization.merge_opted_out",null,proposal,List.of());
    }

    @Override public OrganizationLifecycleResult finalizeMerge(ServerPlayer actor,UUID mergeId,long expectedRevision) {
        requireActor(actor);Objects.requireNonNull(mergeId);var rejected=preflight(expectedRevision);if(rejected!=null)return rejected;
        var current=data.records().merges.get(mergeId);if(current==null)return lifecycleResult(OrganizationLifecycleResult.Status.UNKNOWN,"organization.merge_unavailable",null,null,List.of());
        if(current.state.equals("APPLIED"))return lifecycleResult(OrganizationLifecycleResult.Status.NO_CHANGE,"organization.merge_already_applied",null,current,List.of());
        if(!current.sourceConsent||!current.targetConsent||!current.state.equals("CONSENTED"))return lifecycleResult(OrganizationLifecycleResult.Status.PENDING_CONSENT,"organization.merge_pending_consent",null,current,List.of());
        var source=activeLifecycle(new ResourceLocation(current.source));var target=activeLifecycle(new ResourceLocation(current.target));
        if(source==null||target==null)return lifecycleResult(OrganizationLifecycleResult.Status.UNKNOWN,"organization.merge_party_unavailable",null,current,List.of());
        if(!authorized(actor,source)&&!authorized(actor,target))return lifecycleResult(OrganizationLifecycleResult.Status.UNAUTHORIZED,"organization.merge_authority_required",source,current,List.of());
        List<OrganizationObligation> blockers=obligations(new ResourceLocation(current.source));
        if(!blockers.isEmpty()||hasOtherObligations(current.source))return lifecycleResult(OrganizationLifecycleResult.Status.OBLIGATIONS_BLOCKING,"organization.obligations_unsettled",source,current,blockers);
        OrganizationDefinition targetDefinition=dynamicDefinition(target);
        long additions=data.records().memberships.values().stream().filter(value->value.organization.equals(current.source)&&value.active
                &&!current.optOuts.contains(value.player)&&!data.records().memberships.containsKey(OrganizationSavedData.key(value.player,current.target))).count();
        if(data.records().memberships.size()+additions>OrganizationSavedData.MAX_MEMBERSHIPS)
            return lifecycleResult(OrganizationLifecycleResult.Status.LIMIT_REACHED,"organization.membership_capacity_reached",source,current,List.of());
        for(var member:data.records().memberships.values())if(member.organization.equals(current.source)&&member.active&&!current.optOuts.contains(member.player)){
            String conflict=incompatibleExcluding(member.player,targetDefinition,current.source);
            if(conflict!=null)return lifecycleResult(OrganizationLifecycleResult.Status.CONFLICT,"organization.member_requires_opt_out:"+member.player+":"+conflict,source,current,List.of());
        }
        var next=data.transaction();next.revision++;var sourceNext=next.lifecycles.get(current.source);sourceNext.state="MERGED";sourceNext.redirect=current.target;sourceNext.revision=next.revision;
        next.redirects.put(current.source,current.target);var proposal=next.merges.get(mergeId);proposal.state="APPLIED";proposal.revision=next.revision;
        applyMembershipMerge(next,proposal,now());
        var history=appendHistory(next,actor.getUUID(),new ResourceLocation(current.source),"merged");
        if(!commitDurably(next))return lifecycleResult(OrganizationLifecycleResult.Status.DURABILITY_FAILED,"organization.durable_save_failed",source,current,List.of());
        publish(history);return lifecycleResult(OrganizationLifecycleResult.Status.APPLIED,"organization.merged",sourceNext,proposal,List.of());
    }

    @Override public OrganizationLifecycleResult novateMergeObligation(ServerPlayer actor,UUID mergeId,UUID obligationId,long expectedRevision) {
        requireActor(actor);Objects.requireNonNull(mergeId);Objects.requireNonNull(obligationId);var rejected=preflight(expectedRevision);if(rejected!=null)return rejected;
        var current=data.records().merges.get(mergeId);if(current==null||!current.sourceConsent||!current.targetConsent)
            return lifecycleResult(OrganizationLifecycleResult.Status.PENDING_CONSENT,"organization.merge_pending_consent",null,current,List.of());
        var source=activeLifecycle(new ResourceLocation(current.source));var target=activeLifecycle(new ResourceLocation(current.target));
        if(source==null||target==null||(!authorized(actor,source)&&!authorized(actor,target)))
            return lifecycleResult(OrganizationLifecycleResult.Status.UNAUTHORIZED,"organization.merge_authority_required",source,current,List.of());
        boolean changed=com.ultimakingdoms.civic.CivicRuntime.get(server).novateOrganizationObligation(obligationId,new ResourceLocation(current.source),new ResourceLocation(current.target));
        if(!changed)return lifecycleResult(OrganizationLifecycleResult.Status.UNKNOWN,"organization.obligation_unavailable",source,current,obligations(new ResourceLocation(current.source)));
        var next=data.transaction();next.revision++;var history=appendHistory(next,actor.getUUID(),new ResourceLocation(current.source),"obligation_novated");
        if(!commitDurably(next))return lifecycleResult(OrganizationLifecycleResult.Status.DURABILITY_FAILED,"organization.durable_save_failed",source,current,List.of());
        publish(history);return lifecycleResult(OrganizationLifecycleResult.Status.APPLIED,"organization.obligation_novated",source,current,obligations(new ResourceLocation(current.source)));
    }

    @Override public OrganizationLifecycleResult dissolve(ServerPlayer actor,ResourceLocation organizationId,long expectedRevision) {
        requireActor(actor);Objects.requireNonNull(organizationId);var rejected=preflight(expectedRevision);if(rejected!=null)return rejected;
        var current=activeLifecycle(organizationId);if(current==null)return lifecycleResult(OrganizationLifecycleResult.Status.UNKNOWN,"organization.lifecycle_unavailable",null,null,List.of());
        if(!authorized(actor,current))return lifecycleResult(OrganizationLifecycleResult.Status.UNAUTHORIZED,"organization.dissolve_authority_required",current,null,List.of());
        List<OrganizationObligation> blockers=obligations(organizationId);if(!blockers.isEmpty()||hasOtherObligations(organizationId.toString()))
            return lifecycleResult(OrganizationLifecycleResult.Status.OBLIGATIONS_BLOCKING,"organization.obligations_unsettled",current,null,blockers);
        var next=data.transaction();next.revision++;var record=next.lifecycles.get(organizationId.toString());record.state="DISSOLVED";record.revision=next.revision;
        next.memberships.values().stream().filter(value->value.organization.equals(organizationId.toString())&&value.active).forEach(value->{value.active=false;value.leftAt=now();value.revision=next.revision;});
        var history=appendHistory(next,actor.getUUID(),organizationId,"dissolved");
        if(!commitDurably(next))return lifecycleResult(OrganizationLifecycleResult.Status.DURABILITY_FAILED,"organization.durable_save_failed",current,null,List.of());
        publish(history);return lifecycleResult(OrganizationLifecycleResult.Status.APPLIED,"organization.dissolved",record,null,List.of());
    }

    static String resolveId(OrganizationSavedData.Records records,String organization) {
        Set<String> seen=new LinkedHashSet<>();String value=organization;
        while(records.redirects.containsKey(value)){
            if(!seen.add(value)||seen.size()>128)throw new IllegalStateException("Organization redirect cycle");
            value=records.redirects.get(value);
        }
        return value;
    }
    static void applyMembershipMerge(OrganizationSavedData.Records records,OrganizationSavedData.MergeRecord proposal,long gameTime) {
        for(var membership:List.copyOf(records.memberships.values()))if(membership.organization.equals(proposal.source)&&membership.active){
            membership.active=false;membership.leftAt=gameTime;membership.revision=records.revision;
            if(!proposal.optOuts.contains(membership.player)){
                var key=OrganizationSavedData.key(membership.player,proposal.target);
                var targetMember=records.memberships.computeIfAbsent(key,ignored->new OrganizationSavedData.MembershipRecord(membership.player,new ResourceLocation(proposal.target)));
                targetMember.active=true;targetMember.everJoined=true;targetMember.joinedAt=gameTime;targetMember.leftAt=-1;targetMember.revision=records.revision;
            }
        }
    }
    static boolean revisionMatches(OrganizationSavedData.Records records,long expected) { return expected==records.revision; }

    private OrganizationLifecycleResult preflight(long expectedRevision) {
        if(!data.writable())return lifecycleResult(OrganizationLifecycleResult.Status.READ_ONLY,data.diagnostic(),null,null,List.of());
        if(!revisionMatches(data.records(),expectedRevision))return lifecycleResult(OrganizationLifecycleResult.Status.STALE_REVISION,"organization.state_changed",null,null,List.of());
        return null;
    }
    private OrganizationLifecycleResult lifecycleResult(OrganizationLifecycleResult.Status status,String reason,
                                                        OrganizationSavedData.LifecycleRecord organization,
                                                        OrganizationSavedData.MergeRecord merge,List<OrganizationObligation> obligations) {
        return new OrganizationLifecycleResult(status,data.records().revision,reason,
                Optional.ofNullable(organization).map(this::lifecycleView),Optional.ofNullable(merge).map(this::mergeView),obligations);
    }
    private OrganizationSavedData.LifecycleRecord activeLifecycle(ResourceLocation id) {
        var value=data.records().lifecycles.get(id.toString());return value!=null&&value.state.equals("ACTIVE")?value:null;
    }
    private boolean authorized(ServerPlayer actor,OrganizationSavedData.LifecycleRecord organization) {
        return lifecycleAuthorized(actor.getUUID(),organization,(kingdom,permission)->UltimaPoliticsApi.get(server).authorized(actor,kingdom,permission));
    }
    static boolean lifecycleAuthorized(UUID actor,OrganizationSavedData.LifecycleRecord organization,
                                       java.util.function.BiPredicate<String,Politics.Permission> mandate) {
        return organization.founder.equals(actor)||mandate.test(organization.sponsorKingdom,Politics.Permission.RATIFY)
                ||mandate.test(organization.sponsorKingdom,Politics.Permission.RECOGNIZE);
    }
    private boolean hasOtherObligations(String organization) {
        return com.ultimakingdoms.warfare.contracts.CivilianContractService.hasUnsettledOrganization(server,organization);
    }
    private OrganizationLifecycleView lifecycleView(OrganizationSavedData.LifecycleRecord value) {
        ResourceLocation id=new ResourceLocation(value.id);ResourceLocation canonical=new ResourceLocation(resolveId(data.records(),value.id));
        return new OrganizationLifecycleView(id,new ResourceLocation(value.template),canonical,value.sponsorKingdom,value.displayName,
                OrganizationLifecycleView.State.valueOf(value.state),value.founder,value.createdAt,value.revision,
                Optional.ofNullable(value.redirect).map(ResourceLocation::new));
    }
    private OrganizationMergeView mergeView(OrganizationSavedData.MergeRecord value) {
        return new OrganizationMergeView(value.id,new ResourceLocation(value.source),new ResourceLocation(value.target),value.proposer,
                value.sourceConsent,value.targetConsent,value.optOuts,OrganizationMergeView.State.valueOf(value.state),value.revision);
    }
    private Optional<OrganizationDefinition> definitionOf(ResourceLocation id) {
        OrganizationDefinition fixed=definitions.snapshot().get(id).orElse(null);if(fixed!=null)return Optional.of(fixed);
        var lifecycle=data.records().lifecycles.get(id.toString());return lifecycle==null?Optional.empty():Optional.of(dynamicDefinition(lifecycle));
    }
    private boolean benefitsActive(ResourceLocation id) {
        var lifecycle=data.records().lifecycles.get(id.toString());return lifecycle==null||lifecycle.state.equals("ACTIVE");
    }
    private OrganizationDefinition dynamicDefinition(OrganizationSavedData.LifecycleRecord lifecycle) {
        var frozen=lifecycle.definition;
        List<OrganizationRankView> ranks=frozen.ranks.stream().map(value->new OrganizationRankView(new ResourceLocation(value.id),value.standing,
                value.permissions.stream().map(ResourceLocation::new).collect(java.util.stream.Collectors.toSet()))).toList();
        List<OrganizationServiceRule> services=frozen.services.stream().map(value->new OrganizationServiceRule(new ResourceLocation(value.permission),
                new ResourceLocation(value.rank),value.standing,value.deeds,value.neutralStanding,value.neutralDeeds)).toList();
        List<OrganizationDeedRule> deeds=frozen.deeds.stream().map(value->new OrganizationDeedRule(new ResourceLocation(value.quest),value.credit)).toList();
        return new OrganizationDefinition(new ResourceLocation(lifecycle.id),frozen.kind,false,frozen.nameKey,frozen.descriptionKey,
                Optional.ofNullable(frozen.exclusiveGroup).map(ResourceLocation::new),frozen.conflicts.stream().map(ResourceLocation::new).collect(java.util.stream.Collectors.toSet()),ranks,services,deeds);
    }
    private static OrganizationSavedData.DefinitionRecord freeze(OrganizationDefinition definition) {
        var result=new OrganizationSavedData.DefinitionRecord();result.kind=definition.kind();result.nameKey=definition.nameKey();result.descriptionKey=definition.descriptionKey();
        result.exclusiveGroup=definition.exclusiveGroup().map(ResourceLocation::toString).orElse(null);definition.conflicts().forEach(value->result.conflicts.add(value.toString()));
        definition.ranks().forEach(value->{var rank=new OrganizationSavedData.RankRecord();rank.id=value.id().toString();rank.standing=value.minimumStanding();
            value.permissions().forEach(permission->rank.permissions.add(permission.toString()));result.ranks.add(rank);});
        definition.services().forEach(value->{var service=new OrganizationSavedData.ServiceRecord();service.permission=value.permission().toString();service.rank=value.minimumRank().toString();
            service.standing=value.minimumStanding();service.deeds=value.requiredDeeds();service.neutralStanding=value.neutralMinimumStanding();service.neutralDeeds=value.neutralRequiredDeeds();result.services.add(service);});
        definition.deeds().forEach(value->{var deed=new OrganizationSavedData.DeedRecord();deed.quest=value.questId().toString();deed.credit=value.credit();result.deeds.add(deed);});
        return result;
    }
    private static OrganizationDefinition reidentify(OrganizationDefinition definition,ResourceLocation id) {
        return new OrganizationDefinition(id,definition.kind(),definition.military(),definition.nameKey(),definition.descriptionKey(),
                definition.exclusiveGroup(),definition.conflicts(),definition.ranks(),definition.services(),definition.deeds());
    }

    @Override
    public long revision() {
        requireServerThread();
        return data.records().revision;
    }

    private String incompatible(UUID player, OrganizationDefinition joining,
                                OrganizationDefinitions.Snapshot snapshot) {
        return incompatibleExcluding(player,joining,null);
    }
    private String incompatibleExcluding(UUID player,OrganizationDefinition joining,String excluded) {
        for (OrganizationSavedData.MembershipRecord existing : data.records().memberships.values()) {
            if (!player.equals(existing.player) || !existing.active || existing.organization.equals(joining.id().toString())
                    ||Objects.equals(existing.organization,excluded)) continue;
            ResourceLocation existingId = ResourceLocation.tryParse(existing.organization);
            OrganizationDefinition other = existingId == null ? null : definitionOf(existingId).orElse(null);
            if (other == null) return "organization.active_membership_definition_unavailable:" + existing.organization;
            if (joining.conflicts().contains(other.id()) || other.conflicts().contains(joining.id())) {
                return "organization.membership_conflict:" + other.id();
            }
            if (joining.exclusiveGroup().isPresent() && joining.exclusiveGroup().equals(other.exclusiveGroup())) {
                return "organization.exclusive_group_conflict:" + other.id();
            }
        }
        return null;
    }

    private Optional<OrganizationMembershipSnapshot> member(UUID player, ResourceLocation organization) {
        return Optional.ofNullable(data.records().memberships.get(OrganizationSavedData.key(player, organization)))
                .map(this::membershipSnapshot);
    }

    private OrganizationMembershipSnapshot membershipSnapshot(OrganizationSavedData.MembershipRecord member) {
        ResourceLocation organization = Objects.requireNonNull(ResourceLocation.tryParse(member.organization));
        OrganizationDefinition definition = definitionOf(organization).orElse(null);
        List<OrganizationEvidenceReceipt> recent = data.records().receipts.values().stream()
                .filter(value -> member.player.equals(value.player) && member.organization.equals(value.organization))
                .sorted(Comparator.comparingLong((OrganizationSavedData.EvidenceRecord value) -> value.gameTime)
                        .thenComparing(value -> value.effectId).reversed())
                .limit(SNAPSHOT_EVIDENCE_LIMIT)
                .map(this::evidence)
                .toList();
        OrganizationMembershipSnapshot.Status status = member.active
                ? OrganizationMembershipSnapshot.Status.ACTIVE
                : member.everJoined ? OrganizationMembershipSnapshot.Status.LEFT
                : OrganizationMembershipSnapshot.Status.UNAFFILIATED;
        return new OrganizationMembershipSnapshot(organization, status, definition != null, member.standing,
                member.deedCount, definition == null || !member.active ? Optional.empty()
                : definition.rank(member.standing).map(OrganizationRankView::id), member.joinedAt, member.leftAt,
                member.revision, recent);
    }

    private OrganizationEvidenceReceipt evidence(OrganizationSavedData.EvidenceRecord record) {
        return new OrganizationEvidenceReceipt(record.effectId, record.sourceReceiptId, record.questId,
                Objects.requireNonNull(ResourceLocation.tryParse(record.organization)), record.requestedCredit,
                record.appliedCredit, Optional.ofNullable(record.settlement), record.gameTime, record.revision);
    }

    private OrganizationOperationResult operation(OrganizationOperationResult.Status status,
                                                  ResourceLocation organization,
                                                  OrganizationSavedData.MembershipRecord member, String reason) {
        return new OrganizationOperationResult(status, organization,
                Optional.ofNullable(member).map(this::membershipSnapshot), reason);
    }

    private OrganizationDeedResult deed(OrganizationDeedResult.Status status, ResourceLocation organization,
                                        UUID effectId, int requested, int applied,
                                        Optional<OrganizationMembershipSnapshot> member,
                                        OrganizationEvidenceReceipt receipt, String reason) {
        return new OrganizationDeedResult(status, organization, effectId, requested, applied,
                member == null ? Optional.empty() : member, Optional.ofNullable(receipt), reason);
    }

    private boolean flushDurable() {
        return data.flush(server.getWorldPath(LevelResource.ROOT).resolve("data")
                .resolve(OrganizationSavedData.DATA_NAME + ".dat").toFile());
    }

    private boolean commitDurably(OrganizationSavedData.Records next) {
        return data.commitDurably(next, server.getWorldPath(LevelResource.ROOT).resolve("data")
                .resolve(OrganizationSavedData.DATA_NAME + ".dat").toFile());
    }

    private long now() {
        return server.overworld().getGameTime();
    }

    private void requireActor(ServerPlayer actor) {
        requireServerThread();
        Objects.requireNonNull(actor, "actor");
        if (actor.getServer() != server) throw new IllegalArgumentException("Player does not belong to this server");
    }

    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Organization API requires the server thread");
    }

    static UUID effectId(UUID player, ResourceLocation organization, UUID receipt, String questId) {
        String material = "ultima_kingdoms:organization_deed:v1\0" + player + "\0" + organization
                + "\0" + receipt + "\0" + questId;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    static String fingerprint(UUID player, ResourceLocation organization, UUID receipt, String quest,
                              int credit, Optional<UUID> settlement) {
        String material = player + "\0" + organization + "\0" + receipt + "\0" + quest + "\0" + credit
                + "\0" + settlement.map(UUID::toString).orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
