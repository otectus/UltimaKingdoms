package com.ultimakingdoms.compat.quests;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.api.gating.KingdomGateApi;
import com.ultimakingdoms.api.gating.KingdomPredicate;
import com.ultimakingdoms.integration.IntegrationConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Optional, reflection-only registration of Ultima's offer/accept gate in MCA: Quests. */
public final class McaQuestsCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation CONDITION_ID =
            new ResourceLocation(UltimaKingdomsApi.MOD_ID, "kingdom");
    private static final String API_CLASS = "dev.otectus.mcaquests.api.McaQuestsApi";
    private static final String CONDITION_CLASS = "dev.otectus.mcaquests.quest.condition.QuestCondition";
    private static final String CONTEXT_CLASS = "dev.otectus.mcaquests.quest.condition.QuestContext";
    private static final Set<String> FIELDS = Set.of(
            "type", "gate", "settlement_id", "subject", "include", "exclude", "when_unknown");
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();
    private static final AtomicReference<Object> CONDITION_TYPE = new AtomicReference<>();
    private static final AtomicBoolean RUNTIME_FAILURE_REPORTED = new AtomicBoolean();
    private static volatile Binding binding;

    private McaQuestsCompat() {
    }

    /** Called from common setup; does nothing when MCA: Quests is absent or its public API cannot bind. */
    public static void registerConditionIfPresent() {
        if (!ModList.get().isLoaded("mcaquests") || !ATTEMPTED.compareAndSet(false, true)) return;
        String version = ModList.get().getModContainerById("mcaquests")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("unknown");
        try {
            ClassLoader loader = McaQuestsCompat.class.getClassLoader();
            Class<?> api = Class.forName(API_CLASS, false, loader);
            Class<?> condition = Class.forName(CONDITION_CLASS, false, loader);
            Class<?> context = Class.forName(CONTEXT_CLASS, false, loader);
            Method register = api.getMethod("registerCondition", ResourceLocation.class, Codec.class);
            Binding resolved = new Binding(condition, context.getMethod("player"), context.getMethod("villager"));
            binding = resolved;
            Codec<Object> codec = Codec.PASSTHROUGH.comapFlatMap(
                    dynamic -> decode(dynamic, resolved),
                    value -> new Dynamic<>(JsonOps.INSTANCE, spec(value).toJson()));
            Object type = register.invoke(null, CONDITION_ID, codec);
            if (type == null) throw new IllegalStateException("registerCondition returned null");
            CONDITION_TYPE.set(type);
            LOGGER.info("[Ultima Kingdoms] Registered MCA: Quests {} kingdom offer/accept condition", version);
        } catch (Throwable throwable) {
            binding = null;
            LOGGER.error("[Ultima Kingdoms] MCA: Quests {} public condition API did not bind; quest gates disabled",
                    version, unwrap(throwable));
        }
    }

    private static DataResult<Object> decode(Dynamic<?> dynamic, Binding binding) {
        try {
            JsonElement json = dynamic.convert(JsonOps.INSTANCE).getValue();
            GateSpec gate = GateSpec.fromJson(json);
            InvocationHandler handler = new ConditionHandler(gate, binding);
            Object proxy = Proxy.newProxyInstance(binding.conditionClass().getClassLoader(),
                    new Class<?>[]{binding.conditionClass()}, handler);
            return DataResult.success(proxy);
        } catch (IllegalArgumentException exception) {
            return DataResult.error(exception::getMessage);
        } catch (Throwable throwable) {
            return DataResult.error(() -> "Could not construct kingdom quest condition: " + throwable.getMessage());
        }
    }

    private static GateSpec spec(Object value) {
        if (value != null && Proxy.isProxyClass(value.getClass())
                && Proxy.getInvocationHandler(value) instanceof ConditionHandler handler) {
            return handler.spec;
        }
        throw new IllegalArgumentException("Not an Ultima Kingdoms quest condition");
    }

    private static boolean test(GateSpec spec, Object context, Binding binding) {
        try {
            if (!IntegrationConfig.KINGDOM_GATING_ENABLED.get()) return false;
            Object playerValue = binding.player().invoke(context);
            Object giverValue = binding.villager().invoke(context);
            if (!(playerValue instanceof ServerPlayer player) || !(giverValue instanceof Entity giver)) return false;
            if (spec.gate().isPresent()) {
                return KingdomGateApi.testNamed(player, giver, spec.gate().get(), spec.explicitSettlementId());
            }
            return KingdomGateApi.test(player, giver, spec.inline().orElseThrow(), spec.explicitSettlementId());
        } catch (Throwable throwable) {
            if (RUNTIME_FAILURE_REPORTED.compareAndSet(false, true)) {
                LOGGER.error("[Ultima Kingdoms] MCA: Quests kingdom condition failed closed", unwrap(throwable));
            }
            return false;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable instanceof java.lang.reflect.InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : throwable;
    }

    private record Binding(Class<?> conditionClass, Method player, Method villager) {
    }

    private static final class ConditionHandler implements InvocationHandler {
        private final GateSpec spec;
        private final Binding binding;

        private ConditionHandler(GateSpec spec, Binding binding) {
            this.spec = spec;
            this.binding = binding;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "type" -> CONDITION_TYPE.get();
                case "test" -> arguments != null && arguments.length == 1
                        && test(spec, arguments[0], binding);
                case "describe" -> Component.translatable("condition.ultima_kingdoms.kingdom");
                case "toString" -> "UltimaKingdomCondition[" + spec + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> arguments != null && arguments.length == 1 && proxy == arguments[0];
                default -> throw new UnsupportedOperationException("Unexpected QuestCondition method: " + method);
            };
        }
    }

    private record GateSpec(Optional<ResourceLocation> gate, Optional<KingdomPredicate> inline,
                            Optional<UUID> explicitSettlementId) {
        private GateSpec {
            gate = Objects.requireNonNull(gate, "gate");
            inline = Objects.requireNonNull(inline, "inline");
            explicitSettlementId = Objects.requireNonNull(explicitSettlementId, "explicitSettlementId");
            if (gate.isPresent() == inline.isPresent()) {
                throw new IllegalArgumentException("A kingdom condition must define exactly one of gate or inline fields");
            }
        }

        private static GateSpec fromJson(JsonElement element) {
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("Kingdom quest condition must be a JSON object");
            }
            JsonObject json = element.getAsJsonObject();
            for (String field : json.keySet()) {
                if (!FIELDS.contains(field)) {
                    throw new IllegalArgumentException("Unknown kingdom quest condition field: " + field);
                }
            }
            if (json.has("type")) {
                String type = string(json, "type");
                if (!CONDITION_ID.toString().equals(type)) {
                    throw new IllegalArgumentException("Unexpected kingdom quest condition type: " + type);
                }
            }
            Optional<UUID> settlement = json.has("settlement_id")
                    ? Optional.of(uuid(string(json, "settlement_id"))) : Optional.empty();
            if (json.has("gate")) {
                Set<String> inlineFields = Set.of("subject", "include", "exclude", "when_unknown");
                if (inlineFields.stream().anyMatch(json::has)) {
                    throw new IllegalArgumentException("Named gate cannot be mixed with inline kingdom predicate fields");
                }
                ResourceLocation id = ResourceLocation.tryParse(string(json, "gate"));
                if (id == null || !id.toString().equals(string(json, "gate"))) {
                    throw new IllegalArgumentException("gate must be a canonical resource id");
                }
                return new GateSpec(Optional.of(id), Optional.empty(), settlement);
            }
            JsonObject predicate = json.deepCopy();
            predicate.remove("type");
            predicate.remove("settlement_id");
            return new GateSpec(Optional.empty(), Optional.of(KingdomPredicate.fromJson(predicate)), settlement);
        }

        private JsonObject toJson() {
            JsonObject json = inline.map(KingdomPredicate::toJson).orElseGet(JsonObject::new);
            gate.ifPresent(id -> json.addProperty("gate", id.toString()));
            explicitSettlementId.ifPresent(id -> json.addProperty("settlement_id", id.toString()));
            return json;
        }

        private static String string(JsonObject json, String field) {
            JsonElement element = json.get(field);
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(field + " must be a string");
            }
            return element.getAsString();
        }

        private static UUID uuid(String value) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("settlement_id must be a UUID: " + value);
            }
        }
    }
}
