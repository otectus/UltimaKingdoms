package com.ultimakingdoms.client;

import com.ultimakingdoms.warfare.WarfareNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Server intelligence and direct named GUI task entry points. */
public final class WarRoomScreen extends Screen {
    private final Screen parent;
    private final UUID settlement;
    private final String settlementName;
    private UUID request;
    private WarfareNetwork.Snapshot snapshot;
    private int timeout;
    private Component error;
    private final List<Button> actions=new ArrayList<>();
    private MenuTextPanel intelligence;
    private Button refresh;
    public WarRoomScreen(Screen parent,UUID settlement,String settlementName){super(Component.literal("War room"));this.parent=parent;this.settlement=settlement;this.settlementName=settlementName;}
    private int panelWidth(){return Math.min(500,width-12);}
    private int panelHeight(){return Math.min(360,height-12);}
    private int left(){return (width-panelWidth())/2;}
    private int top(){return (height-panelHeight())/2;}
    private int bottom(){return top()+panelHeight();}
    private static Component label(String key){return Component.translatable("menu.ultima_kingdoms."+key);}
    @Override protected void init(){
        actions.clear();
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),b->onClose()).bounds(left()+10,bottom()-30,74,20).build());
        refresh=addRenderableWidget(Button.builder(Component.literal("Refresh"),b->request()).bounds(left()+panelWidth()-84,bottom()-30,74,20).build());
        intelligence=addRenderableWidget(new MenuTextPanel(left()+8,top()+50,panelWidth()-16,panelHeight()-178,label("local_intelligence")));
        if(snapshot==null&&timeout==0)request();
        else {updateContent();rebuildActions();}
    }
    private void request(){
        request=UUID.randomUUID();snapshot=null;error=null;timeout=100;
        actions.forEach(this::removeWidget);actions.clear();refresh.active=false;updateContent();WarfareNetwork.request(request,settlement);
    }
    public static boolean receive(WarfareNetwork.Snapshot value){
        if(net.minecraft.client.Minecraft.getInstance().screen instanceof WarRoomScreen screen&&value.request().equals(screen.request)&&screen.settlement.equals(value.settlement())){
            screen.snapshot=value;screen.timeout=0;screen.error=null;screen.refresh.active=true;screen.updateContent();screen.rebuildActions();return true;
        }return false;
    }
    private void updateContent(){
        intelligence.setContent(snapshot==null?List.of(error==null?Component.literal("Requesting authorized local intelligence…"):error):
                snapshot.lines().stream().filter(line->!line.startsWith("Campaign revision:")).map(Component::literal).map(c->(Component)c).toList());
    }
    private void rebuildActions(){
        actions.forEach(this::removeWidget);actions.clear();if(snapshot==null)return;
        int buttonWidth=(panelWidth()-26)/2;
        for(int i=0;i<snapshot.commands().size();i++){
            var command=snapshot.commands().get(i);
            Button button=Button.builder(Component.literal(command.label()),b->InteractionClient.open(this,command.value(),""))
                    .bounds(left()+10+(i%2)*(buttonWidth+6),bottom()-106+(i/2)*22,buttonWidth,20)
                    .tooltip(Tooltip.create(Component.literal("Choose named targets and review this action."))).build();
            actions.add(addRenderableWidget(button));
        }
    }
    @Override public void tick(){super.tick();if(snapshot==null&&timeout>0&&--timeout==0){error=Component.literal("War-room request timed out. Refresh to retry.");refresh.active=true;updateContent();}}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        renderBackground(graphics);
        VanillaGui.panel(graphics,left(),top(),panelWidth(),panelHeight());
        VanillaGui.title(graphics,font,title,width/2,top()+12,panelWidth()-24);
        VanillaGui.title(graphics,font,Component.literal(settlementName),width/2,top()+30,panelWidth()-24);
        VanillaGui.title(graphics,font,label("war_actions"),width/2,bottom()-120,panelWidth()-24);

        super.render(graphics,mouseX,mouseY,partialTick);
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
