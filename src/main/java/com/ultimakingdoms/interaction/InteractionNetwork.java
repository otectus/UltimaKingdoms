package com.ultimakingdoms.interaction;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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

/**
 * Authenticated, bounded form protocol. Internal identifiers never become editable GUI fields.
 * Titles, details and option text travel as serialized {@link Component}s so the client renders them in its own language;
 * {@link Reply#titleText()} and friends give the plain English reading for tests and logs.
 */
@Mod.EventBusSubscriber(modid="ultima_kingdoms",bus=Mod.EventBusSubscriber.Bus.MOD)
public final class InteractionNetwork {
    public static final UUID NONE=new UUID(0,0);
    public static final String PROTOCOL="2";
    /** {@code prefill} is an optional JSON object of field key to value supplied by the opening screen; matching choice fields are selected automatically. */
    public record Request(UUID request,UUID session,UUID state,String operation,String value,String search,int page,String prefill) {
        public Request(UUID request,UUID session,UUID state,String operation,String value,String search,int page){this(request,session,state,operation,value,search,page,"");}
    }
    public record Option(String key,String label,String detail,boolean selected) {
        public Component labelComponent(){return component(label);}
        public Component detailComponent(){return component(detail);}
        public String labelText(){return labelComponent().getString();}
        public String detailText(){return detailComponent().getString();}
    }
    /** {@code value} carries the search text on listings, the draft on text pages and the task id on review and result pages. */
    public record Reply(UUID request,UUID session,UUID state,String mode,String title,String detail,List<Option> options,String value,int page,boolean more,boolean back,boolean multi) {
        public Component titleComponent(){return component(title);}
        public Component detailComponent(){return component(detail);}
        public String titleText(){return titleComponent().getString();}
        public String detailText(){return detailComponent().getString();}
    }
    /** A protocol refusal with translatable text. */
    public static final class FormException extends IllegalArgumentException {
        public final transient Component component;
        FormException(Component component){super(component.getString());this.component=component;}
    }
    private static final Gson JSON=new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    private static final Map<UUID,LinkedHashMap<UUID,Session>> RETAINED=new HashMap<>();
    private static final Map<UUID,ArrayDeque<Integer>> LAST_REQUEST=new HashMap<>();
    private static SimpleChannel channel;
    private static Consumer<Reply> receiver=r->{};
    private static final class Session {
        UUID id=UUID.randomUUID(),state=UUID.randomUUID(),startedRequest;Reply lastReply;Task task;int field;long expires;boolean executed;String version="";Component result=Component.empty();String search="";int page;
        final Map<String,String> values=new LinkedHashMap<>();final Map<String,Choice> selected=new LinkedHashMap<>();final Map<String,String> prefill=new LinkedHashMap<>();
        List<Choice> candidates=List.of();final Set<String> multi=new LinkedHashSet<>();
        Context context(ServerPlayer p){return new Context(p,values,selected);}
    }
    @SubscribeEvent public static void setup(FMLCommonSetupEvent event){event.enqueueWork(InteractionNetwork::init);}
    public static synchronized void init(){if(channel!=null)return;InteractionTasks.init();
        channel=NetworkRegistry.ChannelBuilder.named(new ResourceLocation("ultima_kingdoms","interactions")).networkProtocolVersion(()->PROTOCOL).clientAcceptedVersions(PROTOCOL::equals).serverAcceptedVersions(PROTOCOL::equals).simpleChannel();
        channel.messageBuilder(Request.class,0,NetworkDirection.PLAY_TO_SERVER).encoder((q,b)->{b.writeUUID(q.request());b.writeUUID(q.session());b.writeUUID(q.state());b.writeUtf(q.operation(),24);b.writeUtf(q.value(),2048);b.writeUtf(q.search(),100);b.writeVarInt(q.page());b.writeUtf(q.prefill(),2048);})
            .decoder(b->new Request(b.readUUID(),b.readUUID(),b.readUUID(),b.readUtf(24),b.readUtf(2048),b.readUtf(100),b.readVarInt(),b.readUtf(2048)))
            .consumerMainThread((q,ctx)->{var player=ctx.get().getSender();if(player!=null)channel.send(PacketDistributor.PLAYER.with(()->player),handleEnvelope(player,q));}).add();
        channel.messageBuilder(Reply.class,1,NetworkDirection.PLAY_TO_CLIENT).encoder((r,b)->b.writeUtf(encodeReply(r),60000)).decoder(b->{var r=JSON.fromJson(b.readUtf(60000),Reply.class);if(r.options()==null||r.options().size()>20)throw new IllegalArgumentException("Invalid form reply");return r;})
            .consumerMainThread((r,ctx)->receiver.accept(r)).add();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(Cleanup.class);
    }
    public static void receive(Consumer<Reply> value){receiver=Objects.requireNonNull(value);}
    public static void send(Request request){channel.sendToServer(request);}
    /** Decodes serialized component text; plain strings (older peers, tests) are shown literally. */
    public static Component component(String encoded){
        if(encoded==null||encoded.isEmpty())return Component.empty();
        if(encoded.startsWith("{")||encoded.startsWith("[")||encoded.startsWith("\"")){try{var c=Component.Serializer.fromJson(encoded);if(c!=null)return c;}catch(RuntimeException ignored){}}
        return Component.literal(encoded);
    }
    public static String encode(Component component){return Component.Serializer.toJson(component);}
    static Component msg(String key,String fallback,Object... args){return tr("interaction.ultima_kingdoms."+key,fallback,args);}
    static FormException fail(String key,String fallback,Object... args){return new FormException(msg(key,fallback,args));}
    private static MutableComponent lines(List<Component> parts){var out=Component.empty();for(int i=0;i<parts.size();i++){if(i>0)out.append("\n");out.append(parts.get(i));}return out;}
    private static Reply handleEnvelope(ServerPlayer p,Request q){
        int now=p.getServer().getTickCount();var recent=LAST_REQUEST.computeIfAbsent(p.getUUID(),ignored->new ArrayDeque<>());
        while(!recent.isEmpty()&&now-recent.peekFirst()>=20)recent.removeFirst();
        if(recent.size()>=20){var s=SESSIONS.get(p.getUUID());return new Reply(q.request(),s==null?NONE:s.id,s==null?NONE:s.state,"error",encode(msg("wait.title","Please wait")),encode(msg("wait","Please wait a moment, then check the same action or continue.")),List.of(),"",0,false,s!=null,false);}
        recent.addLast(now);return handle(p,q);
    }
    public static Reply handle(ServerPlayer p,Request q){
        try {
            if(q.page()<0||q.page()>100000||!p.getServer().isSameThread())throw fail("page","Invalid page.");
            if(q.operation().equals("LIST"))return catalogue(p,q);
            Session s=q.operation().equals("TASK")?SESSIONS.get(p.getUUID()):RETAINED.getOrDefault(p.getUUID(),new LinkedHashMap<>()).get(q.session());
            if(q.operation().equals("TASK")){
                if(s!=null&&q.request().equals(s.startedRequest)&&s.lastReply!=null){task(s.task.id(),p);return copy(q,s.lastReply);}
                s=new Session();s.startedRequest=q.request();s.task=task(q.value(),p);s.prefill.putAll(prefill(q.prefill()));SESSIONS.put(p.getUUID(),s);
                var retained=RETAINED.computeIfAbsent(p.getUUID(),ignored->new LinkedHashMap<>());retained.put(s.id,s);while(retained.size()>16)retained.remove(retained.keySet().iterator().next());
            }else if(q.operation().equals("CHECK")){
                s=RETAINED.getOrDefault(p.getUUID(),new LinkedHashMap<>()).get(q.session());
                if(s==null){var latest=SESSIONS.get(p.getUUID());if(latest!=null&&latest.startedRequest.toString().equals(q.value()))s=latest;}
                if(s==null)throw fail("check.none","No retained action was received. Inspect current status before starting again.");
                task(s.task.id(),p);if(s.executed)return result(q,s);
                if(s.lastReply!=null)return copy(q,s.lastReply);
                throw fail("check.early","No reviewed action is available yet.");
            }
            else if(s==null||!s.id.equals(q.session())||s.expires<p.serverLevel().getGameTime())throw fail("expired","This form expired. Open the task again.");
            s.expires=p.serverLevel().getGameTime()+6000;
            task(s.task.id(),p);
            if(s.executed)return result(q,s);
            if(!q.operation().equals("TASK")&&!s.state.equals(q.state()))throw fail("changed","The form changed. Review the current page before continuing.");
            task(s.task.id(),p); // Operator authority is checked again on every message.
            if(q.operation().equals("BACK")){if(s.field>0){s.field--;var f=s.task.fields().get(s.field);s.values.remove(f.key());s.selected.remove(f.key());s.multi.clear();s.prefill.remove(f.key());}s.page=0;s.search="";}
            else if(q.operation().equals("FILTER")){s.page=q.page();s.search=q.search();}
            else if(q.operation().equals("PICK")){
                if(s.field>=s.task.fields().size())throw fail("review","Review the current task.");
                var f=s.task.fields().get(s.field);if(f.kind()!=Kind.CHOICE&&f.kind()!=Kind.MULTI)throw fail("pick.kind","This field is not a selection.");
                int index=Integer.parseInt(q.value());if(index<0||index>=s.candidates.size())throw fail("pick.changed","That choice changed. Refresh the form.");
                var choice=s.candidates.get(index);if(f.kind()==Kind.MULTI){if(!s.multi.add(choice.value()))s.multi.remove(choice.value());}
                else {s.values.put(f.key(),choice.value());s.selected.put(f.key(),choice);s.field++;s.page=0;s.search="";}
            }else if(q.operation().equals("NEXT")){
                if(s.field>=s.task.fields().size())throw fail("apply.review","Review the task before applying.");var f=s.task.fields().get(s.field);
                if(f.kind()==Kind.MULTI){if(s.multi.isEmpty()&&!f.optional())throw fail("multi.empty","Select at least one choice.");s.values.put(f.key(),String.join(",",s.multi));s.multi.clear();}
                else {if(f.kind()==Kind.CHOICE)throw fail("choice.named","Choose a named entry.");String value=q.value().strip();if(!f.optional()&&value.isBlank())throw fail("text.empty","Enter %s.",label(s.task,f));if(value.length()>512||value.chars().anyMatch(Character::isISOControl))throw fail("text.long","Please use at most 512 plain-text characters.");if(f.kind()==Kind.NUMBER)Long.parseLong(value);s.values.put(f.key(),value);}
                s.field++;s.page=0;s.search="";
            }else if(q.operation().equals("APPLY")){
                if(s.field!=s.task.fields().size())throw fail("apply.incomplete","Complete the form first.");
                validate(p,s);var c=s.context(p);if(!s.version.equals(s.task.version().apply(c)))throw fail("apply.stale","The record changed since your review. Open the task again and review its new state.");
                return execute(p,q,s,c);
            }else if(!Set.of("TASK","BACK","FILTER","PICK","NEXT").contains(q.operation()))throw fail("operation","Unknown form operation.");
            prefill(p,s);
            if(s.field==s.task.fields().size()&&!s.task.consequential()){validate(p,s);var c=s.context(p);s.version=s.task.version().apply(c);return execute(p,q,s,c);}
            s.state=UUID.randomUUID();s.lastReply=form(p,q,s);return s.lastReply;
        }catch(Exception failure){var s=SESSIONS.get(p.getUUID());return new Reply(q.request(),s==null?NONE:s.id,s==null?NONE:s.state,"error",encode(msg("error.title","Could not continue")),encode(reason(failure,new Context(p,Map.of(),Map.of()))),List.of(),"",0,false,s!=null,false);}
    }
    private static Component reason(Throwable failure,Context c){return failure instanceof FormException form?form.component:PlayerWords.component(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(),c);}
    /** Runs the reviewed handler once; the session keeps the outcome for CHECK. */
    private static Reply execute(ServerPlayer p,Request q,Session s,Context c){
        s.executed=true;s.state=UUID.randomUUID();
        try{var outcome=s.task.handler().run(c);
            if(outcome instanceof Pending pending){s.result=PlayerWords.component(pending.message(),c);Session retained=s;pending.completion().whenComplete((value,failure)->p.getServer().execute(()->retained.result=failure==null?PlayerWords.component(value,c):failed(failure,c)));}
            else s.result=PlayerWords.component(outcome,c);
        }catch(Exception failure){s.result=failed(failure,c);}
        return result(q,s);
    }
    private static Component failed(Throwable failure,Context c){return lines(List.of(msg("failed","Could not complete: %s",reason(failure,c)),msg("failed.hint","Check the current status before repeating a consequential action.")));}
    private static Map<String,String> prefill(String json){
        var out=new LinkedHashMap<String,String>();if(json==null||json.isBlank())return out;
        try{for(var e:JsonParser.parseString(json).getAsJsonObject().entrySet()){String v=e.getValue().isJsonPrimitive()?e.getValue().getAsString():"";if(v.length()<=512&&v.chars().noneMatch(Character::isISOControl))out.put(e.getKey(),v);}}catch(RuntimeException ignored){}
        return out;
    }
    /** Consumes prefilled context from the opening screen: matching choices are selected, text drafts are kept editable. */
    private static void prefill(ServerPlayer p,Session s){
        while(s.field<s.task.fields().size()){
            var f=s.task.fields().get(s.field);String wanted=s.prefill.remove(f.key());if(wanted==null||f.kind()!=Kind.CHOICE)return;
            var found=f.options().apply(s.context(p)).stream().filter(o->o.value().equals(wanted)).findFirst();if(found.isEmpty())return;
            s.values.put(f.key(),wanted);s.selected.put(f.key(),found.get());s.field++;s.page=0;s.search="";
        }
    }
    private static void validate(ServerPlayer p,Session s){
        var values=new LinkedHashMap<String,String>();var selected=new LinkedHashMap<String,Choice>();
        for(var f:s.task.fields()){
            var c=new Context(p,values,selected);String value=s.values.getOrDefault(f.key(),"");
            if(f.kind()==Kind.CHOICE){var found=f.options().apply(c).stream().filter(o->o.value().equals(value)).findFirst().orElseThrow(()->fail("validate.gone","A selected person or record is no longer available."));
                if(!found.equals(s.selected.get(f.key())))throw fail("validate.changed","A selected record changed. Open the task again to review it.");selected.put(f.key(),found);
            }else if(f.kind()==Kind.MULTI){var permitted=f.options().apply(c).stream().map(Choice::value).toList();for(String v:value.isEmpty()&&f.optional()?new String[0]:value.split(","))if(!permitted.contains(v))throw fail("validate.choice","An available choice changed.");}
            values.put(f.key(),value);
        }
    }
    static Component label(Task task,Field f){return tr("task.ultima_kingdoms."+task.id()+".field."+f.key(),f.label());}
    static Component title(Task task){return tr(task.titleKey(),task.title());}
    static Component help(Task task){return tr(task.helpKey(),task.help());}
    static Component category(Task task){return tr(task.categoryKey(),task.category());}
    private static Component help(Field f,Context c){return PlayerWords.component(f.help(),c);}
    private static Reply form(ServerPlayer p,Request q,Session s){
        var c=s.context(p);
        if(s.field==s.task.fields().size()){
            validate(p,s);s.version=s.task.version().apply(c);var lines=new ArrayList<Component>();lines.add(help(s.task));
            for(var f:s.task.fields()){var chosen=s.selected.get(f.key());String value=chosen==null?s.values.get(f.key()):chosen.label();if(f.kind()==Kind.MULTI)value=String.join(", ",f.options().apply(c).stream().filter(o->Arrays.asList(s.values.get(f.key()).split(",")).contains(o.value())).map(Choice::label).toList());
                lines.add(label(s.task,f).copy().append(": ").append(PlayerWords.component(value,c)));if(chosen!=null){if(!chosen.detail().isBlank())lines.add(PlayerWords.component(chosen.detail(),c));entityLocation(p,chosen).ifPresent(lines::add);}}
            lines.add(s.task.consequential()?msg("review.consequential","Review the people, terms and consequences above. Apply performs this action once; backing out changes nothing."):msg("review.ready","Ready. Continue to perform this task."));
            return new Reply(q.request(),s.id,s.state,"review",encode(title(s.task)),encode(lines(lines)),List.of(),s.task.id(),0,false,s.field>0,false);
        }
        var f=s.task.fields().get(s.field);boolean selection=f.kind()==Kind.CHOICE||f.kind()==Kind.MULTI;
        String draft=s.values.containsKey(f.key())?s.values.get(f.key()):s.prefill.getOrDefault(f.key(),f.initial());
        if(!selection)return new Reply(q.request(),s.id,s.state,"text",encode(label(s.task,f)),encode(lines(List.of(title(s.task),help(f,c)))),List.of(),draft,0,false,s.field>0,false);
        s.candidates=List.copyOf(f.options().apply(c));var options=new ArrayList<Option>();String search=s.search.toLowerCase(Locale.ROOT);int matched=0,start=s.page*20;
        for(int i=0;i<s.candidates.size();i++){var option=s.candidates.get(i);if(!(option.label()+" "+option.detail()).toLowerCase(Locale.ROOT).contains(search))continue;
            if(matched>=start&&options.size()<20){var detail=PlayerWords.component(bounded(option.detail(),1024),c).copy();entityLocation(p,option).ifPresent(l->detail.append("\n").append(l));options.add(new Option(Integer.toString(i),encode(PlayerWords.component(bounded(option.label(),256),c)),encode(detail),s.multi.contains(option.value())));}matched++;}
        var help=new ArrayList<Component>();help.add(title(s.task));help.add(help(f,c));
        if(s.candidates.isEmpty())help.add(f.targetKind().isEmpty()?msg("empty","No eligible known choices. Check your location, permissions, prerequisites and installed integrations."):targetHint(f.targetKind(),c).map(h->PlayerWords.component(h,c)).orElse(msg("empty","No eligible known choices. Check your location, permissions, prerequisites and installed integrations.")));
        return new Reply(q.request(),s.id,s.state,"choice",encode(label(s.task,f)),encode(lines(help)),options,s.search,s.page,matched>start+20,s.field>0,f.kind()==Kind.MULTI);
    }
    private static Reply catalogue(ServerPlayer p,Request q){
        String search=q.search().toLowerCase(Locale.ROOT);
        var tasks=tasks(p).stream().filter(t->(t.category()+" "+t.title()+" "+t.help()+" "+t.id()).toLowerCase(Locale.ROOT).contains(search)).sorted(Comparator.comparing(Task::category).thenComparing(Task::title)).toList();
        int start=Math.min(tasks.size(),q.page()*20);
        var options=tasks.subList(start,Math.min(start+20,tasks.size())).stream().map(t->new Option(t.id(),encode(title(t)),encode(category(t).copy().append(" · ").append(help(t))),t.consequential())).toList();
        return new Reply(q.request(),NONE,NONE,"tasks",encode(msg("catalogue.title","Kingdoms")),encode(msg("catalogue.help","Choose a task. Search by what you want to do. No commands are entered or sent.")),options,q.search(),q.page(),start+20<tasks.size(),false,false);
    }
    private static Optional<Component> entityLocation(ServerPlayer player,Choice choice){
        try{var entity=player.serverLevel().getEntity(UUID.fromString(choice.value()));
            if(entity instanceof net.minecraft.world.entity.LivingEntity&&entity.distanceToSqr(player)<=32*32&&player.hasLineOfSight(entity))return Optional.of(msg("location","Current location: %s",entity.blockPosition().toShortString()));
        }catch(IllegalArgumentException ignored){}return Optional.empty();
    }
    public static String encodeReply(Reply reply){
        String encoded=JSON.toJson(reply);if(encoded.length()<=60000)return encoded;
        return JSON.toJson(new Reply(reply.request(),reply.session(),reply.state(),"error",encode(msg("long.title","Details too long")),encode(msg("long","These details exceed the display limit. Narrow your search or ask an operator to shorten the record text before continuing.")),List.of(),"",0,false,reply.back(),false));
    }
    private static String bounded(String text,int limit){return text.length()>limit?text.substring(0,limit-1)+"…":text;}
    private static Reply copy(Request q,Reply r){return new Reply(q.request(),r.session(),r.state(),r.mode(),r.title(),r.detail(),r.options(),r.value(),r.page(),r.more(),r.back(),r.multi());}
    private static Reply result(Request q,Session s){return new Reply(q.request(),s.id,s.state,"result",encode(title(s.task)),encode(s.result),List.of(),s.task.id(),0,false,false,false);}
    public static final class Cleanup {
        @SubscribeEvent public static void logout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){SESSIONS.remove(e.getEntity().getUUID());RETAINED.remove(e.getEntity().getUUID());LAST_REQUEST.remove(e.getEntity().getUUID());}
        @SubscribeEvent public static void stopped(net.minecraftforge.event.server.ServerStoppedEvent e){SESSIONS.clear();RETAINED.clear();LAST_REQUEST.clear();}
    }
    private InteractionNetwork(){}
}
