package com.ultimakingdoms.client;

import com.ultimakingdoms.interaction.InteractionNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Named task browser and server-reviewed forms, with no chat or command execution path. */
public final class KingdomActionsScreen extends Screen {
    static final int ROW=26;
    private final Screen parent;
    private final String initialTask,initialSearch,prefill;
    private InteractionNetwork.Reply reply;
    private UUID pending,lateRequest,actionRequest;
    private InteractionNetwork.Request lastSent;
    private int timeout,localPage;
    private EditBox input;
    private MenuTextPanel detail;
    private Component notice=null;
    private int left,w,rows;
    private final List<Button> rowButtons=new ArrayList<>();
    public KingdomActionsScreen(Screen parent,String initialTask,String initialSearch){this(parent,initialTask,initialSearch,"");}
    /** {@code prefill} is a JSON object of task field keys to values known by the opening screen, such as the settlement it shows. */
    public KingdomActionsScreen(Screen parent,String initialTask,String initialSearch,String prefill){super(text("catalogue.title","Kingdoms"));this.parent=parent;this.initialTask=initialTask;this.initialSearch=initialSearch;this.prefill=prefill;}
    static net.minecraft.network.chat.MutableComponent text(String key,String fallback,Object... args){return Component.translatableWithFallback("interaction.ultima_kingdoms."+key,fallback,args);}
    @Override public void resize(Minecraft client,int width,int height){String draft=input==null?null:input.getValue();super.resize(client,width,height);if(draft!=null&&input!=null)input.setValue(draft);}
    @Override protected void init(){
        input=null;rowButtons.clear();
        w=Math.min(780,width-16);left=(width-w)/2;rows=Math.max(1,(height-166)/ROW);
        if(reply==null){detail=addRenderableWidget(new MenuTextPanel(left+8,42,w-16,height-84,text("connecting","Connecting")));detail.setContent(List.of(notice==null?text("requesting","Requesting your available tasks…"):notice));
            addRenderableWidget(Button.builder(text("close","Close"),b->onClose()).bounds(left+8,height-32,80,20).build());
            if(pending==null)send(initialTask.isEmpty()?"LIST":"TASK",initialTask,initialSearch,0);return;}
        localPage=Math.min(localPage,Math.max(0,(reply.options().size()-1)/rows));
        boolean tasks=reply.mode().equals("tasks"),listing=tasks||reply.mode().equals("choice");
        if(listing){
            input=addRenderableWidget(new EditBox(font,left+8,37,w-100,20,text("search.hint","Search by name or task")));input.setMaxLength(100);input.setValue(reply.value());input.setHint(text("search.hint","Search by name or task"));
            addRenderableWidget(Button.builder(text("search","Search"),b->search()).bounds(left+w-86,37,78,20).build());
            int start=localPage*rows;
            for(int i=0;i<rows&&start+i<reply.options().size();i++){
                var option=reply.options().get(start+i);
                Component label=option.labelComponent(),detailText=option.detailComponent();
                Component first=tasks?label:option.selected()?Component.literal("✓ ").append(label):label;
                Component second=tasks&&option.selected()?text("changes","Changes records").append(" · ").append(detailText):detailText;
                var row=new OptionRow(left+8,87+i*ROW,w-16,ROW-2,first,second,b->{localPage=0;send(tasks?"TASK":"PICK",option.key(),"",0);});
                row.setTooltip(Tooltip.create(label.copy().append("\n").append(detailText)));
                rowButtons.add(addRenderableWidget(row));
            }
            if(reply.options().isEmpty()){detail=addRenderableWidget(new MenuTextPanel(left+8,87,w-16,height-160,text("no_choices","No choices")));detail.setContent(paragraphs(reply.detailComponent()));}
            int y=height-61;
            var previous=addRenderableWidget(Button.builder(text("previous","Previous page"),b->{if(localPage>0){localPage--;rebuildWidgets();}else send(tasks?"LIST":"FILTER","",input.getValue(),reply.page()-1);}).bounds(left+8,y,Math.min(125,(w-24)/3),20).build());previous.active=localPage>0||reply.page()>0;
            var next=addRenderableWidget(Button.builder(text("next","Next page"),b->{if((localPage+1)*rows<reply.options().size()){localPage++;rebuildWidgets();}else{localPage=0;send(tasks?"LIST":"FILTER","",input.getValue(),reply.page()+1);}}).bounds(left+w-Math.min(125,(w-24)/3)-8,y,Math.min(125,(w-24)/3),20).build());next.active=(localPage+1)*rows<reply.options().size()||reply.more();
            if(reply.multi())addRenderableWidget(Button.builder(text("use_selections","Use selections"),b->send("NEXT","","",0)).bounds(left+w/3,y,w/3,20).build());
        }else if(reply.mode().equals("text")){
            detail=addRenderableWidget(new MenuTextPanel(left+8,40,w-16,height-145,reply.titleComponent()));detail.setContent(paragraphs(reply.detailComponent()));
            input=addRenderableWidget(new EditBox(font,left+8,height-96,w-16,20,reply.titleComponent()));input.setMaxLength(512);input.setValue(reply.value());
            addRenderableWidget(Button.builder(text("continue","Continue"),b->send("NEXT",input.getValue(),"",0)).bounds(left+w-108,height-61,100,20).build());setInitialFocus(input);
        }else{
            detail=addRenderableWidget(new MenuTextPanel(left+8,40,w-16,height-109,reply.titleComponent()));detail.setContent(paragraphs(reply.detailComponent()));
            int y=height-61;
            if(reply.mode().equals("error")&&lastSent!=null)addRenderableWidget(Button.builder(text("check","Check this action"),b->send("CHECK",(actionRequest==null?lastSent.request():actionRequest).toString(),"",0)).bounds(left+w/2-100,y,200,20).build());
            if(reply.mode().equals("review"))addRenderableWidget(Button.builder(text("apply","Apply reviewed action"),b->send("APPLY","","",0)).bounds(left+w/2-100,y,200,20).build());
            if(reply.mode().equals("result")){
                int bw=(w-32)/3;String task=reply.value();
                addRenderableWidget(Button.builder(text("check","Check this action"),b->send("CHECK",(actionRequest==null?lastSent.request():actionRequest).toString(),"",0)).bounds(left+8,y,bw,20).build()).active=lastSent!=null;
                addRenderableWidget(Button.builder(text("again","Same task again"),b->{localPage=0;send("TASK",task,"",0);}).bounds(left+16+bw,y,bw,20).build()).active=!task.isEmpty();
                addRenderableWidget(Button.builder(text("related","Related tasks"),b->{localPage=0;send("LIST","",task.contains(".")?task.substring(0,task.indexOf('.')+1):"",0);}).bounds(left+24+2*bw,y,bw,20).build()).active=!task.isEmpty();
            }
        }
        int bw=(w-32)/3;
        var back=addRenderableWidget(Button.builder(text("back","Back"),b->{localPage=0;send("BACK","","",0);}).bounds(left+8,height-32,bw,20).build());back.active=reply.back();
        addRenderableWidget(Button.builder(text("all_tasks","All tasks"),b->{localPage=0;send("LIST","","",0);}).bounds(left+16+bw,height-32,bw,20).build());
        addRenderableWidget(Button.builder(text("close","Close"),b->onClose()).bounds(left+24+2*bw,height-32,bw,20).build());
        if(pending!=null)children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).forEach(widget->widget.active=false);
    }
    /** Splits rendered text into paragraphs for the panel; translation happens on this client. */
    static List<Component> paragraphs(Component component){return Arrays.stream(component.getString().split("\n")).map(Component::literal).map(c->(Component)c).toList();}
    private void search(){localPage=0;send(reply.mode().equals("tasks")?"LIST":"FILTER","",input.getValue(),0);}
    private void send(String operation,String value,String search,int page){
        if(pending!=null)return;lateRequest=null;pending=UUID.randomUUID();timeout=200;notice=null;
        lastSent=new InteractionNetwork.Request(pending,reply==null?InteractionNetwork.NONE:reply.session(),reply==null?InteractionNetwork.NONE:reply.state(),operation,value,search,page,operation.equals("TASK")?prefill:"");
        if(operation.equals("TASK"))actionRequest=pending;
        InteractionNetwork.send(lastSent);
        children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).forEach(widget->widget.active=false);
    }
    public static void receive(InteractionNetwork.Reply value){if(Minecraft.getInstance().screen instanceof KingdomActionsScreen screen&&(value.request().equals(screen.pending)||value.request().equals(screen.lateRequest))){screen.reply=value;screen.pending=null;screen.lateRequest=null;screen.timeout=0;screen.rebuildWidgets();}}
    @Override public void tick(){if(input!=null)input.tick();if(pending!=null&&--timeout<=0){lateRequest=pending;pending=null;notice=text("timeout","No reply yet. Inspect current status before repeating an action.");reply=new InteractionNetwork.Reply(UUID.randomUUID(),reply==null?InteractionNetwork.NONE:reply.session(),reply==null?InteractionNetwork.NONE:reply.state(),"error",InteractionNetwork.encode(text("timeout.title","Request timed out")),InteractionNetwork.encode(notice),List.of(),"",0,false,false,false);rebuildWidgets();}}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if((key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)&&input!=null&&input.isFocused()&&pending==null){if(reply.mode().equals("text"))send("NEXT",input.getValue(),"",0);else search();return true;}
        if(key==GLFW.GLFW_KEY_DOWN&&input!=null&&input.isFocused()&&!rowButtons.isEmpty()){setFocused(rowButtons.get(0));rowButtons.get(0).setFocused(true);input.setFocused(false);return true;}
        if(key==GLFW.GLFW_KEY_UP&&input!=null&&!rowButtons.isEmpty()&&getFocused()==rowButtons.get(0)){rowButtons.get(0).setFocused(false);setFocused(input);input.setFocused(true);return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial){renderBackground(g);VanillaGui.panel(g,left,8,w,height-16);VanillaGui.title(g,font,reply==null?text("catalogue.title","Kingdoms"):reply.titleComponent(),width/2,18,w-24);
        if(reply!=null&&(reply.mode().equals("tasks")||reply.mode().equals("choice")))VanillaGui.title(g,font,pending!=null?text("loading","Loading…"):reply.multi()?text("hint.multi","Select choices, then use selections"):text("hint.choose","Choose an entry; arrow keys move, Enter selects"),width/2,67,w-24);
        super.render(g,mx,my,partial);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    /** Two-line list row: the name, then a dimmer summary, so details are readable without hovering. */
    static final class OptionRow extends Button {
        private final Component second;
        OptionRow(int x,int y,int width,int height,Component first,Component second,OnPress press){super(x,y,width,height,first,press,DEFAULT_NARRATION);this.second=second;}
        @Override protected void renderWidget(GuiGraphics g,int mx,int my,float partial){
            g.blitNineSliced(WIDGETS_LOCATION,getX(),getY(),width,height,20,4,200,20,0,46+(!active?0:isHoveredOrFocused()?2:1)*20);
            var font=Minecraft.getInstance().font;int inner=width-12;var language=net.minecraft.locale.Language.getInstance();
            g.drawString(font,language.getVisualOrder(font.substrByWidth(getMessage(),inner)),getX()+6,getY()+3,active?0xFFFFFF:0xA0A0A0);
            g.drawString(font,language.getVisualOrder(font.substrByWidth(second.copy().withStyle(ChatFormatting.GRAY),inner)),getX()+6,getY()+14,active?0xA0A0A0:0x707070);
        }
    }
}
