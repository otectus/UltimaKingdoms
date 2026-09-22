package com.ultimakingdoms.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Focusable, clipped text region shared by menus, with mouse and keyboard scrolling. */
public final class MenuTextPanel extends AbstractWidget {
    private List<Component> content = List.of();
    private List<FormattedCharSequence> lines = List.of();
    private int scroll;
    public MenuTextPanel(int x, int y, int width, int height, Component heading) {
        super(x, y, width, height, heading);
    }
    public void setContent(List<Component> value) {
        if (content.equals(value)) return;
        content = value.stream().map(Component::copy).map(c -> (Component)c).toList();
        var font = Minecraft.getInstance().font;
        var wrapped = new ArrayList<FormattedCharSequence>();
        for (Component paragraph : content) {
            if (!wrapped.isEmpty()) wrapped.add(FormattedCharSequence.EMPTY);
            wrapped.addAll(font.split(paragraph, Math.max(20, width - 30)));
        }
        lines = wrapped; scroll = 0;
    }
    private int visibleLines() { return Math.max(1, (height - 32) / 12); }
    private int maximum() { return Math.max(0, lines.size() - visibleLines()); }
    private void move(int amount) { scroll = Math.max(0, Math.min(maximum(), scroll + amount)); }
    @Override protected void renderWidget(GuiGraphics graphics, int mx, int my, float partial) {
        var font = Minecraft.getInstance().font;
        VanillaGui.panel(graphics, getX(), getY(), width, height);
        VanillaGui.title(graphics, font, getMessage(), getX() + width / 2, getY() + 8, width - 20);
        graphics.enableScissor(getX() + 7, getY() + 24, getX() + width - 15, getY() + height - 5);
        for (int i = 0; i < visibleLines() && i + scroll < lines.size(); i++)
            graphics.drawString(font, lines.get(i + scroll), getX() + 9, getY() + 24 + i * 12, VanillaGui.TEXT, false);
        graphics.disableScissor();
        if (maximum() > 0) {
            int track = height - 32;
            int thumb = Math.max(12, track * visibleLines() / lines.size());
            VanillaGui.panel(graphics, getX() + width - 12, getY() + 24, 8, track);
            VanillaGui.panel(graphics, getX() + width - 12, getY() + 24 + (track - thumb) * scroll / maximum(), 8, thumb);
        }
        if (isFocused()) graphics.renderOutline(getX() + 2, getY() + 2, width - 4, height - 4, 0xFFFFFFFF);
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (!isMouseOver(x, y) || delta == 0) return false;
        move(delta > 0 ? -3 : 3); return true;
    }
    @Override public void onClick(double x, double y) { if (x >= getX() + width - 16) seek(y); }
    @Override protected void onDrag(double x, double y, double dx, double dy) {
        if (x >= getX() + width - 20) seek(y);
    }
    private void seek(double y) {
        scroll = Math.max(0, Math.min(maximum(), (int)Math.round((y - getY() - 24) / Math.max(1, height - 33.0) * maximum())));
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        switch (key) {
            case GLFW.GLFW_KEY_UP -> move(-1);
            case GLFW.GLFW_KEY_DOWN -> move(1);
            case GLFW.GLFW_KEY_PAGE_UP -> move(-visibleLines());
            case GLFW.GLFW_KEY_PAGE_DOWN -> move(visibleLines());
            case GLFW.GLFW_KEY_HOME -> scroll = 0;
            case GLFW.GLFW_KEY_END -> scroll = maximum();
            default -> { return super.keyPressed(key, scan, modifiers); }
        }
        return true;
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
        output.add(NarratedElementType.USAGE, Component.translatable("menu.ultima_kingdoms.scroll_help"));
        output.add(NarratedElementType.HINT, Component.literal(String.join(". ", content.stream().map(Component::getString).toList())));
    }
}
