package com.ultimakingdoms.interaction;

import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.compat.recruits.RecruitsMobilization;
import com.ultimakingdoms.compat.recruits.RecruitsTransfer;
import com.ultimakingdoms.evolution.*;
import com.ultimakingdoms.evolution.drama.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;


import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

import static com.ultimakingdoms.interaction.ActionRegistry.*;

/** Server-owned GUI adapters for the evolving-world services. */
public final class EvolutionTasks {
    private static EvolutionService evolution(Context c){return EvolutionRuntime.get(c.server());}
    private static ProtectionService protection(Context c){return new ProtectionService(c.server());}
    private static RecruitTransferService transfers(Context c){return new RecruitTransferService(c.server());}
    private static DramaService drama(Context c){return DramaRuntime.get(c.server());}
    private static OrganizationService organizations(Context c){return OrganizationApi.get(c.server());}

    public static void init(){
        targets("scenario",EvolutionTasks::scenarioChoices);
        targets("pact",EvolutionTasks::pactChoices);
        targets("obligation",EvolutionTasks::obligationChoices);
        targets("transfer",EvolutionTasks::transferChoices);
        targets("reconciliation_transfer",EvolutionTasks::reconciliationChoices);
        targets("drama",EvolutionTasks::dramaChoices);
        targets("merge",EvolutionTasks::mergeChoices);
        targets("merge_obligation",EvolutionTasks::mergeObligationChoices);
        targets("native_recruit",EvolutionTasks::recruitChoices);
        targets("native_group",EvolutionTasks::groupChoices);
        targets("own_native_group",EvolutionTasks::ownGroupChoices);
        targets("shared_transfer_destination",EvolutionTasks::ownedShareChoices);
        targets("organization_template",EvolutionTasks::templateChoices);
        targets("organization_lifecycle",EvolutionTasks::lifecycleChoices);

        evolutionTasks(); protectionTasks(); transferTasks(); dramaTasks(); organizationTasks();
    }

    private static void evolutionTasks(){
        task("evolution.overview","Evolution","World evolution status","Read opt-in policy, regions, digest state and visible opportunities.",false,false,List.of(),
                c->Long.toString(evolution(c).revision()),EvolutionTasks::evolutionOverview);
        task("evolution.inspect","Evolution","Inspect opportunity","Read frozen evidence, outcomes and your contribution.",false,false,List.of(pick("scenario","Opportunity","scenario")),
                EvolutionTasks::scenarioVersion,c->scenarioLines(c,selectedScenario(c)));
        task("evolution.family","Evolution","Request family introduction","Open a private opportunity from a verified reciprocal MCA marriage and civic residence.",false,true,List.of(),
                c->Long.toString(evolution(c).revision()),c->evolution(c).familyIntroduction(c.player));
        task("evolution.digest","Evolution","Change political digest","Subscribe to or leave the bounded personal digest.",false,true,List.of(toggle("enabled","Receive digest")),
                c->Long.toString(evolution(c).revision()),c->evolution(c).subscribe(c.player,c.bool("enabled")));
        task("evolution.contribute","Evolution","Contribute to opportunity","Record one receipt-backed or authority-backed choice.",false,true,
                List.of(pick("scenario","Opportunity","scenario"),pick("outcome","Contribution",EvolutionTasks::contributionChoices)),EvolutionTasks::scenarioVersion,
                c->evolution(c).contribute(c.player,c.uuid("scenario"),c.revision("scenario"),EvolutionState.Outcome.valueOf(c.text("outcome"))));
        task("evolution.withdraw","Evolution","Withdraw contribution","Withdraw your current choice; its evidence receipt remains consumed.",false,true,List.of(pick("scenario","Opportunity","scenario")),
                EvolutionTasks::scenarioVersion,c->evolution(c).withdraw(c.player,c.uuid("scenario"),c.revision("scenario")));
        task("evolution.resolve","Evolution","Resolve opportunity","Apply one supported outcome after enough verified contributions.",false,true,
                List.of(pick("scenario","Opportunity","scenario"),pick("outcome","Outcome",EvolutionTasks::outcomeChoices),pick("kingdom","Counterpart government",EvolutionTasks::counterpartChoices)),EvolutionTasks::scenarioVersion,
                c->evolution(c).resolve(c.player,c.uuid("scenario"),c.revision("scenario"),EvolutionState.Outcome.valueOf(c.text("outcome")),c.text("kingdom")));
        task("evolution.retry","Evolution","Retry pending resolution","Resume the same durable owner request after a provider interruption.",false,true,List.of(pick("scenario","Opportunity","scenario")),
                EvolutionTasks::scenarioVersion,c->evolution(c).retry(c.player,c.uuid("scenario")));
        task("evolution.release","Evolution","Release rejected resolution","Reopen an uncommitted rejected owner request for a fresh choice.",false,true,List.of(pick("scenario","Opportunity","scenario")),
                EvolutionTasks::scenarioVersion,c->evolution(c).releaseRejected(c.player,c.uuid("scenario")));
        task("evolution.contract","Evolution","Open opportunity service","Offer the matching scoped MCA civilian contract from a nearby contact.",false,true,
                List.of(pick("scenario","Opportunity","scenario"),pick("outcome","Service",EvolutionTasks::contractChoices),pick("npc","Institutional contact","npc")),EvolutionTasks::scenarioVersion,
                c->EvolutionContracts.offer(c.player,c.uuid("scenario"),c.uuid("npc"),EvolutionContracts.kind(EvolutionState.Outcome.valueOf(c.text("outcome")))));
        task("evolution.configure","Evolution","Configure world evolution","Enable or pause generation and separately allow dramatic scenarios.",true,true,
                List.of(toggle("enabled","Enable evolution"),toggle("drama","Enable dramatic scenarios")),c->Long.toString(evolution(c).revision()),
                c->evolution(c).configure(c.player,c.bool("enabled"),c.bool("drama")));
        task("evolution.region","Evolution","Configure evolution region","Make a known settlement eligible or pause it.",true,true,
                List.of(pick("settlement","Settlement","settlement"),toggle("enabled","Eligible region")),c->Long.toString(evolution(c).revision()),
                c->evolution(c).region(c.player,c.uuid("settlement"),c.bool("enabled")));
    }

    private static void protectionTasks(){
        task("protection.overview","Protection","Browse protection pacts","Read only pacts your public status or current authority permits.",false,false,List.of(),
                c->Long.toString(protection(c).revision()),EvolutionTasks::protectionOverview);
        task("protection.inspect","Protection","Inspect protection pact","Read frozen duties and visible beneficiary obligations.",false,false,List.of(pick("pact","Pact","pact")),EvolutionTasks::pactVersion,
                c->pactLines(c,selectedPact(c)));
        task("protection.propose","Protection","Propose protection pact","Propose bounded voluntary duties between two active governments.",false,true,
                List.of(pick("protector","Protector government","kingdom"),pick("subordinate","Subordinate government","kingdom"),pick("settlement","Beneficiary settlement","settlement"),
                        multi("duties","Duties",c->enums(ProtectionState.Duty.values())),number("duration","Duration in game ticks",72000),number("notice","Exit notice in game ticks",12000),text("terms","Plain-language terms")),
                c->Long.toString(protection(c).revision()),c->protection(c).propose(c.player,c.text("protector"),c.text("subordinate"),c.uuid("settlement"),duties(c),c.number("duration"),c.number("notice"),c.text("terms")));
        task("protection.sign","Protection","Sign protection pact","Ratify frozen terms for one of the two governments.",false,true,
                List.of(pick("pact","Pact","pact"),pick("kingdom","Signing government",EvolutionTasks::pactKingdomChoices)),EvolutionTasks::pactVersion,
                c->protection(c).sign(c.player,c.uuid("pact"),c.text("kingdom"),c.revision("pact")));
        task("protection.exit","Protection","Give peaceful exit notice","Start the pact's frozen notice period without confiscating assets or troops.",false,true,
                List.of(pick("pact","Pact","pact"),pick("kingdom","Exiting government",EvolutionTasks::pactKingdomChoices)),EvolutionTasks::pactVersion,
                c->protection(c).exit(c.player,c.uuid("pact"),c.text("kingdom"),c.revision("pact")));
        task("protection.request","Protection","Request pact duty","Open one enumerated voluntary obligation.",false,true,
                List.of(pick("pact","Pact","pact"),pick("duty","Duty",EvolutionTasks::pactDutyChoices)),EvolutionTasks::pactVersion,
                c->protection(c).request(c.player,c.uuid("pact"),ProtectionState.Duty.valueOf(c.text("duty")),c.revision("pact")));
        task("protection.fulfill","Protection","Fulfill pact obligation","Use your matching completed scoped native contract receipt.",false,true,List.of(pick("obligation","Obligation","obligation")),EvolutionTasks::obligationVersion,
                c->protection(c).fulfill(c.player,c.uuid("obligation"),c.revision("obligation")));
        task("protection.refuse","Protection","Refuse pact obligation","Record an explicit reason without declaring war or transferring property.",false,true,
                List.of(pick("obligation","Obligation","obligation"),text("reason","Reason")),EvolutionTasks::obligationVersion,
                c->protection(c).refuse(c.player,c.uuid("obligation"),c.revision("obligation"),c.text("reason")));
        task("protection.contract","Protection","Open obligation service","Offer the obligation's matching scoped MCA civilian contract.",false,true,
                List.of(pick("obligation","Obligation","obligation"),pick("npc","Institutional contact","npc")),EvolutionTasks::obligationVersion,
                c->{var o=selectedObligation(c);return EvolutionContracts.offer(c.player,o.id(),c.uuid("npc"),EvolutionContracts.kind(o.duty()));});
    }

    private static void transferTasks(){
        task("transfer.overview","Transfers","Browse recruit transfers","Read your bilateral proposals and retained recovery state.",false,false,List.of(),EvolutionTasks::transferVersion,EvolutionTasks::transferOverview);
        task("transfer.inspect","Transfers","Inspect recruit transfer","Read the private parties, notice, phase and recovery detail.",false,false,List.of(pick("transfer","Transfer","transfer")),EvolutionTasks::selectedTransferVersion,c->transferLines(c,selectedTransfer(c)));
        task("transfer.propose","Transfers","Propose recruit transfer","Offer one nearby owned native recruit to an online player and their active native group.",false,true,
                List.of(pick("unit","Recruit","native_recruit"),pick("recipient","Recipient","player"),pick("group","Recipient group","native_group"),toggle("family","Family-backed introduction")),EvolutionTasks::transferProposalVersion,
                c->transfers(c).propose(c.player,c.uuid("unit"),c.uuid("recipient"),c.uuid("group"),c.bool("family")));
        task("transfer.share_destination","Transfers","Share a recruit transfer destination","Allow one named online sender to select one of your native groups for this connection only.",false,true,
                List.of(pick("sender","Allowed sender",c->c.choices("player").stream().filter(v->!v.value().equals(c.player.getUUID().toString())).toList()),pick("group","Your destination group","own_native_group")),c->RecruitTransferDestinations.revision(c.server())+":"+c.text("sender")+":"+c.text("group"),
                c->RecruitTransferDestinations.share(c.player,c.uuid("sender"),c.uuid("group"),c.choice("group").label()));
        task("transfer.revoke_destination","Transfers","Revoke a shared transfer destination","Remove a sender's connection-scoped permission to see and select your destination group.",false,true,
                List.of(pick("destination","Shared destination","shared_transfer_destination")),c->RecruitTransferDestinations.revision(c.server())+":"+c.text("destination"),
                c->RecruitTransferDestinations.revoke(c.player,UUID.fromString(c.meta("destination","sender")),UUID.fromString(c.meta("destination","group"))));
        transferAction("consent","Consent to recruit transfer","Recipient accepts the reviewed terms.",(s,c)->s.consent(c.player,c.uuid("transfer"),c.revision("transfer")));
        transferAction("cancel","Cancel recruit transfer","Either party cancels before native application.",(s,c)->s.cancel(c.player,c.uuid("transfer"),c.revision("transfer")));
        transferAction("apply","Apply recruit transfer","Source owner applies after consent and the notice period.",(s,c)->s.apply(c.player,c.uuid("transfer"),c.revision("transfer")));
        transferAction("confirm","Confirm native transfer","Retry native persistence acknowledgment for retained intent.",(s,c)->s.confirm(c.player,c.uuid("transfer")));
        transferAction("restore","Restore original ownership","Guardedly restore the saved original native state with both parties online.",(s,c)->s.restore(c.player,c.uuid("transfer")));
        task("transfer.reconciliation_preview","Transfers","Review native reconciliation","Inspect the current native fingerprint and observation without writing accounting.",true,false,
                List.of(pick("transfer","Pending transfer",EvolutionTasks::reconciliationChoices)),EvolutionTasks::reconciliationVersion,c->reconciliationLines(transfers(c).reconciliationReview(c.player,c.uuid("transfer"))));
        task("transfer.reconcile","Transfers","Acknowledge native reconciliation","Record the reviewed native state and operator reason without rewriting provider counts.",true,true,
                List.of(pick("transfer","Reviewed transfer",EvolutionTasks::reconciliationChoices),text("reason","Operator reconciliation note")),EvolutionTasks::reconciliationVersion,
                c->transfers(c).reconcile(c.player,c.uuid("transfer"),c.revision("transfer"),c.meta("transfer","fingerprint"),c.text("reason")));
    }

    private static void dramaTasks(){
        task("drama.overview","Drama","Browse authored drama","Read participant dramas and incoming proposals you are currently eligible to consent to.",false,false,List.of(),c->Long.toString(drama(c).revision()),EvolutionTasks::dramaOverview);
        task("drama.inspect","Drama","Inspect authored drama","Preview frozen objective, terms, parties and remaining online time.",false,false,List.of(pick("drama","Drama","drama")),EvolutionTasks::dramaVersion,c->dramaLines(c,selectedDrama(c)));
        task("drama.propose","Drama","Propose authored drama","Create a bounded proposal from an installed template and real authority evidence.",false,true,
                List.of(pick("template","Authored template",c->drama(c).templates().stream().map(t->new Choice(t.id(),t.title(),words(t.kind().name())+" · "+t.objective())).toList()),
                        pick("settlement","Involved settlement","settlement"),pick("subject","Source organization",EvolutionTasks::dramaSubjectChoices)),c->Long.toString(drama(c).revision()),
                c->drama(c).propose(c.player,UUID.randomUUID(),drama(c).revision(),c.text("template"),c.uuid("settlement"),c.text("subject")));
        dramaAction("consent","Consent to authored drama","Accept as the eligible opposing commander or independent active source member.",(s,c)->s.consent(c.player,c.uuid("drama"),c.revision("drama")));
        dramaAction("execute","Execute authored outcome","Proposer begins the one authority-checked native or organization operation.",(s,c)->s.execute(c.player,c.uuid("drama"),c.revision("drama")));
        dramaAction("negotiate","Negotiate authored drama","Propose the installed nonviolent accord outcome.",(s,c)->s.negotiate(c.player,c.uuid("drama"),c.revision("drama")));
        dramaAction("exit","Exit authored drama","Choose the frozen nonviolent exit and preserve civic identity and assets.",(s,c)->s.exit(c.player,c.uuid("drama"),c.revision("drama")));
        dramaAction("recover","Recover authored operation","Retry or withdraw a retained provider operation using its durable intent.",(s,c)->s.recover(c.player,c.uuid("drama"),c.revision("drama")));
    }

    private static void organizationTasks(){
        task("organization.lifecycle_overview","Organizations","Browse organization lifecycles","Read dynamic charters, redirects, tombstones and proposals visible to you.",false,false,List.of(),
                c->Long.toString(organizations(c).revision()),EvolutionTasks::organizationOverview);
        task("organization.lifecycle_inspect","Organizations","Inspect organization lifecycle","Read a dynamic charter's stable identity, sponsor, state and redirect.",false,false,
                List.of(pick("organization","Organization","organization_lifecycle")),EvolutionTasks::organizationVersion,c->lifecycleLines(organizations(c).lifecycle(c.id("organization")).orElseThrow()));
        task("organization.found","Organizations","Found chartered organization","Create a stable organization id from its readable name using an installed charter template.",false,true,
                List.of(pick("template","Charter template","organization_template"),pick("kingdom","Sponsor government","kingdom"),text("name","Organization name")),c->Long.toString(organizations(c).revision()),
                c->{var id=slug(c.text("name"));return organizationResult(organizations(c).found(c.player,id,c.id("template"),c.text("kingdom"),c.text("name"),organizations(c).revision()));});
        task("organization.merge_propose","Organizations","Propose organization merge","Ask a second active dynamic organization to merge while preserving standing and member choice.",false,true,
                List.of(pick("source","Source organization",EvolutionTasks::activeLifecycleChoices),pick("target","Target organization",EvolutionTasks::activeLifecycleChoices)),c->Long.toString(organizations(c).revision()),
                c->organizationResult(organizations(c).proposeMerge(c.player,c.id("source"),c.id("target"),organizations(c).revision())));
        organizationMergeAction("merge_consent","Consent to organization merge","Target charter authority accepts the proposal.",(s,c)->s.consentMerge(c.player,c.uuid("merge"),s.revision()));
        organizationMergeAction("merge_opt_out","Opt out of organization merge","An active source member preserves their independent membership choice.",(s,c)->s.optOutMerge(c.player,c.uuid("merge"),s.revision()));
        organizationMergeAction("merge_finalize","Finalize organization merge","Apply both consents after every accepted commission is completed, canceled, or novated.",(s,c)->s.finalizeMerge(c.player,c.uuid("merge"),s.revision()));
        task("organization.merge_novate","Organizations","Novate merge obligation","Explicitly carry one accepted source commission to the target charter.",false,true,
                List.of(pick("merge","Merge proposal","merge"),pick("obligation","Accepted commission","merge_obligation")),EvolutionTasks::mergeVersion,
                c->organizationResult(organizations(c).novateMergeObligation(c.player,c.uuid("merge"),c.uuid("obligation"),organizations(c).revision())));
        task("organization.dissolve","Organizations","Dissolve organization","Freeze benefits and retain a historical tombstone after obligations are cleared.",false,true,
                List.of(pick("organization","Organization",EvolutionTasks::activeLifecycleChoices)),EvolutionTasks::organizationVersion,
                c->organizationResult(organizations(c).dissolve(c.player,c.id("organization"),organizations(c).revision())));
    }

    private static void task(String id,String category,String title,String help,boolean operator,boolean consequential,List<Field> fields,java.util.function.Function<Context,String> version,Handler handler){
        add(new Task(id,category,title,help,operator,consequential,fields,version,handler));
    }
    private interface TransferAction{String run(RecruitTransferService service,Context context);}
    private static void transferAction(String id,String title,String help,TransferAction action){task("transfer."+id,"Transfers",title,help,false,true,List.of(pick("transfer","Transfer","transfer")),EvolutionTasks::selectedTransferVersion,c->action.run(transfers(c),c));}
    private interface DramaAction{String run(DramaService service,Context context);}
    private static void dramaAction(String id,String title,String help,DramaAction action){task("drama."+id,"Drama",title,help,false,true,List.of(pick("drama","Drama","drama")),EvolutionTasks::dramaVersion,c->action.run(drama(c),c));}
    private interface MergeAction{OrganizationLifecycleResult run(OrganizationService service,Context context);}
    private static void organizationMergeAction(String id,String title,String help,MergeAction action){task("organization."+id,"Organizations",title,help,false,true,List.of(pick("merge","Merge proposal","merge")),EvolutionTasks::mergeVersion,c->organizationResult(action.run(organizations(c),c)));}

    private static List<Choice> scenarioChoices(Context c){return evolution(c).scenarios(c.player).stream().map(v->choice(v.id(),v.title(),words(v.phase().name())+" · "+BasicTargets.settlement(c,v.settlement()),v.revision())).toList();}
    private static List<Choice> pactChoices(Context c){return protection(c).pacts(c.player).stream().map(v->choice(v.id(),v.protector()+" → "+v.subordinate(),words(v.phase().name())+" · "+v.terms(),v.revision())).toList();}
    private static List<Choice> obligationChoices(Context c){return protection(c).obligations(c.player).stream().map(v->choice(v.id(),words(v.duty().name()),words(v.status().name())+" · due "+v.deadline(),v.revision())).toList();}
    private static List<Choice> transferChoices(Context c){return transfers(c).transfers(c.player).stream().map(v->new Choice(v.id().toString(),recruitName(c,v.unit())+" → "+BasicTargets.player(c,v.recipient()),
            "Source "+BasicTargets.player(c,v.owner())+" · destination "+nativeGroupName(c,v.recipient(),v.recipientGroup())+" · "+words(v.phase().name())+" · notice "+v.noticeUntil()+" · deadline "+v.deadline()+" · frozen equipment "+v.equipment(),
            Map.of("revision",Long.toString(v.revision()),"equipment",v.equipment()))).toList();}
    private static List<Choice> dramaChoices(Context c){return drama(c).dramas(c.player).stream().map(v->choice(v.id(),v.title(),(v.canConsent()?"Awaiting your consent · ":"")+v.objective(),v.revision())).toList();}
    private static List<Choice> mergeChoices(Context c){return organizations(c).merges(c.player).stream().map(v->choice(v.id(),organizationName(c,v.source())+" → "+organizationName(c,v.target()),words(v.state().name()),v.revision())).toList();}
    private static List<Choice> mergeObligationChoices(Context c){if(c.text("merge").isBlank())return List.of();return organizations(c).mergeObligations(c.player,c.uuid("merge")).stream()
            .map(o->new Choice(o.id().toString(),"Commission "+o.quest(),"Accepted by "+BasicTargets.player(c,o.player()))).toList();}
    private static List<Choice> templateChoices(Context c){return organizations(c).foundingTemplates().stream().map(v->new Choice(v.id().toString(),PlayerWords.text(v.nameKey()),PlayerWords.text(v.descriptionKey()))).toList();}
    private static List<Choice> lifecycleChoices(Context c){return organizations(c).lifecycles(c.player).stream().map(v->choice(v.id(),v.displayName(),words(v.state().name())+" · sponsor "+v.sponsorKingdom(),v.revision())).toList();}
    private static List<Choice> activeLifecycleChoices(Context c){return organizations(c).lifecycles(c.player).stream().filter(v->v.state()==OrganizationLifecycleView.State.ACTIVE).map(v->choice(v.id(),v.displayName(),"Active · sponsor "+v.sponsorKingdom(),v.revision())).toList();}
    private static List<Choice> recruitChoices(Context c){return RecruitsMobilization.nearbyOwned(c.player,32).stream().map(e->{var o=RecruitsMobilization.snapshot(e);return new Choice(e.getUUID().toString(),e.getDisplayName().getString(),"Nearby owned recruit",Map.of("group",o.group().toString(),"equipment",RecruitsTransfer.equipment(e)));}).toList();}
    private static List<Choice> groupChoices(Context c){if(c.text("recipient").isBlank())return List.of();UUID recipient=c.uuid("recipient");var shared=RecruitTransferDestinations.forSender(c.player,recipient);if(shared.isEmpty())return List.of();
        var active=nativeGroups(c,recipient).stream().collect(Collectors.toMap(Choice::value,v->v));return shared.stream().map(v->active.get(v.group().toString())).filter(Objects::nonNull)
                .map(v->new Choice(v.value(),v.label(),"Shared with you by the recipient",Map.of("share_revision",Long.toString(RecruitTransferDestinations.revision(c.server()))))).toList();}
    private static List<Choice> ownGroupChoices(Context c){return nativeGroups(c,c.player.getUUID());}
    private static List<Choice> ownedShareChoices(Context c){return RecruitTransferDestinations.owned(c.player).stream().map(v->new Choice(v.sender()+"|"+v.group(),v.name(),"Shared with "+BasicTargets.player(c,v.sender()),Map.of("sender",v.sender().toString(),"group",v.group().toString(),"share_revision",Long.toString(v.revision())))).toList();}
    private static List<Choice> reconciliationChoices(Context c){if(!c.player.hasPermissions(2))return List.of();var service=transfers(c);var out=new ArrayList<Choice>();
        for(var v:service.operatorTransfers(c.player))try{var r=service.reconciliationReview(c.player,v.id());out.add(new Choice(v.id().toString(),"Recruit transfer to "+BasicTargets.player(c,v.recipient()),r.observation(),Map.of("revision",Long.toString(r.revision()),"fingerprint",r.fingerprint())));}catch(IllegalArgumentException ignored){}return List.copyOf(out);}

    private static List<Choice> contributionChoices(Context c){return selectedScenario(c).outcomes().stream().filter(o->o!=EvolutionState.Outcome.DECLINE).map(o->new Choice(o.name(),words(o.name()))).toList();}
    private static List<Choice> outcomeChoices(Context c){return selectedScenario(c).outcomes().stream().map(o->new Choice(o.name(),words(o.name()))).toList();}
    private static List<Choice> contractChoices(Context c){return selectedScenario(c).outcomes().stream().filter(o->Set.of(EvolutionState.Outcome.AID,EvolutionState.Outcome.MEDIATE,EvolutionState.Outcome.NEGOTIATE).contains(o)).map(o->new Choice(o.name(),words(o.name()))).toList();}
    private static List<Choice> counterpartChoices(Context c){return EvolutionState.Outcome.INTRODUCE.name().equals(c.text("outcome"))?c.choices("kingdom"):List.of(new Choice("","No counterpart","This outcome does not address another government."));}
    private static List<Choice> dramaSubjectChoices(Context c){return drama(c).templates().stream().filter(t->t.id().equals(c.text("template"))).findFirst().filter(t->t.kind()==DramaState.Kind.SCHISM).isPresent()
            ?c.choices("organization"):List.of(new Choice("","No source organization","This template uses native faction evidence."));}
    private static List<Choice> pactKingdomChoices(Context c){var p=selectedPact(c);return List.of(new Choice(p.protector(),words(p.protector())),new Choice(p.subordinate(),words(p.subordinate())));}
    private static List<Choice> pactDutyChoices(Context c){return selectedPact(c).duties().stream().map(d->new Choice(d.name(),words(d.name()))).toList();}

    private static EvolutionService.ScenarioView selectedScenario(Context c){return evolution(c).scenario(c.player,c.uuid("scenario")).orElseThrow(()->new IllegalArgumentException("Opportunity unavailable."));}
    private static ProtectionService.PactView selectedPact(Context c){return protection(c).pacts(c.player).stream().filter(v->v.id().equals(c.uuid("pact"))).findFirst().orElseThrow(()->new IllegalArgumentException("Pact unavailable."));}
    private static ProtectionService.ObligationView selectedObligation(Context c){return protection(c).obligations(c.player).stream().filter(v->v.id().equals(c.uuid("obligation"))).findFirst().orElseThrow(()->new IllegalArgumentException("Obligation unavailable."));}
    private static RecruitTransferService.TransferView selectedTransfer(Context c){return transfers(c).transfers(c.player).stream().filter(v->v.id().equals(c.uuid("transfer"))).findFirst().orElseThrow(()->new IllegalArgumentException("Transfer unavailable."));}
    private static DramaService.DramaView selectedDrama(Context c){return drama(c).dramas(c.player).stream().filter(v->v.id().equals(c.uuid("drama"))).findFirst().orElseThrow(()->new IllegalArgumentException("Drama unavailable."));}

    private static String scenarioVersion(Context c){return Long.toString(selectedScenario(c).revision());}
    private static String pactVersion(Context c){return Long.toString(selectedPact(c).revision());}
    private static String obligationVersion(Context c){return Long.toString(selectedObligation(c).revision());}
    private static String transferVersion(Context c){return transfers(c).version(c.player);}
    private static String selectedTransferVersion(Context c){return Long.toString(selectedTransfer(c).revision());}
    private static String transferProposalVersion(Context c){return RecruitTransferDestinations.revision(c.server())+":"+c.text("unit")+":"+c.meta("unit","group")+":"+c.meta("unit","equipment")+":"+c.text("recipient")+":"+c.text("group")+":"+c.text("family");}
    private static String reconciliationVersion(Context c){return c.meta("transfer","revision")+":"+c.meta("transfer","fingerprint");}
    private static String dramaVersion(Context c){return Long.toString(selectedDrama(c).revision());}
    private static String mergeVersion(Context c){return organizations(c).revision()+":"+organizations(c).merge(c.uuid("merge")).orElseThrow().revision();}
    private static String organizationVersion(Context c){return organizations(c).revision()+":"+organizations(c).lifecycle(c.id("organization")).orElseThrow().revision();}

    private static List<String> evolutionOverview(Context c){var settings=evolution(c).settings(c.player);var scenarios=evolution(c).scenarios(c.player);var lines=new ArrayList<String>();
        lines.add("World evolution is "+(settings.enabled()?"enabled":"paused")+"; dramatic opportunities are "+(settings.drama()?"enabled":"disabled")+".");
        lines.add("Your political digest is "+(settings.subscribed()?"enabled":"disabled")+". Eligible regions: "+settings.eligibleRegions().size()+"; currently active regions: "+settings.activeRegions().size()+".");
        if(!settings.writable())lines.add("Saved evolution data is in read-only recovery; changes are refused until an operator repairs it.");
        if(scenarios.isEmpty())lines.add("You have no visible opportunities. Visit a known eligible settlement while prerequisites are available.");
        else scenarios.forEach(v->lines.add(v.title()+" · "+words(v.phase().name())+" · "+BasicTargets.settlement(c,v.settlement())+" · "+nextScenario(v)));
        return List.copyOf(lines);}
    private static List<String> scenarioLines(Context c,EvolutionService.ScenarioView v){var lines=new ArrayList<String>();lines.add(v.title()+" · "+words(v.phase().name()));
        lines.add("Settlement: "+BasicTargets.settlement(c,v.settlement())+". Government: "+words(v.kingdom())+". Deadline: game tick "+v.deadline()+".");
        lines.add("Cause: "+v.cause());lines.add("Choices: "+v.outcomes().stream().map(Enum::name).map(ActionRegistry::words).toList()+". Required verified contributions: "+v.requiredContributions()+".");
        v.contributions().forEach((outcome,count)->lines.add(words(outcome.name())+": "+count+" verified contribution"+(count==1?"":"s")));
        v.ownChoice().ifPresent(choice->lines.add("Your current choice: "+words(choice.name())+". You may withdraw it while the opportunity remains open."));
        lines.add("Next: "+nextScenario(v));if(!v.result().isBlank())lines.add("Recorded outcome: "+v.result());return List.copyOf(lines);}
    private static String nextScenario(EvolutionService.ScenarioView v){return switch(v.phase()){case OPEN->"contribute, withdraw your own choice, or let an authorized reviewer resolve a supported outcome";case RESOLVING->"the original resolver may retry or release an uncommitted rejected request";default->"the opportunity is closed and retained as history";};}

    private static List<String> protectionOverview(Context c){var pacts=protection(c).pacts(c.player);var obligations=protection(c).obligations(c.player);var lines=new ArrayList<String>();
        lines.add("Visible protection pacts: "+pacts.size()+". Visible beneficiary obligations: "+obligations.size()+".");
        if(pacts.isEmpty())lines.add("No public active pact or private proposal is available to your current authority.");
        else pacts.forEach(v->lines.add(v.protector()+" → "+v.subordinate()+" · "+words(v.phase().name())+" · duties "+v.duties().stream().map(Enum::name).map(ActionRegistry::words).toList()));return List.copyOf(lines);}
    private static List<String> pactLines(Context c,ProtectionService.PactView v){var lines=new ArrayList<String>();lines.add(v.protector()+" → "+v.subordinate()+" · "+words(v.phase().name()));
        lines.add(v.terms());lines.add("Duties: "+v.duties().stream().map(Enum::name).map(ActionRegistry::words).toList()+". Expires at game tick "+v.expires()+"; exit notice "+v.noticeTicks()+" ticks.");
        if(v.settlementVisible())lines.add("Beneficiary: "+BasicTargets.settlement(c,v.beneficiary())+".");else lines.add("The beneficiary remains undisclosed until you know the settlement.");
        var obligations=protection(c).obligations(c.player).stream().filter(o->o.pact().equals(v.id())).toList();if(obligations.isEmpty())lines.add("No visible obligations are recorded.");
        else obligations.forEach(o->lines.add(words(o.duty().name())+" · "+words(o.status().name())+" · deadline "+o.deadline()+" · "+o.explanation()));return List.copyOf(lines);}

    private static List<String> transferOverview(Context c){var values=transfers(c).transfers(c.player);var lines=new ArrayList<String>();lines.add("Your private recruit transfers: "+values.size()+".");
        if(values.isEmpty())lines.add("No proposal names you as source owner or recipient.");else values.forEach(v->lines.add(recruitName(c,v.unit())+" · "+BasicTargets.player(c,v.owner())+" → "+BasicTargets.player(c,v.recipient())+" · "+nativeGroupName(c,v.recipient(),v.recipientGroup())+" · "+words(v.phase().name())+" · "+transferNext(v)));return List.copyOf(lines);}
    private static List<String> transferLines(Context c,RecruitTransferService.TransferView v){return List.of("Recruit transfer · "+words(v.phase().name()),
            "Recruit: "+recruitName(c,v.unit())+". Source owner: "+BasicTargets.player(c,v.owner())+". Recipient: "+BasicTargets.player(c,v.recipient())+".",
            "Destination group: "+nativeGroupName(c,v.recipient(),v.recipientGroup())+". Family-backed introduction: "+(v.family()?"yes":"no")+".",
            "Notice completes at game tick "+v.noticeUntil()+"; deadline "+v.deadline()+". Frozen equipment fingerprint: "+v.equipment()+".",v.detail(),"Next: "+transferNext(v)+(v.accountingDiverged()?" Native accounting changed separately; an operator must review reconciliation.":""));}
    private static String transferNext(RecruitTransferService.TransferView v){return switch(v.phase()){case OFFERED->"the recipient may consent, or either party may cancel";case CONSENTED->"the source owner may apply after notice while both players and the recruit are present";case APPLYING,CONFIRMING->"either party may confirm retained native persistence";case RESTORING->"either party may restore with both players present, then confirm";case COMPLETE->"the negotiated transfer is complete";case CANCELLED->"native ownership remains or was restored to the source";case RECONCILED->"operator review is durably recorded; divergence evidence remains";};}
    private static List<String> reconciliationLines(RecruitTransferService.ReconciliationReview review){return List.of("Pending native intent revision "+review.revision()+".",review.observation(),"The current fingerprint is captured with this reviewed choice. Applying reconciliation records an operator note; it does not rewrite native counts.");}

    private static List<String> dramaOverview(Context c){var values=drama(c).dramas(c.player);var lines=new ArrayList<String>();lines.add("Authored dramas available to you: "+values.size()+".");
        if(values.isEmpty())lines.add("No drama names you as a participant or currently eligible independent consenter.");else values.forEach(v->lines.add(v.title()+" · "+words(v.phase().name())+" · "+BasicTargets.settlement(c,v.settlement())+(v.canConsent()?" · awaiting your consent":"")));return List.copyOf(lines);}
    private static List<String> dramaLines(Context c,DramaService.DramaView v){var lines=new ArrayList<String>();lines.add(v.title()+" · "+words(v.kind().name())+" · "+words(v.phase().name()));
        lines.add("Settlement: "+BasicTargets.settlement(c,v.settlement())+". Remaining online time: "+v.remainingTicks()+" ticks.");lines.add("Objective: "+v.objective());
        lines.add("Authorized outcomes: "+v.outcomes().stream().map(Enum::name).map(ActionRegistry::words).toList()+".");if(!v.subject().isBlank())lines.add("Recorded subject: "+organizationNameText(c,v.subject())+".");
        lines.add(v.canConsent()?"Next: review the frozen objective and consent as the eligible independent participant.":v.participant()?"Next: use only an outcome authorized by these frozen terms.":"Read-only preview.");if(!v.result().isBlank())lines.add("Status: "+v.result());return List.copyOf(lines);}

    private static List<String> organizationOverview(Context c){var lifecycles=organizations(c).lifecycles(c.player);var merges=organizations(c).merges(c.player);var lines=new ArrayList<String>();lines.add("Dynamic organization records: "+lifecycles.size()+". Merge proposals visible to you: "+merges.size()+".");
        if(lifecycles.isEmpty())lines.add("No dynamic charter, historical redirect, or dissolution tombstone exists.");else lifecycles.forEach(v->lines.add(v.displayName()+" · "+words(v.state().name())+" · sponsor "+v.sponsorKingdom()));
        merges.forEach(v->lines.add("Merge "+organizationName(c,v.source())+" → "+organizationName(c,v.target())+" · "+words(v.state().name())+" · "+v.memberOptOuts().size()+" member opt-out(s)"));return List.copyOf(lines);}
    private static List<String> lifecycleLines(OrganizationLifecycleView v){var lines=new ArrayList<String>();lines.add(v.displayName()+" · "+words(v.state().name()));lines.add("Stable id: "+v.id()+". Sponsor government: "+v.sponsorKingdom()+". Charter template: "+v.template()+".");
        if(v.redirect().isPresent())lines.add("Historical references resolve to "+v.redirect().get()+"; the original id remains a tombstone.");else if(v.state()==OrganizationLifecycleView.State.ACTIVE)lines.add("Benefits remain active. Merger or dissolution requires current authority and settled accepted commissions.");else lines.add("Benefits are frozen; history and standing evidence remain retained.");return List.copyOf(lines);}
    private static String organizationNameText(Context c,String value){var id=ResourceLocation.tryParse(value);return id==null?value:organizationName(c,id);}
    private static String recruitName(Context c,UUID id){for(var level:c.server().getAllLevels()){var entity=level.getEntity(id);if(entity!=null)return entity.getDisplayName().getString();}return "Recruit (unloaded)";}
    private static String nativeGroupName(Context c,UUID owner,UUID id){try{Class<?> events=Class.forName("com.talhanation.recruits.RecruitEvents",false,EvolutionTasks.class.getClassLoader());Object manager=events.getField("recruitsGroupsManager").get(null);
        Object group=manager.getClass().getMethod("getGroup",UUID.class).invoke(manager,id);if(group==null||!owner.equals(group.getClass().getMethod("getPlayerUUID").invoke(group))||(boolean)group.getClass().getMethod("isDisabled").invoke(group))return "Destination group unavailable";
        return String.valueOf(group.getClass().getMethod("getName").invoke(group));}catch(ReflectiveOperationException|LinkageError failure){return "Destination group unavailable";}}

    private static Choice choice(Object value,String label,String detail,long revision){return new Choice(value.toString(),label,detail,Map.of("revision",Long.toString(revision)));}
    private static Set<ProtectionState.Duty> duties(Context c){try{return Arrays.stream(c.text("duties").split(",")).filter(s->!s.isBlank()).map(s->ProtectionState.Duty.valueOf(s.toUpperCase(Locale.ROOT))).collect(Collectors.toCollection(()->EnumSet.noneOf(ProtectionState.Duty.class)));}
        catch(IllegalArgumentException failure){throw new IllegalArgumentException("Choose one or more listed duties.");}}
    private static String organizationName(Context c,ResourceLocation id){return organizations(c).lifecycle(id).map(OrganizationLifecycleView::displayName).orElse(id.toString());}
    private static String organizationResult(OrganizationLifecycleResult result){return words(result.status().name())+": "+result.reason()+" (revision "+result.revision()+")"+(result.obligations().isEmpty()?"":"; "+result.obligations().size()+" accepted commission(s) still require completion, cancellation, or confirmed novation");}
    private static ResourceLocation slug(String name){String path=Normalizer.normalize(name,Normalizer.Form.NFKD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");
        if(path.isBlank())throw new IllegalArgumentException("Organization name must contain letters or numbers.");if(path.length()>64)path=path.substring(0,64).replaceAll("_+$","");return new ResourceLocation("ultima_kingdoms",path);}

    private static List<Choice> nativeGroups(Context c,UUID owner){try{
        var recipient=c.server().getPlayerList().getPlayer(owner);if(recipient==null)throw new IllegalArgumentException("Recipient must remain online.");
        Class<?> events=Class.forName("com.talhanation.recruits.RecruitEvents",false,EvolutionTasks.class.getClassLoader());Object manager=events.getField("recruitsGroupsManager").get(null);
        Object groups=manager.getClass().getMethod("getPlayerGroups",net.minecraft.world.entity.player.Player.class).invoke(manager,recipient);
        if(!(groups instanceof Iterable<?> iterable))return List.of();var out=new ArrayList<Choice>();for(Object group:iterable){
            if((boolean)group.getClass().getMethod("isDisabled").invoke(group))continue;UUID id=(UUID)group.getClass().getMethod("getUUID").invoke(group);String name=String.valueOf(group.getClass().getMethod("getName").invoke(group));
            out.add(new Choice(id.toString(),name,"Active native group"));}return List.copyOf(out);
        }catch(ReflectiveOperationException|LinkageError failure){throw new IllegalArgumentException("Native recruit groups are unavailable.",failure);}}
    private EvolutionTasks(){}
}
