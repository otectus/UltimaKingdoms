package com.ultimakingdoms.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.ultimakingdoms.presentation.KingdomSummary;
import com.ultimakingdoms.presentation.SettlementSummary;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/** A compact heraldic arrival plaque, measured in GUI pixels. */
final class SettlementOverlayRenderer {
    private static final int HEIGHT = 62;
    private static final int GOLD = 0xC8A767;

    private SettlementOverlayRenderer() {
    }

    static void render(GuiGraphics graphics, Font font, SettlementSummary settlement, KingdomSummary kingdom,
                       int screenWidth, int screenHeight, int configuredY, int ticks, int duration, float partialTick) {
        float remaining = ticks - partialTick;
        float opacity = Math.min(Mth.clamp((duration - remaining) / 8.0F, 0, 1),
                Mth.clamp(remaining / 12.0F, 0, 1));
        // Minecraft treats near-zero text alpha as opaque; skip the invisible frames entirely.
        if (opacity < 0.02F) return;

        Component title = Component.literal(settlement.displayName());
        Component subtitle = Component.translatable("message.ultima_kingdoms.kingdom_of",
                Component.translatable(kingdom.translationKey()));
        int desiredTextWidth = Math.max(Mth.ceil(font.width(title) * 1.4F), font.width(subtitle));
        int width = Math.min(Math.max(224, desiredTextWidth + 84), Math.min(360, screenWidth - 16));
        if (width < 100 || screenHeight < HEIGHT + 4) return;
        int left = (screenWidth - width) / 2;
        int top = Mth.clamp(configuredY, 2, screenHeight - HEIGHT - 2);
        int right = left + width;
        int bottom = top + HEIGHT;

        graphics.fill(left + 2, top + 3, right + 2, bottom + 3, color(0x000000, opacity * 0.25F));
        graphics.fillGradient(left, top, right, bottom,
                color(0x181B20, opacity * 0.92F), color(0x090C10, opacity * 0.88F));
        graphics.fill(left, top, right, top + 1, color(GOLD, opacity * 0.8F));
        graphics.fill(left, bottom - 1, right, bottom, color(GOLD, opacity * 0.45F));
        graphics.fill(left, top + 1, left + 1, bottom - 1, color(GOLD, opacity * 0.35F));
        graphics.fill(right - 1, top + 1, right, bottom - 1, color(GOLD, opacity * 0.35F));
        for (int corner : new int[]{left, right - 9}) {
            graphics.fill(corner, top, corner + 9, top + 2, color(0xE8CD91, opacity));
            graphics.fill(corner, bottom - 2, corner + 9, bottom, color(GOLD, opacity));
        }

        int crestX = left + 11;
        int crestY = top + 12;
        graphics.fill(crestX - 3, crestY - 3, crestX + 37, crestY + 37, color(GOLD, opacity * 0.85F));
        graphics.fill(crestX - 2, crestY - 2, crestX + 36, crestY + 36, color(0x111319, opacity));
        graphics.fill(crestX, crestY, crestX + 34, crestY + 34, color(kingdom.uiColor(), opacity));
        ResourceLocation logical = kingdom.heraldryIcon();
        ResourceLocation texture = new ResourceLocation(logical.getNamespace(),
                "textures/gui/heraldry/" + logical.getPath() + ".png");
        RenderSystem.enableBlend();
        graphics.setColor(1, 1, 1, opacity);
        try {
            graphics.blit(texture, crestX + 1, crestY + 1, 0.0F, 0.0F, 32, 32, 32, 32);
        } finally {
            graphics.setColor(1, 1, 1, 1);
            RenderSystem.disableBlend();
        }
        graphics.fill(crestX + 14, crestY + 38, crestX + 20, crestY + 39, color(GOLD, opacity));
        graphics.fill(crestX + 16, crestY + 39, crestX + 18, crestY + 41, color(GOLD, opacity));

        int textLeft = left + 62;
        int textWidth = width - 76;
        int textCenter = textLeft + textWidth / 2;
        float titleScale = Math.max(1.0F, Math.min(1.4F, (float) textWidth / Math.max(1, font.width(title))));
        FormattedCharSequence fittedTitle = Language.getInstance().getVisualOrder(
                font.ellipsize(title, (int) (textWidth / titleScale)));
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(textCenter, top + 14, 0);
            graphics.pose().scale(titleScale, titleScale, 1);
            graphics.drawString(font, fittedTitle, -font.width(fittedTitle) / 2, 0, color(0xFFF0CF, opacity), true);
        } finally {
            graphics.pose().popPose();
        }
        int ruleY = top + 33;
        graphics.fill(textLeft, ruleY, textCenter - 3, ruleY + 1, color(GOLD, opacity * 0.4F));
        graphics.fill(textCenter + 3, ruleY, textLeft + textWidth, ruleY + 1, color(GOLD, opacity * 0.4F));
        graphics.fill(textCenter - 1, ruleY - 1, textCenter + 1, ruleY + 2, color(GOLD, opacity));
        FormattedCharSequence fittedSubtitle = Language.getInstance().getVisualOrder(font.ellipsize(subtitle, textWidth));
        graphics.drawString(font, fittedSubtitle, textCenter - font.width(fittedSubtitle) / 2, top + 42,
                color(0xD4C5AA, opacity), true);
    }

    private static int color(int rgb, float opacity) {
        return (Mth.clamp(Math.round(opacity * 255), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }
}
