package com.ultimakingdoms.interaction;

import com.google.gson.Gson;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.function.Consumer;
import static com.ultimakingdoms.interaction.ActionRegistry.*;

/** Authenticated, bounded form protocol. Internal identifiers never become editable GUI fields. */
@Mod.EventBusSubscriber(modid="ultima_kingdoms",bus=Mod.EventBusSubscriber.Bus.MOD)
public final class InteractionNetwork {
    public static final UUID NONE=new UUID(0,0);
    public record Request(UUID request,UUID session,UUID state,String operation,String value,String search,int page) {}
    public record Option(String key,String label,String detail,boolean selected) {}
    public record Reply(UUID request,UUID session,UUID state,String mode,String title,String detail,List<Option> options,String value,int page,boolean more,boolean back,boolean multi) {}
    private static final Gson JSON=new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    private static final Map<UUID,LinkedHashMap<UUID,Session>> RETAINED=new HashMap<>();
    private static final Map<UUID,ArrayDeque<Integer>> LAST_REQUEST=new HashMap<>();
    private static SimpleChannel channel;
    private static Consumer<Reply> receiver=r->{};
    private static final class Session {
        UUID id=UUID.randomUUID(),state=UUID.randomUUID(),startedRequest;Reply lastReply;Task task;int field;long expires;boolean executed;String version="",result="",search="";int page;
        final Map<String,String> values=new LinkedHashMap<>();final Map<String,Choice> selected=new LinkedHashMap<>();
        List<Choice> candidates=List.of();final Set<String> multi=new LinkedHashSet<>();
        Context context(ServerPlayer p){return new Context(p,values,selected);}
    }
    @SubscribeEvent public static void setup(FMLCommonSetupEvent event){event.enqueueWork(InteractionNetwork::init);}
    public static synchronized void init(){if(channel!=null)return;InteractionTasks.init();
        channel=NetworkRegistry.ChannelBuilder.named(new ResourceLocation("ultima_kingdoms","interactions")).networkProtocolVersion(()->"1").clientAcceptedVersions("1"::equals).serverAcceptedVersions("1"::equals).simpleChannel();
        channel.messageBuilder(Request.class,0,NetworkDirection.PLAY_TO_SERVER).encoder((q,b)->{b.writeUUID(q.request());b.writeUUID(q.session());b.writeUUID(q.state());b.writeUtf(q.operation(),24);b.writeUtf(q.value(),2048);b.writeUtf(q.search(),100);b.writeVarInt(q.page());})
            .decoder(b->new Request(b.readUUID(),b.readUUID(),b.readUUID(),b.readUtf(24),b.readUtf(2048),b.readUtf(100),b.readVarInt()))
            .consumerMainThread((q,ctx)->{var player=ctx.get().getSender();if(player!=null)channel.send(PacketDistributor.PLAYER.with(()->player),handleEnvelope(player,q));}).add();
        channel.messageBuilder(Reply.class,1,NetworkDirection.PLAY_TO_CLIENT).encoder((r,b)->b.writeUtf(encodeReply(r),60000)).decoder(b->{var r=JSON.fromJson(b.readUtf(60000),Reply.class);if(r.options()==null||r.options().size()>20)throw new IllegalArgumentException("Invalid form reply");return r;})
            .consumerMainThread((r,ctx)->receiver.accept(r)).add();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(Cleanup.class);
    }
    public static void receive(Consumer<Reply> value){receiver=Objects.requireNonNull(value);}
    public static void send(Request request){channel.sendToServer(request);}
    private static Reply handleEnvelope(ServerPlayer p,Request q){
        int now=p.getServer().getTickCount();var recent=LAST_REQUEST.computeIfAbsent(p.getUUID(),ignored->new ArrayDeque<>());
        while(!recent.isEmpty()&&now-recent.peekFirst()>=20)recent.removeFirst();
        if(recent.size()>=20){var s=SESSIONS.get(p.getUUID());return new Reply(q.request(),s==null?NONE:s.id,s==null?NONE:s.state,"error","Please wait","Please wait a moment, then check the same action or continue.",List.of(),"",0,false,s!=null,false);}
        recent.addLast(now);return handle(p,q);
    }
    public static Reply handle(ServerPlayer p,Request q){
        try {
            if(q.page()<0||q.page()>100000||!p.getServer().isSameThread())throw new IllegalArgumentException("Invalid page.");
            if(q.operation().equals("LIST"))return catalogue(p,q);
            Session s=q.operation().equals("TASK")?SESSIONS.get(p.getUUID()):RETAINED.getOrDefault(p.getUUID(),new LinkedHashMap<>()).get(q.session());
            if(q.operation().equals("TASK")){
                if(s!=null&&q.request().equals(s.startedRequest)&&s.lastReply!=null){task(s.task.id(),p);return copy(q,s.lastReply);}
                s=new Session();s.startedRequest=q.request();s.task=task(q.value(),p);SESSIONS.put(p.getUUID(),s);
                var retained=RETAINED.computeIfAbsent(p.getUUID(),ignored->new LinkedHashMap<>());retained.put(s.id,s);while(retained.size()>16)retained.remove(retained.keySet().iterator().next());
            }else if(q.operation().equals("CHECK")){
                s=RETAINED.getOrDefault(p.getUUID(),new LinkedHashMap<>()).get(q.session());
                if(s==null){var latest=SESSIONS.get(p.getUUID());if(latest!=null&&latest.startedRequest.toString().equals(q.value()))s=latest;}
                if(s==null)throw new IllegalArgumentException("No retained action was received. Inspect current status before starting again.");
                task(s.task.id(),p);if(s.executed)return result(q,s,"Task result",s.result);
                if(s.lastReply!=null)return copy(q,s.lastReply);
                throw new IllegalArgumentException("No reviewed action is available yet.");
            }
            else if(s==null||!s.id.equals(q.session())||s.expires<p.serverLevel().getGameTime())throw new IllegalArgumentException("This form expired. Open the task again.");
            s.expires=p.serverLevel().getGameTime()+6000;
            task(s.task.id(),p);
            if(s.executed)return result(q,s,"Task result",s.result);
            if(!q.operation().equals("TASK")&&!s.state.equals(q.state()))throw new IllegalArgumentException("The form changed. Review the current page before continuing.");
            task(s.task.id(),p); // Operator authority is checked again on every message.
            if(q.operation().equals("BACK")){if(s.field>0){s.field--;var f=s.task.fields().get(s.field);s.values.remove(f.key());s.selected.remove(f.key());s.multi.clear();}s.page=0;s.search="";}
            else if(q.operation().equals("FILTER")){s.page=q.page();s.search=q.search();}
            else if(q.operation().equals("PICK")){
                if(s.field>=s.task.fields().size())throw new IllegalArgumentException("Review the current task.");
                var f=s.task.fields().get(s.field);if(f.kind()!=Kind.CHOICE&&f.kind()!=Kind.MULTI)throw new IllegalArgumentException("This field is not a selection.");
                int index=Integer.parseInt(q.value());if(index<0||index>=s.candidates.size())throw new IllegalArgumentException("That choice changed. Refresh the form.");
                var choice=s.candidates.get(index);if(f.kind()==Kind.MULTI){if(!s.multi.add(choice.value()))s.multi.remove(choice.value());}
                else {s.values.put(f.key(),choice.value());s.selected.put(f.key(),choice);s.field++;s.page=0;s.search="";}
            }else if(q.operation().equals("NEXT")){
                if(s.field>=s.task.fields().size())throw new IllegalArgumentException("Review the task before applying.");var f=s.task.fields().get(s.field);
                if(f.kind()==Kind.MULTI){if(s.multi.isEmpty()&&!f.optional())throw new IllegalArgumentException("Select at least one choice.");s.values.put(f.key(),String.join(",",s.multi));s.multi.clear();}
                else {if(f.kind()==Kind.CHOICE)throw new IllegalArgumentException("Choose a named entry.");String value=q.value().strip();if(!f.optional()&&value.isBlank())throw new IllegalArgumentException("Enter "+f.label().toLowerCase(Locale.ROOT)+".");if(value.length()>512||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Please use at most 512 plain-text characters.");if(f.kind()==Kind.NUMBER)Long.parseLong(value);s.values.put(f.key(),value);}
                s.field++;s.page=0;s.search="";
            }else if(q.operation().equals("APPLY")){
                if(s.field!=s.task.fields().size())throw new IllegalArgumentException("Complete the form first.");
                validate(p,s);var c=s.context(p);if(!s.version.equals(s.task.version().apply(c)))throw new IllegalArgumentException("The record changed since your review. Open the task again and review its new state.");
                s.executed=true;try{var outcome=s.task.handler().run(c);if(outcome instanceof Pending pending){s.result=pending.message();Session retained=s;pending.completion().whenComplete((value,failure)->p.getServer().execute(()->retained.result=failure==null?PlayerWords.safe(value,c):"Could not complete: "+PlayerWords.safe(failure.getMessage(),c)));}else s.result=PlayerWords.safe(outcome,c);}catch(Exception failure){s.result="Could not complete: "+PlayerWords.safe(failure.getMessage(),c)+"\nCheck the current status before repeating a consequential action.";}
                return result(q,s,"Task result",s.result);
            }else if(!Set.of("TASK","BACK","FILTER","PICK","NEXT").contains(q.operation()))throw new IllegalArgumentException("Unknown form operation.");
            s.state=UUID.randomUUID();s.lastReply=form(p,q,s);return s.lastReply;
        }catch(Exception failure){var s=SESSIONS.get(p.getUUID());return new Reply(q.request(),s==null?NONE:s.id,s==null?NONE:s.state,"error","Could not continue",PlayerWords.safe(failure.getMessage(),new Context(p,Map.of(),Map.of())),List.of(),"",0,false,s!=null,false);}
    }
    private static void validate(ServerPlayer p,Session s){
        var values=new LinkedHashMap<String,String>();var selected=new LinkedHashMap<String,Choice>();
        for(var f:s.task.fields()){
            var c=new Context(p,values,selected);String value=s.values.getOrDefault(f.key(),"");
            if(f.kind()==Kind.CHOICE){var found=f.options().apply(c).stream().filter(o->o.value().equals(value)).findFirst().orElseThrow(()->new IllegalArgumentException("A selected person or record is no longer available."));
                if(!found.equals(s.selected.get(f.key())))throw new IllegalArgumentException("A selected record changed. Open the task again to review it.");selected.put(f.key(),found);
            }else if(f.kind()==Kind.MULTI){var permitted=f.options().apply(c).stream().map(Choice::value).toList();for(String v:value.isEmpty()&&f.optional()?new String[0]:value.split(","))if(!permitted.contains(v))throw new IllegalArgumentException("An available choice changed.");}
            values.put(f.key(),value);
        }
    }
    private static Reply form(ServerPlayer p,Request q,Session s){
        var c=s.context(p);
        if(s.field==s.task.fields().size()){
            validate(p,s);s.version=s.task.version().apply(c);var lines=new ArrayList<String>();lines.add(s.task.help());
            for(var f:s.task.fields()){var chosen=s.selected.get(f.key());String value=chosen==null?s.values.get(f.key()):chosen.label();if(f.kind()==Kind.MULTI)value=String.join(", ",f.options().apply(c).stream().filter(o->Arrays.asList(s.values.get(f.key()).split(",")).contains(o.value())).map(Choice::label).toList());lines.add(f.label()+": "+value);if(chosen!=null){if(!chosen.detail().isBlank())lines.add(chosen.detail());String location=entityLocation(p,chosen);if(!location.isBlank())lines.add(location);}}
            lines.add(s.task.consequential()?"Review the people, terms and consequences above. Apply performs this action once; backing out changes nothing.":"Ready. Continue to perform this task.");
            return new Reply(q.request(),s.id,s.state,"review",s.task.title(),PlayerWords.safe(lines,c),List.of(),"",0,false,s.field>0,false);
        }
        var f=s.task.fields().get(s.field);boolean selection=f.kind()==Kind.CHOICE||f.kind()==Kind.MULTI;
        if(!selection)return new Reply(q.request(),s.id,s.state,"text",f.label(),s.task.title()+"\n"+f.help(),List.of(),s.values.getOrDefault(f.key(),f.initial()),0,false,s.field>0,false);
        s.candidates=List.copyOf(f.options().apply(c));var options=new ArrayList<Option>();String search=s.search.toLowerCase(Locale.ROOT);int matched=0,start=s.page*20;
        for(int i=0;i<s.candidates.size();i++){var option=s.candidates.get(i);if(!(option.label()+" "+option.detail()).toLowerCase(Locale.ROOT).contains(search))continue;if(matched>=start&&options.size()<20)options.add(new Option(Integer.toString(i),bounded(PlayerWords.safe(option.label(),c),256),bounded(PlayerWords.safe(option.detail()+entityLocation(p,option),c),1024),s.multi.contains(option.value())));matched++;}
        String help=s.task.title()+"\n"+f.help();if(s.candidates.isEmpty())help+="\nNo eligible known choices. Check your location, permissions, prerequisites and installed integrations.";
        return new Reply(q.request(),s.id,s.state,"choice",f.label(),help,options,s.search,s.page,matched>start+20,s.field>0,f.kind()==Kind.MULTI);
    }
    private static Reply catalogue(ServerPlayer p,Request q){var tasks=tasks(p).stream().filter(t->(t.category()+" "+t.title()+" "+t.help()).toLowerCase(Locale.ROOT).contains(q.search().toLowerCase(Locale.ROOT))).toList();int start=Math.min(tasks.size(),q.page()*20);var options=tasks.subList(start,Math.min(start+20,tasks.size())).stream().map(t->new Option(t.id(),t.title(),t.category()+" · "+t.help(),false)).toList();return new Reply(q.request(),NONE,NONE,"tasks","Kingdoms","Choose a task. Search by what you want to do. No commands are entered or sent.",options,q.search(),q.page(),start+20<tasks.size(),false,false);}
    private static String entityLocation(ServerPlayer player,Choice choice){
        try{var entity=player.serverLevel().getEntity(UUID.fromString(choice.value()));
            if(entity instanceof net.minecraft.world.entity.LivingEntity&&entity.distanceToSqr(player)<=32*32&&player.hasLineOfSight(entity))return "\nCurrent location: "+entity.blockPosition().toShortString();
        }catch(IllegalArgumentException ignored){}return "";
    }
    public static String encodeReply(Reply reply){
        String encoded=JSON.toJson(reply);if(encoded.length()<=60000)return encoded;
        return JSON.toJson(new Reply(reply.request(),reply.session(),reply.state(),"error","Details too long","These details exceed the display limit. Narrow your search or ask an operator to shorten the record text before continuing.",List.of(),"",0,false,reply.back(),false));
    }
    private static String bounded(String text,int limit){return text.length()>limit?text.substring(0,limit-1)+"…":text;}
    private static Reply copy(Request q,Reply r){return new Reply(q.request(),r.session(),r.state(),r.mode(),r.title(),r.detail(),r.options(),r.value(),r.page(),r.more(),r.back(),r.multi());}
    private static Reply result(Request q,Session s,String title,String detail){return new Reply(q.request(),s.id,s.state,"result",title,detail,List.of(),"",0,false,false,false);}
    public static final class Cleanup {
        @SubscribeEvent public static void logout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){SESSIONS.remove(e.getEntity().getUUID());RETAINED.remove(e.getEntity().getUUID());LAST_REQUEST.remove(e.getEntity().getUUID());}
        @SubscribeEvent public static void stopped(net.minecraftforge.event.server.ServerStoppedEvent e){SESSIONS.clear();RETAINED.clear();LAST_REQUEST.clear();}
    }
    private InteractionNetwork(){}
}
