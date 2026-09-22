package com.ultimakingdoms.client;

import com.ultimakingdoms.civic.CivicNetwork;
import com.ultimakingdoms.civic.CivicViews;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Own-player record and contextual services; the server remains authoritative. */
public final class GuildScreen extends Screen {
    private final Screen parent;
    private CivicViews.View view;
    private UUID pending;
    private int timeout, index;
    private String outcome = "";
    private Button membership, previous, next, refresh;
    private final List<Button> services = new ArrayList<>();
    private MenuTextPanel record;
    public GuildScreen(Screen parent) { super(Component.translatable("civic.ultima_kingdoms.title")); this.parent=parent; }
    private static Component label(String key) { return Component.translatable("menu.ultima_kingdoms." + key); }
    private int panelWidth() { return Math.min(500, width - 12); }
    private int panelHeight() { return Math.min(360, height - 12); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private int bottom() { return top() + panelHeight(); }
    private int serviceWidth() { return Math.min(146, panelWidth() / 2 - 18); }
    private int serviceX() { return left() + panelWidth() - serviceWidth() - 10; }
    @Override protected void init() {
        services.clear();
        int x=left(), y=top(), w=panelWidth(), footer=bottom()-30;
        previous=addRenderableWidget(Button.builder(Component.literal("<"), b -> request(index-1,CivicNetwork.Action.READ))
                .bounds(x+10,y+30,24,20).tooltip(Tooltip.create(label("previous_guild"))).build());
        next=addRenderableWidget(Button.builder(Component.literal(">"), b -> request(index+1,CivicNetwork.Action.READ))
                .bounds(x+w-34,y+30,24,20).tooltip(Tooltip.create(label("next_guild"))).build());
        record=addRenderableWidget(new MenuTextPanel(x+8,y+65,w-serviceWidth()-26,panelHeight()-116,label("guild_record")));
        String[] names={"civic.introductions","civic.commissions","civic.hospitality","civic.workshop"};
        String[] hints={"introductions_help","commissions_help","hospitality_help","workshop_help"};
        CivicNetwork.Action[] actions={CivicNetwork.Action.INTRODUCE,CivicNetwork.Action.COMMISSIONS,CivicNetwork.Action.HOSPITALITY,CivicNetwork.Action.WORKSHOP};
        for(int i=0;i<actions.length;i++) {
            CivicNetwork.Action action=actions[i];
            services.add(addRenderableWidget(Button.builder(Component.translatable(names[i]), b -> request(index,action))
                    .bounds(serviceX(),y+85+i*22,serviceWidth(),20).tooltip(Tooltip.create(label(hints[i]))).build()));
        }
        membership=addRenderableWidget(Button.builder(Component.empty(), b -> request(index,view!=null&&view.active()?CivicNetwork.Action.LEAVE:CivicNetwork.Action.JOIN))
                .bounds(x+10,footer,Math.min(132,w-178),20).tooltip(Tooltip.create(label("membership_help"))).build());
        refresh=addRenderableWidget(Button.builder(Component.translatable("civic.ultima_kingdoms.refresh"), b -> request(index,CivicNetwork.Action.READ))
                .bounds(x+w-160,footer,72,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(x+w-84,footer,74,20).build());
        if(view==null&&pending==null) request(index,CivicNetwork.Action.READ);
        else updateButtons();
    }
    private void request(int page, CivicNetwork.Action action) {
        index=Math.max(0,page); pending=UUID.randomUUID(); timeout=60; outcome="";
        CivicNetwork.send(new CivicNetwork.Query(pending,index,action,view==null?"":view.organization()));
        updateButtons();
    }
    public static void receive(CivicNetwork.Reply reply) {
        if (!(Minecraft.getInstance().screen instanceof GuildScreen screen) || !reply.request().equals(screen.pending)) return;
        screen.view=reply.view(); screen.index=reply.view().index(); screen.pending=null; screen.timeout=0;
        screen.outcome=reply.outcome().isEmpty()?"":com.ultimakingdoms.civic.CivicText.reason(reply.outcome()).getString(); screen.updateButtons();
    }
    private void updateButtons() {
        boolean ready=pending==null && view!=null && view.total()>0;
        services.forEach(button -> button.active=ready);
        membership.active=ready;
        membership.setMessage(Component.translatable(view!=null&&view.active()?"civic.ultima_kingdoms.leave":"civic.ultima_kingdoms.join"));
        previous.active=ready && index>0; next.active=ready && index+1<view.total(); refresh.active=pending==null;
        record.setContent(view==null?List.of(Component.translatable("civic.ultima_kingdoms.loading")):view.lines());
    }
    @Override public void tick() {
        if (pending!=null && --timeout<=0) { pending=null; outcome=Component.translatable("civic.ultima_kingdoms.timeout").getString(); updateButtons(); }
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        renderBackground(graphics);
        int x=left(),y=top(),w=panelWidth();
        VanillaGui.panel(graphics,x,y,w,panelHeight());
        VanillaGui.title(graphics,font,title,width/2,y+12,w-20);
        if(view!=null) {
            VanillaGui.title(graphics,font,Component.translatable(view.nameKey()),width/2,y+33,w-90);
            VanillaGui.title(graphics,font,label("guild_page").copy().append(" "+(view.total()==0?0:view.index()+1)+" / "+view.total()),width/2,y+50,w-90);
        }
        VanillaGui.panel(graphics,serviceX()-2,y+65,serviceWidth()+4,panelHeight()-116);
        VanillaGui.title(graphics,font,label("guild_services"),serviceX()+serviceWidth()/2,y+73,serviceWidth()-6);
        Component message=pending!=null?Component.translatable("civic.ultima_kingdoms.loading"):outcome.isEmpty()?label("guild_context"):Component.literal(outcome);
        VanillaGui.title(graphics,font,message,width/2,bottom()-45,w-24);
        super.render(graphics,mouseX,mouseY,partialTick);
        if(mouseY>=bottom()-48&&mouseY<bottom()-32&&mouseX>=x&&mouseX<x+w)
            graphics.renderTooltip(font,font.split(message,Math.min(360,w-24)),mouseX,mouseY);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
