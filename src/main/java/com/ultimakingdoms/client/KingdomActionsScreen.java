package com.ultimakingdoms.client;

import com.ultimakingdoms.interaction.InteractionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Named task browser and server-reviewed forms, with no chat or command execution path. */
public final class KingdomActionsScreen extends Screen {
    private final Screen parent;
    private final String initialTask,initialSearch;
    private InteractionNetwork.Reply reply;
    private UUID pending,lateRequest,actionRequest;
    private InteractionNetwork.Request lastSent;
    private int timeout,localPage;
    private EditBox input;
    private MenuTextPanel detail;
    private String notice="";
    private int left,w,rows;
    public KingdomActionsScreen(Screen parent,String initialTask,String initialSearch){super(Component.literal("Kingdoms"));this.parent=parent;this.initialTask=initialTask;this.initialSearch=initialSearch;}
    @Override public void resize(Minecraft client,int width,int height){String draft=input==null?null:input.getValue();super.resize(client,width,height);if(draft!=null&&input!=null)input.setValue(draft);}
    @Override protected void init(){
        input=null;
        w=Math.min(780,width-16);left=(width-w)/2;rows=Math.max(1,(height-166)/23);
        if(reply==null){detail=addRenderableWidget(new MenuTextPanel(left+8,42,w-16,height-84,Component.literal("Connecting")));detail.setContent(List.of(Component.literal(notice.isEmpty()?"Requesting your available tasks…":notice)));
            addRenderableWidget(Button.builder(Component.literal("Close"),b->onClose()).bounds(left+8,height-32,80,20).build());
            if(pending==null)send(initialTask.isEmpty()?"LIST":"TASK",initialTask,initialSearch,0);return;}
        localPage=Math.min(localPage,Math.max(0,(reply.options().size()-1)/rows));
        boolean listing=reply.mode().equals("tasks")||reply.mode().equals("choice");
        if(listing){
            input=addRenderableWidget(new EditBox(font,left+8,37,w-100,20,Component.literal("Search by name or task")));input.setMaxLength(100);input.setValue(reply.value());input.setHint(Component.literal("Search by name or task"));
            addRenderableWidget(Button.builder(Component.literal("Search"),b->search()).bounds(left+w-86,37,78,20).build());
            int start=localPage*rows;
            for(int i=0;i<rows&&start+i<reply.options().size();i++){
                var option=reply.options().get(start+i);String name=(option.selected()?"✓ ":"")+option.label();
                if(font.width(name)>w-34)name=font.plainSubstrByWidth(name,w-46)+"…";
                addRenderableWidget(Button.builder(Component.literal(name),b->{localPage=0;send(reply.mode().equals("tasks")?"TASK":"PICK",option.key(),"",0);}).bounds(left+8,87+i*23,w-16,21)
                    .tooltip(Tooltip.create(Component.literal(option.label()+"\n"+option.detail()))).build());
            }
            if(reply.options().isEmpty()){detail=addRenderableWidget(new MenuTextPanel(left+8,87,w-16,height-160,Component.literal("No choices")));detail.setContent(List.of(Component.literal(reply.detail())));}
            int y=height-61;
            var previous=addRenderableWidget(Button.builder(Component.literal("Previous page"),b->{if(localPage>0){localPage--;rebuildWidgets();}else send(reply.mode().equals("tasks")?"LIST":"FILTER","",input.getValue(),reply.page()-1);}).bounds(left+8,y,Math.min(125,(w-24)/3),20).build());previous.active=localPage>0||reply.page()>0;
            var next=addRenderableWidget(Button.builder(Component.literal("Next page"),b->{if((localPage+1)*rows<reply.options().size()){localPage++;rebuildWidgets();}else{localPage=0;send(reply.mode().equals("tasks")?"LIST":"FILTER","",input.getValue(),reply.page()+1);}}).bounds(left+w-Math.min(125,(w-24)/3)-8,y,Math.min(125,(w-24)/3),20).build());next.active=(localPage+1)*rows<reply.options().size()||reply.more();
            if(reply.multi())addRenderableWidget(Button.builder(Component.literal("Use selections"),b->send("NEXT","","",0)).bounds(left+w/3,y,w/3,20).build());
        }else if(reply.mode().equals("text")){
            detail=addRenderableWidget(new MenuTextPanel(left+8,40,w-16,height-145,Component.literal(reply.title())));detail.setContent(List.of(Component.literal(reply.detail())));
            input=addRenderableWidget(new EditBox(font,left+8,height-96,w-16,20,Component.literal(reply.title())));input.setMaxLength(512);input.setValue(reply.value());
            addRenderableWidget(Button.builder(Component.literal("Continue"),b->send("NEXT",input.getValue(),"",0)).bounds(left+w-108,height-61,100,20).build());setInitialFocus(input);
        }else{
            detail=addRenderableWidget(new MenuTextPanel(left+8,40,w-16,height-109,Component.literal(reply.title())));detail.setContent(Arrays.stream(reply.detail().split("\n")).map(Component::literal).map(c->(Component)c).toList());
            if((reply.mode().equals("error")||reply.mode().equals("result"))&&lastSent!=null)addRenderableWidget(Button.builder(Component.literal("Check this action"),b->send("CHECK",(actionRequest==null?lastSent.request():actionRequest).toString(),"",0)).bounds(left+w/2-100,height-61,200,20).build());
            if(reply.mode().equals("review"))addRenderableWidget(Button.builder(Component.literal("Apply reviewed action"),b->send("APPLY","","",0)).bounds(left+w/2-100,height-61,200,20).build());
        }
        int bw=(w-32)/3;
        var back=addRenderableWidget(Button.builder(Component.literal("Back"),b->{localPage=0;send("BACK","","",0);}).bounds(left+8,height-32,bw,20).build());back.active=reply.back();
        addRenderableWidget(Button.builder(Component.literal("All tasks"),b->{localPage=0;send("LIST","","",0);}).bounds(left+16+bw,height-32,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"),b->onClose()).bounds(left+24+2*bw,height-32,bw,20).build());
        if(pending!=null)children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).forEach(widget->widget.active=false);
    }
    private void search(){localPage=0;send(reply.mode().equals("tasks")?"LIST":"FILTER","",input.getValue(),0);}
    private void send(String operation,String value,String search,int page){
        if(pending!=null)return;lateRequest=null;pending=UUID.randomUUID();timeout=200;notice="";
        lastSent=new InteractionNetwork.Request(pending,reply==null?InteractionNetwork.NONE:reply.session(),reply==null?InteractionNetwork.NONE:reply.state(),operation,value,search,page);
        if(operation.equals("TASK"))actionRequest=pending;
        InteractionNetwork.send(lastSent);
        children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).forEach(widget->widget.active=false);
    }
    public static void receive(InteractionNetwork.Reply value){if(Minecraft.getInstance().screen instanceof KingdomActionsScreen screen&&(value.request().equals(screen.pending)||value.request().equals(screen.lateRequest))){screen.reply=value;screen.pending=null;screen.lateRequest=null;screen.timeout=0;screen.rebuildWidgets();}}
    @Override public void tick(){if(input!=null)input.tick();if(pending!=null&&--timeout<=0){lateRequest=pending;pending=null;notice="No reply yet. Inspect current status before repeating an action.";reply=new InteractionNetwork.Reply(UUID.randomUUID(),reply==null?InteractionNetwork.NONE:reply.session(),reply==null?InteractionNetwork.NONE:reply.state(),"error","Request timed out",notice,List.of(),"",0,false,false,false);rebuildWidgets();}}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if((key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER)&&input!=null&&input.isFocused()&&pending==null){if(reply.mode().equals("text"))send("NEXT",input.getValue(),"",0);else search();return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){renderBackground(g);VanillaGui.panel(g,left,8,w,height-16);VanillaGui.title(g,font,Component.literal(reply==null?"Kingdoms":reply.title()),width/2,18,w-24);
        if(reply!=null&&(reply.mode().equals("tasks")||reply.mode().equals("choice")))VanillaGui.title(g,font,Component.literal(pending!=null?"Loading…":reply.multi()?"Select choices, then use selections":"Choose an entry; hover for details"),width/2,67,w-24);
        super.render(g,mx,my,partial);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
