package dev.otectus.mcaquests.quest.condition;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.otectus.mcaquests.McaQuests;
import dev.otectus.mcaquests.quest.condition.composite.AllOfCondition;
import dev.otectus.mcaquests.quest.condition.composite.AnyOfCondition;
import dev.otectus.mcaquests.quest.condition.composite.NotCondition;
import dev.otectus.mcaquests.quest.condition.leaf.AdvancementCondition;
import dev.otectus.mcaquests.quest.condition.leaf.AgeGroupCondition;
import dev.otectus.mcaquests.quest.condition.leaf.BiomeCondition;
import dev.otectus.mcaquests.quest.condition.leaf.CapitalAllegianceCondition;
import dev.otectus.mcaquests.quest.condition.leaf.CapitalInterregnumCondition;
import dev.otectus.mcaquests.quest.condition.leaf.CapitalPresentCondition;
import dev.otectus.mcaquests.quest.condition.leaf.CapitalRelationCondition;
import dev.otectus.mcaquests.quest.condition.leaf.CapitalRoleCondition;
import dev.otectus.mcaquests.quest.condition.leaf.CompatCapabilityCondition;
import dev.otectus.mcaquests.quest.condition.leaf.DimensionCondition;
import dev.otectus.mcaquests.quest.condition.leaf.FtbqChapterCompletedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.FtbqQuestCompletedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.FtbqTaskCompletedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.GiverDistanceFromAnyVillageCondition;
import dev.otectus.mcaquests.quest.condition.leaf.GiverDistanceFromVillageCondition;
import dev.otectus.mcaquests.quest.condition.leaf.HasHomeCondition;
import dev.otectus.mcaquests.quest.condition.leaf.HealthBelowCondition;
import dev.otectus.mcaquests.quest.condition.leaf.HeartsCondition;
import dev.otectus.mcaquests.quest.condition.leaf.TownsteadAvailableCondition;
import dev.otectus.mcaquests.quest.condition.leaf.TownsteadBuildingCondition;
import dev.otectus.mcaquests.quest.condition.leaf.TownsteadProfessionTrackCondition;
import dev.otectus.mcaquests.quest.condition.leaf.TownsteadSkillCondition;
import dev.otectus.mcaquests.quest.condition.leaf.TownsteadSpiritCondition;
import dev.otectus.mcaquests.quest.condition.leaf.TownsteadValueCondition;
import dev.otectus.mcaquests.quest.condition.leaf.InfectedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.InstitutionalServiceAvailableCondition;
import dev.otectus.mcaquests.quest.condition.leaf.IsFamilyMemberCondition;
import dev.otectus.mcaquests.quest.condition.leaf.IsPlayerSpouseCondition;
import dev.otectus.mcaquests.quest.condition.leaf.ItemHeldCondition;
import dev.otectus.mcaquests.quest.condition.leaf.MoodCondition;
import dev.otectus.mcaquests.quest.condition.leaf.PersonalityCondition;
import dev.otectus.mcaquests.quest.condition.leaf.PlayerLevelCondition;
import dev.otectus.mcaquests.quest.condition.leaf.ProfessionCondition;
import dev.otectus.mcaquests.quest.condition.leaf.QuestAbandonedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.QuestDeclinedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.QuestCompletedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.QuestFailedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.QuestNotCompletedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.RandomChanceCondition;
import dev.otectus.mcaquests.quest.condition.leaf.RelatedVillagerStatusCondition;
import dev.otectus.mcaquests.quest.condition.leaf.RelationshipStateCondition;
import dev.otectus.mcaquests.quest.condition.leaf.ReputationTierCondition;
import dev.otectus.mcaquests.quest.condition.leaf.TimeCondition;
import dev.otectus.mcaquests.quest.condition.leaf.VillageMemberCondition;
import dev.otectus.mcaquests.quest.condition.leaf.VillageReputationCondition;
import dev.otectus.mcaquests.quest.condition.leaf.WeatherCondition;
import dev.otectus.mcaquests.quest.condition.leaf.KingdomStandingCondition;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of leaf condition types plus the top-level {@link Codec} that also understands the
 * {@code all_of}/{@code any_of}/{@code not} composites (spec section 13). Composites are matched
 * first; otherwise the {@code "type"} field dispatches to a leaf.
 */
public final class ConditionTypes {

    private static final Map<ResourceLocation, QuestConditionType<?>> BY_ID = new LinkedHashMap<>();

    /**
     * {@code mcareputation:has_incident} — a deed on the player's public record with this village.
     *
     * <p>Registered under the <b>mcareputation</b> namespace, and registered <b>unconditionally</b>,
     * even when that mod is absent. A datapack that uses it must parse cleanly either way; without
     * Reputation the condition is simply never met (see the class docs).
     */
    public static final QuestConditionType<dev.otectus.mcaquests.quest.condition.leaf.HasIncidentCondition>
            HAS_INCIDENT = register(new net.minecraft.resources.ResourceLocation("mcareputation",
                    "has_incident"),
            dev.otectus.mcaquests.quest.condition.leaf.HasIncidentCondition.CODEC);

    /**
     * {@code mcareputation:villager_opinion} — what this giver personally makes of the player, as
     * opposed to what their village records.
     *
     * <p>Registered unconditionally for the same reason {@link #HAS_INCIDENT} is: the pack must parse
     * identically with or without MCA: Reputation, and it is evaluation that degrades.
     */
    public static final QuestConditionType<dev.otectus.mcaquests.quest.condition.leaf.VillagerOpinionCondition>
            VILLAGER_OPINION = register(new net.minecraft.resources.ResourceLocation("mcareputation",
                    "villager_opinion"),
            dev.otectus.mcaquests.quest.condition.leaf.VillagerOpinionCondition.CODEC);

    /**
     * {@code mcareputation:profile} — the player's public profile: how widely they are known in this
     * village, and what they are known for (MCA: Reputation 0.6.0, 1.6.6).
     *
     * <p>Registered unconditionally for the same reason the two above are. The difference is that its
     * degradation is <b>authored</b> rather than fixed: an installation that cannot answer the
     * question takes the condition's own {@code on_unavailable} branch, which defaults to "not met",
     * so a pack can keep a quest reachable where the profile layer is only flavour.
     */
    public static final QuestConditionType<dev.otectus.mcaquests.quest.condition.leaf.ProfileCondition>
            PROFILE = register(new net.minecraft.resources.ResourceLocation("mcareputation", "profile"),
            dev.otectus.mcaquests.quest.condition.leaf.ProfileCondition.CODEC);

    // Townstead (Townstead spec 5.1). Registered unconditionally, exactly like the FTB Quests and
    // MCA: Reputation conditions above: a datapack must parse identically whether or not the mod is
    // installed, so the types always exist and it is evaluation that is gated. Every bundled Townstead
    // definition opens with townstead_available, so an absent Townstead makes content ineligible rather
    // than broken.
    public static final QuestConditionType<TownsteadAvailableCondition> TOWNSTEAD_AVAILABLE =
            register("townstead_available", TownsteadAvailableCondition.CODEC);
    public static final QuestConditionType<TownsteadValueCondition> TOWNSTEAD_VALUE =
            register("townstead_value", TownsteadValueCondition.CODEC);
    public static final QuestConditionType<TownsteadBuildingCondition> TOWNSTEAD_BUILDING =
            register("townstead_building", TownsteadBuildingCondition.CODEC);
    public static final QuestConditionType<TownsteadSpiritCondition> TOWNSTEAD_SPIRIT =
            register("townstead_spirit", TownsteadSpiritCondition.CODEC);
    public static final QuestConditionType<TownsteadSkillCondition> TOWNSTEAD_SKILL =
            register("townstead_skill", TownsteadSkillCondition.CODEC);
    /**
     * Spec §5.1. The gate that proves a profession goal is reachable in the <em>loaded</em> Townstead
     * before the quest is offered, rather than after the player has accepted it and started waiting.
     */
    public static final QuestConditionType<TownsteadProfessionTrackCondition> TOWNSTEAD_PROFESSION_TRACK =
            register("townstead_profession_track", TownsteadProfessionTrackCondition.CODEC);

    public static final QuestConditionType<HeartsCondition> HEARTS = register("hearts", HeartsCondition.CODEC);
    public static final QuestConditionType<ProfessionCondition> PROFESSION = register("profession", ProfessionCondition.CODEC);
    public static final QuestConditionType<BiomeCondition> BIOME = register("biome", BiomeCondition.CODEC);
    public static final QuestConditionType<DimensionCondition> DIMENSION = register("dimension", DimensionCondition.CODEC);
    public static final QuestConditionType<TimeCondition> TIME = register("time", TimeCondition.CODEC);
    public static final QuestConditionType<WeatherCondition> WEATHER = register("weather", WeatherCondition.CODEC);
    public static final QuestConditionType<ItemHeldCondition> ITEM_HELD = register("item_held", ItemHeldCondition.CODEC);
    public static final QuestConditionType<AdvancementCondition> ADVANCEMENT = register("advancement", AdvancementCondition.CODEC);
    public static final QuestConditionType<PlayerLevelCondition> PLAYER_LEVEL = register("player_level", PlayerLevelCondition.CODEC);
    public static final QuestConditionType<RandomChanceCondition> RANDOM_CHANCE = register("random_chance", RandomChanceCondition.CODEC);
    public static final QuestConditionType<QuestCompletedCondition> QUEST_COMPLETED = register("quest_completed", QuestCompletedCondition.CODEC);
    public static final QuestConditionType<QuestNotCompletedCondition> QUEST_NOT_COMPLETED = register("quest_not_completed", QuestNotCompletedCondition.CODEC);
    public static final QuestConditionType<QuestFailedCondition> QUEST_FAILED = register("quest_failed", QuestFailedCondition.CODEC);
    public static final QuestConditionType<QuestAbandonedCondition> QUEST_ABANDONED = register("quest_abandoned", QuestAbandonedCondition.CODEC);
    public static final QuestConditionType<QuestDeclinedCondition> QUEST_DECLINED = register("quest_declined", QuestDeclinedCondition.CODEC);

    // v0.3.0 — MCA-aware conditions (see docs/0.3.0-design.md). All read MCA state via the per-pass
    // McaVillagerSnapshot and fail safe to "not met" when MCA data is unavailable.
    public static final QuestConditionType<IsPlayerSpouseCondition> IS_PLAYER_SPOUSE = register("is_player_spouse", IsPlayerSpouseCondition.CODEC);
    public static final QuestConditionType<RelationshipStateCondition> RELATIONSHIP_STATE = register("relationship_state", RelationshipStateCondition.CODEC);
    public static final QuestConditionType<IsFamilyMemberCondition> IS_FAMILY_MEMBER = register("is_family_member", IsFamilyMemberCondition.CODEC);
    public static final QuestConditionType<AgeGroupCondition> AGE_GROUP = register("age_group", AgeGroupCondition.CODEC);
    public static final QuestConditionType<PersonalityCondition> PERSONALITY = register("personality", PersonalityCondition.CODEC);
    public static final QuestConditionType<MoodCondition> MOOD = register("mood", MoodCondition.CODEC);
    public static final QuestConditionType<VillageMemberCondition> VILLAGE_MEMBER = register("village_member", VillageMemberCondition.CODEC);
    public static final QuestConditionType<HasHomeCondition> HAS_HOME = register("has_home", HasHomeCondition.CODEC);
    public static final QuestConditionType<HealthBelowCondition> HEALTH_BELOW = register("health_below", HealthBelowCondition.CODEC);
    public static final QuestConditionType<InfectedCondition> INFECTED = register("infected", InfectedCondition.CODEC);
    public static final QuestConditionType<RelatedVillagerStatusCondition> RELATED_VILLAGER_STATUS = register("related_villager_status", RelatedVillagerStatusCondition.CODEC);

    // v0.4.0 — independent mod-side village reputation as a condition.
    public static final QuestConditionType<VillageReputationCondition> VILLAGE_REPUTATION = register("village_reputation", VillageReputationCondition.CODEC);

    // v0.7.0 — named reputation tier gate over the same per-village reputation.
    public static final QuestConditionType<ReputationTierCondition> REPUTATION_TIER = register("reputation_tier", ReputationTierCondition.CODEC);

    // Distance gate for lead-style escorts and "out after dark" content (pair with time:NIGHT via any_of).
    public static final QuestConditionType<GiverDistanceFromVillageCondition> GIVER_DISTANCE_FROM_VILLAGE =
            register("giver_distance_from_village", GiverDistanceFromVillageCondition.CODEC);

    // The same gate widened to every village, not only the giver's own: vanilla and modded village
    // structures in #mcaquests:villages, MCA villages and Capitals capitals all count.
    public static final QuestConditionType<GiverDistanceFromAnyVillageCondition> GIVER_DISTANCE_FROM_ANY_VILLAGE =
            register("giver_distance_from_any_village", GiverDistanceFromAnyVillageCondition.CODEC);

    // 1.0.0 (§17) — read FTB Quests completion state. Registered always regardless of FTB Quests'
    // presence (zero FTB imports; evaluation goes through FtbqBridge.Holder), so datapacks referencing
    // them validate and load identically whether or not FTB Quests is installed.
    public static final QuestConditionType<FtbqQuestCompletedCondition> FTBQ_QUEST_COMPLETED =
            register("ftbq_quest_completed", FtbqQuestCompletedCondition.CODEC);
    public static final QuestConditionType<FtbqChapterCompletedCondition> FTBQ_CHAPTER_COMPLETED =
            register("ftbq_chapter_completed", FtbqChapterCompletedCondition.CODEC);
    public static final QuestConditionType<FtbqTaskCompletedCondition> FTBQ_TASK_COMPLETED =
            register("ftbq_task_completed", FtbqTaskCompletedCondition.CODEC);

    // 1.5.4 -- the mod-agnostic capability gate. Registered unconditionally for the same reason every
    // optional-mod condition above is: a pack must parse identically whether or not the mod is
    // installed, and an unknown provider id answers "not present" rather than failing the load.
    public static final QuestConditionType<CompatCapabilityCondition> COMPAT_CAPABILITY =
            register("compat_capability", CompatCapabilityCondition.CODEC);

    /**
     * Required tripwire for institutional definitions. Its unknown type makes pre-protocol providers
     * quarantine the whole quest instead of treating its paid content as an ordinary native quest.
     */
    public static final QuestConditionType<InstitutionalServiceAvailableCondition>
            INSTITUTIONAL_SERVICE_AVAILABLE = register("institutional_service_available",
            InstitutionalServiceAvailableCondition.CODEC);

    public static final QuestConditionType<KingdomStandingCondition> KINGDOM_STANDING =
            register(new ResourceLocation("ultima_kingdoms", "standing"), KingdomStandingCondition.CODEC);

    // 1.6.0 -- MCA Capitals. Registered unconditionally like every other optional-mod condition; each
    // one reads through the Capitals bridge, which answers "no capital here" when the mod is absent,
    // disabled, or only partially bound. Court content therefore parses on a Quests-only install and
    // simply never becomes eligible.
    public static final QuestConditionType<CapitalPresentCondition> CAPITAL_PRESENT =
            register("capital_present", CapitalPresentCondition.CODEC);
    public static final QuestConditionType<CapitalRoleCondition> CAPITAL_ROLE =
            register("capital_role", CapitalRoleCondition.CODEC);
    public static final QuestConditionType<CapitalAllegianceCondition> CAPITAL_ALLEGIANCE =
            register("capital_allegiance", CapitalAllegianceCondition.CODEC);
    public static final QuestConditionType<CapitalRelationCondition> CAPITAL_RELATION =
            register("capital_relation", CapitalRelationCondition.CODEC);
    public static final QuestConditionType<CapitalInterregnumCondition> CAPITAL_INTERREGNUM =
            register("capital_interregnum", CapitalInterregnumCondition.CODEC);

    public static final Codec<QuestConditionType<?>> TYPE_CODEC = ResourceLocation.CODEC.flatXmap(
            id -> {
                QuestConditionType<?> type = BY_ID.get(id);
                return type != null
                        ? DataResult.success(type)
                        : DataResult.error(() -> "Unknown condition type: " + id);
            },
            type -> DataResult.success(type.id()));

    // DFU 6.0.8 has no Codec.recursive, so CODEC is a lazy delegate that forwards to DELEGATE; the
    // composites reference CODEC for nested conditions. DELEGATE is built after CODEC exists.
    public static final Codec<QuestCondition> CODEC = new Codec<QuestCondition>() {
        @Override
        public <T> DataResult<Pair<QuestCondition, T>> decode(DynamicOps<T> ops, T input) {
            return DELEGATE.decode(ops, input);
        }

        @Override
        public <T> DataResult<T> encode(QuestCondition input, DynamicOps<T> ops, T prefix) {
            return DELEGATE.encode(input, ops, prefix);
        }
    };

    private static final Codec<QuestCondition> DELEGATE = buildConditionCodec();

    private static Codec<QuestCondition> buildConditionCodec() {
        Codec<QuestCondition> self = CODEC;
        Codec<QuestCondition> leaf = TYPE_CODEC.dispatch("type", QuestCondition::type, QuestConditionType::codec);
        Codec<QuestCondition> allOf = self.listOf().fieldOf("all_of").codec()
                .xmap(list -> (QuestCondition) new AllOfCondition(list), c -> ((AllOfCondition) c).conditions());
        Codec<QuestCondition> anyOf = self.listOf().fieldOf("any_of").codec()
                .xmap(list -> (QuestCondition) new AnyOfCondition(list), c -> ((AnyOfCondition) c).conditions());
        Codec<QuestCondition> not = self.fieldOf("not").codec()
                .xmap(cond -> (QuestCondition) new NotCondition(cond), c -> ((NotCondition) c).condition());

        return new Codec<QuestCondition>() {
            @Override
            public <T> DataResult<Pair<QuestCondition, T>> decode(DynamicOps<T> ops, T input) {
                // Composites first (each fails cleanly when its key is absent), then a leaf "type".
                DataResult<Pair<QuestCondition, T>> result = allOf.decode(ops, input);
                if (result.result().isPresent()) {
                    return result;
                }
                result = anyOf.decode(ops, input);
                if (result.result().isPresent()) {
                    return result;
                }
                result = not.decode(ops, input);
                if (result.result().isPresent()) {
                    return result;
                }
                return leaf.decode(ops, input);
            }

            @Override
            public <T> DataResult<T> encode(QuestCondition input, DynamicOps<T> ops, T prefix) {
                if (input instanceof AllOfCondition) {
                    return allOf.encode(input, ops, prefix);
                }
                if (input instanceof AnyOfCondition) {
                    return anyOf.encode(input, ops, prefix);
                }
                if (input instanceof NotCondition) {
                    return not.encode(input, ops, prefix);
                }
                return leaf.encode(input, ops, prefix);
            }
        };
    }

    private ConditionTypes() {
    }

    public static <T extends QuestCondition> QuestConditionType<T> register(ResourceLocation id, Codec<T> codec) {
        QuestConditionType<T> type = new QuestConditionType<>(id, codec);
        if (BY_ID.putIfAbsent(id, type) != null) {
            throw new IllegalArgumentException("Duplicate condition type id: " + id);
        }
        return type;
    }

    public static <T extends QuestCondition> QuestConditionType<T> register(String path, Codec<T> codec) {
        return register(new ResourceLocation(McaQuests.MOD_ID, path), codec);
    }

    public static boolean exists(ResourceLocation id) {
        return BY_ID.containsKey(id);
    }

    /** Evaluates an optional top-level condition tree (absent = always eligible). */
    public static boolean testAll(List<QuestCondition> conditions, QuestContext context) {
        return conditions.stream().allMatch(c -> c.test(context));
    }

    public static void bootstrap() {
    }
}
