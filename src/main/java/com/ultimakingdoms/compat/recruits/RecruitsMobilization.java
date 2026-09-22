package com.ultimakingdoms.compat.recruits;

import com.ultimakingdoms.warfare.mobilization.MobilizationDoctrine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.*;
import java.util.*;

/** Exact Recruits 1.15.2 order adapter. Ownership, group, inventory and equipment are never written. */
public final class RecruitsMobilization {
    private static final String TYPE="com.talhanation.recruits.entities.AbstractRecruitEntity";
    public record Orders(UUID unit,UUID owner,UUID group,int followState,boolean listen,
                         boolean follow,boolean hold,boolean move,boolean protect,boolean mount,
                         boolean block,boolean rest,boolean ranged,double holdX,double holdY,double holdZ,
                         int moveX,int moveY,int moveZ,UUID protectId) { }
    private RecruitsMobilization(){ }
    public static boolean isRecruit(Entity entity){try{return Class.forName(TYPE,false,RecruitsMobilization.class.getClassLoader()).isInstance(entity);}catch(ClassNotFoundException|LinkageError unavailable){return false;}}
    public static List<Entity> nearbyOwned(ServerPlayer actor,int limit){
        RecruitsMilitary.ready(actor.getServer());Class<?> type=type();return actor.serverLevel().getEntities(actor,actor.getBoundingBox().inflate(32),
                e->type.isInstance(e)&&ownedBy(e,actor)).stream().sorted(Comparator.comparingDouble((Entity entity)->actor.distanceToSqr(entity)).thenComparing(Entity::getUUID)).limit(limit).toList();
    }
    public static boolean ownedBy(Entity unit,ServerPlayer actor){try{return type().isInstance(unit)&&(boolean)call(unit,"isOwned")
                &&actor.getUUID().equals(call(unit,"getOwnerUUID"));}catch(RuntimeException failure){return false;}}
    public static Orders snapshot(Entity entity){
        if(!type().isInstance(entity))throw new IllegalArgumentException("Unit is not a supported recruit.");
        UUID owner=(UUID)call(entity,"getOwnerUUID");UUID group=(UUID)call(entity,"getGroup");Vec3 hold=(Vec3)call(entity,"getHoldPos");BlockPos move=(BlockPos)call(entity,"getMovePos");
        return new Orders(entity.getUUID(),owner,group,(int)call(entity,"getFollowState"),(boolean)call(entity,"getListen"),
                bool(entity,"getShouldFollow"),bool(entity,"getShouldHoldPos"),bool(entity,"getShouldMovePos"),bool(entity,"getShouldProtect"),
                bool(entity,"getShouldMount"),bool(entity,"getShouldBlock"),bool(entity,"getShouldRest"),bool(entity,"getShouldRanged"),
                hold==null?0:hold.x,hold==null?0:hold.y,hold==null?0:hold.z,move==null?0:move.getX(),move==null?0:move.getY(),move==null?0:move.getZ(),(UUID)call(entity,"getProtectUUID"));
    }
    public static Orders planned(Entity unit,ServerPlayer actor,MobilizationDoctrine doctrine){
        Orders before=snapshot(unit);Vec3 hold=unit.position();BlockPos scout=BlockPos.containing(actor.getEyePosition().add(actor.getLookAngle().scale(24)));
        return switch(doctrine){
            case DEFENSE->new Orders(before.unit(),before.owner(),before.group(),before.followState(),true,false,true,false,false,false,true,false,before.ranged(),hold.x,hold.y,hold.z,0,0,0,null);
            case ESCORT->new Orders(before.unit(),before.owner(),before.group(),before.followState(),true,true,false,false,true,false,true,false,before.ranged(),0,0,0,0,0,0,actor.getUUID());
            case SCOUT->new Orders(before.unit(),before.owner(),before.group(),before.followState(),true,false,false,true,false,false,false,false,before.ranged(),0,0,0,scout.getX(),scout.getY(),scout.getZ(),null);
        };
    }
    public static boolean apply(Entity entity,Orders orders){
        if(!entity.getUUID().equals(orders.unit()))return false;write(entity,orders);return snapshot(entity).equals(orders);
    }
    public static boolean identity(Entity entity,Orders orders){
        try{return entity.getUUID().equals(orders.unit())&&Objects.equals(call(entity,"getOwnerUUID"),orders.owner())
                &&Objects.equals(call(entity,"getGroup"),orders.group())&&(boolean)call(entity,"isOwned");}catch(RuntimeException failure){return false;}
    }
    private static void write(Entity entity,Orders o){
        call(entity,"setFollowState",new Class<?>[]{int.class},o.followState());call(entity,"setListen",new Class<?>[]{boolean.class},o.listen());
        call(entity,"setShouldMount",new Class<?>[]{boolean.class},o.mount());call(entity,"setShouldBlock",new Class<?>[]{boolean.class},o.block());
        call(entity,"setShouldRest",new Class<?>[]{boolean.class},o.rest());call(entity,"setShouldRanged",new Class<?>[]{boolean.class},o.ranged());
        call(entity,"setHoldPos",new Class<?>[]{Vec3.class},new Vec3(o.holdX(),o.holdY(),o.holdZ()));
        call(entity,"setMovePos",new Class<?>[]{BlockPos.class},new BlockPos(o.moveX(),o.moveY(),o.moveZ()));
        call(entity,"setProtectUUID",new Class<?>[]{Optional.class},Optional.ofNullable(o.protectId()));
        call(entity,"setShouldFollow",new Class<?>[]{boolean.class},o.follow());call(entity,"setShouldHoldPos",new Class<?>[]{boolean.class},o.hold());
        call(entity,"setShouldMovePos",new Class<?>[]{boolean.class},o.move());call(entity,"setShouldProtect",new Class<?>[]{boolean.class},o.protect());
    }
    private static boolean bool(Object target,String method){return (boolean)call(target,method);}
    private static Class<?> type(){try{return Class.forName(TYPE,false,RecruitsMobilization.class.getClassLoader());}catch(ClassNotFoundException|LinkageError failure){throw new IllegalArgumentException("Supported Recruits unit API unavailable.",failure);}}
    private static Object call(Object target,String name){return call(target,name,new Class<?>[0]);}
    private static Object call(Object target,String name,Class<?>[] types,Object...args){try{return target.getClass().getMethod(name,types).invoke(target,args);}
        catch(ReflectiveOperationException|LinkageError failure){throw new IllegalArgumentException("Recruits order API unavailable: "+name,failure);}}
}
