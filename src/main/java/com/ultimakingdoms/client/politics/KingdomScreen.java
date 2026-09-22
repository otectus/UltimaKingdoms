package com.ultimakingdoms.client.politics;

import com.ultimakingdoms.api.politics.Politics.*;
import com.ultimakingdoms.politics.PoliticalNetwork;
import com.ultimakingdoms.client.VanillaGui;
import com.ultimakingdoms.client.MenuTextPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** A disposable, paginated extension of the village ledger. No authoritative client state. */
public final class KingdomScreen extends Screen {
    private static final List<String> TABS = List.of("overview", "council", "agreements", "petitions", "institutions", "honors", "history");
    private final Screen parent;
    private final String kingdom;
    private final String settlement;
    private String tab = "overview";
    private Page page;
    private Row selected;
    private int offset, rowScroll, detailScroll;
    private String status = "";
    private PoliticalNetwork.Query pending;
    private int timeout, attempts;
    private MenuTextPanel detailPanel;
    public KingdomScreen(Screen parent, String kingdom, String settlement) {
        super(Component.translatable("politics.ultima_kingdoms.kingdom"));
        this.parent = parent; this.kingdom = kingdom; this.settlement = settlement;
    }
    private static Component label(String key) { return Component.translatable("politics.ultima_kingdoms." + key); }
    public static void receive(PoliticalNetwork.Reply reply) {
        if (Minecraft.getInstance().screen instanceof KingdomScreen screen) screen.accept(reply);
    }
    private void accept(PoliticalNetwork.Reply reply) {
        if (pending == null || !reply.page().requestId().equals(pending.id()) || !reply.page().kingdom().equals(kingdom)) return;
        page = reply.page(); pending = null; timeout = 0;
        status = reply.result() == null ? page.diagnostic() : reply.result().message();
        if (reply.result() != null && reply.result().success()) { selected = null; }
        rebuildWidgets();
    }
    private void request(Request mutation) {
        pending = new PoliticalNetwork.Query(UUID.randomUUID(), kingdom, tab, offset, mutation);
        attempts = 1; timeout = 40; PoliticalNetwork.send(pending);
    }
    @Override protected void init() {
        clearWidgets();
        int tabWidth = (width - 16) / 4;
        for (int i = 0; i < TABS.size(); i++) {
            String value = TABS.get(i);
            var button = addRenderableWidget(Button.builder(label(value), b -> { tab = value; offset = rowScroll = detailScroll = 0; selected = null; request(null); })
                    .bounds(8 + (i % 4) * tabWidth, 28 + (i / 4) * 22, tabWidth - 3, 20).build());
            button.active = !tab.equals(value);
        }
        int count = visibleRows();
        rowScroll = Math.max(0, Math.min(rowScroll, page == null ? 0 : Math.max(0, page.rows().size() - count)));
        detailPanel = addRenderableWidget(new MenuTextPanel(width / 2 + 3, 78, width / 2 - 11, height - 144,
                Component.translatable("menu.ultima_kingdoms.record_details")));
        updateDetail();
        if (page != null) for (int i = 0; i < count && i + rowScroll < page.rows().size(); i++) {
            Row row = page.rows().get(i + rowScroll);
            Component title = translated(row.title());
            addRenderableWidget(Button.builder(title, b -> { selected = row; detailScroll = 0; rebuildWidgets(); })
                    .bounds(14, 102 + i * 21, width / 2 - 30, 20).tooltip(Tooltip.create(title)).build()).active = !row.equals(selected);
        }
        addRenderableWidget(Button.builder(label("previous"), b -> { offset = Math.max(0, offset - 20); rowScroll = 0; request(null); })
                .bounds(8, height - 48, 66, 20).build()).active = offset > 0;
        addRenderableWidget(Button.builder(label("next"), b -> { offset += 20; rowScroll = 0; request(null); })
                .bounds(78, height - 48, 66, 20).build()).active = page != null && page.hasMore();
        addRenderableWidget(Button.builder(label("actions"), b -> com.ultimakingdoms.client.InteractionClient.open(this,"","Government"))
                .bounds(width - 148, height - 48, 68, 20).build()).active = page != null && pending == null && !tab.equals("history");
        addRenderableWidget(Button.builder(label("refresh"), b -> request(null)).bounds(width - 76, height - 48, 68, 20).build());
        addRenderableWidget(Button.builder(label("back"), b -> onClose()).bounds(width / 2 - 40, height - 24, 80, 20).build());
        if (page == null && pending == null) request(null);
    }
    private int visibleRows() { return Math.max(1, (height - 172) / 21); }
    private void updateDetail() {
        Row row = selected != null ? selected : page == null || page.rows().isEmpty() ? null : page.rows().get(0);
        detailPanel.setContent(row == null ? List.of(Component.translatable("menu.ultima_kingdoms.no_records")) :
                List.of(translated(row.title()), Component.literal(row.detail())));
    }
    private Component translated(String value) { return value.startsWith("politics.") ? Component.translatable(value) : Component.literal(value); }
    @Override public void tick() {
        if (timeout > 0 && --timeout == 0 && pending != null) {
            if (attempts++ < 3) { PoliticalNetwork.send(pending); timeout = 40; }
            else { pending = null; status = "No response; refresh before retrying"; rebuildWidgets(); }
        }
    }
    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (page != null && y >= 78 && y < height - 66 && delta != 0) {
            int direction = delta > 0 ? -1 : 1;
            if (x < width / 2.0) { rowScroll = Math.max(0, Math.min(Math.max(0, page.rows().size() - visibleRows()), rowScroll + direction)); rebuildWidgets(); }
            else return super.mouseScrolled(x,y,delta);
            return true;
        }
        return super.mouseScrolled(x,y,delta);
    }
    @Override public void render(GuiGraphics graphics, int mx, int my, float partial) {
        renderBackground(graphics);
        VanillaGui.panel(graphics,4,4,width-8,height-8);
        VanillaGui.title(graphics,font,title.copy().append(" — " + kingdom.substring(kingdom.indexOf(':') + 1)),width/2,12,width-24);
        VanillaGui.panel(graphics, 8, 78, width / 2 - 16, height - 144);
        VanillaGui.title(graphics, font, label(tab), width / 4, 86, width / 2 - 36);
        if (pending != null) VanillaGui.title(graphics,font,Component.translatable("civic.ultima_kingdoms.loading"),width/2,height-62,width-24);
        if (!status.isEmpty() && pending == null) {
            String clipped = font.plainSubstrByWidth(status, width - 16);
            graphics.drawString(font, clipped, 8, height - 62, VanillaGui.STATUS,false);
            if (my >= height - 65 && my < height - 28) graphics.renderTooltip(font, font.split(Component.literal(status), Math.max(120, width - 32)), mx, my);
        }
        super.render(graphics,mx,my,partial);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
