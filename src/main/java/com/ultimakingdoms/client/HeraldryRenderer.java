package com.ultimakingdoms.client;

import com.ultimakingdoms.presentation.KingdomSummary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class HeraldryRenderer {
    private HeraldryRenderer() {
    }

    public static void render(GuiGraphics graphics, KingdomSummary kingdom, int x, int y, int size) {
        render(graphics, kingdom.heraldryIcon(), kingdom.uiColor(), x, y, size);
    }

    public static void render(GuiGraphics graphics, ResourceLocation logical, int uiColor,
                              int x, int y, int size) {
        VanillaGui.panel(graphics, x - 3, y - 3, size + 6, size + 6);
        ResourceLocation texture = new ResourceLocation(logical.getNamespace(),
                "textures/gui/heraldry/" + logical.getPath() + ".png");
        graphics.blit(texture, x, y, 0.0F, 0.0F, size, size, size, size);
    }
}
