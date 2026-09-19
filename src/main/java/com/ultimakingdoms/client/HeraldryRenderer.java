package com.ultimakingdoms.client;

import com.ultimakingdoms.presentation.KingdomSummary;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

public final class HeraldryRenderer {
    private HeraldryRenderer() {
    }

    public static void render(GuiGraphics graphics, KingdomSummary kingdom, int x, int y, int size) {
        int background = 0xFF000000 | kingdom.uiColor() & 0xFFFFFF;
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xCC000000);
        graphics.fill(x, y, x + size, y + size, background);
        ResourceLocation logical = kingdom.heraldryIcon();
        ResourceLocation texture = new ResourceLocation(logical.getNamespace(),
                "textures/gui/heraldry/" + logical.getPath() + ".png");
        graphics.blit(texture, x, y, 0.0F, 0.0F, size, size, size, size);
    }
}
