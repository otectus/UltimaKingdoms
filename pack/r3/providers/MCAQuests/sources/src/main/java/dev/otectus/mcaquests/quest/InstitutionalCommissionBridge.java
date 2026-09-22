package dev.otectus.mcaquests.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.otectus.mcaquests.McaQuests;
import dev.otectus.mcaquests.quest.reward.ItemReward;
import dev.otectus.mcaquests.quest.condition.QuestCondition;
import dev.otectus.mcaquests.quest.condition.composite.AllOfCondition;
import dev.otectus.mcaquests.quest.condition.composite.AnyOfCondition;
import dev.otectus.mcaquests.quest.condition.leaf.InstitutionalServiceAvailableCondition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Reflection-only boundary for Ultima Kingdoms' institutional commission contract. */
public final class InstitutionalCommissionBridge {

    private static final String API_CLASS = "com.ultimakingdoms.api.civic.InstitutionalCommissionApi";
    private static final int MAX_SNAPSHOT_CHARS = 131_072;

    private InstitutionalCommissionBridge() {
    }

    /**
     * A binding is opaque to this provider. Legacy R2 owners use a canonical UUID; R3 owners use the
     * namespaced {@code r3:UUID} form so Ultima can route both generations through one stable ABI.
     */
    public static boolean validBinding(String binding) {
        if (binding == null) return false;
        String value = binding.startsWith("r3:") ? binding.substring(3) : binding;
        if (value.length() != 36) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * R2 institutional commissions are deliberately narrow: a concrete quest with one or more fixed,
     * unenchanted emerald item rewards. This keeps native MCA reward ownership and persistence intact.
     */
    public static boolean supportedDefinition(QuestDefinition definition) {
        return definition != null && definition.institutionalCommission() && !definition.isTemplate()
                && definition.conditions().map(InstitutionalCommissionBridge::hasMandatoryServiceGate)
                        .orElse(false)
                && definition.rewards().size() == 1
                && definition.rewards().stream().allMatch(reward -> reward instanceof ItemReward item
                        && item.item() == Items.EMERALD && item.count() > 0 && item.enchantments().isEmpty());
    }

    /** True only when Ultima exposes the complete, exact ABI used by offer and acceptance commits. */
    public static boolean serviceAvailable() {
        try {
            apiMethod("validate", ServerPlayer.class, Entity.class,
                    ResourceLocation.class, String.class, boolean.class);
            apiMethod("accepted", ServerPlayer.class, Entity.class,
                    ResourceLocation.class, String.class, UUID.class);
            apiMethod("cancelled", ServerPlayer.class, ResourceLocation.class, String.class, UUID.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            return false;
        }
    }

    /**
     * Requires the service gate on every path that can satisfy an authored condition. A direct gate
     * or one member of {@code all_of} is mandatory; every branch of {@code any_of} must contain it.
     * Negation never counts as the positive availability tripwire.
     */
    static boolean hasMandatoryServiceGate(QuestCondition condition) {
        if (condition instanceof InstitutionalServiceAvailableCondition) return true;
        if (condition instanceof AllOfCondition all) {
            return all.conditions().stream().anyMatch(InstitutionalCommissionBridge::hasMandatoryServiceGate);
        }
        if (condition instanceof AnyOfCondition any) {
            return !any.conditions().isEmpty()
                    && any.conditions().stream().allMatch(InstitutionalCommissionBridge::hasMandatoryServiceGate);
        }
        return false;
    }

    /** Empty means permit; every missing, malformed, throwing, or non-empty result denies. */
    public static String validate(ServerPlayer player, Entity giver, ResourceLocation questId,
                                  String binding, boolean completion) {
        if (player == null || giver == null || questId == null || !validBinding(binding)) {
            return "This commission is not currently authorized.";
        }
        try {
            Method method = apiMethod("validate", ServerPlayer.class, Entity.class,
                    ResourceLocation.class, String.class, boolean.class);
            Object result = method.invoke(null, player, giver, questId, binding, completion);
            return validationResult(result);
        } catch (ReflectiveOperationException | LinkageError failure) {
            logBridgeFailure("validate", failure);
            return "This commission service is currently unavailable.";
        }
    }

    static String validationResult(@Nullable Object result) {
        if (!(result instanceof String reason)) return "This commission is not currently authorized.";
        if (reason.isEmpty()) return "";
        return reason.length() <= 512 ? reason : "This commission is not currently authorized.";
    }

    /** Durably binds the opaque owner token to exactly this accepted native quest copy. */
    public static boolean accepted(ServerPlayer player, Entity giver, ResourceLocation questId,
                                   String binding, UUID instance) {
        if (player == null || giver == null || questId == null || instance == null || !validBinding(binding)) {
            return false;
        }
        try {
            Method method = apiMethod("accepted", ServerPlayer.class, Entity.class,
                    ResourceLocation.class, String.class, UUID.class);
            return Boolean.TRUE.equals(method.invoke(null, player, giver, questId, binding, instance));
        } catch (ReflectiveOperationException | LinkageError failure) {
            logBridgeFailure("accepted", failure);
            return false;
        }
    }

    /** Durably releases an accepted owner contract before native abandonment removes its quest copy. */
    public static boolean cancelled(ServerPlayer player, ResourceLocation questId,
                                    String binding, UUID instance) {
        if (player == null || questId == null || instance == null || !validBinding(binding)) {
            return false;
        }
        try {
            Method method = apiMethod("cancelled", ServerPlayer.class, ResourceLocation.class,
                    String.class, UUID.class);
            return Boolean.TRUE.equals(method.invoke(null, player, questId, binding, instance));
        } catch (ReflectiveOperationException | LinkageError failure) {
            logBridgeFailure("cancelled", failure);
            return false;
        }
    }

    private static Method apiMethod(String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = Class.forName(API_CLASS, false,
                InstitutionalCommissionBridge.class.getClassLoader()).getMethod(name, parameters);
        if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())) {
            throw new NoSuchMethodException(API_CLASS + "#" + name + " is not public static");
        }
        return method;
    }

    private static void logBridgeFailure(String operation, Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : failure;
        McaQuests.LOGGER.warn("[MCA: Quests] Institutional commission {} failed closed ({})",
                operation, cause.getClass().getSimpleName());
    }

    /** Stable persisted JSON used both to keep accepted terms and to detect a changed datapack definition. */
    public static Optional<Snapshot> snapshot(QuestDefinition definition) {
        Optional<JsonElement> encoded = QuestDefinition.CODEC.encodeStart(JsonOps.INSTANCE, definition).result();
        if (encoded.isEmpty()) return Optional.empty();
        String json = encoded.get().toString();
        if (json.length() > MAX_SNAPSHOT_CHARS) return Optional.empty();
        return Optional.of(new Snapshot(json, sha256(json)));
    }

    public static Optional<QuestDefinition> decodeSnapshot(String json, String fingerprint) {
        if (json == null || fingerprint == null || json.length() > MAX_SNAPSHOT_CHARS
                || !sha256(json).equals(fingerprint)) return Optional.empty();
        try {
            return QuestDefinition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    public static boolean matches(QuestDefinition current, String fingerprint) {
        return current != null && fingerprint != null
                && snapshot(current).map(Snapshot::fingerprint).filter(fingerprint::equals).isPresent();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record Snapshot(String json, String fingerprint) {
    }
}
