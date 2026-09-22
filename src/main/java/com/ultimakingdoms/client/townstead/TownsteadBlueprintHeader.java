package com.ultimakingdoms.client.townstead;

import com.mojang.logging.LogUtils;
import com.ultimakingdoms.client.HeraldryRenderer;
import com.ultimakingdoms.client.VanillaGui;
import com.ultimakingdoms.compat.townstead.TownsteadIntegrationConfig;
import com.ultimakingdoms.compat.townstead.network.TownsteadCivicHeaderPacket;
import com.ultimakingdoms.compat.townstead.network.TownsteadCivicNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Soft Forge render hook for MCA/Townstead Blueprint screens. */
public final class TownsteadBlueprintHeader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long RETRY_MILLIS = 1_000L;
    private static final long REFRESH_MILLIS = 5_000L;
    private static final int MAX_HEADER_WIDTH = 230;
    private static final int MIN_HEADER_WIDTH = 96;
    private static final int HEADER_HEIGHT = 22;
    private static final int LEFT_CONTROL_EDGE = 29;
    private static final int RIGHT_CONTROL_WIDTH = 55;
    private static final String[] SCREEN_CLASSES = {
            "forge.net.conczin.mca.client.gui.BlueprintScreen",
            "forge.net.mca.client.gui.BlueprintScreen",
            "net.conczin.mca.client.gui.BlueprintScreen",
            "net.mca.client.gui.BlueprintScreen"
    };
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static Screen activeScreen;
    private static Key activeKey;
    private static long requestId;
    private static long lastRequestMillis;
    private static boolean responseReceived;
    private static TownsteadCivicHeaderPacket.Header header;

    private TownsteadBlueprintHeader() {
    }

    public static void init() {
        TownsteadCivicNetwork.setClientReceiver(TownsteadBlueprintHeader::receive);
        MinecraftForge.EVENT_BUS.register(TownsteadBlueprintHeader.class);
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if (!TownsteadIntegrationConfig.ENABLE_BLUEPRINT_HEADER.get() || !isBlueprint(event.getScreen())) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        Optional<Integer> villageId = villageId(event.getScreen());
        if (villageId.isEmpty()) return;
        ResourceLocation dimension = minecraft.player.level().dimension().location();
        Key key = new Key(dimension, villageId.get());
        if (activeScreen != event.getScreen() || !key.equals(activeKey)) {
            activeScreen = event.getScreen();
            activeKey = key;
            header = null;
            responseReceived = false;
            sendRequest(dimension, villageId.get());
        } else {
            long interval = responseReceived ? REFRESH_MILLIS : RETRY_MILLIS;
            if (System.currentTimeMillis() - lastRequestMillis >= interval) {
                sendRequest(dimension, villageId.get());
            }
        }
        if (header != null && !isTownsteadPage(event.getScreen())) {
            draw(event.getGuiGraphics(), event.getScreen(), header);
        }
    }

    @SubscribeEvent
    public static void closing(ScreenEvent.Closing event) {
        if (event.getScreen() == activeScreen) {
            activeScreen = null;
            activeKey = null;
            header = null;
            requestId = 0;
            lastRequestMillis = 0;
            responseReceived = false;
        }
    }

    private static void receive(TownsteadCivicHeaderPacket packet) {
        Key key = new Key(packet.dimension(), packet.villageId());
        if (packet.requestId() == requestId && key.equals(activeKey)) {
            responseReceived = true;
            header = packet.header().orElse(null);
        }
    }

    private static void sendRequest(ResourceLocation dimension, int villageId) {
        responseReceived = false;
        requestId = TownsteadCivicNetwork.request(dimension, villageId);
        lastRequestMillis = System.currentTimeMillis();
    }

    private static void draw(GuiGraphics graphics, Screen screen, TownsteadCivicHeaderPacket.Header value) {
        Minecraft minecraft = Minecraft.getInstance();
        int availableWidth = screen.width - LEFT_CONTROL_EDGE - RIGHT_CONTROL_WIDTH;
        if (availableWidth < MIN_HEADER_WIDTH) return;
        int width = Math.min(MAX_HEADER_WIDTH, availableWidth);
        int x = Math.max(LEFT_CONTROL_EDGE,
                Math.min((screen.width - width) / 2, screen.width - RIGHT_CONTROL_WIDTH - width));
        int y = 4;
        VanillaGui.panel(graphics, x, y, width, HEADER_HEIGHT);
        HeraldryRenderer.render(graphics, value.heraldryIcon(), value.uiColor(), x + 6, y + 3, 16);
        Component kingdom = Component.translatable(value.kingdomNameKey());
        int textWidth = width - 29;
        Component title = fit(minecraft,
                Component.literal(value.settlementName()).append(" · ").append(kingdom), textWidth);
        graphics.drawString(minecraft.font,
                title, x + 25, y + 3, VanillaGui.TEXT, false);
        graphics.drawString(minecraft.font,
                fit(minecraft, Component.literal("Standing: " + display(value.standingTier())), textWidth),
                x + 25, y + 12, VanillaGui.SECONDARY, false);
    }

    private static Component fit(Minecraft minecraft, Component value, int maxWidth) {
        String text = value.getString();
        if (minecraft.font.width(text) <= maxWidth) return value;
        String ellipsis = "...";
        int contentWidth = Math.max(0, maxWidth - minecraft.font.width(ellipsis));
        return Component.literal(minecraft.font.plainSubstrByWidth(text, contentWidth) + ellipsis);
    }

    private static String display(String value) {
        String text = value.replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static boolean isBlueprint(Screen screen) {
        String name = screen.getClass().getName();
        for (String candidate : SCREEN_CLASSES) if (candidate.equals(name)) return true;
        return false;
    }

    private static boolean isTownsteadPage(Screen screen) {
        try {
            Field field = screen.getClass().getDeclaredField("page");
            field.setAccessible(true);
            Object value = field.get(screen);
            return value instanceof String page && page.startsWith("townstead_");
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static Optional<Integer> villageId(Screen screen) {
        try {
            Object village;
            try {
                Method accessor = screen.getClass().getMethod("townstead$getVillage");
                village = accessor.invoke(screen);
            } catch (ReflectiveOperationException noAccessor) {
                Field field = screen.getClass().getDeclaredField("village");
                field.setAccessible(true);
                village = field.get(screen);
            }
            if (village == null) return Optional.empty();
            Object id = village.getClass().getMethod("getId").invoke(village);
            return id instanceof Number number && number.intValue() >= 0
                    ? Optional.of(number.intValue()) : Optional.empty();
        } catch (Throwable throwable) {
            if (FAILURE_REPORTED.compareAndSet(false, true)) {
                LOGGER.warn("[Ultima Kingdoms] Townstead Blueprint civic header probe failed; server integration remains active",
                        throwable);
            }
            return Optional.empty();
        }
    }

    private record Key(ResourceLocation dimension, int villageId) {
    }
}
