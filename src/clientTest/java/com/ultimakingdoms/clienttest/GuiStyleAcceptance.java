package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.client.*;
import com.ultimakingdoms.client.politics.KingdomScreen;
import com.ultimakingdoms.client.townstead.TownsteadBlueprintHeader;
import com.ultimakingdoms.compat.townstead.network.TownsteadCivicHeaderPacket.Header;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;

/** Opt-in visual tour of the real screens at three GUI scales; never submitted in runtime jars. */
final class GuiStyleAcceptance {
    private final Path output;
    private int step, ticks;
    private Screen screen;
    private boolean captured, behaviorChecked;
    private VillageLedgerScreen ledger;
    GuiStyleAcceptance(Path output) { this.output=output; }
    boolean tick(Minecraft mc) throws Exception {
        if(step==18)return true;
        int page=step%6;
        if(screen==null) {
            mc.options.guiScale().set(2+step/6);mc.options.pauseOnLostFocus=false;mc.resizeDisplay();
            screen=switch(page) {
                case 0 -> ledger=new VillageLedgerScreen(null);
                case 1 -> ledger;
                case 2 -> new GuildScreen(null);
                case 3 -> new KingdomScreen(null,"ultima_kingdoms:serenum","");
                case 4 -> new KingdomActionsScreen(null,"settlement.create","");
                default -> new HeaderPreview();
            };
            mc.setScreen(screen);ticks=0;captured=false;behaviorChecked=false;
            if(page==1){var select=VillageLedgerScreen.class.getDeclaredMethod("selectRow",int.class);select.setAccessible(true);select.invoke(ledger,0);}
        }
        if(mc.screen!=screen)throw new AssertionError("GUI tour screen changed unexpectedly");
        if(++ticks>240)throw new AssertionError("GUI tour timed out: "+step);
        boolean ready=switch(page) {
            case 0 -> !((boolean)field(screen,"loading"))&&field(screen,"page")!=null;
            case 1 -> field(screen,"selected")!=null;
            case 2 -> field(screen,"view")!=null&&field(screen,"pending")==null;
            case 3 -> field(screen,"page")!=null&&field(screen,"pending")==null;
            case 4 -> field(screen,"reply")!=null&&field(screen,"pending")==null;
            default -> true;
        };
        if(!ready||ticks<30)return false;
        if (!behaviorChecked) {
            if (page == 2) {
                AbstractWidget record = (AbstractWidget)field(screen,"record");
                screen.setFocused(record);
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_END,0,0);
                if ((int)field(record,"scroll") <= 0) throw new AssertionError("Guild record keyboard scroll did not advance");
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
                if ((int)field(record,"scroll") != 0) throw new AssertionError("Guild record Home did not reset scroll");
                record.mouseScrolled(record.getX()+12,record.getY()+30,-1);
                if ((int)field(record,"scroll") <= 0) throw new AssertionError("Guild record wheel scroll did not advance");
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
                screen.setFocused(null);
            }
            if (page == 4) {
                var target=(net.minecraft.client.gui.components.EditBox)field(screen,"input");
                target.setValue("draft-retained");
                screen.resize(mc,screen.width,screen.height);
                target=(net.minecraft.client.gui.components.EditBox)field(screen,"input");
                if (!target.getValue().equals("draft-retained")) throw new AssertionError("Kingdom form lost its draft on resize");
                target.setValue("");
            }
            behaviorChecked=true;
        }
        for(var child:screen.children())if(child instanceof AbstractWidget widget&&widget.visible) {
            if(widget.getX()<0||widget.getY()<0||widget.getX()+widget.getWidth()>screen.width||widget.getY()+widget.getHeight()>screen.height)
                throw new AssertionError("GUI widget outside screen: "+widget.getMessage().getString());
        }
        String name="gui-"+(2+step/6)+"-"+new String[]{"ledger","settlement","guild","kingdom","form","blueprint-header"}[page]+".png";
        Path image=output.resolve("screenshots").resolve(name);
        if(!captured){Screenshot.grab(output.toFile(),name,mc.getMainRenderTarget(),message->{});captured=true;return false;}
        if(!Files.isRegularFile(image)||Files.size(image)==0)return false;
        System.out.println("GUI_STYLE_PASS "+name);step++;screen=null;return step==18;
    }
    private static Object field(Object instance,String name)throws Exception {var f=instance.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(instance);}
    private static final class HeaderPreview extends Screen {
        HeaderPreview(){super(Component.literal("Blueprint header"));}
        @Override public void render(GuiGraphics graphics,int x,int y,float tick) {
            renderBackground(graphics);
            try {
                var draw=TownsteadBlueprintHeader.class.getDeclaredMethod("draw",GuiGraphics.class,Screen.class,Header.class);draw.setAccessible(true);
                draw.invoke(null,graphics,this,new Header("Willowbrook",new ResourceLocation("ultima_kingdoms:serenum"),"kingdom.ultima_kingdoms.serenum",new ResourceLocation("ultima_kingdoms:serenum"),0x426946,"respected"));
            }catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}
            super.render(graphics,x,y,tick);
        }
        @Override public boolean isPauseScreen(){return false;}
    }
}
