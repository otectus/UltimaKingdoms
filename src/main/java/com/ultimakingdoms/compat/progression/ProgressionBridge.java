package com.ultimakingdoms.compat.progression;

import com.ultimakingdoms.api.progression.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.Method;
import java.util.*;

/** Exact installed provider surfaces, no optional provider types in signatures or constant-pool class links. */
public final class ProgressionBridge {
    private record Binding(String mod,String version,Method first,Method second,Method third) { }
    private static final Map<ProgressionPredicate.Kind,Binding> BINDINGS=new EnumMap<>(ProgressionPredicate.Kind.class);
    private static final Map<ProgressionPredicate.Kind,ProgressionResult.Status> HEALTH=new EnumMap<>(ProgressionPredicate.Kind.class);
    private ProgressionBridge() { }
    public static void register() {
        bind(ProgressionPredicate.Kind.SKILL_LEVEL,"runicskills","2.2.1",()->{
            var cap=Class.forName("com.otectus.runicskills.common.capability.SkillCapability");
            var skills=Class.forName("com.otectus.runicskills.registry.RegistrySkills");
            var skill=Class.forName("com.otectus.runicskills.registry.skill.Skill");
            return new Method[]{cap.getMethod("get",Player.class),skills.getMethod("getSkill",String.class),cap.getMethod("getSkillLevel",skill)};
        });
        bind(ProgressionPredicate.Kind.DEITY,"runic_gods","0.2.1",()->new Method[]{Class.forName("com.otectus.runic_gods.compat.RunicGodsAPI").getMethod("getPlayerGodId",ServerPlayer.class),null,null});
        bind(ProgressionPredicate.Kind.RACE,"runic_races","1.7.1",()->new Method[]{Class.forName("com.otectus.runic_races.util.RaceHelper").getMethod("getRaceId",Player.class),null,null});
        bind(ProgressionPredicate.Kind.LORE_COLLECTED,"rpg_lore","2.2.0",()->{
            var type=Class.forName("com.rpglore.codex.CodexTrackingData");
            return new Method[]{type.getMethod("getInstance"),type.getMethod("hasBook",UUID.class,String.class),null};
        },"2.2.1");
        ProgressionApi.register(ProgressionBridge::evaluate);
    }
    @FunctionalInterface private interface Probe { Method[] methods() throws ReflectiveOperationException; }
    private static void bind(ProgressionPredicate.Kind kind,String mod,String version,Probe probe,String... compatibleVersions) {
        var container=ModList.get().getModContainerById(mod);
        if(container.isEmpty()){HEALTH.put(kind,ProgressionResult.Status.ABSENT);return;}
        String installed=container.get().getModInfo().getVersion().toString();
        if(!installed.equals(version)&&Arrays.stream(compatibleVersions).noneMatch(installed::equals)){HEALTH.put(kind,ProgressionResult.Status.UNSUPPORTED);return;}
        try {var methods=probe.methods();BINDINGS.put(kind,new Binding(mod,installed,methods[0],methods[1],methods[2]));HEALTH.put(kind,ProgressionResult.Status.AVAILABLE);}
        catch(ReflectiveOperationException|LinkageError failure){HEALTH.put(kind,ProgressionResult.Status.UNSUPPORTED);}
    }
    private static ProgressionResult evaluate(ServerPlayer player,ProgressionPredicate predicate) {
        var status=HEALTH.getOrDefault(predicate.kind(),ProgressionResult.Status.UNAVAILABLE);
        if(status!=ProgressionResult.Status.AVAILABLE)return new ProgressionResult(status,false,"progression.provider_"+status.name().toLowerCase(Locale.ROOT));
        var b=BINDINGS.get(predicate.kind());
        try {
            boolean matches;
            switch(predicate.kind()) {
                case SKILL_LEVEL -> {
                    var cap=b.first.invoke(null,player);var skill=b.second.invoke(null,predicate.subject());
                    if(cap==null||skill==null)return unavailable("progression.skill_unavailable");
                    matches=((Number)b.third.invoke(cap,skill)).intValue()>=predicate.minimum();
                }
                case DEITY -> matches=Objects.equals(predicate.subject(),b.first.invoke(null,player));
                case RACE -> matches=((Optional<?>)b.first.invoke(null,player)).map(Object::toString).filter(predicate.subject()::equals).isPresent();
                case LORE_COLLECTED -> {
                    var codex=b.first.invoke(null);if(codex==null)return unavailable("progression.codex_unavailable");
                    matches=(boolean)b.second.invoke(codex,player.getUUID(),predicate.subject());
                }
                default -> throw new IllegalStateException("Unhandled predicate");
            }
            return new ProgressionResult(status,matches,matches?"progression.matches":"progression.requirement_not_met");
        }catch(ReflectiveOperationException|LinkageError|RuntimeException failure){return unavailable("progression.read_failed");}
    }
    private static ProgressionResult unavailable(String reason){return new ProgressionResult(ProgressionResult.Status.UNAVAILABLE,false,reason);}
}
