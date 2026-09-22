package com.ultimakingdoms.warfare;

import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.compat.recruits.RecruitsMilitary;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.eventbus.api.*;
import java.util.*;

/** Server attribution for target selection and damage, including provider-owned spells/projectiles. */
public final class MilitaryTargetPolicy {
    private MilitaryTargetPolicy() { }
    public static Entity principal(Entity original) {
        Entity current = original; var seen = new HashSet<UUID>();
        for (int depth = 0; current != null && depth < 4 && seen.add(current.getUUID()); depth++) {
            Entity next = null;
            if (current instanceof Projectile projectile) next = projectile.getOwner();
            else if (current instanceof OwnableEntity ownable) {
                next = ownable.getOwner();
                if (next == null && ownable.getOwnerUUID() != null && current.level() instanceof ServerLevel level) {
                    next = level.getEntity(ownable.getOwnerUUID());
                    if (next == null) next = level.getServer().getPlayerList().getPlayer(ownable.getOwnerUUID());
                }
            }
            else if (current instanceof AreaEffectCloud cloud) next = cloud.getOwner();
            else if (current instanceof AbstractHorse horse && horse.getOwnerUUID() != null && current.level() instanceof ServerLevel level)
                { next = level.getEntity(horse.getOwnerUUID());
                    if (next == null) next = level.getServer().getPlayerList().getPlayer(horse.getOwnerUUID()); }
            if (next == null && current.level() instanceof ServerLevel level) {
                // Read public provider ownership, never arbitrary NBT or client-supplied attribution.
                for (String getter : List.of("getOwner", "getOwnerUUID", "getSummoner", "getSummonerUUID")) {
                    try {
                        var method = current.getClass().getMethod(getter);
                        if (method.getParameterCount() != 0) continue;
                        Object value = method.invoke(current);
                        if (value instanceof Entity entity) next = entity;
                        else if (value instanceof UUID id) {
                            next = level.getEntity(id);
                            if (next == null) next = level.getServer().getPlayerList().getPlayer(id);
                        }
                        if (next != null) break;
                    } catch (ReflectiveOperationException | RuntimeException ignored) { }
                }
            }
            if (next == null || next == current || next.level() != original.level()) return current;
            current = next;
        }
        return current == null ? original : current;
    }
    private static String faction(Entity entity) { return entity == null || entity.getTeam() == null ? "" : entity.getTeam().getName(); }
    private static boolean recruit(Entity entity) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("com.talhanation.recruits.entities.AbstractRecruitEntity")) return true;
        return false;
    }
    private static boolean civilian(Entity entity) {
        if (entity instanceof Player || entity instanceof AbstractVillager) return true;
        var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return key != null && key.getNamespace().equals("mca") && (key.getPath().equals("male_villager") || key.getPath().equals("female_villager"));
    }
    public static boolean allowed(Entity attacker, LivingEntity target) {
        if (!WarfareConfig.ENABLED.get() || !WarfareConfig.TARGET_POLICY.get() || attacker == null || !(target.level() instanceof ServerLevel level)
                || attacker.level() != target.level()) return true;
        Entity source = principal(attacker), victim = principal(target);
        String a = faction(source), b = faction(victim);
        if (a.isEmpty()) a = faction(attacker);
        if (b.isEmpty()) b = faction(target);
        boolean army = recruit(attacker) || !a.isEmpty() && WarfareRuntime.get(level.getServer()).mapping(a).isPresent();
        if (!army) return true;
        if (source == victim || source.getUUID().equals(victim.getUUID())) return false;
        if (!a.isEmpty() && a.equals(b)) return false;
        // Civilians retain local law even if a provider puts their household on an enemy team.
        if (civilian(victim) && !(victim instanceof Player)) return legalEnforcement(attacker, target);
        if (victim instanceof net.minecraft.server.level.ServerPlayer player) {
            var residence = UltimaKingdomsApi.get(level.getServer()).getSettlementAt(level, target.blockPosition());
            if (residence.filter(v -> CampaignService.get(level.getServer()).safeConduct(player, v.id())).isPresent())
                return legalEnforcement(attacker, target);
        }
        try {
            if (!a.isEmpty() && !b.isEmpty() && RecruitsMilitary.faction(level.getServer(), a).isPresent() && RecruitsMilitary.faction(level.getServer(), b).isPresent()) {
                var ab = RecruitsMilitary.relation(level.getServer(), a, b);
                var ba = RecruitsMilitary.relation(level.getServer(), b, a);
                if (ab == CampaignState.Relation.ALLY || ba == CampaignState.Relation.ALLY) return false;
                if (ab == CampaignState.Relation.ENEMY) return true;
            }
        } catch (IllegalArgumentException absent) { }
        var settlement = UltimaKingdomsApi.get(level.getServer()).getSettlementAt(level, target.blockPosition());
        if (civilian(victim) && (recruit(attacker) || settlement.filter(s -> WarfareRuntime.get(level.getServer()).binding(s.id()).isPresent()).isPresent()))
            return legalEnforcement(attacker, target);
        // Neutral companions are protected from an army's unprovoked target acquisition.
        if (victim != target && target instanceof Mob mob && mob.getTarget() != attacker && mob.getTarget() != source) return false;
        return true;
    }
    private static boolean legalEnforcement(Entity responder, LivingEntity target) {
        if (!(responder instanceof LivingEntity living)) return false;
        try {
            return (boolean)Class.forName("dev.otectus.mcacrime.api.JurisdictionPolicyApi")
                    .getMethod("mayEnforce", LivingEntity.class, LivingEntity.class).invoke(null, living, target);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return false; }
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void target(LivingChangeTargetEvent event) {
        if (event.getNewTarget() != null && !allowed(event.getEntity(), event.getNewTarget())) event.setNewTarget(null);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void attack(LivingAttackEvent event) {
        Entity attacker = event.getSource().getEntity();
        if (attacker == null) attacker = event.getSource().getDirectEntity();
        if (!allowed(attacker, event.getEntity())) event.setCanceled(true);
    }
}
