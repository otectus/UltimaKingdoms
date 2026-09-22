package com.ultimakingdoms.compat.crime;

import com.ultimakingdoms.api.McaCommunityRef;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.warfare.WarfareApi;
import com.ultimakingdoms.warfare.WarfareRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.*;
import java.util.*;

/** Reflection-only Ultima evidence provider for MCA: Crime's optional R3 jurisdiction extension. */
public final class JurisdictionPolicyBridge {
    private record Binding(Object provider, Method unregister) { }
    private static final Map<MinecraftServer, Binding> BINDINGS = new WeakHashMap<>();

    private JurisdictionPolicyBridge() { }

    /** Safe no-op when Crime or its compatible extension is absent. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean attach(MinecraftServer server) {
        requireThread(server);
        if (BINDINGS.containsKey(server)) return true;
        try {
            Class<?> api = Class.forName("dev.otectus.mcacrime.api.JurisdictionPolicyApi");
            Class<?> providerType = Class.forName("dev.otectus.mcacrime.api.jurisdiction.JurisdictionPolicyProvider");
            Class<?> communityType = Class.forName("dev.otectus.mcacrime.api.model.CrimeCommunityKey");
            Class<?> snapshotType = Class.forName("dev.otectus.mcacrime.api.jurisdiction.JurisdictionSnapshot");
            Class<? extends Enum> civilLaw = (Class<? extends Enum>)Class.forName(snapshotType.getName() + "$CivilLaw");
            Class<? extends Enum> cooperation = (Class<? extends Enum>)Class.forName(snapshotType.getName() + "$Cooperation");
            Class<? extends Enum> availability = (Class<? extends Enum>)Class.forName(snapshotType.getName() + "$Availability");
            Constructor<?> community = communityType.getConstructor(net.minecraft.resources.ResourceLocation.class, int.class);
            Constructor<?> snapshot = snapshotType.getConstructor(communityType, String.class, String.class,
                    String.class, String.class, civilLaw, cooperation, boolean.class, boolean.class,
                    boolean.class, boolean.class, long.class, availability, String.class);
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "resolve" -> resolve(server, args, snapshot, civilLaw, cooperation, availability);
                case "jurisdiction" -> jurisdiction(server, args, community);
                case "toString" -> "UltimaKingdomsJurisdictionPolicy";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.toString());
            };
            Object provider = Proxy.newProxyInstance(providerType.getClassLoader(),
                    new Class<?>[]{providerType}, handler);
            Method register = api.getMethod("register", MinecraftServer.class, providerType);
            Method unregister = api.getMethod("unregister", MinecraftServer.class, providerType);
            register.invoke(null, server, provider);
            BINDINGS.put(server, new Binding(provider, unregister));
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().info("MCA: Crime jurisdiction extension unavailable: {}",
                    failure.getClass().getSimpleName());
            return false;
        }
    }

    public static void detach(MinecraftServer server) {
        requireThread(server);
        Binding binding = BINDINGS.remove(server);
        if (binding == null) return;
        try { binding.unregister().invoke(null, server, binding.provider()); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("MCA: Crime jurisdiction extension detach failed", failure);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<?> resolve(MinecraftServer server, Object[] args, Constructor<?> snapshot,
                                       Class<? extends Enum> civilLaw, Class<? extends Enum> cooperation,
                                       Class<? extends Enum> availability) throws ReflectiveOperationException {
        if (args == null || args.length != 3 || args[0] != server || args[1] == null) return Optional.empty();
        Object key = args[1];
        var dimension = (net.minecraft.resources.ResourceLocation)key.getClass().getMethod("dimension").invoke(key);
        int village = (int)key.getClass().getMethod("villageId").invoke(key);
        var settlement = UltimaKingdomsApi.get(server).getSettlementForMcaVillage(dimension, village).orElse(null);
        if (settlement == null) return Optional.empty();
        var provider = WarfareApi.get(server).orElse(null);
        if (provider == null) return Optional.empty();
        var control = provider.control(settlement.id()).orElse(null);
        if (control == null) return Optional.empty();
        ServerPlayer subject = args[2] instanceof UUID id ? server.getPlayerList().getPlayer(id) : null;
        boolean safe = subject != null && provider.safeConduct(subject, settlement.id());
        var controller = WarfareRuntime.get(server).mapping(control.nativeController()).orElse(null);
        boolean usable = "available".equals(control.availability()) && controller != null;
        // Civic identity can remain equal to recognized sovereignty during a native occupation.
        // Physical controller mapping is the evidence; an unmapped controller suspends use and is
        // conservatively represented as occupied rather than silently legalizing enforcement.
        boolean occupied = controller == null || !controller.kingdom().equals(control.recognizedKingdom());
        String explanation = controller == null ? "Native controller political mapping unavailable"
                : bounded(control.availability(), 512);
        Object value = snapshot.newInstance(key, settlement.slug().toString(), control.recognizedKingdom(),
                control.nativeController(), "ultima_kingdoms:civil", Enum.valueOf(civilLaw, "CONTINUES"),
                Enum.valueOf(cooperation, "LOCAL_ONLY"), occupied, control.contested(), safe, safe,
                control.sequence(), Enum.valueOf(availability, usable ? "AVAILABLE" : "SUSPENDED"), explanation);
        return Optional.of(value);
    }

    private static Optional<?> jurisdiction(MinecraftServer server, Object[] args, Constructor<?> community)
            throws ReflectiveOperationException {
        if (args == null || args.length != 2 || args[0] != server || !(args[1] instanceof LivingEntity responder)
                || responder.level().isClientSide()) return Optional.empty();
        var level = (net.minecraft.server.level.ServerLevel)responder.level();
        var settlement = UltimaKingdomsApi.get(server).getSettlementAt(level, responder.blockPosition()).orElse(null);
        if (settlement == null) return Optional.empty();
        return UltimaKingdomsApi.get(server).getSettlementExternalRefs(settlement.id())
                .getOrDefault(McaCommunityRef.EXTERNAL_REF_NAMESPACE, Set.of()).stream()
                .map(McaCommunityRef::parse).flatMap(Optional::stream)
                .filter(ref -> ref.dimension().equals(level.dimension().location()))
                .sorted(Comparator.comparingInt(McaCommunityRef::villageId)).findFirst()
                .map(ref -> {
                    try { return community.newInstance(ref.dimension(), ref.villageId()); }
                    catch (ReflectiveOperationException failure) { return null; }
                }).filter(Objects::nonNull);
    }

    private static String bounded(String value, int limit) {
        if (value == null || value.isBlank()) return "Jurisdiction evidence unavailable";
        return value.substring(0, Math.min(limit, value.length()));
    }

    private static void requireThread(MinecraftServer server) {
        if (server == null || !server.isSameThread())
            throw new IllegalStateException("Jurisdiction integration requires server thread");
    }
}
