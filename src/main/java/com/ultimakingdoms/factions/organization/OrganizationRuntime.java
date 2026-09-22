package com.ultimakingdoms.factions.organization;

import com.ultimakingdoms.api.KingdomsService;
import com.ultimakingdoms.api.Registration;
import com.ultimakingdoms.api.factions.organization.OrganizationApi;
import com.ultimakingdoms.api.factions.organization.OrganizationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lifecycle boundary intentionally separate from the legacy sovereign standing runtime. */
public final class OrganizationRuntime {
    private static final OrganizationDefinitions DEFINITIONS = new OrganizationDefinitions();
    private static final Map<MinecraftServer, OrganizationServiceImpl> SERVICES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicBoolean COMMANDS_REGISTERED=new AtomicBoolean();

    private OrganizationRuntime() {
    }

    public static PreparableReloadListener reloadListener() {
        if(COMMANDS_REGISTERED.compareAndSet(false,true))net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(OrganizationLifecycleCommands.class);
        return DEFINITIONS.reloadListener();
    }

    public static void commitPending() {
        DEFINITIONS.commitPending();
    }

    public static Registration attach(MinecraftServer server, KingdomsService kingdoms) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(kingdoms, "kingdoms");
        OrganizationServiceImpl service = new OrganizationServiceImpl(server, kingdoms, DEFINITIONS);
        OrganizationServiceImpl previous = SERVICES.putIfAbsent(server, service);
        if (previous != null) throw new IllegalStateException("Organization runtime is already attached");
        boolean attached = false;
        try {
            OrganizationApi.attach(server, service);
            attached = true;
        } finally {
            if (!attached) SERVICES.remove(server, service);
        }
        return () -> {
            SERVICES.remove(server, service);
            OrganizationApi.detach(server, service);
        };
    }

    public static OrganizationService get(MinecraftServer server) {
        return OrganizationApi.get(server);
    }

    /** Internal receipt delivery. Award parameters come exclusively from the durable intent ledger. */
    public static com.ultimakingdoms.api.factions.organization.OrganizationDeedResult applyCommittedQuestEffect(
            MinecraftServer server,java.util.UUID epoch,java.util.UUID receipt,java.util.UUID player,
            net.minecraft.resources.ResourceLocation organization) {
        if(!server.isSameThread())throw new IllegalStateException("Receipt delivery requires server thread");
        var proof=com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge.committedEffect(server,epoch,receipt,player,organization)
                .orElseThrow(()->new IllegalArgumentException("No durable effect authorization"));
        var service=SERVICES.get(server);if(service==null)throw new IllegalStateException("Organization runtime unavailable");
        return service.recordCommittedDeed(player,organization,proof.effectReceipt(),proof.quest(),proof.credit(),proof.settlement());
    }

    public static void clear() {
        synchronized (SERVICES) {
            SERVICES.forEach((server, service) -> OrganizationApi.detach(server, service));
            SERVICES.clear();
        }
        DEFINITIONS.clear();
    }
}
