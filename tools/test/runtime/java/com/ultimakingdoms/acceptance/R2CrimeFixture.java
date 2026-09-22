package com.ultimakingdoms.acceptance;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Test-only native MCA: Crime fixture for the packaged R2 scenario.
 *
 * <p>The acceptance source set deliberately has no compile dependency on MCA: Crime. Reflection keeps
 * the production jar optional while still writing through Crime's own ledger, report store and civic
 * work service when the provider is present. The fixture never ships in Ultima Kingdoms' production
 * jar.
 */
public final class R2CrimeFixture {

    private static final String ROOT = "dev.otectus.mcacrime.";

    private R2CrimeFixture() {
    }

    /** Enables the three existing Crime switches used by the workshop and registers a FakePlayer online. */
    public static void setup(ServerPlayer player, Entity giver) throws Exception {
        if (player == null || giver == null || player.getServer() == null
                || player.level() != giver.level()) {
            throw new IllegalArgumentException("fixture needs a server player and giver in one level");
        }
        setCommonBoolean("townsteadEnabled", true);
        setCommonBoolean("townsteadServiceRestrictions", true);
        setCommonBoolean("townsteadCommunityService", true);
        setCommonBoolean("enableObservations", true);
        registerOnline(player);
        player.moveTo(giver.getX(), giver.getY(), giver.getZ());
    }

    /**
     * Creates a witnessed local {@code mcacrime:theft} through Crime's ledger. When {@code reported}
     * is true it also adds a maximum-confidence accepted report in the same resolved community. With
     * observations enabled, the false variant is the real hidden/unreported control case.
     *
     * @return the exact Crime case id
     */
    public static UUID reportTheft(ServerPlayer player, Entity giver, boolean reported) throws Exception {
        MinecraftServer server = requireServerThread(player);
        Object community = community(giver, player.serverLevel());
        UUID caseId = UUID.randomUUID();
        Object resolution = enumConstant(ROOT + "ledger.Resolution", "UNRESOLVED");
        Class<?> recordType = Class.forName(ROOT + "ledger.CrimeRecord");
        int villageId = ((Number) call(community, "villageId")).intValue();
        long now = player.serverLevel().getGameTime();

        Constructor<?> constructor = java.util.Arrays.stream(recordType.getConstructors())
                .filter(candidate -> candidate.getParameterCount() == 19)
                .findFirst().orElseThrow(() -> new NoSuchMethodException("CrimeRecord canonical constructor"));
        Object record = constructor.newInstance(caseId, player.getUUID(), giver.getUUID(),
                new ResourceLocation("mcacrime", "theft"), OptionalInt.of(villageId), community,
                true, Set.of(giver.getUUID()), now, 6L, -2L, 8L, 0L, resolution, 0L,
                List.of(), null, null, Map.of("detection", "witness"));
        Class.forName(ROOT + "ledger.CrimeLedger")
                .getMethod("record", MinecraftServer.class, recordType).invoke(null, server, record);

        if (reported) {
            addAcceptedReport(server, community, caseId, player, giver, now);
        }
        return caseId;
    }

    /**
     * Offers and accepts Crime's own victim-amends contract for {@code caseId}, then supplies each unit
     * through {@code CivicWorkService.credit}. The first receipt is replayed once and must be rejected,
     * proving the provider's durable receipt dedupe is active. Completion, settlement and case revision
     * all remain owned by Crime.
     *
     * @return the completed Crime service-contract id
     */
    public static UUID resolveRestitution(ServerPlayer player, UUID caseId) throws Exception {
        MinecraftServer server = requireServerThread(player);
        Class<?> taskType = Class.forName(ROOT + "civic.CivicTask");
        Object task = enumConstant(ROOT + "civic.CivicTask", "VICTIM_AMENDS");
        Class<?> service = Class.forName(ROOT + "civic.CivicWorkService");
        Object offer = service.getMethod("offer", MinecraftServer.class, UUID.class, boolean.class,
                        taskType, UUID.class, UUID.class)
                .invoke(null, server, player.getUUID(), true, task, caseId, null);
        Object contract = call(offer, "contract");
        if (contract == null) {
            throw new AssertionError("Crime refused fixture restitution offer: " + call(offer, "refusal"));
        }
        UUID contractId = (UUID) call(contract, "contractId");
        Optional<?> accepted = (Optional<?>) service.getMethod("accept", MinecraftServer.class,
                        UUID.class, UUID.class).invoke(null, server, player.getUUID(), contractId);
        if (accepted.isEmpty()) {
            throw new AssertionError("Crime did not accept its restitution contract " + contractId);
        }

        int required = ((Number) call(accepted.get(), "requiredUnits")).intValue();
        Method credit = service.getMethod("credit", MinecraftServer.class, UUID.class, taskType,
                String.class, int.class);
        for (int unit = 0; unit < required; unit++) {
            String receipt = "ultima-r2-fixture:" + contractId + ':' + unit;
            Optional<?> progressed = (Optional<?>) credit.invoke(null, server, player.getUUID(), task,
                    receipt, 1);
            if (progressed.isEmpty()) {
                throw new AssertionError("Crime rejected restitution unit " + unit);
            }
            if (unit == 0) {
                Optional<?> duplicate = (Optional<?>) credit.invoke(null, server, player.getUUID(), task,
                        receipt, 1);
                if (duplicate.isPresent()) {
                    throw new AssertionError("Crime credited a replayed restitution receipt");
                }
            }
        }

        Object data = crimeData(server);
        Object completed = data.getClass().getMethod("serviceContract", UUID.class).invoke(data, contractId);
        if (completed == null || !"completed".equals(call(call(completed, "state"), "id"))) {
            throw new AssertionError("Crime did not complete restitution contract " + contractId);
        }
        Object record = ((Optional<?>) data.getClass().getMethod("recordById", UUID.class)
                .invoke(data, caseId)).orElseThrow();
        if ((boolean) call(record, "actionable")) {
            throw new AssertionError("Crime restitution left case actionable " + caseId);
        }
        return contractId;
    }

    private static void addAcceptedReport(MinecraftServer server, Object community, UUID caseId,
                                          ServerPlayer player, Entity giver, long now) throws Exception {
        Class<?> reportType = Class.forName(ROOT + "memory.CrimeReport");
        Object report = reportType.getConstructors()[0].newInstance(UUID.randomUUID(), caseId,
                UUID.randomUUID(), giver.getUUID(), player.getUUID(),
                new ResourceLocation("mcacrime", "theft"), community, now, now + 24_000L, 1.0F, true);
        Object data = crimeData(server);
        boolean stored = (boolean) data.getClass().getMethod("addReport", reportType).invoke(data, report);
        if (!stored) {
            throw new AssertionError("Crime rejected fixture report for " + caseId);
        }
    }

    private static Object community(Entity giver, ServerLevel level) throws Exception {
        Optional<?> resolved = (Optional<?>) Class.forName(ROOT + "detect.CrimeCommunityResolver")
                .getMethod("resolve", Entity.class, ServerLevel.class).invoke(null, giver, level);
        return resolved.orElseThrow(() -> new AssertionError("fixture giver has no Crime community"));
    }

    private static Object crimeData(MinecraftServer server) throws Exception {
        return Class.forName(ROOT + "state.world.CrimeWorldData")
                .getMethod("get", MinecraftServer.class).invoke(null, server);
    }

    private static MinecraftServer requireServerThread(ServerPlayer player) {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("R2 Crime fixture must run for an online server player on-thread");
        }
        return server;
    }

    /**
     * FakePlayerFactory does not join PlayerList. Production workshop authentication intentionally
     * requires {@code getPlayer(uuid) == player}, so the fixture locates the UUID-to-player map by
     * behavior, updates it, and leaves ordinary real-player registration untouched.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerOnline(ServerPlayer player) throws Exception {
        var playerList = player.getServer().getPlayerList();
        if (playerList.getPlayer(player.getUUID()) == player) {
            return;
        }
        for (Field field : allFields(playerList.getClass())) {
            if (!Map.class.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Map map = (Map) field.get(playerList);
            Object previous;
            try {
                previous = map.put(player.getUUID(), player);
            } catch (RuntimeException incompatible) {
                continue;
            }
            if (playerList.getPlayer(player.getUUID()) == player) {
                return;
            }
            if (previous == null) {
                map.remove(player.getUUID());
            } else {
                map.put(player.getUUID(), previous);
            }
        }
        throw new AssertionError("could not register FakePlayer in PlayerList's UUID index");
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            fields.addAll(List.of(cursor.getDeclaredFields()));
        }
        return fields;
    }

    private static void setCommonBoolean(String name, boolean value) throws Exception {
        Class<?> config = Class.forName(ROOT + "McaCrimeConfig");
        Object common = config.getField("COMMON").get(null);
        Object configValue = common.getClass().getField(name).get(common);
        configValue.getClass().getMethod("set", Object.class).invoke(configValue, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(String type, String name) throws Exception {
        return Enum.valueOf((Class<? extends Enum>) Class.forName(type), name);
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }
}
