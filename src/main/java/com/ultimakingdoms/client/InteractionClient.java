package com.ultimakingdoms.client;

import com.ultimakingdoms.interaction.InteractionNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid="ultima_kingdoms",value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class InteractionClient {
    private static final KeyMapping OPEN=new KeyMapping("key.ultima_kingdoms.actions",GLFW.GLFW_KEY_K,"key.categories.misc");
    @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event){event.register(OPEN);}
    public static void init(){InteractionNetwork.receive(KingdomActionsScreen::receive);}
    public static void open(Screen parent,String task,String search){Minecraft.getInstance().setScreen(new KingdomActionsScreen(parent,task,search));}
    static void tickKeys(){while(OPEN.consumeClick())if(Minecraft.getInstance().player!=null&&Minecraft.getInstance().screen==null)open(null,"","");}
    private InteractionClient(){}
}
