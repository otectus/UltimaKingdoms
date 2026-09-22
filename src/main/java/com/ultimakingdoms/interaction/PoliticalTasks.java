package com.ultimakingdoms.interaction;

import com.ultimakingdoms.api.politics.*;
import com.ultimakingdoms.api.politics.Politics.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import java.util.*;
import static com.ultimakingdoms.interaction.ActionRegistry.*;

public final class PoliticalTasks {
    private static PoliticalService service(Context c){return UltimaPoliticsApi.get(c.server());}
    private static Field kingdom(){return pick("kingdom","Kingdom","kingdom");}
    private static Field person(){return pick("person","Person","person");}
    private static Field definition(String kind){return pick("definition",words(kind)+" type",kind+"_definition");}
    private static Field record(String kind){return pick("target",words(kind),kind);}
    private static void task(String id,String title,boolean operator,boolean change,List<Field> fields,Handler handler){add(new Task("politics."+id,"Government",title,"Political authority is checked for the selected kingdom. Review the named parties and terms before applying.",operator,change,fields,c->Long.toString(service(c).revision()),handler));}
    public static void init(){
        for(String kind:List.of("government","office","agreement","petition","institution","honor"))targets(kind+"_definition",c->{String kingdom=c.text("kingdom");if(kingdom.isEmpty())kingdom=c.choices("kingdom").stream().findFirst().map(ActionRegistry.Choice::value).orElse("");return service(c).page(c.player,UUID.randomUUID(),kingdom,"overview",0).definitions().stream().filter(d->d.kind().equals(kind)&&!d.id().endsWith(":commission_petition")).map(d->new ActionRegistry.Choice(d.id(),PlayerWords.text(d.title()))).toList();});
        for(String kind:List.of("agreement","petition","institution","honor"))targets(kind,c->records(c,kind+"s"));
        targets("political_permission",c->Arrays.stream(Permission.values()).map(p->new ActionRegistry.Choice(p.name(),words(p.name()))).toList());
        targets("political_record",c->{var result=new ArrayList<ActionRegistry.Choice>();for(String kind:List.of("agreement","petition","institution","honor"))result.addAll(c.choices(kind));return result;});
        targets("withdrawable",c->{var list=new ArrayList<>(c.choices("agreement"));list.addAll(c.choices("petition"));return list;});
        targets("election",c->{var out=new ArrayList<ActionRegistry.Choice>();for(int offset=0;offset<=100000;offset+=64){var page=service(c).elections(c.player,offset,64);for(var e:page)out.add(new ActionRegistry.Choice(e.id().toString(),"Election · "+kingdomName(c,e.kingdom()),words(e.state().name())+" · "+e.ballotsCast()+" ballots cast; "+(e.viewerVoted()?"your vote recorded":"your vote not yet recorded"),Map.of("revision",Long.toString(e.revision()),"kingdom",e.kingdom())));if(page.size()<64)break;}return out;});
        targets("building",c->{BlockPos feet=c.player.blockPosition();var hit=c.player.pick(6,0,false);BlockPos aimed=BlockPos.containing(hit.getLocation());var out=new ArrayList<ActionRegistry.Choice>();for(var pos:new LinkedHashSet<>(List.of(feet,aimed)))out.add(new ActionRegistry.Choice(pos.asLong()+"","Building at "+pos.toShortString(),pos.equals(feet)?"Where you are standing":"Where you are looking",Map.of("x",""+pos.getX(),"y",""+pos.getY(),"z",""+pos.getZ())));return out;});
        for(String tab:List.of("overview","council","agreements","petitions","institutions","honors","history"))task("read_"+tab,"Read "+words(tab).toLowerCase(Locale.ROOT),false,false,List.of(kingdom(),number("page","Page",1)),c->{int page=c.integer("page");if(page<1||page>5001)throw new IllegalArgumentException("Choose a page from 1 to 5001.");var result=service(c).page(c.player,UUID.randomUUID(),c.text("kingdom"),tab,(page-1)*20);var lines=new ArrayList<String>();if(!result.diagnostic().isBlank())lines.add(result.diagnostic());result.rows().forEach(r->lines.add(PlayerWords.text(r.title())+"\n"+r.detail()));return lines.isEmpty()?List.of("No records on this page."):lines;});
        task("diagnose","Diagnose government availability",true,false,List.of(kingdom()),c->service(c).page(c.player,UUID.randomUUID(),c.text("kingdom"),"overview",0).diagnostic());
        action(Action.BOOTSTRAP,"Establish a government",true,List.of(kingdom(),pick("target","Capital settlement","settlement"),definition("government"),person()));
        action(Action.SEAT,"Move the capital",false,List.of(kingdom(),pick("target","New capital","settlement")));
        var scope=pick("target","Office jurisdiction",c->{var list=new ArrayList<ActionRegistry.Choice>();list.add(new ActionRegistry.Choice("","Kingdom-wide office"));list.addAll(c.choices("settlement"));return list;});
        action(Action.APPOINT,"Appoint an official",false,List.of(kingdom(),definition("office"),person(),scope));
        action(Action.REMOVE_OFFICE,"Remove an official",false,List.of(kingdom(),definition("office"),scope));
        action(Action.DELEGATE,"Authorize a political responsibility",false,List.of(kingdom(),pick("person","Player","player"),pick("definition","Responsibility",c->enums(Arrays.stream(Permission.values()).filter(p->p!=Permission.DELEGATE).toArray(Permission[]::new)))));
        action(Action.REVOKE,"Revoke a player's mandates",false,List.of(kingdom(),pick("person","Player","player")));
        action(Action.PROPOSE,"Propose an agreement",false,List.of(kingdom(),pick("counterpart","Other kingdom","kingdom"),definition("agreement"),text("text","Agreement terms")));
        action(Action.SIGN,"Sign reviewed agreement terms",false,List.of(kingdom(),record("agreement")));
        for(Action action:List.of(Action.DECLINE,Action.TERMINATE))action(action,action==Action.DECLINE?"Decline an agreement":"End an agreement",false,List.of(kingdom(),record("agreement")));
        action(Action.WITHDRAW,"Withdraw an agreement or petition",false,List.of(kingdom(),record("withdrawable")));
        for(Action action:List.of(Action.REVIEW,Action.APPROVE,Action.REJECT))action(action,words(action.name())+" a petition",false,List.of(kingdom(),record("petition"),text("text","Reason for this decision")));
        for(String type:List.of("institution","honor","introduction")){
            var fields=new ArrayList<Field>();fields.add(kingdom());fields.add(pick("target","Settlement","settlement"));
            if(type.equals("institution"))fields.add(pick("building","Building to nominate","building"));
            if(type.equals("honor"))fields.add(person());if(type.equals("introduction"))fields.add(pick("counterpart","Other kingdom","kingdom"));fields.add(text("text","Explain your request"));
            task("petition_"+type,"Request "+(type.equals("institution")?"institution recognition":type.equals("honor")?"an honor":"a diplomatic introduction"),false,true,fields,c->execute(c,Action.PETITION,"ultima_kingdoms:"+type+"_petition"));
        }
        action(Action.RECOGNIZE,"Recognize a civic building",false,List.of(kingdom(),definition("institution"),pick("building","Building to recognize","building")));
        action(Action.REVALIDATE,"Revalidate an institution",false,List.of(kingdom(),record("institution")));
        action(Action.SUSPEND_RECOGNITION,"Suspend institution recognition",false,List.of(kingdom(),record("institution")));
        action(Action.HONOR,"Award an honor",false,List.of(kingdom(),definition("honor"),person(),text("text","Reason for the award")));
        action(Action.REVOKE_HONOR,"Revoke an honor",false,List.of(kingdom(),record("honor")));
        action(Action.NAME_SUCCESSOR,"Name a successor",false,List.of(kingdom(),person()));
        action(Action.ABDICATE,"Abdicate leadership",false,List.of(kingdom()));action(Action.SUCCEED,"Confirm succession",false,List.of(kingdom()));
        action(Action.HOUSE,"Record a political house",false,List.of(kingdom(),text("definition","House name"),person(),text("text","House motto","",true)));
        task("transition_rule","Set election and regency rules",false,true,List.of(kingdom(),toggle("elections","Allow elections?"),toggle("regency","Allow regency?"),pick("time_unit","Duration units",c->List.of(new ActionRegistry.Choice("minutes","Game minutes"),new ActionRegistry.Choice("seconds","Game seconds"),new ActionRegistry.Choice("ticks","Game ticks (advanced)"))),number("election_duration","Election duration",60),number("grace_duration","Tie grace duration",10),number("regency_duration","Regency duration",60),optionalMulti("permissions","Regent responsibilities",c->enums(Arrays.stream(Permission.values()).filter(p->p!=Permission.DELEGATE).toArray(Permission[]::new)))),c->{Set<Permission> permissions=new HashSet<>();for(String v:c.text("permissions").split(","))if(!v.isBlank())permissions.add(Permission.valueOf(v.toUpperCase(Locale.ROOT)));return result(service(c).adoptTransitionRule(c.player,UUID.randomUUID(),service(c).revision(),c.text("kingdom"),new PoliticalTransition.Rule(c.bool("elections"),c.bool("regency"),duration(c,"election_duration"),duration(c,"grace_duration"),duration(c,"regency_duration"),8,permissions)));});
        task("election_open","Open a leadership election",false,true,List.of(kingdom(),multi("candidates","Nominate candidates",c->c.choices("player"))),c->result(service(c).openElection(c.player,UUID.randomUUID(),service(c).revision(),c.text("kingdom"),Arrays.stream(c.text("candidates").split(",")).map(UUID::fromString).toList())));
        task("election_vote","Vote in an election",false,true,List.of(pick("election","Election","election"),pick("candidate","Candidate",c->service(c).election(c.player,c.uuid("election")).orElseThrow().candidates().stream().map(id->new ActionRegistry.Choice(id.toString(),BasicTargets.player(c,id))).toList())),c->result(service(c).castBallot(c.player,UUID.randomUUID(),service(c).revision(),c.uuid("election"),c.uuid("candidate"))));
        task("election_close","Close and count an election",false,true,List.of(pick("election","Election","election")),c->result(service(c).closeElection(c.player,UUID.randomUUID(),service(c).revision(),c.uuid("election"))));
        task("election_inspect","Read an election",false,false,List.of(pick("election","Election","election")),c->{var e=service(c).election(c.player,c.uuid("election")).orElseThrow();return List.of(c.choice("election").label(),"Status: "+words(e.state().name()),"Candidates: "+String.join(", ",e.candidates().stream().map(id->BasicTargets.player(c,id)).toList()),"Ballots: "+e.ballotsCast()+" of "+e.electorateSize(),"You are eligible: "+e.viewerEligible()+"; your vote recorded: "+e.viewerVoted(),"Voting closes: "+gameTime(e.deadline())+"; tie grace ends: "+gameTime(e.graceUntil()),"Winner: "+e.winner().map(id->BasicTargets.player(c,id)).orElse("No winner recorded"));});
        task("appoint_regent","Appoint a temporary regent",false,true,List.of(kingdom(),pick("person","Regent","player")),c->result(service(c).appointRegent(c.player,UUID.randomUUID(),service(c).revision(),c.text("kingdom"),c.uuid("person"))));
        task("end_regency","End a regency",false,true,List.of(kingdom()),c->result(service(c).endRegency(c.player,UUID.randomUUID(),service(c).revision(),c.text("kingdom"))));
    }
    private static void action(Action action,String title,boolean operator,List<Field> fields){task(action.name().toLowerCase(Locale.ROOT),title,operator,true,fields,c->execute(c,action,c.text("definition")));}
    private static String execute(Context c,Action action,String definition){
        Person person=null;if(!c.text("person").isEmpty()){var entity=BasicTargets.entity(c,"person");person=new Person(entity.getUUID(),entity instanceof Player?Politics.Kind.PLAYER:Politics.Kind.NPC);}
        String hash=action==Action.SIGN?c.meta("target","hash"):"";int x=0,y=0,z=0;if(c.choice("building")!=null){x=Integer.parseInt(c.meta("building","x"));y=Integer.parseInt(c.meta("building","y"));z=Integer.parseInt(c.meta("building","z"));}
        if(action==Action.DELEGATE)definition=definition.toUpperCase(Locale.ROOT);
        return result(service(c).execute(c.player,new Request(UUID.randomUUID(),service(c).revision(),action,c.text("kingdom"),definition,c.text("target"),person,c.text("counterpart"),c.text("text"),hash,x,y,z)));
    }
    private static String result(Result value){if(!value.success())throw new IllegalArgumentException(value.message());return PlayerWords.text(value.message());}
    static List<ActionRegistry.Choice> records(Context c,String tab){
        var out=new ArrayList<ActionRegistry.Choice>();List<String> kingdoms=c.text("kingdom").isEmpty()?c.choices("kingdom").stream().map(ActionRegistry.Choice::value).toList():List.of(c.text("kingdom"));
        for(String kingdom:kingdoms)for(int offset=0;offset<=100000;offset+=20){var page=service(c).page(c.player,UUID.randomUUID(),kingdom,tab,offset);for(var r:page.rows())if(!r.id().isEmpty()&&out.stream().noneMatch(v->v.value().equals(r.id())))out.add(new ActionRegistry.Choice(r.id(),PlayerWords.text(r.title())+" · "+kingdomName(c,kingdom),PlayerWords.safe(r.detail(),c),Map.of("revision",Long.toString(page.revision()),"hash",r.termsHash(),"kingdom",kingdom)));if(!page.hasMore())break;}return out;
    }
    private static long duration(Context c,String key){return Math.multiplyExact(c.number(key),switch(c.text("time_unit")){case "minutes"->1200;case "seconds"->20;default->1;});}
    public static String gameTime(long ticks){return "game day "+(ticks/24000)+", minute "+((ticks%24000)/1200);}
    private static String kingdomName(Context c,String value){return c.choices("kingdom").stream().filter(k->k.value().equals(value)).map(ActionRegistry.Choice::label).findFirst().orElse("Kingdom");}
    private PoliticalTasks(){}
}
