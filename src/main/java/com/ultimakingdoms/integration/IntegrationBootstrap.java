package com.ultimakingdoms.integration;

import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.gating.KingdomGateApi;
import com.ultimakingdoms.compat.quests.McaQuestsCompat;
import com.ultimakingdoms.compat.townstead.TownsteadRuntime;
import com.ultimakingdoms.factions.FactionsRuntime;
import com.ultimakingdoms.integration.gating.KingdomGateRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns optional-integration setup and per-server lifecycle registrations. */
public final class IntegrationBootstrap {
    private static final KingdomGateRegistry GATES = new KingdomGateRegistry();

    private IntegrationBootstrap() {
    }

    public static void init(IEventBus modBus) {
        FactionsRuntime.registerGlobalHooks();
        TownsteadRuntime.registerGlobalHooks();
        Objects.requireNonNull(modBus, "modBus").addListener(IntegrationBootstrap::commonSetup);
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(com.ultimakingdoms.compat.recruits.RecruitsEvents::register);
        event.enqueueWork(McaQuestsCompat::registerConditionIfPresent);
        event.enqueueWork(com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge::register);
        event.enqueueWork(com.ultimakingdoms.compat.progression.ProgressionBridge::register);
    }

    public static PreparableReloadListener gateReloadListener() {
        return GATES.reloadListener();
    }

    public static void commitPending(KingdomsService service) {
        GATES.commitPending(Objects.requireNonNull(service, "service"));
    }

    public static Registration attach(MinecraftServer server, KingdomsService service) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(server, "server");
        Registration gates = KingdomGateApi.attachNamedGates(server, GATES::get);
        Registration factions = () -> { };
        Registration organizations = () -> { };
        Registration civic = () -> { };
        try {
            factions = FactionsRuntime.attach(server, service);
            organizations = com.ultimakingdoms.factions.organization.OrganizationRuntime.attach(server, service);
            civic = com.ultimakingdoms.civic.CivicRuntime.attach(server, service);
            Registration townstead = TownsteadRuntime.attach(server, service);
            Registration attachedFactions = factions;
            Registration attachedOrganizations = organizations;
            Registration attachedCivic = civic;
            AtomicBoolean open = new AtomicBoolean(true);
            return () -> {
                if (!open.compareAndSet(true, false)) return;
                try {
                    try { townstead.close(); } finally { try { attachedCivic.close(); } finally { attachedOrganizations.close(); } }
                } finally {
                    try {
                        attachedFactions.close();
                    } finally {
                        gates.close();
                    }
                }
            };
        } catch (RuntimeException exception) {
            try {
                try { civic.close(); } finally { try { organizations.close(); } finally { factions.close(); } }
            } finally {
                gates.close();
            }
            throw exception;
        }
    }

    public static void clear() {
        GATES.clear();
    }
}
