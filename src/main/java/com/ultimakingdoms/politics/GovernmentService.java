package com.ultimakingdoms.politics;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.event.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.api.politics.PoliticalTransition.*;
import com.ultimakingdoms.api.townstead.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.ModList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

/** All mutations share one authenticated, revision-checked copy-on-write transaction path. */
public final class GovernmentService implements PoliticalService {
    private static final String LEADER = "ultima_kingdoms:leader";
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final PoliticalDefinitions definitions;
    private final PoliticalSavedData data;
    private final boolean conflict;
    private final java.util.function.Predicate<UUID> knownPlayer;
    private final java.util.function.BooleanSupplier institutionalServices;
    private int maintenanceCursor;
    private final java.util.Deque<LivingDeathEvent> pendingDeaths = new java.util.ArrayDeque<>();
    public GovernmentService(MinecraftServer server, KingdomsService kingdoms, PoliticalDefinitions definitions) {
        this(server, kingdoms, definitions, PoliticalSavedData.get(server), ModList.get().isLoaded("mcacapitals"));
    }
    GovernmentService(MinecraftServer server, KingdomsService kingdoms, PoliticalDefinitions definitions,
                      PoliticalSavedData data, boolean conflict) {
        this(server, kingdoms, definitions, data, conflict, id -> server.getPlayerList().getPlayer(id) != null
                || server.getProfileCache() != null && server.getProfileCache().get(id).isPresent());
    }
    GovernmentService(MinecraftServer server, KingdomsService kingdoms, PoliticalDefinitions definitions,
                      PoliticalSavedData data, boolean conflict, java.util.function.Predicate<UUID> knownPlayer) {
        this(server, kingdoms, definitions, data, conflict, knownPlayer,
                () -> com.ultimakingdoms.civic.CivicConfig.INSTITUTIONAL_SERVICES.get());
    }
    GovernmentService(MinecraftServer server, KingdomsService kingdoms, PoliticalDefinitions definitions,
                      PoliticalSavedData data, boolean conflict, java.util.function.Predicate<UUID> knownPlayer,
                      java.util.function.BooleanSupplier institutionalServices) {
        this.server = server; this.kingdoms = kingdoms; this.definitions = definitions;
        this.data = data; this.conflict = conflict; this.knownPlayer = knownPlayer;
        this.institutionalServices = institutionalServices;
    }
    private void thread() { if (!server.isSameThread()) throw new IllegalStateException("Politics requires the server thread"); }
    @Override public long revision() { thread(); return data.records().revision; }
    @Override public Optional<Government> government(String kingdom) {
        thread(); Objects.requireNonNull(kingdom); return Optional.ofNullable(data.records().governments.get(kingdom));
    }
    @Override public Optional<Result> receipt(ServerPlayer actor, UUID requestId) {
        thread(); check(actor.getServer() == server && !actor.hasDisconnected(), "Player is not connected to this server");
        Receipt value = data.records().receipts.get(Objects.requireNonNull(requestId));
        return value != null && value.actor().equals(actor.getUUID()) ? Optional.of(value.result()) : Optional.empty();
    }
    @Override public List<ElectionView> elections(ServerPlayer viewer,int offset,int limit) {
        thread();check(offset>=0&&offset<=100000&&limit>0&&limit<=64,"Invalid election page");
        return data.records().elections.keySet().stream().sorted().map(id->election(viewer,id)).flatMap(Optional::stream).skip(offset).limit(limit).toList();
    }
    @Override public Optional<Rule> transitionRule(String kingdom) { thread(); return Optional.ofNullable(data.records().transitionRules.get(kingdom)); }
    @Override public Optional<Regency> regency(String kingdom) { thread(); return Optional.ofNullable(data.records().regencies.get(kingdom)); }
    @Override public Optional<ElectionView> election(ServerPlayer viewer, UUID id) {
        thread(); check(viewer.getServer() == server && !viewer.hasDisconnected(), "Player is not connected to this server");
        Election value = data.records().elections.get(id); if (value == null) return Optional.empty();
        Government government = data.records().governments.get(value.kingdom()); boolean eligible = value.electorate().contains(viewer.getUUID());
        if (!eligible && !viewer.hasPermissions(2) && !permitted(viewer, government, Permission.APPOINT)) return Optional.empty();
        return Optional.of(new ElectionView(value.id(), value.kingdom(), value.candidates(), value.state(), value.electorate().size(), value.ballots().size(),
                eligible, value.ballots().containsKey(viewer.getUUID()), value.deadline(), value.graceUntil(), value.revision(), Optional.ofNullable(value.winner())));
    }

    @Override public Result adoptTransitionRule(ServerPlayer actor,UUID request,long expected,String kingdom,Rule rule) {
        Objects.requireNonNull(rule); return transition(actor, request, expected, kingdom, Action.ADOPT_TRANSITION_RULE, PoliticalSavedData.JSON.toJson(rule), state -> {
            Government government = state.governments.get(kingdom); authorize(actor, government, Permission.APPOINT);
            check(government.offices().get(LEADER) != null && government.offices().get(LEADER).holder().equals(new Person(actor.getUUID(), Kind.PLAYER)),
                    "Only the current leader may adopt constitutional transition rules");
            check(state.elections.values().stream().noneMatch(e->e.kingdom().equals(kingdom)&&(e.state()==ElectionState.OPEN||e.state()==ElectionState.GRACE)),"Close the current election before changing its constitutional rule");
            Regency regency=state.regencies.get(kingdom);check(regency==null||!regency.active(),"End the current regency before changing its constitutional rule");
            state.transitionRules.put(kingdom, rule); return kingdom;
        });
    }
    @Override public Result openElection(ServerPlayer actor,UUID request,long expected,String kingdom,List<UUID> candidates) {
        List<UUID> frozen = List.copyOf(candidates); return transition(actor, request, expected, kingdom, Action.OPEN_ELECTION, frozen.toString(), state -> {
            Government government = state.governments.get(kingdom); authorize(actor, government, Permission.APPOINT);
            Rule rule = state.transitionRules.get(kingdom); check(rule != null && rule.elections(), "This government has not opted into elections");
            Set<UUID> nominees = new LinkedHashSet<>(frozen); check(nominees.size() >= 2 && nominees.size() <= rule.maxCandidates(), "Candidate count violates the adopted rule");
            nominees.forEach(id -> person(new Person(id, Kind.PLAYER), kingdom, null));
            check(state.elections.values().stream().noneMatch(e -> e.kingdom().equals(kingdom) && (e.state() == ElectionState.OPEN || e.state() == ElectionState.GRACE)), "An election is already open");
            Set<UUID> electorate = electorate(government); Regency regent = state.regencies.get(kingdom); if (unexpired(regent,now())) electorate.add(regent.regent().id());
            check(!electorate.isEmpty() && electorate.size() <= 128, "No bounded eligible electorate is available");
            state.elections.put(request, new Election(request, kingdom, nominees, electorate, Map.of(), ElectionState.OPEN, now(), now()+rule.electionTicks(),
                    now()+rule.electionTicks()+rule.graceTicks(), state.revision, null)); return request.toString();
        });
    }
    @Override public Result castBallot(ServerPlayer actor,UUID request,long expected,UUID election,UUID candidate) {
        Election existing = data.records().elections.get(election); if (existing == null) return new Result(false,revision(),"Election unavailable","");
        return transition(actor, request, expected, existing.kingdom(), Action.CAST_BALLOT, election+"|"+candidate, state -> {
            Election value = state.elections.get(election); check(value != null && (value.state()==ElectionState.OPEN || value.state()==ElectionState.GRACE) && now()<value.graceUntil(), "Election is closed");
            check(value.electorate().contains(actor.getUUID()), "You are not in the frozen electorate"); check(value.candidates().contains(candidate), "Candidate is not on this ballot");
            Map<UUID,UUID> ballots = new LinkedHashMap<>(value.ballots()); ballots.put(actor.getUUID(), candidate);
            state.elections.put(election, new Election(value.id(),value.kingdom(),value.candidates(),value.electorate(),ballots,value.state(),value.openedAt(),value.deadline(),value.graceUntil(),state.revision,null));
            return election.toString();
        });
    }
    @Override public Result closeElection(ServerPlayer actor,UUID request,long expected,UUID election) {
        Election existing = data.records().elections.get(election); if (existing == null) return new Result(false,revision(),"Election unavailable","");
        return transition(actor, request, expected, existing.kingdom(), Action.CLOSE_ELECTION, election.toString(), state -> closeElection(actor,election,state));
    }
    @Override public Result appointRegent(ServerPlayer actor,UUID request,long expected,String kingdom,UUID regentId) {
        return transition(actor, request, expected, kingdom, Action.APPOINT_REGENT, regentId.toString(), state -> {
            Government government = state.governments.get(kingdom); Rule rule = state.transitionRules.get(kingdom); check(rule != null && rule.regency(), "This government has not opted into regency");
            if (government.state()==State.ACTIVE) authorize(actor,government,Permission.APPOINT); else check(actor.hasPermissions(2),"Interregnum regency recovery requires operator authority");
            Regency prior = state.regencies.get(kingdom); check(prior==null || !prior.active() || now()>=prior.expiresAt(),"A regency is already active");
            Person regent = person(new Person(regentId,Kind.PLAYER),kingdom,null); UUID id = request;
            state.regencies.put(kingdom,new Regency(id,kingdom,regent,rule.regentPermissions(),government.successor(),now(),now()+rule.regencyTicks(),true,state.revision));
            return id.toString();
        });
    }
    @Override public Result endRegency(ServerPlayer actor,UUID request,long expected,String kingdom) {
        return transition(actor, request, expected, kingdom, Action.END_REGENCY, kingdom, state -> {
            Government government=state.governments.get(kingdom);Regency value=state.regencies.get(kingdom);check(value!=null&&value.active(),"No active regency");
            check(actor.hasPermissions(2)||value.regent().id().equals(actor.getUUID())||permitted(actor,government,Permission.APPOINT),"Only the regent or appointing authority may end the regency");
            state.regencies.put(kingdom,new Regency(value.id(),kingdom,value.regent(),value.permissions(),value.preservedSuccessor(),value.appointedAt(),value.expiresAt(),false,state.revision));return value.id().toString();
        });
    }

    private interface TransitionMutation { String apply(PoliticalSavedData.Records state); }
    private Result transition(ServerPlayer actor,UUID request,long expected,String kingdom,Action action,String detail,TransitionMutation mutation) {
        thread();check(actor.getServer()==server&&!actor.hasDisconnected(),"Player is not connected to this server");Objects.requireNonNull(request); Politics.bounded(kingdom,128);
        String fingerprint=hash(action+"|"+expected+"|"+kingdom+"|"+detail);Receipt replay=data.records().receipts.get(request);
        if(replay!=null)return replay.actor().equals(actor.getUUID())&&replay.fingerprint().equals(fingerprint)?replay.result():new Result(false,revision(),"Request ID already used","");
        try{check(data.writable(),data.diagnostic());check(!conflict,"Native governance disabled while MCA: Capitals is installed");check(expected==revision(),"Political state changed; refresh before retrying");
            Government government=data.records().governments.get(kingdom);check(government!=null,"Government is unorganized");
            check(definitions.available(government.profileId()),"Constitution unavailable; government dormant");check(data.records().receipts.size()<8192,"Political request receipt capacity reached; operator maintenance required");
            var next=data.transaction();next.revision++;String record=mutation.apply(next);Result result=new Result(true,next.revision,"Constitutional transition recorded",record);
            next.receipts.put(request,new Receipt(actor.getUUID(),fingerprint,result));Notice notice=new Notice(request,kingdom,action,actor.getUUID(),record,now());append(next,notice,transitionFact(request,kingdom,action,actor.getUUID(),record,next));
            if(!data.commitDurably(server,next))return new Result(false,revision(),"Constitutional transition could not be durably saved","");publish(notice);return result;
        }catch(IllegalArgumentException|IllegalStateException failure){return new Result(false,revision(),Objects.toString(failure.getMessage(),"Constitutional transition rejected"),"");}
    }
    @Override public List<PoliticalFact> history(ServerPlayer viewer, int offset, int limit) {
        thread(); check(viewer.getServer() == server && !viewer.hasDisconnected(), "Player is not connected to this server");
        check(offset >= 0 && offset <= 100000 && limit > 0 && limit <= 50, "Invalid history page");
        return visibleHistory(viewer).skip(offset).limit(limit).toList();
    }
    private long now() { return server.overworld().getGameTime(); }
    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static void check(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public Result execute(ServerPlayer actor, Request request) {
        thread();
        check(actor.getServer() == server && !actor.hasDisconnected(), "Player is not connected to this server");
        String fingerprint = hash(PoliticalSavedData.JSON.toJson(request));
        Receipt receipt = data.records().receipts.get(request.requestId());
        if (receipt != null) {
            return receipt.actor().equals(actor.getUUID()) && receipt.fingerprint().equals(fingerprint)
                    ? receipt.result() : new Result(false, revision(), "Request ID already used", "");
        }
        try {
            check(data.writable(), data.diagnostic());
            check(!conflict, "Native governance disabled while MCA: Capitals is installed");
            check(request.expectedRevision() == revision(), "Political state changed; refresh before retrying");
            check(kingdoms.getKingdom(new ResourceLocation(request.kingdom())).filter(KingdomView::defined).isPresent(),
                    "Kingdom definition unavailable");
            // Receipts are not evicted: old requests can never become new awards after pruning.
            check(data.records().receipts.size() < 8192, "Political request receipt capacity reached; operator maintenance required");
            PoliticalSavedData.Records next = data.transaction();
            next.revision++;
            String id = mutate(actor, request, next);
            Result result = new Result(true, next.revision, "Political decision recorded", id);
            next.receipts.put(request.requestId(), new Receipt(actor.getUUID(), fingerprint, result));
            Notice notice = new Notice(request.requestId(), request.kingdom(), request.action(), actor.getUUID(), id, now());
            append(next, notice, fact(request, result, next, actor.getUUID()));
            if (!data.commitDurably(server, next))
                return new Result(false, revision(), "Political decision could not be durably saved", "");
            try {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new PoliticalCommittedEvent(server, notice));
            } catch (RuntimeException listenerFailure) {
                com.mojang.logging.LogUtils.getLogger().error("Political state committed but a post-commit listener failed; no provider delivery is assumed", listenerFailure);
            }
            return result;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return new Result(false, revision(), Objects.toString(failure.getMessage(), "Political action rejected"), "");
        }
    }

    private PoliticalFact fact(Request request, Result result, PoliticalSavedData.Records records, UUID actor) {
        String recordId = result.recordId();
        Agreement agreement = parse(recordId).map(records.agreements::get).orElse(null);
        Petition petition = parse(recordId).map(records.petitions::get).orElse(null);
        PoliticalFact.Type type = switch (request.action()) {
            case BOOTSTRAP -> PoliticalFact.Type.GOVERNMENT_FOUNDED;
            case SEAT -> PoliticalFact.Type.CAPITAL_MOVED;
            case APPOINT -> PoliticalFact.Type.OFFICE_APPOINTED;
            case REMOVE_OFFICE -> PoliticalFact.Type.OFFICE_REMOVED;
            case DELEGATE -> PoliticalFact.Type.MANDATE_GRANTED;
            case REVOKE -> PoliticalFact.Type.MANDATE_REVOKED;
            case PROPOSE -> PoliticalFact.Type.AGREEMENT_PROPOSED;
            case SIGN -> PoliticalFact.Type.AGREEMENT_SIGNED;
            case DECLINE -> PoliticalFact.Type.AGREEMENT_DECLINED;
            case WITHDRAW -> petition == null ? PoliticalFact.Type.AGREEMENT_WITHDRAWN : PoliticalFact.Type.PETITION_WITHDRAWN;
            case TERMINATE -> PoliticalFact.Type.AGREEMENT_TERMINATED;
            case PETITION -> PoliticalFact.Type.PETITION_SUBMITTED;
            case REVIEW -> PoliticalFact.Type.PETITION_REVIEWED;
            case APPROVE -> PoliticalFact.Type.PETITION_APPROVED;
            case REJECT -> PoliticalFact.Type.PETITION_REJECTED;
            case RECOGNIZE -> PoliticalFact.Type.INSTITUTION_RECOGNIZED;
            case REVALIDATE -> PoliticalFact.Type.INSTITUTION_REVALIDATED;
            case SUSPEND_RECOGNITION -> PoliticalFact.Type.INSTITUTION_SUSPENDED;
            case HONOR -> PoliticalFact.Type.HONOR_GRANTED;
            case REVOKE_HONOR -> PoliticalFact.Type.HONOR_REVOKED;
            case ABDICATE -> PoliticalFact.Type.LEADER_ABDICATED;
            case NAME_SUCCESSOR -> PoliticalFact.Type.SUCCESSOR_NAMED;
            case SUCCEED -> PoliticalFact.Type.SUCCESSION_CONFIRMED;
            case HOUSE -> PoliticalFact.Type.HOUSE_UPDATED;
            case ADOPT_TRANSITION_RULE -> PoliticalFact.Type.TRANSITION_RULE_ADOPTED;
            case OPEN_ELECTION -> PoliticalFact.Type.ELECTION_OPENED;
            case CAST_BALLOT -> PoliticalFact.Type.BALLOT_CAST;
            case CLOSE_ELECTION -> PoliticalFact.Type.ELECTION_RESOLVED;
            case APPOINT_REGENT -> PoliticalFact.Type.REGENCY_APPOINTED;
            case END_REGENCY -> PoliticalFact.Type.REGENCY_ENDED;
        };
        Set<String> affectedKingdoms = new LinkedHashSet<>();
        affectedKingdoms.add(request.kingdom());
        Set<UUID> affectedPlayers = new LinkedHashSet<>();
        affectedPlayers.add(actor);
        if (request.person() != null) affectedPlayers.add(request.person().id());
        if (agreement != null) {
            affectedKingdoms.add(agreement.proposer()); affectedKingdoms.add(agreement.recipient());
            affectedPlayers.addAll(agreement.signatures().values());
        }
        if (petition != null) {
            affectedKingdoms.add(petition.kingdom()); affectedPlayers.add(petition.requester());
            if (petition.nominee() != null) affectedPlayers.add(petition.nominee().id());
            if (petition.decidedBy() != null) affectedPlayers.add(petition.decidedBy());
        }
        PoliticalFact.Visibility visibility;
        if (petition != null || request.action() == Action.PETITION) visibility = PoliticalFact.Visibility.PRIVATE;
        else if (agreement != null && agreement.state() != AgreementState.ACTIVE && agreement.state() != AgreementState.TERMINATED)
            visibility = PoliticalFact.Visibility.PARTIES;
        else if (Set.of(Action.DELEGATE, Action.REVOKE).contains(request.action())) visibility = PoliticalFact.Visibility.PARTIES;
        else visibility = PoliticalFact.Visibility.PUBLIC;
        return new PoliticalFact(request.requestId(), request.requestId(), result.revision(), now(), request.kingdom(),
                type, actor, recordId, affectedKingdoms, affectedPlayers, visibility,
                PoliticalFact.Correction.CURRENT, null, summary(type));
    }

    private static Optional<UUID> parse(String value) {
        try { return Optional.of(UUID.fromString(value)); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    private static String summary(PoliticalFact.Type type) {
        String value = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void append(PoliticalSavedData.Records records, Notice notice, PoliticalFact fact) {
        records.journal.add(notice); records.facts.add(fact);
        while (records.journal.stream().filter(n -> n.kingdom().equals(notice.kingdom())).count() > 256)
            removeFirst(records.journal, n -> n.kingdom().equals(notice.kingdom()));
        while (records.facts.stream().filter(value -> value.kingdom().equals(fact.kingdom())).count() > 256)
            removeFirst(records.facts, value -> value.kingdom().equals(fact.kingdom()));
    }

    private static <T> void removeFirst(List<T> values, java.util.function.Predicate<T> predicate) {
        for (int i = 0; i < values.size(); i++) if (predicate.test(values.get(i))) { values.remove(i); return; }
    }

    private java.util.stream.Stream<PoliticalFact> visibleHistory(ServerPlayer viewer) {
        return data.records().facts.stream().filter(fact -> visible(viewer, fact)).map(this::corrected)
                .sorted(Comparator.comparingLong(PoliticalFact::revision).thenComparingLong(PoliticalFact::gameTime)
                        .thenComparing(PoliticalFact::id).reversed());
    }

    private boolean visible(ServerPlayer viewer, PoliticalFact fact) {
        if (fact.visibility() == PoliticalFact.Visibility.PUBLIC || fact.affectedPlayers().contains(viewer.getUUID())) return true;
        boolean petition = petitionFact(fact.type());
        for (String kingdom : fact.affectedKingdoms()) {
            Government government = data.records().governments.get(kingdom);
            if (petition && permitted(viewer, government, Permission.REVIEW)) return true;
            if (fact.visibility() == PoliticalFact.Visibility.PARTIES
                    && (permitted(viewer, government, Permission.PROPOSE) || permitted(viewer, government, Permission.RATIFY))) return true;
        }
        return false;
    }

    private PoliticalFact corrected(PoliticalFact fact) {
        if (!lifecycleFact(fact.type()) || fact.type() == PoliticalFact.Type.AGREEMENT_EXPIRED
                || fact.type() == PoliticalFact.Type.PETITION_EXPIRED) return fact;
        PoliticalFact correction = data.records().facts.stream()
                .filter(other -> !other.id().equals(fact.id()) && other.recordId().equals(fact.recordId())
                        && other.revision() > fact.revision() && lifecycleFact(other.type()))
                .max(Comparator.comparingLong(PoliticalFact::revision).thenComparing(PoliticalFact::id)).orElse(null);
        boolean expired = correction != null && (correction.type() == PoliticalFact.Type.AGREEMENT_EXPIRED
                || correction.type() == PoliticalFact.Type.PETITION_EXPIRED);
        if (!expired) {
            Optional<UUID> id = parse(fact.recordId());
            Agreement agreement = id.map(data.records().agreements::get).orElse(null);
            Petition petition = id.map(data.records().petitions::get).orElse(null);
            expired = agreement != null && (agreement.state() == AgreementState.EXPIRED
                    || now() >= agreement.expiresAt() && (agreement.state() == AgreementState.PROPOSED || agreement.state() == AgreementState.ACTIVE))
                    || petition != null && (petition.state() == PetitionState.EXPIRED || now() >= petition.expiresAt() && open(petition));
        }
        if (correction == null && !expired) return fact;
        return new PoliticalFact(fact.id(), fact.sourceReceiptId(), fact.revision(), fact.gameTime(), fact.kingdom(), fact.type(),
                fact.actor(), fact.recordId(), fact.affectedKingdoms(), fact.affectedPlayers(), fact.visibility(),
                expired ? PoliticalFact.Correction.EXPIRED : PoliticalFact.Correction.SUPERSEDED,
                correction == null ? null : correction.id(), fact.summary());
    }

    private static boolean lifecycleFact(PoliticalFact.Type type) {
        return switch (type) {
            case AGREEMENT_PROPOSED, AGREEMENT_SIGNED, AGREEMENT_DECLINED, AGREEMENT_WITHDRAWN,
                    AGREEMENT_TERMINATED, AGREEMENT_EXPIRED, PETITION_SUBMITTED, PETITION_REVIEWED,
                    PETITION_APPROVED, PETITION_REJECTED, PETITION_WITHDRAWN, PETITION_EXPIRED -> true;
            default -> false;
        };
    }

    private static boolean petitionFact(PoliticalFact.Type type) {
        return switch (type) {
            case PETITION_SUBMITTED, PETITION_REVIEWED, PETITION_APPROVED, PETITION_REJECTED,
                    PETITION_WITHDRAWN, PETITION_EXPIRED -> true;
            default -> false;
        };
    }
    private String mutate(ServerPlayer actor, Request r, PoliticalSavedData.Records s) {
        Government g = s.governments.get(r.kingdom());
        if (r.action() == Action.BOOTSTRAP) {
            check(actor.hasPermissions(2), "Founding requires operator permission level 2");
            check(g == null, "Government already constituted");
            Definition profile = definitions.get(r.definition(), "government");
            SettlementView seat = settlement(r.target(), r.kingdom());
            Person leader = person(r.person(), r.kingdom(), null);
            check(profile.offices().contains(LEADER), "Constitution has no leadership office");
            Office office = new Office(LEADER, definitions.get(LEADER, "office"), leader, null, actor.getUUID(), now());
            s.governments.put(r.kingdom(), new Government(r.kingdom(), seat.id(), r.definition(), profile, State.ACTIVE,
                    Map.of(LEADER, office), Map.of(), null, s.revision));
            return r.kingdom();
        }
        if (r.action() == Action.PETITION) return petition(actor, r, s).toString();
        check(g != null, "Government is unorganized");
        check(definitions.available(g.profileId()), "Constitution unavailable; government dormant");
        switch (r.action()) {
            case SEAT -> {
                authorize(actor, g, Permission.SEAT);
                SettlementView seat = settlement(r.target(), r.kingdom());
                s.governments.put(g.kingdom(), government(g, seat.id(), g.offices(), g.mandates(), g.successor(), s.revision));
            }
            case APPOINT -> {
                if (g.state() == State.INTERREGNUM && r.definition().equals(LEADER))
                    check(actor.hasPermissions(2), "Vacant leadership without a successor requires operator appointment");
                else authorize(actor, g, Permission.APPOINT);
                check(g.constitution().offices().contains(r.definition()), "Office is not in this constitution");
                Definition office = definitions.get(r.definition(), "office");
                UUID scope = r.target().isEmpty() ? null : settlement(r.target(), r.kingdom()).id();
                check(!r.definition().equals(LEADER) || scope == null, "Leadership is kingdom-wide");
                Person candidate = person(r.person(), r.kingdom(), scope);
                check(g.offices().values().stream().filter(o -> o.holder().equals(candidate)).count() < g.constitution().maxOffices(), "Office limit reached");
                String key = officeKey(r.definition(), scope);
                check(g.offices().values().stream().noneMatch(o -> o.definitionId().equals(r.definition())
                        && Objects.equals(scope, o.settlement() == null ? null : kingdoms.getSettlement(o.settlement()).orElseThrow().id())), "Office occupied; remove its holder explicitly first");
                Map<String, Office> offices = new LinkedHashMap<>(g.offices());
                offices.put(key, new Office(r.definition(), office, candidate, scope, actor.getUUID(), now()));
                s.governments.put(g.kingdom(), government(g, g.capital(), offices, g.mandates(), g.successor(), s.revision));
            }
            case REMOVE_OFFICE, ABDICATE -> {
                String key = r.action() == Action.ABDICATE ? LEADER : officeKey(r.definition(), r.target().isEmpty() ? null : uuid(r.target()));
                Office office = g.offices().get(key); check(office != null, "Office is vacant");
                if (r.action() == Action.ABDICATE) check(office.holder().equals(new Person(actor.getUUID(), Kind.PLAYER)), "Only the holder may abdicate");
                else authorize(actor, g, Permission.APPOINT);
                Map<String, Office> offices = new LinkedHashMap<>(g.offices()); offices.remove(key);
                Map<UUID, Set<Permission>> mandates = key.equals(LEADER) ? Map.of() : g.mandates();
                s.governments.put(g.kingdom(), government(g, g.capital(), offices, mandates, g.successor(), s.revision));
            }
            case DELEGATE, REVOKE -> {
                authorize(actor, g, Permission.DELEGATE);
                check(r.person() != null && r.person().kind() == Kind.PLAYER, "Mandates require a player");
                Map<UUID, Set<Permission>> mandates = new LinkedHashMap<>(g.mandates());
                if (r.action() == Action.REVOKE) mandates.remove(r.person().id());
                else {
                    Permission permission = Permission.valueOf(r.definition().toUpperCase(Locale.ROOT));
                    check(permission != Permission.DELEGATE, "Delegation authority cannot be redelegated");
                    check(g.offices().get(LEADER) != null && g.offices().get(LEADER).holder().equals(new Person(actor.getUUID(), Kind.PLAYER)), "Only the leader delegates mandates");
                    Set<Permission> grant = new HashSet<>(mandates.getOrDefault(r.person().id(), Set.of())); grant.add(permission);
                    mandates.put(r.person().id(), grant);
                }
                s.governments.put(g.kingdom(), government(g, g.capital(), g.offices(), mandates, g.successor(), s.revision));
            }
            case PROPOSE -> { return propose(actor, r, s, g).toString(); }
            case SIGN, DECLINE, WITHDRAW, TERMINATE -> {
                if (r.action() == Action.WITHDRAW && s.petitions.containsKey(uuid(r.target()))) {
                    Petition p = s.petitions.get(uuid(r.target()));
                    check(p.requester().equals(actor.getUUID()) && p.kingdom().equals(r.kingdom()), "Only the petitioner can withdraw");
                    check(open(p), "Petition is closed");
                    s.petitions.put(p.id(), decision(p, PetitionState.WITHDRAWN, r.text(), actor.getUUID(), s.revision));
                } else agreement(actor, r, s, g);
            }
            case REVIEW, APPROVE, REJECT -> {
                authorize(actor, g, Permission.REVIEW);
                Petition p = s.petitions.get(uuid(r.target()));
                check(p != null && p.kingdom().equals(r.kingdom()), "Petition unavailable");
                check(open(p) && now() < p.expiresAt(), "Petition is closed or expired");
                check(definitions.available(p.definitionId()), "Petition definition unavailable");
                if (p.terms().independentApproval()) check(!p.requester().equals(actor.getUUID())
                        && (p.nominee() == null || !p.nominee().id().equals(actor.getUUID())), "An independent approver is required");
                check(!r.text().isBlank(), "A decision reason is required");
                if (r.action() == Action.APPROVE) approvePetition(actor, p, s, g);
                s.petitions.put(p.id(), decision(p, r.action() == Action.REVIEW ? PetitionState.UNDER_REVIEW
                        : r.action() == Action.APPROVE ? PetitionState.APPROVED : PetitionState.DECLINED,
                        r.text(), actor.getUUID(), s.revision));
            }
            case RECOGNIZE -> {
                Building evidence = building(actor.serverLevel(), new BlockPos(r.x(), r.y(), r.z()), g.kingdom());
                authorizeScoped(actor, g, Permission.RECOGNIZE, evidence.settlement());
                return recognize(actor, s, g.kingdom(), r.definition(), evidence).toString();
            }
            case SUSPEND_RECOGNITION -> {
                Recognition recognition = s.recognitions.get(uuid(r.target()));
                check(recognition != null && recognition.kingdom().equals(g.kingdom()), "Recognition unavailable");
                UUID scope = kingdoms.getSettlement(recognition.building().settlement()).orElseThrow().id();
                authorizeScoped(actor, g, Permission.RECOGNIZE, scope);
                s.recognitions.put(recognition.id(), new Recognition(recognition.id(), recognition.kingdom(), recognition.definitionId(), recognition.terms(), recognition.building(), actor.getUUID(), false, s.revision));
            }
            case REVALIDATE -> {
                Recognition recognition = s.recognitions.get(uuid(r.target()));
                check(recognition != null && recognition.kingdom().equals(g.kingdom()), "Recognition unavailable");
                authorizeScoped(actor, g, Permission.RECOGNIZE, settlement(recognition.building().settlement().toString(), g.kingdom()).id());
                check(definitions.available(recognition.definitionId()), "Charter definition unavailable");
                Building original = recognition.building();
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(original.dimension())));
                check(level != null && level.hasChunkAt(new BlockPos(original.x(), original.y(), original.z())), "Building status unknown: location unloaded");
                Optional<TownsteadBuildingView> observed = townstead().buildingAt(level, new BlockPos(original.x(), original.y(), original.z()));
                check(townstead().capabilities().contains(TownsteadCapability.READ_BUILDING), "Building capability unavailable");
                boolean valid = observed.filter(b -> b.villageId() == original.villageId() && b.buildingId() == original.buildingId()
                        && b.type().equals(original.type())).isPresent();
                s.recognitions.put(recognition.id(), new Recognition(recognition.id(), recognition.kingdom(), recognition.definitionId(), recognition.terms(), original, actor.getUUID(), valid, s.revision));
            }
            case HONOR -> {
                authorize(actor, g, Permission.HONOR);
                return honor(actor, s, g.kingdom(), r.definition(), person(r.person(), g.kingdom(), null), "Discretionary: " + r.text(), true).toString();
            }
            case REVOKE_HONOR -> {
                authorize(actor, g, Permission.HONOR);
                Honor h = s.honors.get(uuid(r.target())); check(h != null && h.kingdom().equals(g.kingdom()), "Honor unavailable");
                s.honors.put(h.id(), new Honor(h.id(), h.kingdom(), h.definitionId(), h.terms(), h.recipient(), actor.getUUID(), h.evidence(), h.discretionary(), false, s.revision));
            }
            case NAME_SUCCESSOR -> {
                authorize(actor, g, Permission.APPOINT);
                check(!activeRegency(g.kingdom()), "Named successor is frozen during an active regency");
                Person successor = person(r.person(), g.kingdom(), null);
                s.governments.put(g.kingdom(), government(g, g.capital(), g.offices(), g.mandates(), successor, s.revision));
            }
            case SUCCEED -> {
                check(g.state() == State.INTERREGNUM && g.successor() != null, "No named successor in interregnum");
                check(actor.hasPermissions(2) || g.successor().equals(new Person(actor.getUUID(), Kind.PLAYER)), "Only the named successor or operator can confirm");
                Person successor = person(g.successor(), g.kingdom(), null);
                Map<String, Office> offices = new LinkedHashMap<>(g.offices());
                offices.put(LEADER, new Office(LEADER, definitions.get(LEADER, "office"), successor, null, actor.getUUID(), now()));
                s.governments.put(g.kingdom(), government(g, g.capital(), offices, Map.of(), null, s.revision));
                Regency regency=s.regencies.get(g.kingdom());if(regency!=null&&regency.active())s.regencies.put(g.kingdom(),new Regency(regency.id(),regency.kingdom(),regency.regent(),regency.permissions(),regency.preservedSuccessor(),regency.appointedAt(),regency.expiresAt(),false,s.revision));
            }
            case HOUSE -> {
                authorize(actor, g, Permission.APPOINT);
                Person member = person(r.person(), g.kingdom(), null);
                s.houses.put(g.kingdom(), new House(g.kingdom(), r.definition(), r.text(), Set.of(member)));
            }
            default -> throw new IllegalArgumentException("Unsupported action");
        }
        return r.target().isEmpty() ? r.kingdom() : r.target();
    }
    private static String officeKey(String definition, UUID scope) { return scope == null ? definition : definition + "@" + scope; }
    private Government government(Government g, UUID capital, Map<String, Office> offices,
                                  Map<UUID, Set<Permission>> mandates, Person successor, long revision) {
        return new Government(g.kingdom(), capital, g.profileId(), g.constitution(), offices.containsKey(LEADER) ? State.ACTIVE : State.INTERREGNUM,
                offices, mandates, successor, revision);
    }
    private Set<UUID> electorate(Government government) {
        Set<UUID> result=new LinkedHashSet<>(government.mandates().keySet());
        government.offices().values().stream().map(Office::holder).filter(p->p.kind()==Kind.PLAYER).map(Person::id).forEach(result::add);
        return result;
    }
    private String closeElection(ServerPlayer actor,UUID id,PoliticalSavedData.Records state) {
        Election value=state.elections.get(id);check(value!=null&&(value.state()==ElectionState.OPEN||value.state()==ElectionState.GRACE),"Election is closed");
        Government government=state.governments.get(value.kingdom());check(value.electorate().contains(actor.getUUID())||actor.hasPermissions(2)||permitted(actor,government,Permission.APPOINT),"Only electorate or appointing authority may close the ballot");
        check(value.ballots().size()==value.electorate().size()||now()>=value.deadline(),"Ballot remains open until all eligible votes arrive or the deadline passes");
        ElectionState outcome=closureState(value,now());
        if(outcome!=ElectionState.RESOLVED){ElectionState phase=outcome;
            state.elections.put(id,new Election(value.id(),value.kingdom(),value.candidates(),value.electorate(),value.ballots(),phase,value.openedAt(),value.deadline(),value.graceUntil(),state.revision,null));return id.toString();}
        UUID winner=uniqueWinner(value).orElseThrow();Person elected=person(new Person(winner,Kind.PLAYER),value.kingdom(),null);Regency activeRegency=state.regencies.get(value.kingdom());
        check(activeRegency==null||!activeRegency.active()||government.state()==State.INTERREGNUM,"End the active regency before replacing its preserved successor by election");
        if(government.state()==State.INTERREGNUM){Map<String,Office> offices=new LinkedHashMap<>(government.offices());
            offices.put(LEADER,new Office(LEADER,definitions.get(LEADER,"office"),elected,null,actor.getUUID(),now()));
            state.governments.put(government.kingdom(),government(government,government.capital(),offices,Map.of(),null,state.revision));
            Regency regency=state.regencies.get(government.kingdom());if(regency!=null&&regency.active())state.regencies.put(government.kingdom(),new Regency(regency.id(),regency.kingdom(),regency.regent(),regency.permissions(),regency.preservedSuccessor(),regency.appointedAt(),regency.expiresAt(),false,state.revision));
        }else state.governments.put(government.kingdom(),government(government,government.capital(),government.offices(),government.mandates(),elected,state.revision));
        state.elections.put(id,new Election(value.id(),value.kingdom(),value.candidates(),value.electorate(),value.ballots(),ElectionState.RESOLVED,value.openedAt(),value.deadline(),value.graceUntil(),state.revision,winner));return id.toString();
    }
    static Optional<UUID> uniqueWinner(Election value){Map<UUID,Long> counts=new TreeMap<>();value.candidates().forEach(candidate->counts.put(candidate,0L));
        value.ballots().values().forEach(candidate->counts.put(candidate,counts.get(candidate)+1));long highest=counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        if(highest==0)return Optional.empty();List<UUID>winners=counts.entrySet().stream().filter(e->e.getValue()==highest).map(Map.Entry::getKey).toList();return winners.size()==1?Optional.of(winners.get(0)):Optional.empty();}
    static ElectionState closureState(Election value,long time){if(time>=value.graceUntil())return ElectionState.EXPIRED;return uniqueWinner(value).isPresent()?ElectionState.RESOLVED:ElectionState.GRACE;}
    static boolean unexpired(Regency value,long time){return value!=null&&value.active()&&time<value.expiresAt();}
    private PoliticalFact transitionFact(UUID source,String kingdom,Action action,UUID actor,String record,PoliticalSavedData.Records state) {
        PoliticalFact.Type type=switch(action){case ADOPT_TRANSITION_RULE->PoliticalFact.Type.TRANSITION_RULE_ADOPTED;case OPEN_ELECTION->PoliticalFact.Type.ELECTION_OPENED;
            case CAST_BALLOT->PoliticalFact.Type.BALLOT_CAST;case CLOSE_ELECTION->{Election e=state.elections.get(parse(record).orElse(new UUID(0,0)));yield e!=null&&e.state()==ElectionState.EXPIRED?PoliticalFact.Type.ELECTION_EXPIRED:e!=null&&e.state()==ElectionState.GRACE?PoliticalFact.Type.ELECTION_GRACE:PoliticalFact.Type.ELECTION_RESOLVED;}
            case APPOINT_REGENT->PoliticalFact.Type.REGENCY_APPOINTED;case END_REGENCY->PoliticalFact.Type.REGENCY_ENDED;default->throw new IllegalArgumentException("Not a transition action");};
        PoliticalFact.Visibility visibility=action==Action.CAST_BALLOT?PoliticalFact.Visibility.PRIVATE:PoliticalFact.Visibility.PUBLIC;
        return new PoliticalFact(source,source,state.revision,now(),kingdom,type,actor,record,Set.of(kingdom),Set.of(actor),visibility,PoliticalFact.Correction.CURRENT,null,summary(type));
    }
    private boolean permitted(ServerPlayer actor, Government g, Permission permission) {
        return permitted(actor.getUUID(), g, permission);
    }
    private boolean activeRegency(String kingdom){Regency value=data.records().regencies.get(kingdom);return value!=null&&value.active()&&now()<value.expiresAt();}
    private boolean permitted(UUID actor, Government g, Permission permission) {
        if (g == null || !definitions.available(g.profileId())) return false;
        Regency regency=data.records().regencies.get(g.kingdom());
        if(regency!=null&&regency.active()&&now()<regency.expiresAt()&&regency.regent().id().equals(actor)&&regency.permissions().contains(permission))return true;
        if(g.state()!=State.ACTIVE)return false;
        if (g.mandates().getOrDefault(actor, Set.of()).contains(permission)) return true;
        if (kingdoms.getKingdom(new ResourceLocation(g.kingdom())).filter(KingdomView::defined).isEmpty()) return false;
        return g.offices().values().stream().anyMatch(o -> o.settlement() == null && definitions.available(o.definitionId())
                && o.holder().equals(new Person(actor, Kind.PLAYER)) && o.terms().permissions().contains(permission));
    }
    private void authorize(ServerPlayer actor, Government g, Permission permission) {
        check(permitted(actor, g, permission), "Requires a kingdom-wide " + permission.name().toLowerCase(Locale.ROOT) + " mandate");
    }
    private void authorizeScoped(ServerPlayer actor, Government g, Permission permission, UUID settlement) {
        if (permitted(actor, g, permission)) return;
        check(g.state() == State.ACTIVE && g.offices().values().stream().anyMatch(o -> o.settlement() != null
                && o.holder().equals(new Person(actor.getUUID(), Kind.PLAYER)) && definitions.available(o.definitionId())
                && o.terms().permissions().contains(permission) && kingdoms.getSettlement(o.settlement()).map(v -> v.id().equals(settlement)).orElse(false)),
                "Requires a " + permission.name().toLowerCase(Locale.ROOT) + " mandate for this settlement");
    }
    private SettlementView settlement(String id, String kingdom) {
        SettlementView result = kingdoms.findSettlement(id).orElseThrow(() -> new IllegalArgumentException("Settlement unavailable"));
        check(result.kingdomId().toString().equals(kingdom), "Settlement belongs to another kingdom"); return result;
    }
    private TownsteadService townstead() { return UltimaTownsteadApi.get(server); }
    private Person person(Person candidate, String kingdom, UUID scope) {
        check(candidate != null, "A person is required");
        if (candidate.kind() == Kind.PLAYER) {
            check(knownPlayer.test(candidate.id()), "Unknown player");
            return candidate;
        }
        Entity entity = null;
        for (ServerLevel level : server.getAllLevels()) { entity = level.getEntity(candidate.id()); if (entity != null) break; }
        check(entity instanceof LivingEntity && entity.isAlive() && !(entity instanceof ServerPlayer), "NPC life status unknown: candidate must be alive and loaded");
        var identity = kingdoms.getCivicIdentity(entity).orElseThrow(() -> new IllegalArgumentException("Civic residence unknown"));
        UUID residence = identity.residenceSettlement().orElseThrow(() -> new IllegalArgumentException("Civic residence unknown"));
        SettlementView home = settlement(residence.toString(), kingdom);
        check(scope == null || scope.equals(home.id()), "Candidate is not resident in this office's settlement");
        if (ModList.get().isLoaded("townstead")) {
            String version = ModList.get().getModContainerById("townstead").orElseThrow().getModInfo().getVersion().toString();
            check(version.equals(PoliticalProviderVersions.TOWNSTEAD), "Political life-stage evidence unsupported for Townstead " + version);
            TownsteadVillagerView view = townstead().villager(entity).orElseThrow(() -> new IllegalArgumentException("Townstead life-stage evidence unavailable"));
            String presentation = view.rootId().flatMap(townstead()::origin).stream().flatMap(root -> root.lifeStages().stream())
                    .filter(stage -> stage.id().equalsIgnoreCase(view.lifeStage())).map(TownsteadLifeStageView::presentsAs).findFirst().orElse("");
            check(townstead().capabilities().contains(TownsteadCapability.READ_VILLAGER)
                    && townstead().capabilities().contains(TownsteadCapability.READ_ORIGIN)
                    && Set.of("adult", "senior").contains(presentation.toLowerCase(Locale.ROOT)), "Adulthood unconfirmed for life stage " + view.lifeStage());
        } else {
            check(entity instanceof Villager && !((Villager) entity).isBaby(), "Adult evidence unavailable for this NPC provider");
        }
        return candidate;
    }
    private Building building(ServerLevel level, BlockPos position, String kingdom) {
        check(level.hasChunkAt(position), "Building status unknown: location unloaded");
        check(ModList.get().getModContainerById("townstead").map(c -> c.getModInfo().getVersion().toString()).orElse("").equals(PoliticalProviderVersions.TOWNSTEAD), "Political building evidence requires verified Townstead " + PoliticalProviderVersions.TOWNSTEAD);
        TownsteadBuildingView b = townstead().buildingAt(level, position).orElseThrow(() -> new IllegalArgumentException("Building evidence unavailable"));
        check(townstead().capabilities().contains(TownsteadCapability.READ_BUILDING), "Building capability unavailable");
        SettlementView settlement = settlement(b.settlementId().toString(), kingdom);
        return new Building("townstead", b.dimension().toString(), b.villageId(), b.buildingId(), settlement.id(), b.type(), b.centerX(), b.centerY(), b.centerZ());
    }
    private UUID propose(ServerPlayer actor, Request r, PoliticalSavedData.Records s, Government g) {
        authorize(actor, g, Permission.PROPOSE);
        Government other = s.governments.get(r.counterpart());
        check(other != null && !other.kingdom().equals(g.kingdom()) && other.state() == State.ACTIVE
                && definitions.available(other.profileId()), "Counterpart government unavailable");
        Definition terms = definitions.get(r.definition(), "agreement");
        for (String prerequisite : terms.requires()) check(s.agreements.values().stream().anyMatch(a -> a.involves(g.kingdom()) && a.involves(other.kingdom())
                && a.definitionId().equals(prerequisite) && active(a)), "Requires active " + prerequisite);
        check(s.agreements.values().stream().noneMatch(a -> a.involves(g.kingdom()) && a.involves(other.kingdom()) && a.definitionId().equals(r.definition())
                && (active(a) || a.state() == AgreementState.PROPOSED && now() < a.expiresAt())), "An agreement of this type is already pending or active");
        UUID id = UUID.randomUUID();
        String termsHash = hash(g.kingdom() + "|" + other.kingdom() + "|" + r.definition() + "|" + PoliticalSavedData.JSON.toJson(terms) + "|" + r.text());
        s.agreements.put(id, new Agreement(id, g.kingdom(), other.kingdom(), r.definition(), terms, r.text(), termsHash,
                Map.of(), AgreementState.PROPOSED, now(), now() + 720000, s.revision)); return id;
    }
    private boolean active(Agreement a) { return a.state() == AgreementState.ACTIVE && now() < a.expiresAt(); }
    @Override public AgreementClauseView clause(String first, String second, Clause clause) {
        thread(); Objects.requireNonNull(clause);
        if (!data.writable() || conflict || !institutionalServices.getAsBoolean())
            return new AgreementClauseView(clause, AgreementClauseView.Status.UNAVAILABLE, Optional.empty(), revision(), 0, "civic.agreement_unavailable");
        if (first.equals(second)) return new AgreementClauseView(clause, AgreementClauseView.Status.NOT_AGREED,
                Optional.empty(), revision(), 0, "civic.agreement_required");
        var state = data.records();
        var agreement = state.agreements.values().stream()
                .filter(a -> a.involves(first) && a.involves(second) && active(a) && a.signatures().size() == 2)
                .filter(a -> a.terms().clauses().contains(clause))
                .sorted(Comparator.comparing(Agreement::id)).findFirst();
        if (agreement.isEmpty()) return new AgreementClauseView(clause, AgreementClauseView.Status.NOT_AGREED,
                Optional.empty(), revision(), 0, "civic.agreement_required");
        var a = agreement.get();
        boolean available = clause == Clause.HOSPITALITY && definitions.available(a.definitionId()) && java.util.stream.Stream.of(first, second).allMatch(id -> {
            var government = state.governments.get(id);
            return government != null && government.state() == State.ACTIVE && definitions.available(government.profileId())
                    && kingdoms.getKingdom(new ResourceLocation(id)).filter(KingdomView::defined).isPresent();
        });
        return new AgreementClauseView(clause, available ? AgreementClauseView.Status.ACTIVE : AgreementClauseView.Status.UNAVAILABLE,
                Optional.of(a.id()), revision(), a.expiresAt(), available ? "civic.agreement_active" : "civic.agreement_unavailable");
    }
    private void agreement(ServerPlayer actor, Request r, PoliticalSavedData.Records s, Government g) {
        Agreement a = s.agreements.get(uuid(r.target())); check(a != null && a.involves(g.kingdom()), "Agreement unavailable");
        authorize(actor, g, Permission.RATIFY);
        check(now() < a.expiresAt(), "Agreement expired");
        check(definitions.available(a.definitionId()), "Agreement definition unavailable; retained terms are dormant");
        Map<String, UUID> signatures = new LinkedHashMap<>(a.signatures());
        AgreementState state;
        long expires = a.expiresAt();
        if (r.action() == Action.SIGN) {
            check(a.state() == AgreementState.PROPOSED, "Agreement is not pending");
            check(a.termsHash().equals(r.termsHash()), "Terms hash does not match; review the proposal again");
            check(!signatures.containsKey(g.kingdom()), "Government has already signed");
            for (var signature : signatures.entrySet()) check(permitted(signature.getValue(), s.governments.get(signature.getKey()), Permission.RATIFY),
                    "A previous signatory lost ratification authority; withdraw and submit a new proposal");
            for (String prerequisite : a.terms().requires()) check(s.agreements.values().stream().anyMatch(required -> required.involves(a.proposer())
                    && required.involves(a.recipient()) && required.definitionId().equals(prerequisite) && active(required)), "Agreement prerequisite is no longer active");
            signatures.put(g.kingdom(), actor.getUUID());
            state = signatures.size() == 2 ? AgreementState.ACTIVE : AgreementState.PROPOSED;
            if (state == AgreementState.ACTIVE) expires = now() + a.terms().durationTicks();
        } else if (r.action() == Action.TERMINATE) {
            check(active(a), "Agreement is not active"); state = AgreementState.TERMINATED;
        } else {
            check(a.state() == AgreementState.PROPOSED, "Agreement is not pending");
            check(r.action() == Action.DECLINE ? a.recipient().equals(g.kingdom()) : a.proposer().equals(g.kingdom()), "Wrong party for this action");
            state = r.action() == Action.DECLINE ? AgreementState.DECLINED : AgreementState.WITHDRAWN;
        }
        s.agreements.put(a.id(), new Agreement(a.id(), a.proposer(), a.recipient(), a.definitionId(), a.terms(), a.explanation(), a.termsHash(), signatures, state, a.proposedAt(), expires, s.revision));
    }
    private static boolean open(Petition p) { return p.state() == PetitionState.SUBMITTED || p.state() == PetitionState.UNDER_REVIEW; }
    private UUID petition(ServerPlayer actor, Request r, PoliticalSavedData.Records s) {
        Government g = s.governments.get(r.kingdom()); check(g != null && g.state() == State.ACTIVE && definitions.available(g.profileId()), "Government unavailable");
        Definition terms = definitions.get(r.definition(), "petition");
        check(Set.of("ultima_kingdoms:institution_petition", "ultima_kingdoms:honor_petition", "ultima_kingdoms:introduction_petition", "ultima_kingdoms:commission_petition").contains(r.definition()),
                "No supported handler for this petition definition");
        check(s.petitions.values().stream().filter(p -> p.requester().equals(actor.getUUID()) && p.kingdom().equals(g.kingdom()) && open(p) && now() < p.expiresAt()).count() < 3, "At most three open petitions per kingdom");
        check(!r.text().isBlank(), "Explain the request");
        UUID settlement = r.target().isEmpty() ? g.capital() : settlement(r.target(), r.kingdom()).id();
        Building building = r.definition().equals("ultima_kingdoms:institution_petition") ? building(actor.serverLevel(), new BlockPos(r.x(), r.y(), r.z()), r.kingdom()) : null;
        if (r.definition().equals("ultima_kingdoms:commission_petition")) throw new IllegalArgumentException("Commission provider completion receipts are unavailable; sponsorship is disabled");
        if (r.definition().equals("ultima_kingdoms:honor_petition")) person(r.person(), r.kingdom(), null);
        if (r.definition().equals("ultima_kingdoms:introduction_petition")) check(s.governments.containsKey(r.counterpart()) && !r.counterpart().equals(r.kingdom()), "Counterpart government unavailable");
        UUID id = UUID.randomUUID();
        s.petitions.put(id, new Petition(id, actor.getUUID(), r.kingdom(), r.definition(), terms, settlement, r.person(), building,
                r.counterpart(), r.text(), PetitionState.SUBMITTED, "", null, now(), now() + terms.durationTicks(), s.revision)); return id;
    }
    private Petition decision(Petition p, PetitionState state, String reason, UUID actor, long revision) {
        return new Petition(p.id(), p.requester(), p.kingdom(), p.definitionId(), p.terms(), p.settlement(), p.nominee(), p.building(), p.counterpart(), p.explanation(), state, reason, actor, p.submittedAt(), p.expiresAt(), revision);
    }
    private void approvePetition(ServerPlayer actor, Petition p, PoliticalSavedData.Records s, Government g) {
        settlement(p.settlement().toString(), g.kingdom());
        switch (p.definitionId()) {
            case "ultima_kingdoms:institution_petition" -> {
                authorize(actor, g, Permission.RECOGNIZE);
                Building b = p.building();
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(b.dimension())));
                check(level != null, "Building dimension unavailable");
                Building observed = building(level, new BlockPos(b.x(), b.y(), b.z()), g.kingdom());
                check(observed.villageId() == b.villageId() && observed.buildingId() == b.buildingId(), "Original institution no longer exists");
                String definition = definitions.snapshot().entrySet().stream().filter(e -> e.getValue().kind().equals("institution") && e.getValue().buildingTypes().contains(observed.type()))
                        .map(Map.Entry::getKey).sorted().findFirst().orElseThrow(() -> new IllegalArgumentException("No charter supports this building type"));
                recognize(actor, s, g.kingdom(), definition, observed);
            }
            case "ultima_kingdoms:honor_petition" -> {
                authorize(actor, g, Permission.HONOR);
                String honor = "ultima_kingdoms:" + switch (g.kingdom()) {
                    case "ultima_kingdoms:lunari" -> "winter_record"; case "ultima_kingdoms:madera" -> "living_craft";
                    case "ultima_kingdoms:shimaguni" -> "tide_archive";
                    case "ultima_kingdoms:anemosia" -> "open_road"; case "ultima_kingdoms:yew" -> "greenwood"; default -> "open_bell";
                };
                honor(actor, s, g.kingdom(), honor, person(p.nominee(), g.kingdom(), null), "Discretionary petition " + p.id(), true);
            }
            case "ultima_kingdoms:introduction_petition" -> {
                Request proposal = new Request(UUID.randomUUID(), s.revision, Action.PROPOSE, g.kingdom(), "ultima_kingdoms:diplomatic_recognition", "", null, p.counterpart(), p.explanation(), "", 0, 0, 0);
                propose(actor, proposal, s, g);
            }
            default -> throw new IllegalArgumentException("No supported fulfillment handler for this petition");
        }
    }
    private UUID recognize(ServerPlayer actor, PoliticalSavedData.Records s, String kingdom, String id, Building b) {
        Definition terms = definitions.get(id, "institution");
        check(terms.buildingTypes().contains(b.type()), "This charter does not support building type " + b.type());
        check(s.recognitions.values().stream().noneMatch(v -> v.building().dimension().equals(b.dimension()) && v.building().villageId() == b.villageId() && v.building().buildingId() == b.buildingId()), "Institution already recognized; use revalidation");
        UUID record = UUID.randomUUID(); s.recognitions.put(record, new Recognition(record, kingdom, id, terms, b, actor.getUUID(), true, s.revision)); return record;
    }
    private UUID honor(ServerPlayer actor, PoliticalSavedData.Records s, String kingdom, String id, Person person, String evidence, boolean discretionary) {
        check(honorCapacity(s), "Honor capacity reserved for accepted institutional work");
        Definition terms = definitions.get(id, "honor");
        check(s.honors.values().stream().noneMatch(h -> h.kingdom().equals(kingdom) && h.definitionId().equals(id) && h.recipient().equals(person)), "Honor has already been awarded in this scope");
        UUID record = UUID.randomUUID(); s.honors.put(record, new Honor(record, kingdom, id, terms, person, actor.getUUID(), evidence, discretionary, true, s.revision)); return record;
    }
    private boolean honorCapacity(PoliticalSavedData.Records records) {
        int reserved=com.ultimakingdoms.civic.CivicRuntime.get(server).reservedHonorSlots(records.honors.keySet());
        return records.honors.size()+reserved<8192;
    }
    public boolean canPromiseCommissionHonor() { thread();return data.writable()&&!conflict&&honorCapacity(data.records()); }
    /** Internal recipient: frozen recognized-workshop authorization plus durable provider evidence. */
    public boolean recordCommissionHonor(ServerPlayer player, UUID epoch, UUID receipt) {
        thread();if(!data.writable()||conflict||player.getServer()!=server)return false;
        var proof=com.ultimakingdoms.civic.CivicRuntime.get(server).commissionHonor(player,epoch,receipt);
        if(proof.isEmpty())return false;
        var grant=proof.get();var prior=data.records().honors.get(grant.contract());
        String evidence="Verified workshop commission "+grant.contract()+"; provider "+epoch+"/"+receipt;
        if(prior!=null)return prior.recipient().equals(new Person(player.getUUID(),Kind.PLAYER))&&prior.evidence().equals(evidence);
        if(data.records().honors.size()>=8192)return false;
        var next=data.transaction();next.revision++;
        next.honors.put(grant.contract(),new Honor(grant.contract(),grant.kingdom(),grant.honor(),grant.terms(),
                new Person(player.getUUID(),Kind.PLAYER),grant.giver(),evidence,false,true,next.revision));
        // Personal receipt, not a public crime/quest announcement. Existing honor panel shows the award.
        if(!data.commitDurably(server,next))return false;
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("civic.workshop_honor_awarded"));
        return true;
    }
    @Override public boolean canRecognize(ServerPlayer actor, UUID settlementId) {
        thread(); if (actor.getServer() != server || !data.writable()) return false;
        var settlement = kingdoms.getSettlement(settlementId);
        if (settlement.isEmpty()) return false;
        Government government = data.records().governments.get(settlement.get().kingdomId().toString());
        if (government == null) return false;
        try { authorizeScoped(actor, government, Permission.RECOGNIZE, settlement.get().id()); return true; }
        catch (IllegalArgumentException failure) { return false; }
    }
    @Override public boolean authorized(ServerPlayer actor, String kingdom, Permission permission) {
        thread();
        return actor.getServer() == server && !actor.hasDisconnected() && data.writable() && !conflict
                && permitted(actor, data.records().governments.get(kingdom), permission);
    }
    @Override public boolean isCapital(UUID settlement) {
        thread();
        return data.records().governments.values().stream().anyMatch(g -> g.capital().equals(settlement));
    }
    @Override public Optional<com.ultimakingdoms.api.politics.InstitutionView> institution(UUID id) {
        thread(); Recognition recognition = data.records().recognitions.get(id);
        if (recognition == null) return Optional.empty();
        var original = recognition.building();
        var settlement = kingdoms.getSettlement(original.settlement());
        if (settlement.isEmpty()) return Optional.empty();
        var status = com.ultimakingdoms.api.politics.InstitutionView.Status.AVAILABLE;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                new ResourceLocation(original.dimension())));
        BlockPos position = new BlockPos(original.x(),original.y(),original.z());
        if (!recognition.active() || !settlement.get().kingdomId().toString().equals(recognition.kingdom()))
            status = com.ultimakingdoms.api.politics.InstitutionView.Status.SUSPENDED;
        else if (!data.writable() || !definitions.available(recognition.definitionId()))
            status = com.ultimakingdoms.api.politics.InstitutionView.Status.DEFINITION_UNAVAILABLE;
        else if (level == null || !level.hasChunkAt(position))
            status = com.ultimakingdoms.api.politics.InstitutionView.Status.UNLOADED;
        else if (!townstead().capabilities().contains(TownsteadCapability.READ_BUILDING))
            status = com.ultimakingdoms.api.politics.InstitutionView.Status.PROVIDER_UNAVAILABLE;
        else {
            var current = townstead().buildingAt(level,position);
            if (current.isEmpty() || current.get().buildingId()!=original.buildingId()
                    || current.get().villageId()!=original.villageId()
                    || !recognition.terms().buildingTypes().contains(current.get().type())
                    || !kingdoms.getSettlement(current.get().settlementId()).map(s -> s.id().equals(settlement.get().id())).orElse(false))
                status = com.ultimakingdoms.api.politics.InstitutionView.Status.BUILDING_CHANGED;
        }
        return Optional.of(new com.ultimakingdoms.api.politics.InstitutionView(recognition.id(),
                settlement.get().kingdomId(),settlement.get().id(),new ResourceLocation(original.dimension()),position,
                original.type(),recognition.terms().title(),status,data.records().revision));
    }
    @Override public List<com.ultimakingdoms.api.politics.InstitutionView> knownInstitutions(ServerPlayer viewer,int offset,int limit) {
        thread(); check(viewer.getServer()==server,"Wrong server"); check(offset>=0&&offset<=4096&&limit>0&&limit<=32,"Invalid institution page");
        return data.records().recognitions.keySet().stream().sorted().map(this::institution).flatMap(Optional::stream)
                .filter(v -> com.ultimakingdoms.knowledge.SettlementKnowledge.get(server).visible(viewer,v.settlement()))
                .skip(offset).limit(limit).toList();
    }
    @Override public Page page(ServerPlayer viewer, UUID requestId, String kingdom, String tab, int offset) {
        thread(); check(viewer.getServer() == server, "Wrong server");
        check(offset >= 0 && offset <= 100000 && offset % 20 == 0, "Invalid page offset");
        PoliticalSavedData.Records s = data.records(); Government g = s.governments.get(kingdom);
        List<Row> rows = new ArrayList<>();
        switch (tab) {
            case "overview" -> {
                if (g == null) rows.add(new Row("", "UNORGANIZED", "An operator may explicitly constitute this kingdom at an existing settlement."));
                else {
                    rows.add(new Row(g.kingdom(), definitions.available(g.profileId()) ? g.state().name() : "DORMANT", g.profileId()));
                    rows.add(new Row(g.capital().toString(), "Capital seat", kingdoms.getSettlement(g.capital()).map(SettlementView::displayName).orElse("Unavailable")));
                    rows.add(new Row("", "Succession preview", g.successor() == null ? "No named successor; vacant leadership remains in interregnum" : successionPreview(g)));
                    House house = s.houses.get(kingdom); if (house != null) rows.add(new Row("", house.name(), house.motto()));
                }
                rows.add(new Row("", "Provider status", "Chronicles ingestion and commission completion receipts unsupported; mechanical commissions unavailable."));
            }
            case "council" -> {
                if (g != null) g.offices().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> rows.add(new Row(e.getKey(), e.getValue().terms().title(),
                        personLabel(e.getValue().holder()) + (e.getValue().settlement() == null ? "" : " at " + e.getValue().settlement()))));
                if (g != null && permitted(viewer, g, Permission.DELEGATE)) g.mandates().forEach((id, permissions) -> rows.add(new Row(id.toString(), "Mandate", permissions.toString())));
            }
            case "agreements" -> s.agreements.values().stream().filter(a -> a.involves(kingdom))
                    .filter(a -> a.signatures().size() == 2 || permitted(viewer, s.governments.get(a.proposer()), Permission.PROPOSE)
                            || permitted(viewer, s.governments.get(a.proposer()), Permission.RATIFY) || permitted(viewer, s.governments.get(a.recipient()), Permission.PROPOSE)
                            || permitted(viewer, s.governments.get(a.recipient()), Permission.RATIFY))
                    .sorted(Comparator.comparing(a -> a.id().toString())).forEach(a -> rows.add(new Row(a.id().toString(), a.terms().title(),
                            (now() >= a.expiresAt() && (a.state() == AgreementState.PROPOSED || a.state() == AgreementState.ACTIVE) ? "EXPIRED" : a.state().name())
                                    + " " + a.proposer() + " ↔ " + a.recipient() + "; " + a.explanation()
                                    + "; signatures=" + a.signatures().keySet() + "; deadline=" + a.expiresAt() + " game ticks"
                                    + (definitions.available(a.definitionId()) ? "" : "; definition unavailable")
                                    + (a.terms().clauses().isEmpty() ? "; ceremonial terms; no operational clauses" : "; clauses=" + a.terms().clauses().stream().sorted().map(c -> c + ": " + (active(a) ? clause(a.proposer(), a.recipient(), c).status() : AgreementClauseView.Status.NOT_AGREED)).collect(Collectors.joining(", ")))
                                    + (a.terms().commissionPool().isEmpty() ? "" : "; opportunity=" + a.terms().commissionPool()), a.termsHash())));
            case "petitions" -> s.petitions.values().stream().filter(p -> p.kingdom().equals(kingdom))
                    .filter(p -> p.requester().equals(viewer.getUUID()) || permitted(viewer, g, Permission.REVIEW))
                    .sorted(Comparator.comparing(p -> p.id().toString())).forEach(p -> rows.add(new Row(p.id().toString(), p.definitionId(),
                            (open(p) && now() >= p.expiresAt() ? "EXPIRED" : p.state().name()) + "; " + p.explanation() + "; " + p.decision())));
            case "institutions" -> s.recognitions.values().stream().filter(r -> r.kingdom().equals(kingdom))
                    .filter(r -> kingdoms.getSettlement(r.building().settlement()).map(settlement ->
                            com.ultimakingdoms.knowledge.SettlementKnowledge.get(server).visible(viewer, settlement.id())).orElse(false))
                    .forEach(r -> rows.add(new Row(r.id().toString(), r.terms().title(),
                    r.building().type() + " at " + r.building().x() + "," + r.building().y() + "," + r.building().z() + "; " + (r.active() ? "Recognized; current building eligibility requires revalidation" : "Suspended"))));
            case "honors" -> s.honors.values().stream().filter(h -> h.kingdom().equals(kingdom)).forEach(h -> rows.add(new Row(h.id().toString(), h.terms().title(),
                    h.recipient().id() + "; " + (h.active() ? "Awarded" : "Revoked") + "; " + h.evidence())));
            case "history" -> visibleHistory(viewer).filter(fact -> fact.affectedKingdoms().contains(kingdom))
                    .forEach(fact -> rows.add(new Row(fact.id().toString(), fact.summary(),
                            "Recorded on day " + (fact.gameTime() / 24000 + 1) + ". "
                                    + (fact.actor() == null ? "Government record." : "By " + server.getProfileCache().get(fact.actor()).map(com.mojang.authlib.GameProfile::getName).orElse("a recorded participant") + ".")
                                    + " Status: " + fact.correction().name().toLowerCase(java.util.Locale.ROOT) + ".")));
            default -> throw new IllegalArgumentException("Unknown political tab");
        }
        String diagnostic = conflict ? "MCA: Capitals conflict: native governance disabled" : data.diagnostic();
        int start = Math.min(offset, rows.size());
        return new Page(requestId, revision(), kingdom, tab, offset, rows.size() > start + 20,
                rows.subList(start, Math.min(start + 20, rows.size())), definitions.snapshot().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).limit(128).map(e -> new Choice(e.getKey(), e.getValue().kind(), e.getValue().title())).toList(),
                actionDenials(viewer, g), diagnostic);
    }
    private String successionPreview(Government government) {
        String label = personLabel(government.successor());
        try { person(government.successor(), government.kingdom(), null); return label + "; currently eligible; confirmed vacancy required"; }
        catch (IllegalArgumentException unavailable) { return label + "; pending: " + unavailable.getMessage(); }
    }
    private String personLabel(Person person) {
        if (person.kind() == Kind.PLAYER) {
            var player = server.getPlayerList().getPlayer(person.id());
            if (player != null) return player.getName().getString();
        } else for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(person.id());
            if (entity != null) return entity.getName().getString() + " (" + person.id() + ")";
        }
        return person.kind() + " " + person.id();
    }
    private Map<Action, String> actionDenials(ServerPlayer viewer, Government g) {
        Map<Action, String> reasons = new EnumMap<>(Action.class);
        for (Action action : Action.values()) {
            String reason = "";
            if (!data.writable()) reason = data.diagnostic();
            else if (conflict) reason = "Native governance disabled while MCA: Capitals is installed";
            else if (action == Action.BOOTSTRAP) reason = g != null ? "Government already constituted" : viewer.hasPermissions(2) ? "" : "Founding requires operator permission level 2";
            else if (g == null) reason = "Government is unorganized";
            else if (!definitions.available(g.profileId())) reason = "Constitution unavailable; government dormant";
            else if (action == Action.ABDICATE) reason = g.offices().containsKey(LEADER) && g.offices().get(LEADER).holder().equals(new Person(viewer.getUUID(), Kind.PLAYER)) ? "" : "Only the leader may abdicate";
            else if (action == Action.SUCCEED) reason = g.state() == State.INTERREGNUM && g.successor() != null && (viewer.hasPermissions(2) || g.successor().equals(new Person(viewer.getUUID(), Kind.PLAYER))) ? "" : "No eligible named succession to confirm";
            else if (action != Action.PETITION && action != Action.WITHDRAW) {
                Permission permission = switch (action) {
                    case SEAT -> Permission.SEAT; case APPOINT, REMOVE_OFFICE, NAME_SUCCESSOR, HOUSE -> Permission.APPOINT;
                    case DELEGATE, REVOKE -> Permission.DELEGATE; case PROPOSE -> Permission.PROPOSE;
                    case SIGN, DECLINE, TERMINATE -> Permission.RATIFY; case REVIEW, APPROVE, REJECT -> Permission.REVIEW;
                    case RECOGNIZE, REVALIDATE, SUSPEND_RECOGNITION -> Permission.RECOGNIZE; case HONOR, REVOKE_HONOR -> Permission.HONOR;
                    case ADOPT_TRANSITION_RULE, OPEN_ELECTION, CLOSE_ELECTION, APPOINT_REGENT, END_REGENCY -> Permission.APPOINT;
                    case CAST_BALLOT -> Permission.APPOINT;
                    default -> throw new IllegalStateException("Unmapped political action");
                };
                boolean scopedRecognition = permission == Permission.RECOGNIZE && g.state() == State.ACTIVE && g.offices().values().stream()
                        .anyMatch(o -> o.settlement() != null && o.holder().equals(new Person(viewer.getUUID(), Kind.PLAYER))
                                && o.terms().permissions().contains(Permission.RECOGNIZE) && definitions.available(o.definitionId()));
                if (!permitted(viewer, g, permission) && !scopedRecognition && !(action == Action.APPOINT && g.state() == State.INTERREGNUM && viewer.hasPermissions(2)))
                    reason = "Requires a " + permission.name().toLowerCase(Locale.ROOT) + " mandate";
            }
            reasons.put(action, reason);
        }
        return reasons;
    }
    @SubscribeEvent public void preflight(SettlementMutationPreflightEvent event) {
        if (event.server() != server) return;
        if (!data.writable()) { event.reject("Political data is read-only; settlement references cannot be validated"); return; }
        UUID source = event.source().id();
        for (Government g : data.records().governments.values()) {
            boolean seat = kingdoms.getSettlement(g.capital()).map(v -> v.id().equals(source)).orElse(false);
            if (seat && !event.destinationKingdom().toString().equals(g.kingdom())) {
                event.reject("Designate a replacement capital before changing this settlement's kingdom"); return;
            }
        }
        if (event.mergeTarget().isPresent()) {
            UUID target = event.mergeTarget().get().id();
            for (Government g : data.records().governments.values()) {
                Set<String> sourceOffices = new HashSet<>();
                Set<String> targetOffices = new HashSet<>();
                for (Office office : g.offices().values()) if (office.settlement() != null) {
                    UUID scope = kingdoms.getSettlement(office.settlement()).orElseThrow().id();
                    if (scope.equals(source)) sourceOffices.add(office.definitionId());
                    if (scope.equals(target)) targetOffices.add(office.definitionId());
                }
                if (!Collections.disjoint(sourceOffices, targetOffices)) {
                    event.reject("Both settlements have holders of the same local office; remove one appointment before merging"); return;
                }
            }
            boolean sourceRecognition = data.records().recognitions.values().stream().anyMatch(r -> r.active() && kingdoms.getSettlement(r.building().settlement()).map(v -> v.id().equals(source)).orElse(false));
            boolean targetRecognition = data.records().recognitions.values().stream().anyMatch(r -> r.active() && kingdoms.getSettlement(r.building().settlement()).map(v -> v.id().equals(target)).orElse(false));
            if (sourceRecognition && targetRecognition) event.reject("Both settlements have active institution charters; suspend the source charters before merging");
        }
        if (!event.source().kingdomId().equals(event.destinationKingdom()) && data.records().recognitions.values().stream()
                .anyMatch(r -> r.active() && kingdoms.getSettlement(r.building().settlement()).map(v -> v.id().equals(source)).orElse(false)))
            event.reject("Settlement has active institution charters; suspend them before kingdom reassignment");
    }
    @SubscribeEvent public void merged(SettlementMergedEvent event) {
        if (event.server() != server || !data.writable()) return;
        PoliticalSavedData.Records s = data.transaction(); boolean changed = false;
        for (Government g : List.copyOf(s.governments.values())) if (g.capital().equals(event.source().id())) {
            s.governments.put(g.kingdom(), government(g, event.target().id(), g.offices(), g.mandates(), g.successor(), s.revision + 1)); changed = true;
        }
        if (changed) { s.revision++; data.commit(s); }
    }
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void deathObserved(LivingDeathEvent event) {
        if (event.getEntity().getServer() == server && pendingDeaths.size() < 8192
                && data.records().governments.values().stream().flatMap(g -> g.offices().values().stream())
                .anyMatch(o -> o.holder().kind() == Kind.NPC && o.holder().id().equals(event.getEntity().getUUID()))) pendingDeaths.addLast(event);
    }
    @SubscribeEvent public void tick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.getServer() != server || event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (server.getTickCount() % 20 == 0) maintainDeadlines();
        for (int i = 0; i < 32 && !pendingDeaths.isEmpty(); i++) {
            LivingDeathEvent death = pendingDeaths.removeFirst();
            if (!death.isCanceled() && death.getEntity().isDeadOrDying() && !confirmedDeath(death)) {
                pendingDeaths.addFirst(death); break;
            }
        }
    }
    private void maintainDeadlines() {
        if (!data.writable() || conflict) return;
        var current = data.records();
        List<UUID> ids = new ArrayList<>(current.agreements.keySet()); ids.addAll(current.petitions.keySet());
        PoliticalSavedData.Records next = null;
        List<Notice> notices = new ArrayList<>();
        for (int count = 0; count < Math.min(8, ids.size()); count++) {
            UUID id = ids.get(Math.floorMod(maintenanceCursor++, ids.size()));
            Agreement a = current.agreements.get(id);
            if (a != null && now() >= a.expiresAt() && (a.state() == AgreementState.PROPOSED || a.state() == AgreementState.ACTIVE)) {
                if (next == null) { next = data.transaction(); next.revision++; }
                next.agreements.put(id, new Agreement(a.id(), a.proposer(), a.recipient(), a.definitionId(), a.terms(), a.explanation(), a.termsHash(),
                        a.signatures(), AgreementState.EXPIRED, a.proposedAt(), a.expiresAt(), next.revision));
                UUID source = stable("agreement-expired", id, a.expiresAt());
                Notice notice = new Notice(source, a.proposer(), Action.PROPOSE, new UUID(0, 0), id.toString(), now());
                append(next, notice, new PoliticalFact(source, source, next.revision, now(), a.proposer(), PoliticalFact.Type.AGREEMENT_EXPIRED,
                        null, id.toString(), Set.of(a.proposer(), a.recipient()), new LinkedHashSet<>(a.signatures().values()),
                        a.state() == AgreementState.ACTIVE ? PoliticalFact.Visibility.PUBLIC : PoliticalFact.Visibility.PARTIES,
                        PoliticalFact.Correction.CURRENT, null, summary(PoliticalFact.Type.AGREEMENT_EXPIRED)));
                notices.add(notice);
            }
            Petition p = current.petitions.get(id);
            if (p != null && open(p) && now() >= p.expiresAt()) {
                if (next == null) { next = data.transaction(); next.revision++; }
                next.petitions.put(id, decision(p, PetitionState.EXPIRED, "Submission deadline elapsed", null, next.revision));
                UUID source = stable("petition-expired", id, p.expiresAt());
                Notice notice = new Notice(source, p.kingdom(), Action.PETITION, new UUID(0, 0), id.toString(), now());
                Set<UUID> affected = new LinkedHashSet<>(); affected.add(p.requester());
                if (p.nominee() != null) affected.add(p.nominee().id());
                append(next, notice, new PoliticalFact(source, source, next.revision, now(), p.kingdom(), PoliticalFact.Type.PETITION_EXPIRED,
                        null, id.toString(), Set.of(p.kingdom()), affected, PoliticalFact.Visibility.PRIVATE,
                        PoliticalFact.Correction.CURRENT, null, summary(PoliticalFact.Type.PETITION_EXPIRED)));
                notices.add(notice);
            }
        }
        List<UUID> electionIds=new ArrayList<>(current.elections.keySet());
        for(int count=0;count<Math.min(8,electionIds.size());count++){
            UUID id=electionIds.get(Math.floorMod(maintenanceCursor++,electionIds.size()));Election election=current.elections.get(id);ElectionState phase=null;
            if(election.state()==ElectionState.OPEN&&now()>=election.deadline())phase=ElectionState.GRACE;
            else if(election.state()==ElectionState.GRACE&&now()>=election.graceUntil())phase=ElectionState.EXPIRED;
            if(phase!=null){if(next==null){next=data.transaction();next.revision++;}
                next.elections.put(id,new Election(election.id(),election.kingdom(),election.candidates(),election.electorate(),election.ballots(),phase,election.openedAt(),election.deadline(),election.graceUntil(),next.revision,null));
                UUID source=stable("election-"+phase.name().toLowerCase(Locale.ROOT),id,phase==ElectionState.GRACE?election.deadline():election.graceUntil());
                Notice notice=new Notice(source,election.kingdom(),Action.CLOSE_ELECTION,new UUID(0,0),id.toString(),now());
                PoliticalFact.Type type=phase==ElectionState.EXPIRED?PoliticalFact.Type.ELECTION_EXPIRED:PoliticalFact.Type.ELECTION_GRACE;
                append(next,notice,new PoliticalFact(source,source,next.revision,now(),election.kingdom(),type,null,id.toString(),Set.of(election.kingdom()),election.candidates(),PoliticalFact.Visibility.PUBLIC,PoliticalFact.Correction.CURRENT,null,phase==ElectionState.EXPIRED?"Election expired without a unique authorized result":"Election entered neutral grace"));notices.add(notice);}
        }
        List<Regency> regencies=new ArrayList<>(current.regencies.values());for(int count=0;count<Math.min(8,regencies.size());count++){Regency regency=regencies.get(Math.floorMod(maintenanceCursor++,regencies.size()));if(regency.active()&&now()>=regency.expiresAt()){
            if(next==null){next=data.transaction();next.revision++;}next.regencies.put(regency.kingdom(),new Regency(regency.id(),regency.kingdom(),regency.regent(),regency.permissions(),regency.preservedSuccessor(),regency.appointedAt(),regency.expiresAt(),false,next.revision));
            UUID source=stable("regency-expired",regency.id(),regency.expiresAt());Notice notice=new Notice(source,regency.kingdom(),Action.END_REGENCY,new UUID(0,0),regency.id().toString(),now());
            append(next,notice,new PoliticalFact(source,source,next.revision,now(),regency.kingdom(),PoliticalFact.Type.REGENCY_EXPIRED,null,regency.id().toString(),Set.of(regency.kingdom()),Set.of(regency.regent().id()),PoliticalFact.Visibility.PUBLIC,PoliticalFact.Correction.CURRENT,null,"Regency authority expired; named successor preserved"));notices.add(notice);
        }}
        if (next != null && data.commitDurably(server, next)) notices.forEach(this::publish);
    }
    private boolean confirmedDeath(LivingDeathEvent event) {
        // Player respawn is not political death. Unload, absence and restart are not death signals.
        if (event.getEntity() instanceof net.minecraft.world.entity.player.Player || event.getEntity().getServer() != server || !data.writable() || conflict) return true;
        UUID id = event.getEntity().getUUID(); PoliticalSavedData.Records s = data.transaction(); boolean changed = false;
        List<Notice> notices = new ArrayList<>();
        for (Government g : List.copyOf(s.governments.values())) {
            Map<String, Office> offices = new LinkedHashMap<>(g.offices());
            boolean removed = offices.values().removeIf(o -> o.holder().kind() == Kind.NPC && o.holder().id().equals(id));
            if (removed) {
                if (!changed) s.revision++;
                s.governments.put(g.kingdom(), government(g, g.capital(), offices, offices.containsKey(LEADER) ? g.mandates() : Map.of(), g.successor(), s.revision));
                UUID source = stable("npc-office-vacated", id, now(), g.kingdom());
                Notice notice = new Notice(source, g.kingdom(), Action.REMOVE_OFFICE, id, id.toString(), now());
                append(s, notice, new PoliticalFact(source, source, s.revision, now(), g.kingdom(), PoliticalFact.Type.NPC_OFFICE_VACATED,
                        id, id.toString(), Set.of(g.kingdom()), Set.of(), PoliticalFact.Visibility.PUBLIC,
                        PoliticalFact.Correction.CURRENT, null, summary(PoliticalFact.Type.NPC_OFFICE_VACATED)));
                notices.add(notice); changed = true;
            }
        }
        if (!changed) return true;
        if (!data.commitDurably(server, s)) return false;
        notices.forEach(this::publish); return true;
    }

    private static UUID stable(String kind, UUID record, long sequence, String... scope) {
        return UUID.nameUUIDFromBytes((kind + "|" + record + "|" + sequence + "|" + String.join("|", scope)).getBytes(StandardCharsets.UTF_8));
    }

    private void publish(Notice notice) {
        try { net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new PoliticalCommittedEvent(server, notice)); }
        catch (RuntimeException listenerFailure) {
            com.mojang.logging.LogUtils.getLogger().error("Political state committed but a post-commit listener failed; no provider delivery is assumed", listenerFailure);
        }
    }
}
