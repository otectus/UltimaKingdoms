package com.ultimakingdoms.warfare.contracts;

import net.minecraft.resources.ResourceLocation;

/** Authored native quest contracts. Political consequences consume the durable proof separately. */
public enum CivilianContractKind {
    RELIEF("relief"),
    RECONNAISSANCE("reconnaissance"),
    MEDIATION("mediation"),
    AUTONOMY("autonomy"),
    DEFENSE("defense"),
    ESCORT("escort"),
    EVOLVING_AID("evolving_aid", "evolution/aid"),
    EVOLVING_MEDIATION("evolving_mediation", "evolution/mediation"),
    EVOLVING_AUTONOMY("evolving_autonomy", "evolution/autonomy"),
    EVOLVING_DEFENSE("evolving_defense", "evolution/defense");

    private final String id;
    private final ResourceLocation quest;

    CivilianContractKind(String id) {
        this(id, "warfare/" + id);
    }
    CivilianContractKind(String id, String quest) {
        this.id = id;
        this.quest = new ResourceLocation("ultima", quest);
    }

    public String id() { return id; }
    public ResourceLocation quest() { return quest; }
    public boolean scoped() { return id.startsWith("evolving_"); }

    public static CivilianContractKind parse(String id) {
        for (var value : values()) if (value.id.equals(id)) return value;
        throw new IllegalArgumentException("Unknown civilian contract kind: " + id);
    }
}
