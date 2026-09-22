package dev.otectus.mcaquests.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcaquests.data.StrictCodecs;
import dev.otectus.mcaquests.quest.condition.ConditionTypes;
import dev.otectus.mcaquests.quest.condition.HistoryScope;
import dev.otectus.mcaquests.quest.condition.QuestCondition;
import dev.otectus.mcaquests.quest.condition.QuestContext;
import dev.otectus.mcaquests.quest.condition.composite.AllOfCondition;
import dev.otectus.mcaquests.quest.condition.composite.NotCondition;
import dev.otectus.mcaquests.quest.condition.leaf.FtbqQuestCompletedCondition;
import dev.otectus.mcaquests.quest.condition.leaf.FtbqWhenMissing;
import dev.otectus.mcaquests.quest.condition.leaf.QuestCompletedCondition;
import dev.otectus.mcaquests.quest.objective.FtbqCompleteQuestObjective;
import dev.otectus.mcaquests.quest.objective.ObjectiveTypes;
import dev.otectus.mcaquests.quest.objective.QuestObjective;
import dev.otectus.mcaquests.quest.reward.QuestReward;
import dev.otectus.mcaquests.quest.reward.RewardTypes;
import dev.otectus.mcaquests.quest.template.PlaceholderResolver;
import dev.otectus.mcaquests.quest.template.TemplateSpec;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ExtraCodecs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An immutable, data-loaded quest template (spec section 11). Parsed from
 * {@code data/<ns>/mcaquests/quests/**.json} by {@code QuestDataLoader}.
 */
public record QuestDefinition(
        ResourceLocation id,
        boolean enabled,
        int weight,
        Optional<String> category,
        Optional<QuestText> titleOverride,
        RepeatRule repeat,
        GiverSpec giver,
        Map<String, QuestText> dialogue,
        List<QuestObjective> objectives,
        List<QuestReward> rewards,
        TurnInSpec turnIn,
        Optional<QuestCondition> conditions,
        Optional<ChainSpec> chain,
        Optional<FailureSpec> failure,
        Optional<TemplateSpec> template,
        OfferShaping offerShaping,
        dev.otectus.mcaquests.quest.reputation.QuestReputationBlock reputation,
        Optional<dev.otectus.mcaquests.quest.kingdom.KingdomLifecycleSpec> kingdomLifecycle,
        boolean institutionalCommission) {

    /** Source-compatible shape from before institutional commissions were introduced. */
    public QuestDefinition(ResourceLocation id, boolean enabled, int weight, Optional<String> category,
                           Optional<QuestText> titleOverride, RepeatRule repeat, GiverSpec giver,
                           Map<String, QuestText> dialogue, List<QuestObjective> objectives,
                           List<QuestReward> rewards, TurnInSpec turnIn, Optional<QuestCondition> conditions,
                           Optional<ChainSpec> chain, Optional<FailureSpec> failure,
                           Optional<TemplateSpec> template, OfferShaping offerShaping,
                           dev.otectus.mcaquests.quest.reputation.QuestReputationBlock reputation,
                           Optional<dev.otectus.mcaquests.quest.kingdom.KingdomLifecycleSpec> kingdomLifecycle) {
        this(id, enabled, weight, category, titleOverride, repeat, giver, dialogue, objectives, rewards,
                turnIn, conditions, chain, failure, template, offerShaping, reputation, kingdomLifecycle, false);
    }

    /** Source-compatible shape for add-ons and tests written before kingdom lifecycle metadata. */
    public QuestDefinition(ResourceLocation id, boolean enabled, int weight, Optional<String> category,
                           Optional<QuestText> titleOverride, RepeatRule repeat, GiverSpec giver,
                           Map<String, QuestText> dialogue, List<QuestObjective> objectives,
                           List<QuestReward> rewards, TurnInSpec turnIn, Optional<QuestCondition> conditions,
                           Optional<ChainSpec> chain, Optional<FailureSpec> failure,
                           Optional<TemplateSpec> template, OfferShaping offerShaping,
                           dev.otectus.mcaquests.quest.reputation.QuestReputationBlock reputation) {
        this(id, enabled, weight, category, titleOverride, repeat, giver, dialogue, objectives, rewards,
                turnIn, conditions, chain, failure, template, offerShaping, reputation, Optional.empty(), false);
    }

    /** Dialogue states (spec section 9). */
    public static final String OFFER = "offer";
    public static final String ACCEPT = "accept";
    public static final String DECLINE = "decline";
    public static final String IN_PROGRESS = "in_progress";
    public static final String READY = "ready";
    public static final String COMPLETE = "complete";
    public static final String COOLDOWN = "cooldown";
    public static final String LOCKED = "locked";
    public static final String FAILED = "failed";

    public static final Codec<QuestDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(QuestDefinition::id),
            StrictCodecs.strictOptional(Codec.BOOL, "enabled", true).forGetter(QuestDefinition::enabled),
            StrictCodecs.strictOptional(ExtraCodecs.POSITIVE_INT, "weight", 1).forGetter(QuestDefinition::weight),
            StrictCodecs.strictOptional(Codec.STRING, "category").forGetter(QuestDefinition::category),
            StrictCodecs.strictOptional(QuestText.CODEC, "title").forGetter(QuestDefinition::titleOverride),
            StrictCodecs.strictOptional(RepeatRule.CODEC, "repeat", RepeatRule.DEFAULT).forGetter(QuestDefinition::repeat),
            GiverSpec.CODEC.fieldOf("giver").forGetter(QuestDefinition::giver),
            Codec.unboundedMap(Codec.STRING, QuestText.CODEC).fieldOf("dialogue").forGetter(QuestDefinition::dialogue),
            StrictCodecs.strictOptional(ObjectiveTypes.CODEC.listOf(), "objectives", List.of())
                    .forGetter(QuestDefinition::objectives),
            StrictCodecs.strictOptional(RewardTypes.CODEC.listOf(), "rewards", List.of())
                    .forGetter(QuestDefinition::rewards),
            StrictCodecs.strictOptional(TurnInSpec.CODEC, "turn_in", TurnInSpec.DEFAULT).forGetter(QuestDefinition::turnIn),
            StrictCodecs.strictOptional(ConditionTypes.CODEC, "conditions").forGetter(QuestDefinition::conditions),
            StrictCodecs.strictOptional(ChainSpec.CODEC, "chain").forGetter(QuestDefinition::chain),
            StrictCodecs.strictOptional(FailureSpec.CODEC, "failure").forGetter(QuestDefinition::failure),
            StrictCodecs.strictOptional(TemplateSpec.CODEC, "template").forGetter(QuestDefinition::template),
            // DataFixerUpper's RecordCodecBuilder tops out at 16 grouped fields, and this definition
            // was already at 16. Rather than restructure the whole quest format to add one optional
            // block, the last two are read as a pair — the JSON shape is unchanged, since a MapCodec
            // pair still reads both fields from the same object.
            Codec.mapPair(
                            OfferShaping.MAP_CODEC,
                            Codec.mapPair(
                                    StrictCodecs.strictOptional(dev.otectus.mcaquests.quest.reputation.QuestReputationBlock.CODEC, "reputation",
                                                    dev.otectus.mcaquests.quest.reputation.QuestReputationBlock.NONE),
                                    Codec.mapPair(
                                            StrictCodecs.strictOptional(dev.otectus.mcaquests.quest.kingdom.KingdomLifecycleSpec.CODEC,
                                                    "kingdom_lifecycle"),
                                            StrictCodecs.strictOptional(Codec.BOOL, "institutional_commission", false))))
                    .forGetter(def -> com.mojang.datafixers.util.Pair.of(def.offerShaping(),
                            com.mojang.datafixers.util.Pair.of(def.reputation(),
                                    com.mojang.datafixers.util.Pair.of(def.kingdomLifecycle(),
                                            def.institutionalCommission()))))
    ).apply(instance, (id, enabled, weight, category, title, repeat, giver, dialogue, objectives, rewards,
                       turnIn, conditions, chain, failure, template, tail) ->
            new QuestDefinition(id, enabled, weight, category, title, repeat, giver, dialogue, objectives,
                    rewards, turnIn, conditions, chain, failure, template, tail.getFirst(),
                    tail.getSecond().getFirst(), tail.getSecond().getSecond().getFirst(),
                    tail.getSecond().getSecond().getSecond())));

    /** Translation key for this quest's display title (spec section 32), e.g. {@code mcaquests.quest.<path>.title}. */
    public String titleKey() {
        return "mcaquests.quest." + id.getPath() + ".title";
    }

    /**
     * The display title, resolving inline {@code {token}} placeholders (e.g. {@code {player}} and template
     * variables) through {@code resolver}.
     */
    public Component title(PlaceholderResolver resolver) {
        return titleOverride.map(text -> text.resolve(resolver)).orElseGet(() -> Component.translatable(titleKey()));
    }

    /**
     * Resolves the dialogue line for {@code state} (filling {@code {token}} placeholders via
     * {@code resolver}), or {@code fallback} if the quest omits that state.
     */
    public Component dialogueOr(String state, Component fallback, PlaceholderResolver resolver) {
        QuestText line = dialogue.get(state);
        if (line == null) {
            return fallback;
        }
        return line.resolve(resolver);
    }

    public boolean isTemplate() {
        return template.isPresent();
    }

    /**
     * A copy of this definition with its objectives/rewards replaced by the concrete ones produced from
     * a resolved template. All other fields (giver, conditions, chain, turn-in, dialogue, …) are
     * preserved, so the rest of the lifecycle treats a concretized template exactly like a normal quest.
     */
    public QuestDefinition withConcrete(TemplateSpec.Concrete concrete) {
        return new QuestDefinition(id, enabled, weight, category, titleOverride, repeat, giver, dialogue,
                concrete.objectives(), concrete.rewards(), turnIn, conditions, chain, failure, template,
                offerShaping, reputation, kingdomLifecycle, institutionalCommission);
    }

    /** Datapack offer priority tier, if set (see {@link OfferShaping}). */
    public Optional<Integer> priority() {
        return offerShaping.priority();
    }

    /**
     * The declared difficulty band, if any (see {@link OfferShaping}). A quest that omits it keeps its
     * explicit reward amounts untouched — difficulty only supplies defaults for rewards that ask for them,
     * so third-party packs written before 1.1.0 behave exactly as they did.
     */
    public Optional<QuestDifficulty> difficulty() {
        return offerShaping.difficulty();
    }

    /**
     * The offer group this quest belongs to, if any (see {@link OfferShaping}). Used only by offer
     * selection, so it never crosses the wire.
     */
    public Optional<String> offerGroup() {
        return offerShaping.offerGroup();
    }

    /** Conditional selection-weight bonuses (see {@link OfferShaping}). */
    public List<WeightBonus> weightBonus() {
        return offerShaping.weightBonus();
    }

    /**
     * The selection weight for this offer in {@code context}: the base {@link #weight()} plus every
     * {@link WeightBonus} whose condition holds, clamped to at least 1 (mirrors {@code WeightedPicker},
     * which treats sub-1 weights as 1). Identical to {@code weight()} when no {@code weight_bonus} is set.
     */
    public int effectiveWeight(QuestContext context) {
        long total = weight;
        for (WeightBonus bonus : weightBonus()) {
            total += bonus.evaluate(context);
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, total));
    }

    public int cooldownTicks() {
        return repeat.cooldownTicks();
    }

    /**
     * The condition gate actually used for offer eligibility: the author's {@code conditions} AND a
     * {@code quest_completed} requirement for each chain {@code prerequisite} AND a
     * {@code not(ftbq_quest_completed)} guard for each {@code ftbq_complete_quest} objective whose
     * {@code already_complete} is {@code block_offer} (spec §18). This is the single desugaring point
     * for both — they reuse the existing condition system, so offer filtering needs no chain- or
     * FTBQ-specific logic anywhere else.
     */
    public Optional<QuestCondition> effectiveConditions() {
        List<QuestCondition> extra = new ArrayList<>();
        for (ResourceLocation prerequisite : chain.map(ChainSpec::prerequisites).orElse(List.of())) {
            // GIVER scope: a chain stage is gated on what the player did with THIS villager, so the same
            // arc can be lived out independently with different villagers (per-villager relationship arcs).
            extra.add(new QuestCompletedCondition(prerequisite, HistoryScope.GIVER));
        }
        for (QuestObjective objective : objectives) {
            if (objective instanceof FtbqCompleteQuestObjective ftbq && ftbq.alreadyComplete().blocksOffer()) {
                // NOT_MET: an absent/failed bridge must not hide the offer — only a positively-confirmed
                // FTB completion should (mirrors FtbqQuestCompletedCondition's own default policy).
                extra.add(new NotCondition(new FtbqQuestCompletedCondition(ftbq.quest(), FtbqWhenMissing.NOT_MET)));
            }
        }
        if (extra.isEmpty()) {
            return conditions;
        }
        List<QuestCondition> all = new ArrayList<>();
        conditions.ifPresent(all::add);
        all.addAll(extra);
        return Optional.of(new AllOfCondition(all));
    }
}
