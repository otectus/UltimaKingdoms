package com.ultimakingdoms.interaction;

public final class InteractionTasks {
    private static boolean initialized;
    public static synchronized void init(){if(initialized)return;initialized=true;BasicTargets.init();CoreTasks.init();PoliticalTasks.init();CivicTasks.init();StandingTasks.init();WarfareTasks.init();EvolutionTasks.init();ActionRegistry.targets("scope",c->{var values=new java.util.ArrayList<>(c.choices("scenario"));values.addAll(c.choices("obligation"));return values;});}
    private InteractionTasks(){}
}
