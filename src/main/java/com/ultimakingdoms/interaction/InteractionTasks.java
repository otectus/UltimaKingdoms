package com.ultimakingdoms.interaction;

public final class InteractionTasks {
    private static boolean initialized;
    public static synchronized void init(){if(initialized)return;initialized=true;
        hints();BasicTargets.init();CoreTasks.init();PoliticalTasks.init();CivicTasks.init();StandingTasks.init();WarfareTasks.init();EvolutionTasks.init();ActionRegistry.targets("scope",c->{var values=new java.util.ArrayList<>(c.choices("scenario"));values.addAll(c.choices("obligation"));return values;});}
    private InteractionTasks(){}
    /** Plain explanations shown when a selector has nothing to offer. */
    private static void hints(){
        ActionRegistry.targetHint("settlement",c->{var knowledge=com.ultimakingdoms.knowledge.SettlementKnowledge.get(c.server());return knowledge.writable()?"No settlement is known to you yet. Walk into a village or town so its record is discovered, or ask an operator to register one.":knowledge.diagnostic()+". Settlements stay hidden until an operator repairs the discovery file.";});
        ActionRegistry.targetHint("kingdom","No kingdoms are loaded. Kingdom definitions come from data packs.");
        ActionRegistry.targetHint("player","No other players are online right now.");
        ActionRegistry.targetHint("person","No one is nearby and visible. Stand within 32 blocks of the person with a clear line of sight.");
        ActionRegistry.targetHint("npc","No villager or recruit is nearby and visible. Stand within 32 blocks with a clear line of sight.");
        ActionRegistry.targetHint("organization","No guild or organization definitions are loaded from data packs.");
        ActionRegistry.targetHint("native_faction","No native factions are known. The Recruits integration must be installed and a faction team must exist.");
        ActionRegistry.targetHint("campaign","No campaigns are known to you. Declare one from the War Room of a settlement you can see.");
        ActionRegistry.targetHint("accord","No accords are known to you. Accords are proposed from a settlement's War Room.");
        ActionRegistry.targetHint("deployment","You have no native deployments. Muster a mobilization first.");
        ActionRegistry.targetHint("site","No world sites have been discovered by you yet. Explore, or ask an operator to map sites.");
        ActionRegistry.targetHint("route","No routes are authorized for you yet. Routes connect institutions you have discovered.");
        ActionRegistry.targetHint("election","No election is open in a kingdom where you may vote.");
        ActionRegistry.targetHint("building","Stand on, or look at, the building block you want to register.");
        ActionRegistry.targetHint("scenario","No evolving-world scenario is open for you. Scenarios arise from institutions, family ties and protection events.");
        ActionRegistry.targetHint("pact","No protectorate pacts involve your government.");
        ActionRegistry.targetHint("obligation","No protectorate obligations are open for you.");
        ActionRegistry.targetHint("transfer","No recruit transfers involve you. Transfers are proposed by the recruit's current owner.");
        ActionRegistry.targetHint("reconciliation_transfer","No transfers need reconciliation.");
        ActionRegistry.targetHint("drama","No drama proposals involve you.");
        ActionRegistry.targetHint("merge","No organization merges are pending for your organizations.");
        ActionRegistry.targetHint("merge_obligation","No merge obligations are open for you.");
        ActionRegistry.targetHint("native_recruit","No recruit you own is nearby and visible. Stand within 32 blocks of your recruit.");
        ActionRegistry.targetHint("native_group","No native recruit groups are known. Recruits must be installed and groups configured.");
        ActionRegistry.targetHint("own_native_group","You own no native recruit groups.");
        ActionRegistry.targetHint("shared_transfer_destination","You have shared no transfer destinations.");
        ActionRegistry.targetHint("organization_template","No organization templates are loaded from data packs.");
        ActionRegistry.targetHint("organization_lifecycle","No organization lifecycle action is available for you.");
        ActionRegistry.targetHint("chapter","No guild chapters are known to you. Discover the settlement that hosts the chapter first.");
        ActionRegistry.targetHint("contact","No dismissible contacts are recorded for you.");
        ActionRegistry.targetHint("own_membership","You hold no active guild memberships. Join a chapter first.");
        ActionRegistry.targetHint("guild_permission","This organization defines no services.");
        ActionRegistry.targetHint("standing_event","No pending standing events await review.");
        ActionRegistry.targetHint("mca_community","No native communities are visible. MCA must be installed and its villages loaded.");
        ActionRegistry.targetHint("withdrawable","No agreements or petitions of yours are open.");
        ActionRegistry.targetHint("political_record","No political records are open for your government.");
        ActionRegistry.targetHint("political_permission","No permissions are defined.");
        ActionRegistry.targetHint("scope","No scope is available.");
        ActionRegistry.targetHint("commodity","No items are registered.");
    }
}
