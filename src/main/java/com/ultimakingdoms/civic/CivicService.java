package com.ultimakingdoms.civic;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.api.civic.CivicContactContext;
import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.townstead.*;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Coordinates existing civic, institutional and social owners; owns only explicit guild appointments. */
public final class CivicService implements com.ultimakingdoms.api.civic.InstitutionalCommissionApi.Handler {
    public static final ResourceLocation WORKSHOP_QUEST=new ResourceLocation("ultima:civic/lamplighters/workshop_lanterns");
    public static final ResourceLocation INTRODUCTIONS=new ResourceLocation("ultima_kingdoms:route_introductions");
    public static final ResourceLocation COMMISSIONS=new ResourceLocation("ultima_kingdoms:commission_access");
    public record Result(boolean success,String reason,Optional<UUID> settlement) {
        static Result deny(String reason) { return new Result(false,reason,Optional.empty()); }
        static Result ok(String reason) { return new Result(true,reason,Optional.empty()); }
    }
    public record ChapterView(UUID id,String organization,UUID institution,UUID settlement,String settlementName,
                              InstitutionView.Status status,List<UUID> contacts) { public ChapterView { contacts=List.copyOf(contacts); } }
    private final MinecraftServer server;
    private final KingdomsService kingdoms;
    private final CivicSavedData data;
    private InstitutionalCommissionData contracts() { return InstitutionalCommissionData.get(server); }
    public int reservedHonorSlots(Set<UUID> awarded) { thread();return contracts().reservedHonors(awarded,server.overworld().getGameTime()); }
    public List<OrganizationObligation> organizationObligations(ResourceLocation organization) {
        thread();Objects.requireNonNull(organization);return contracts().obligations(organization.toString());
    }
    /** Internal lifecycle write; caller must establish bilateral authority before requesting novation. */
    public boolean novateOrganizationObligation(UUID obligation,ResourceLocation source,ResourceLocation target) {
        thread();Objects.requireNonNull(obligation);Objects.requireNonNull(source);Objects.requireNonNull(target);
        return contracts().novate(server,obligation,source.toString(),target.toString());
    }
    CivicService(MinecraftServer server,KingdomsService kingdoms) { this.server=server;this.kingdoms=kingdoms;this.data=CivicSavedData.get(server); }
    private void thread() { if(!server.isSameThread())throw new IllegalStateException("Civic API requires server thread"); }
    private void actor(ServerPlayer player) { thread();if(player.getServer()!=server)throw new IllegalArgumentException("Wrong server"); }
    private PoliticalService politics() { return UltimaPoliticsApi.get(server); }
    private OrganizationService organizations() { return OrganizationApi.get(server); }
    public long revision() { thread();return data.state().revision; }
    public String diagnostic() { thread();return data.writable()?(CivicConfig.ENABLED.get()?"available":"disabled"):data.diagnostic(); }

    public Result charter(ServerPlayer player,ResourceLocation organization,UUID institution) {
        actor(player); if(!data.writable())return Result.deny(data.diagnostic());
        if(organizations().definition(organization).isEmpty())return Result.deny("civic.organization_unavailable");
        var place=politics().institution(institution);
        if(place.isEmpty()||!place.get().operational())return Result.deny("civic.institution_unavailable");
        if(!player.hasPermissions(2)&&!politics().canRecognize(player,place.get().settlement()))return Result.deny("civic.recognition_authority_required");
        if(data.state().chapters.values().stream().anyMatch(c -> c.organization().equals(organization.toString())&&Objects.equals(c.institution(),institution)))
            return Result.deny("civic.chapter_exists");
        if(data.state().chapters.size()>=4096)return Result.deny("civic.capacity");
        var next=data.copy(); UUID id=UUID.randomUUID();
        next.chapters.put(id,new CivicSavedData.Chapter(id,organization.toString(),institution,player.getUUID(),++next.revision));
        return data.commit(server,next)?Result.ok("civic.chapter_created"):Result.deny("civic.save_failed");
    }
    public Result appoint(ServerPlayer player,UUID chapterId,Entity npc) {
        actor(player); if(!data.writable())return Result.deny(data.diagnostic());
        var chapter=data.state().chapters.get(chapterId); if(chapter==null)return Result.deny("civic.chapter_unavailable");
        var place=place(chapter);
        if(place.isEmpty()||!place.get().operational())return Result.deny("civic.institution_unavailable");
        if(!player.hasPermissions(2)&&!politics().canRecognize(player,place.get().settlement()))return Result.deny("civic.recognition_authority_required");
        if(!near(player,npc)||!adult(npc)||!resident(npc,place.get().settlement()))return Result.deny("civic.adult_resident_required");
        if(!data.state().contacts.containsKey(npc.getUUID())&&data.state().contacts.size()>=16384)return Result.deny("civic.capacity");
        // Reassignment requires the previous chapter's authority too, not merely control over a destination.
        var prior=data.state().contacts.get(npc.getUUID());
        if(prior!=null&&!prior.chapter().equals(chapterId)&&!player.hasPermissions(2)) {
            var priorChapter=data.state().chapters.get(prior.chapter());
            var priorPlace=priorChapter==null?Optional.<InstitutionView>empty():place(priorChapter);
            if(priorPlace.isEmpty()||!politics().canRecognize(player,priorPlace.get().settlement()))return Result.deny("civic.previous_authority_required");
        }
        var next=data.copy();next.dismissed.remove(npc.getUUID());next.contacts.put(npc.getUUID(),new CivicSavedData.Contact(npc.getUUID(),chapterId,player.getUUID(),++next.revision));
        return data.commit(server,next)?Result.ok("civic.contact_appointed"):Result.deny("civic.save_failed");
    }
    public Result dismiss(ServerPlayer player,UUID npc) {
        actor(player);if(!data.writable())return Result.deny(data.diagnostic());
        var contact=data.state().contacts.get(npc);if(contact==null)return Result.deny("civic.contact_unavailable");
        var chapter=data.state().chapters.get(contact.chapter());var place=chapter==null?Optional.<InstitutionView>empty():place(chapter);
        if(!player.hasPermissions(2)&&(place.isEmpty()||!politics().canRecognize(player,place.get().settlement())))return Result.deny("civic.recognition_authority_required");
        if(!data.state().dismissed.contains(npc)&&data.state().dismissed.size()>=16384)return Result.deny("civic.capacity");
        var next=data.copy();next.contacts.remove(npc);next.seeds.remove(npc);next.dismissed.add(npc);next.revision++;
        return data.commit(server,next)?Result.ok("civic.contact_dismissed"):Result.deny("civic.save_failed");
    }
    /** Called only by the durable quest receipt consumer, never by a packet or player command. */
    public boolean recordAuthoredContact(UUID npc,ResourceLocation organization,UUID settlement,UUID receipt,ResourceLocation quest) {
        thread();var rules=CivicDefinitions.INSTANCE.get(organization);
        if(rules.isEmpty()||!rules.get().sponsorQuests().contains(quest))return true;
        return recordCommittedContact(npc,organization,settlement,null,null,receipt);
    }
    /** Trusted replay of a previously committed authored-role decision; no live policy reinterpretation. */
    boolean recordCommittedContact(UUID npc,ResourceLocation organization,UUID settlement,String dimension,Integer village,UUID receipt) {
        thread();
        if(settlement==null&&(dimension==null||village==null))return true; // No authoritative home context: no invented appointment.
        if(!data.writable())return false;
        if(data.state().dismissed.contains(npc)||data.state().contacts.containsKey(npc)||data.state().seeds.containsKey(npc))return true;
        if(data.state().seeds.size()>=16384)return false;
        var next=data.copy();next.seeds.put(npc,new CivicSavedData.Seed(npc,organization.toString(),settlement,dimension,village,receipt,++next.revision));
        return data.commit(server,next);
    }
    private void activateSeed(Entity npc) {
        var seed=data.state().seeds.get(npc.getUUID());
        if(seed==null||!data.writable()||!CivicConfig.ENABLED.get()||data.state().contacts.containsKey(npc.getUUID())||!adult(npc))return;
        var rules=CivicDefinitions.INSTANCE.get(new ResourceLocation(seed.organization()));
        var home=seed.settlement()!=null?kingdoms.getSettlement(seed.settlement()):kingdoms.getSettlementForMcaVillage(new ResourceLocation(seed.dimension()),seed.village());
        if(rules.isEmpty()||home.isEmpty()||!resident(npc,home.get().id()))return;
        var town=UltimaTownsteadApi.get(server);
        var building=town.buildings((net.minecraft.server.level.ServerLevel)npc.level(),home.get().id()).stream()
                .filter(b->rules.get().buildingFamilies().contains(b.family()))
                .filter(b->((net.minecraft.server.level.ServerLevel)npc.level()).hasChunkAt(b.center()))
                .min(Comparator.comparingInt(TownsteadBuildingView::buildingId));
        if(building.isEmpty()||data.state().contacts.size()>=16384)return;
        var existing=data.state().chapters.values().stream().filter(c->c.organization().equals(seed.organization()))
                .filter(c->place(c).map(p->p.settlement().equals(home.get().id())&&p.operational()).orElse(false)).findFirst();
        var next=data.copy();CivicSavedData.Chapter chapter;
        if(existing.isPresent())chapter=existing.get();
        else {
            if(next.chapters.size()>=4096)return;
            var b=building.get();UUID id=UUID.randomUUID();
            chapter=new CivicSavedData.Chapter(id,seed.organization(),null,
                    new CivicSavedData.Site(home.get().id(),b.dimension().toString(),b.villageId(),b.buildingId(),b.family(),b.type(),b.centerX(),b.centerY(),b.centerZ()),
                    seed.npc(),++next.revision);next.chapters.put(id,chapter);
        }
        next.contacts.put(npc.getUUID(),new CivicSavedData.Contact(npc.getUUID(),chapter.id(),seed.npc(),++next.revision));
        data.commit(server,next);
    }
    private Optional<InstitutionView> place(CivicSavedData.Chapter chapter) {
        if(chapter.institution()!=null)return politics().institution(chapter.institution());
        var b=chapter.site();var home=kingdoms.getSettlement(b.settlement());if(home.isEmpty())return Optional.empty();
        var dimension=new ResourceLocation(b.dimension());var pos=new BlockPos(b.x(),b.y(),b.z());
        var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,dimension));
        var status=InstitutionView.Status.AVAILABLE;
        var rules=CivicDefinitions.INSTANCE.get(new ResourceLocation(chapter.organization()));
        if(rules.isEmpty()||!rules.get().buildingFamilies().contains(b.family()))status=InstitutionView.Status.DEFINITION_UNAVAILABLE;
        else if(level==null||!level.hasChunkAt(pos))status=InstitutionView.Status.UNLOADED;
        else {
            var town=UltimaTownsteadApi.get(server);
            if(!town.capabilities().contains(TownsteadCapability.READ_BUILDING))status=InstitutionView.Status.PROVIDER_UNAVAILABLE;
            else {
                var live=town.buildingAt(level,pos);
                if(live.isEmpty()||live.get().buildingId()!=b.buildingId()||live.get().villageId()!=b.villageId()
                        ||!live.get().family().equals(b.family())||!kingdoms.getSettlement(live.get().settlementId()).map(s->s.id().equals(home.get().id())).orElse(false))
                    status=InstitutionView.Status.BUILDING_CHANGED;
            }
        }
        return Optional.of(new InstitutionView(chapter.id(),home.get().kingdomId(),home.get().id(),dimension,pos,b.type(),
                "civic.chapter",status,data.state().revision));
    }
    private boolean near(ServerPlayer player,Entity npc) {
        int range=CivicConfig.CONTACT_RANGE.get();return npc!=null&&npc.isAlive()&&npc.level()==player.level()
                &&npc.distanceToSqr(player)<=range*range&&player.hasLineOfSight(npc);
    }
    private boolean adult(Entity npc) {
        if(!(npc instanceof LivingEntity)||!npc.isAlive())return false;
        var type=ForgeRegistries.ENTITY_TYPES.getKey(npc.getType());
        if(type==null||(!type.getNamespace().equals("mca")&&!type.equals(new ResourceLocation("minecraft:villager"))))return false;
        if(npc instanceof Villager villager)return !villager.isBaby();
        var town=UltimaTownsteadApi.get(server);var view=town.villager(npc);
        if(view.isEmpty())return false;
        return view.get().rootId().flatMap(town::origin).stream().flatMap(root->root.lifeStages().stream())
                .anyMatch(stage->stage.id().equalsIgnoreCase(view.get().lifeStage())&&Set.of("adult","senior").contains(stage.presentsAs().toLowerCase(Locale.ROOT)));
    }
    private boolean resident(Entity npc,UUID settlement) {
        return kingdoms.getResidence(npc).map(s->s.id().equals(settlement)).orElse(false);
    }
    public Optional<CivicContactContext> speakerContext(ServerPlayer player,Entity npc) {
        actor(player);if(!near(player,npc))return Optional.empty();
        activateSeed(npc);
        var contact=data.state().contacts.get(npc.getUUID());
        if(contact==null) {
            var seed=data.state().seeds.get(npc.getUUID());
            if(seed==null)return Optional.empty();
            var definition=organizations().definition(new ResourceLocation(seed.organization()));
            return definition.map(d->new CivicContactContext(npc.getUUID(),seed.organization(),d.nameKey(),"guild_contact",false,false,false,
                    List.of("civic.workshop_needed"),List.of("civic.workshop_needed"),data.state().revision,d.definitionRevision()));
        }
        var chapter=data.state().chapters.get(contact.chapter());if(chapter==null)return Optional.empty();
        var definition=organizations().definition(new ResourceLocation(chapter.organization()));
        if(definition.isEmpty())return Optional.empty();
        var place=place(chapter);
        boolean available=data.writable()&&CivicConfig.ENABLED.get()&&place.isPresent()&&place.get().operational()
                &&adult(npc)&&resident(npc,place.get().settlement());
        var intro=organizations().explainOwn(player,definition.get().id(),INTRODUCTIONS);
        var commission=organizations().explainOwn(player,definition.get().id(),COMMISSIONS);
        List<String> reasons=new ArrayList<>();if(!available)reasons.add("civic.institution_unavailable");
        reasons.addAll(intro.reasons());
        return Optional.of(new CivicContactContext(npc.getUUID(),chapter.organization(),definition.get().nameKey(),"guild_steward",
                available,intro.decision()==OrganizationExplanation.Decision.ALLOW,commission.decision()==OrganizationExplanation.Decision.ALLOW,
                reasons,commission.reasons(),data.state().revision,definition.get().definitionRevision()));
    }
    public Optional<Entity> nearbyContact(ServerPlayer player,ResourceLocation organization) {
        actor(player);int range=CivicConfig.CONTACT_RANGE.get();
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class,player.getBoundingBox().inflate(range),e->data.state().contacts.containsKey(e.getUUID())||data.state().seeds.containsKey(e.getUUID()))
                .stream().sorted(Comparator.comparingDouble(e->e.distanceToSqr(player))).limit(64)
                .filter(e->speakerContext(player,e).filter(c->c.organization().equals(organization.toString())).isPresent()).map(e->(Entity)e).findFirst();
    }
    public Result introduction(ServerPlayer player,Entity npc) {
        return introduction(player,npc,false);
    }
    /** Voluntary neutral route opened by two governments' explicit hospitality terms. */
    public Result hospitality(ServerPlayer player,Entity npc) {
        return introduction(player,npc,true);
    }
    private boolean hospitality(CivicSavedData.Chapter source, CivicSavedData.Chapter destination) {
        if(source.institution()==null||destination.institution()==null)return false;
        var from=place(source);var to=place(destination);
        return from.isPresent()&&from.get().operational()&&to.isPresent()
                &&(to.get().operational()||to.get().status()==InstitutionView.Status.UNLOADED)
                &&politics().clause(from.get().kingdom().toString(),
                to.get().kingdom().toString(),Politics.Clause.HOSPITALITY).operational();
    }
    private Result introduction(ServerPlayer player,Entity npc,boolean hospitality) {
        actor(player);var context=speakerContext(player,npc);
        if(hospitality&&!CivicConfig.INSTITUTIONAL_SERVICES.get())return Result.deny("civic.agreement_unavailable");
        if(context.isEmpty())return Result.deny("civic.contact_unavailable");
        if(!context.get().servicesAvailable())return Result.deny("civic.institution_unavailable");
        if(!hospitality&&!context.get().introductionQualified())return Result.deny("civic.introduction_not_qualified");
        var contact=data.state().contacts.get(npc.getUUID());var source=data.state().chapters.get(contact.chapter());
        var sourcePlace=place(source).orElseThrow();
        String key=player.getUUID()+"|"+source.organization();long now=server.overworld().getGameTime();
        var existing=data.state().introductions.get(key);
        if(existing!=null&&now-existing.createdAt()<CivicConfig.INTRODUCTION_COOLDOWN.get()) {
            var target=data.state().chapters.get(existing.target());
            if(!hospitality||target!=null&&hospitality(source,target))return reveal(player,existing);
            return Result.deny("civic.agreement_required");
        }
        int radius=CivicConfig.INTRODUCTION_RADIUS.get();
        Map<ResourceLocation,Boolean> hospitalityByKingdom=new HashMap<>();
        var destination=data.state().chapters.values().stream()
                .filter(c->c.organization().equals(source.organization())&&!c.id().equals(source.id()))
                .filter(c->!hospitality||source.institution()!=null&&c.institution()!=null)
                .map(c->Map.entry(c,place(c)))
                .filter(e->e.getValue().isPresent()).map(e->Map.entry(e.getKey(),e.getValue().get()))
                .filter(e->e.getValue().dimension().equals(sourcePlace.dimension())
                        &&(e.getValue().operational()||e.getValue().status()==InstitutionView.Status.UNLOADED)
                        &&!e.getValue().settlement().equals(sourcePlace.settlement())
                        &&e.getValue().position().distSqr(sourcePlace.position())<=((long)radius*radius)
                        &&!SettlementKnowledge.get(server).visible(player,e.getValue().settlement()))
                .filter(e->!hospitality||hospitalityByKingdom.computeIfAbsent(e.getValue().kingdom(),kingdom->
                        politics().clause(sourcePlace.kingdom().toString(),kingdom.toString(),Politics.Clause.HOSPITALITY).operational()))
                .sorted(Comparator.<Map.Entry<CivicSavedData.Chapter,InstitutionView>>comparingDouble(e->e.getValue().position().distSqr(sourcePlace.position()))
                        .thenComparing(e->e.getKey().id())).findFirst();
        if(destination.isEmpty())return Result.deny("civic.no_new_introduction");
        if(existing==null&&data.state().introductions.size()>=100000)return Result.deny("civic.capacity");
        var selected=destination.get();var receipt=new CivicSavedData.Introduction(player.getUUID(),source.organization(),source.id(),selected.getKey().id(),selected.getValue().settlement(),now);
        var next=data.copy();next.introductions.put(key,receipt);next.revision++;
        if(!data.commit(server,next))return Result.deny("civic.save_failed");
        return reveal(player,receipt);
    }
    public Result commissions(ServerPlayer player,Entity npc) {
        actor(player);var context=speakerContext(player,npc);
        if(context.isEmpty())return Result.deny("civic.contact_unavailable");
        if(!context.get().servicesAvailable())return Result.deny("civic.institution_unavailable");
        if(!context.get().commissionQualified())return Result.deny("civic.commission_not_qualified");
        var rules=CivicDefinitions.INSTANCE.get(new ResourceLocation(context.get().organization()));
        if(rules.isEmpty()||!com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge.openCommissions(player,npc,rules.get().commissions()))return Result.deny("civic.commissions_unavailable");
        return Result.ok("civic.commissions_opened");
    }
    private String buildingFingerprint(InstitutionView place) {
        var level=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,place.dimension()));
        if(level==null||!level.hasChunkAt(place.position()))return "";
        return UltimaTownsteadApi.get(server).buildingAt(level,place.position()).filter(b->b.family().equals("workshop"))
                .map(b->com.ultimakingdoms.politics.GovernmentService.hash(b.toString())).orElse("");
    }
    /** Fixed native emerald reward; no fee, debit, conversion or inventory mutation here. */
    public Result workshop(ServerPlayer player,Entity npc) {
        actor(player);
        if(!CivicConfig.ENABLED.get()||!CivicConfig.INSTITUTIONAL_SERVICES.get()||!contracts().writable())return Result.deny("civic.institution_unavailable");
        var context=speakerContext(player,npc);
        if(context.isEmpty()||!context.get().servicesAvailable())return Result.deny("civic.institution_unavailable");
        if(!context.get().commissionQualified())return Result.deny("civic.commission_not_qualified");
        var contact=data.state().contacts.get(npc.getUUID());var chapter=data.state().chapters.get(contact.chapter());
        if(!chapter.organization().equals("ultima_kingdoms:lamplighters"))return Result.deny("civic.commissions_unavailable");
        if(chapter.institution()==null)return Result.deny("civic.recognized_workshop_required");
        var place=place(chapter).orElseThrow();String building=buildingFingerprint(place);
        if(building.isEmpty())return Result.deny("civic.recognized_workshop_required");
        var legal=com.ultimakingdoms.compat.crime.InstitutionalCrimeBridge.workshop(player,npc);
        if(!legal.allowed())return Result.deny(legal.reason());
        if(!(politics() instanceof com.ultimakingdoms.politics.GovernmentService government)||!government.canPromiseCommissionHonor())
            return Result.deny("civic.honor_capacity");
        String honor="ultima_kingdoms:workshop_service";
        final Politics.Definition terms;
        try { terms=com.ultimakingdoms.UltimaKingdoms.POLITICS.get(honor,"honor"); }
        catch(IllegalArgumentException failure) { return Result.deny("civic.institution_unavailable"); }
        var contract=new InstitutionalCommissionData.Contract(UUID.randomUUID(),player.getUUID(),npc.getUUID(),place.id(),
                chapter.organization(),place.kingdom().toString(),WORKSHOP_QUEST.toString(),politics().revision(),place.revision(),
                building,legal.fingerprint(),server.overworld().getGameTime()+1200,null,honor,terms);
        if(!contracts().put(server,contract))return Result.deny("civic.save_failed");
        if(!com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge.openInstitutionalCommissions(player,npc,Set.of(WORKSHOP_QUEST),contract.id().toString()))
            return Result.deny("civic.commissions_unavailable");
        return Result.ok("civic.workshop_terms");
    }
    @Override public String validate(ServerPlayer player,Entity giver,ResourceLocation quest,String binding,boolean completing) {
        actor(player);
        if(com.ultimakingdoms.warfare.contracts.CivilianContractService.handles(binding))
            return com.ultimakingdoms.warfare.contracts.CivilianContractService.validate(player,giver,quest,binding,completing);
        if(!CivicConfig.ENABLED.get()||!CivicConfig.INSTITUTIONAL_SERVICES.get()||!contracts().writable()||!data.writable())return "civic.institution_unavailable";
        final InstitutionalCommissionData.Contract contract;
        try { contract=contracts().get(UUID.fromString(binding)).orElse(null); }
        catch(RuntimeException invalid) { return "civic.commission_binding_invalid"; }
        if(contract==null||!contract.player().equals(player.getUUID())||!contract.quest().equals(quest.toString())
                ||giver==null||!contract.giver().equals(giver.getUUID())||!near(player,giver))return "civic.commission_binding_invalid";
        if(completing&&contract.instance()==null)return "civic.commission_binding_invalid";
        if(!completing&&(contract.instance()!=null||server.overworld().getGameTime()>=contract.offerExpires()))return "civic.commission_offer_expired";
        var place=politics().institution(contract.institution());
        if(place.isEmpty()||!place.get().operational()||!resident(giver,place.get().settlement())||!adult(giver)
                ||!place.get().kingdom().toString().equals(contract.kingdom())||!contract.buildingFingerprint().equals(buildingFingerprint(place.get())))return "civic.institution_unavailable";
        var contact=data.state().contacts.get(giver.getUUID());
        var chapter=contact==null?null:data.state().chapters.get(contact.chapter());
        if(chapter==null||!Objects.equals(chapter.institution(),contract.institution())||!chapter.organization().equals(contract.organization()))return "civic.contact_unavailable";
        var legal=com.ultimakingdoms.compat.crime.InstitutionalCrimeBridge.workshop(player,giver);
        if(!legal.allowed())return legal.reason();
        if(!completing) {
            if(politics().revision()!=contract.politicsRevision()||place.get().revision()!=contract.recognitionRevision()
                    ||!contract.legalFingerprint().equals(legal.fingerprint()))return "civic.commission_terms_changed";
            var qualification=organizations().explainOwn(player,new ResourceLocation(contract.organization()),COMMISSIONS);
            if(qualification.decision()!=OrganizationExplanation.Decision.ALLOW)return "civic.commission_not_qualified";
        }
        // Accepted work survives guild departure; current legal/building refusal merely suspends it.
        if(!com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge.ensureInstitutionalRecipient(player))return "civic.commissions_unavailable";
        return "";
    }
    @Override public boolean accepted(ServerPlayer player,Entity giver,ResourceLocation quest,String binding,UUID instance) {
        actor(player);Objects.requireNonNull(instance);
        if(com.ultimakingdoms.warfare.contracts.CivilianContractService.handles(binding))
            return com.ultimakingdoms.warfare.contracts.CivilianContractService.accepted(player,giver,quest,binding,instance);
        if(!validate(player,giver,quest,binding,false).isEmpty())return false;
        var contract=contracts().get(UUID.fromString(binding)).orElseThrow();
        return contracts().put(server,contract.accepted(instance));
    }
    @Override public boolean cancelled(ServerPlayer player,ResourceLocation quest,String binding,UUID instance) {
        actor(player);
        if(com.ultimakingdoms.warfare.contracts.CivilianContractService.handles(binding))
            return com.ultimakingdoms.warfare.contracts.CivilianContractService.cancelled(player,quest,binding,instance);
        if(!contracts().writable()||instance==null||quest==null)return false;
        final UUID id;
        try { id=UUID.fromString(binding); } catch(RuntimeException invalid) { return false; }
        var contract=contracts().get(id).orElse(null);
        if(contract==null)return true; // Retry after owner commit and before native removal.
        if(!contract.player().equals(player.getUUID())||!contract.quest().equals(quest.toString())
                ||!instance.equals(contract.instance()))return false;
        return contracts().remove(server,id);
    }
    public record CommissionHonor(UUID contract,UUID player,UUID giver,UUID institution,String kingdom,String honor,Politics.Definition terms) { }
    /** Resolves only a durable owner receipt and the matching accepted native quest instance. */
    public Optional<CommissionHonor> commissionHonor(ServerPlayer player,UUID epoch,UUID receipt) {
        actor(player);if(!contracts().writable())return Optional.empty();
        var proof=com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge.committedInstitutionalEffect(server,epoch,receipt,player.getUUID());
        if(proof.isEmpty())return Optional.empty();
        try {
            return contracts().get(UUID.fromString(proof.get().binding())).filter(c->c.player().equals(player.getUUID())
                    &&c.giver().equals(proof.get().giver())&&Objects.equals(c.instance(),receipt)&&c.quest().equals(proof.get().quest()))
                    .map(c->new CommissionHonor(c.id(),c.player(),c.giver(),c.institution(),c.kingdom(),c.honor(),c.honorTerms()));
        } catch(IllegalArgumentException invalid) { return Optional.empty(); }
    }
    private Result reveal(ServerPlayer player,CivicSavedData.Introduction receipt) {
        var settlement=kingdoms.getSettlement(receipt.settlement());if(settlement.isEmpty())return Result.deny("civic.destination_unavailable");
        var knowledge=SettlementKnowledge.get(server);if(!knowledge.writable())return Result.deny("civic.discovery_unavailable");
        knowledge.discover(player.getUUID(),settlement.get().id());
        knowledge.save(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(SettlementKnowledge.NAME+".dat").toFile());
        if(knowledge.isDirty()||!knowledge.visible(player,settlement.get().id()))return Result.deny("civic.save_failed");
        return new Result(true,"civic.introduction_received",Optional.of(settlement.get().id()));
    }
    public record ContactView(UUID npc,String label,String detail,long revision) {}
    /** Saved contacts the viewer can actually dismiss, including operator recovery of orphaned places. */
    public List<ContactView> dismissibleContacts(ServerPlayer viewer,int offset,int limit) {
        actor(viewer);if(offset<0||offset>16384||limit<1||limit>64)throw new IllegalArgumentException("Invalid contact page");
        return data.state().contacts.values().stream().sorted(Comparator.comparing(CivicSavedData.Contact::npc)).filter(contact->{
            var chapter=data.state().chapters.get(contact.chapter());var institution=chapter==null?Optional.<InstitutionView>empty():place(chapter);
            return viewer.hasPermissions(2)||institution.filter(i->SettlementKnowledge.get(server).visible(viewer,i.settlement())&&politics().canRecognize(viewer,i.settlement())).isPresent();
        }).skip(offset).limit(limit).map(contact->{
            var chapter=data.state().chapters.get(contact.chapter());var institution=chapter==null?Optional.<InstitutionView>empty():place(chapter);
            String settlement=institution.flatMap(i->kingdoms.getSettlement(i.settlement())).map(SettlementView::displayName).orElse("Unavailable institution");
            Entity entity=viewer.serverLevel().getEntity(contact.npc());String name=entity==null?"Contact at "+settlement:entity.getDisplayName().getString();
            return new ContactView(contact.npc(),name,settlement+" · appointment "+contact.revision(),contact.revision());
        }).toList();
    }
    public List<ChapterView> knownChapters(ServerPlayer viewer,int offset) {
        actor(viewer);if(offset<0||offset>4096)throw new IllegalArgumentException("Invalid chapter page");
        return data.state().chapters.values().stream().sorted(Comparator.comparing(CivicSavedData.Chapter::id))
                .map(c->Map.entry(c,place(c))).filter(e->e.getValue().isPresent())
                .filter(e->SettlementKnowledge.get(server).visible(viewer,e.getValue().get().settlement()))
                .skip(offset).limit(16).map(e->{var p=e.getValue().get();var c=e.getKey();return new ChapterView(c.id(),c.organization(),p.id(),p.settlement(),
                        kingdoms.getSettlement(p.settlement()).map(SettlementView::displayName).orElse("Unavailable"),p.status(),
                        data.state().contacts.values().stream().filter(contact->contact.chapter().equals(c.id())).map(CivicSavedData.Contact::npc).limit(16).toList());}).toList();
    }
}
