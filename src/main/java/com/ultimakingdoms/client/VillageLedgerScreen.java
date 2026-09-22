package com.ultimakingdoms.client;

import com.ultimakingdoms.network.LedgerPagePacket;
import com.ultimakingdoms.network.NetworkHandler;
import com.ultimakingdoms.presentation.KingdomSummary;
import com.ultimakingdoms.presentation.SettlementSummary;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class VillageLedgerScreen extends Screen {
    private static final int PAGE_SIZE = 20;
    private static final int ROW_HEIGHT = 20;
    private static final int REQUEST_TIMEOUT_TICKS = 30;
    private static final int MAX_REQUEST_ATTEMPTS = 3;

    private LedgerPagePacket page;
    private List<KingdomSummary> kingdoms = List.of();
    private Optional<ResourceLocation> filter = Optional.empty();
    private int offset;
    private Button previousButton;
    private Button nextButton;
    private Button filterButton;
    private Button scrollUpButton;
    private Button scrollDownButton;
    private Button backButton;
    private Button warRoomButton;
    private final List<Button> rowButtons = new ArrayList<>();
    private boolean loading = true;
    private boolean initialRequestSent;
    private long pendingRequestId;
    private Optional<ResourceLocation> pendingFilter = Optional.empty();
    private int pendingOffset;
    private long expectedRevision;
    private int requestTimeoutTicks;
    private int retryTicks;
    private int requestAttempts;
    private int scrollIndex;
    private int visibleRows;
    private SettlementSummary selected;
    private Component requestError;

    public VillageLedgerScreen(LedgerPagePacket cachedPage) {
        super(Component.translatable("screen.ultima_kingdoms.ledger.title"));
        if (cachedPage != null && cachedPage.retryAfterTicks() == 0) {
            page = cachedPage;
            kingdoms = cachedPage.kingdoms();
            filter = cachedPage.kingdomId();
        }
    }

    @Override
    protected void init() {
        rowButtons.clear();
        addRenderableWidget(Button.builder(Component.literal("Tasks"), b -> InteractionClient.open(this,"","")).bounds(panelLeft()+8,panelTop()+7,62,18).build());
        addRenderableWidget(Button.builder(Component.translatable("civic.ultima_kingdoms.title"),
                button -> minecraft.setScreen(new GuildScreen(this))).bounds(panelLeft() + 10, panelTop() + 30, 70, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("politics.ultima_kingdoms.kingdom"), button -> {
            if(selected==null&&filter.isEmpty()){InteractionClient.open(this,"","Government");return;}
            String kingdom = selected != null ? selected.kingdomId().toString() : filter.map(Object::toString).orElse("ultima_kingdoms:serenum");
            minecraft.setScreen(new com.ultimakingdoms.client.politics.KingdomScreen(this, kingdom, selected == null ? "" : selected.id().toString()));
        }).bounds(panelRight() - 90, panelTop() + 30, 80, 20).build());
        warRoomButton = addRenderableWidget(Button.builder(Component.literal("War room"), button -> {
            if (selected != null) minecraft.setScreen(new WarRoomScreen(this, selected.id(), selected.displayName()));
        }).bounds(width / 2 - 40, panelBottom() - 54, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("civic.ultima_kingdoms.refresh"), b -> request(offset))
                .bounds(width / 2 - 36, panelTop() + 30, 72, 20).build());
        addRenderableWidget(Button.builder(Component.literal("×"), b -> onClose())
                .bounds(panelRight() - 28, panelTop() + 7, 18, 18).tooltip(Tooltip.create(Component.translatable("menu.ultima_kingdoms.close"))).build());
        int left = panelLeft();
        int right = panelRight();
        int footerY = panelBottom() - 30;
        int availableWidth = right - left - 8;
        int sideWidth = Math.max(54, (availableWidth - 146) / 2);
        int filterWidth = availableWidth - sideWidth * 2 - 8;

        previousButton = addRenderableWidget(Button.builder(Component.translatable("gui.ultima_kingdoms.previous"),
                button -> request(Math.max(0, offset - PAGE_SIZE))).bounds(left + 4, footerY, sideWidth, 20).build());
        filterButton = addRenderableWidget(Button.builder(filterLabel(), button -> cycleFilter())
                .bounds(left + sideWidth + 8, footerY, filterWidth, 20).build());
        nextButton = addRenderableWidget(Button.builder(Component.translatable("gui.ultima_kingdoms.next"),
                button -> request(offset + PAGE_SIZE)).bounds(right - sideWidth - 4, footerY, sideWidth, 20).build());
        backButton = addRenderableWidget(Button.builder(Component.translatable("gui.ultima_kingdoms.back"),
                button -> showList()).bounds(width / 2 - 50, footerY, 100, 20).build());

        int listTop = panelTop() + 72;
        int listBottom = footerY - 5;
        visibleRows = Math.max(1, Math.min(PAGE_SIZE, (listBottom - listTop) / ROW_HEIGHT));
        int rowWidth = Math.max(60, right - left - 31);
        for (int slot = 0; slot < visibleRows; slot++) {
            final int rowSlot = slot;
            Button row = addRenderableWidget(Button.builder(Component.empty(), button -> selectRow(rowSlot))
                    .bounds(left + 5, listTop + slot * ROW_HEIGHT, rowWidth, 18).build());
            rowButtons.add(row);
        }
        scrollUpButton = addRenderableWidget(Button.builder(Component.translatable("gui.ultima_kingdoms.scroll_up"), button -> scroll(-1))
                .bounds(right - 23, listTop, 18, 18).build());
        scrollDownButton = addRenderableWidget(Button.builder(Component.translatable("gui.ultima_kingdoms.scroll_down"), button -> scroll(1))
                .bounds(right - 23, Math.max(listTop + 20, listBottom - 18), 18, 18).build());

        if (!initialRequestSent) {
            initialRequestSent = true;
            request(0);
        } else {
            updateWidgets();
        }
    }

    public boolean receive(LedgerPagePacket newPage) {
        if (newPage.requestId() != pendingRequestId
                || !newPage.kingdomId().equals(pendingFilter)) {
            return false;
        }
        if (newPage.retryAfterTicks() > 0) {
            if (newPage.offset() != pendingOffset) return false;
            retryTicks = Math.max(1, Math.min(100, newPage.retryAfterTicks()));
            requestTimeoutTicks = retryTicks + REQUEST_TIMEOUT_TICKS;
            return false;
        }
        if (!newPage.revisionReset() && newPage.offset() != pendingOffset) return false;
        if (!newPage.revisionReset() && expectedRevision != 0
                && newPage.registryRevision() != expectedRevision) {
            request(0);
            return false;
        }
        page = newPage;
        kingdoms = new ArrayList<>(newPage.kingdoms());
        filter = newPage.kingdomId();
        offset = newPage.offset();
        loading = false;
        retryTicks = 0;
        requestTimeoutTicks = 0;
        requestAttempts = 0;
        requestError = null;
        selected = null;
        scrollIndex = 0;
        updateWidgets();
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (!loading) return;
        if (retryTicks > 0) {
            retryTicks--;
            if (retryTicks == 0) retryPending();
            return;
        }
        if (requestTimeoutTicks > 0) {
            requestTimeoutTicks--;
            if (requestTimeoutTicks == 0) retryPending();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = panelLeft();
        int right = panelRight();
        VanillaGui.panel(graphics, left, panelTop(), right - left, panelBottom() - panelTop());
        VanillaGui.title(graphics, font, title, width / 2, panelTop() + 12, right - left - 66);

        if (selected == null) VanillaGui.title(graphics, font,
                Component.translatable("menu.ultima_kingdoms.settlements"), width / 2, panelTop() + 57, right - left - 20);
        if (selected != null) {
            renderDetails(graphics, selected, left);
        } else if (loading) {
            graphics.drawCenteredString(font, Component.translatable("screen.ultima_kingdoms.ledger.loading"),
                    width / 2, panelTop() + 80, VanillaGui.SECONDARY);
        } else if (requestError != null) {
            graphics.drawCenteredString(font, requestError, width / 2, panelTop() + 80, VanillaGui.ERROR);
        } else if (page == null || page.settlements().isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.ultima_kingdoms.ledger.empty"),
                    width / 2, panelTop() + 80, VanillaGui.SECONDARY);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (selected == null && delta != 0) {
            scroll(delta > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void request(int requestedOffset) {
        pendingFilter = filter;
        pendingOffset = requestedOffset;
        expectedRevision = page == null ? 0 : page.registryRevision();
        requestAttempts = 0;
        requestError = null;
        selected = null;
        scrollIndex = 0;
        loading = true;
        sendPendingRequest();
    }

    private void sendPendingRequest() {
        requestAttempts++;
        pendingRequestId = NetworkHandler.requestLedgerPage(pendingFilter, pendingOffset, expectedRevision);
        retryTicks = 0;
        requestTimeoutTicks = REQUEST_TIMEOUT_TICKS;
        updateWidgets();
    }

    private void retryPending() {
        if (requestAttempts < MAX_REQUEST_ATTEMPTS) {
            sendPendingRequest();
        } else {
            loading = false;
            requestError = Component.translatable("screen.ultima_kingdoms.ledger.request_failed");
            updateWidgets();
        }
    }

    private void cycleFilter() {
        if (kingdoms.isEmpty()) return;
        if (filter.isEmpty()) {
            filter = Optional.of(kingdoms.get(0).id());
        } else {
            int current = -1;
            for (int i = 0; i < kingdoms.size(); i++) {
                if (kingdoms.get(i).id().equals(filter.get())) current = i;
            }
            filter = current < 0 || current + 1 >= kingdoms.size()
                    ? Optional.empty() : Optional.of(kingdoms.get(current + 1).id());
        }
        offset = 0;
        request(0);
    }

    private void selectRow(int slot) {
        if (page == null) return;
        int index = scrollIndex + slot;
        int count = Math.min(PAGE_SIZE, page.settlements().size());
        if (index >= 0 && index < count) {
            selected = page.settlements().get(index);
            updateWidgets();
        }
    }

    private void showList() {
        selected = null;
        updateWidgets();
    }

    private void scroll(int amount) {
        int maxScroll = Math.max(0, resultCount() - visibleRows);
        int next = Math.max(0, Math.min(maxScroll, scrollIndex + amount));
        if (next != scrollIndex) {
            scrollIndex = next;
            updateWidgets();
        }
    }

    private void renderDetails(GuiGraphics graphics, SettlementSummary settlement, int left) {
        KingdomSummary kingdom = kingdom(settlement.kingdomId());
        int y = panelTop() + 65;
        if (kingdom != null) HeraldryRenderer.render(graphics, kingdom, left + 12, y - 3, 24);
        graphics.drawString(font, font.plainSubstrByWidth(settlement.displayName(), panelRight() - left - 58), left + 43, y + 5, VanillaGui.TEXT, false);
        y += 34;
        Component kingdomName = kingdom == null ? Component.literal(settlement.kingdomId().toString())
                : Component.translatable(kingdom.translationKey());
        y = detailLine(graphics, left, y, "screen.ultima_kingdoms.ledger.detail.kingdom", kingdomName);
        y = detailLine(graphics, left, y, "screen.ultima_kingdoms.ledger.detail.recognized",
                Component.translatable("screen.ultima_kingdoms.ledger.day",
                        settlement.recognizedGameTime() / 24_000L + 1));
        y = detailLine(graphics, left, y, "screen.ultima_kingdoms.ledger.detail.biome",
                Component.translatable(Util.makeDescriptionId("biome", settlement.biome())));
        y = detailLine(graphics, left, y, "screen.ultima_kingdoms.ledger.detail.assignment",
                Component.translatable("assignment.ultima_kingdoms."
                        + settlement.assignmentSource().name().toLowerCase(Locale.ROOT)));
        y = detailLine(graphics, left, y, "screen.ultima_kingdoms.ledger.detail.dimension",
                dimensionName(settlement.dimension()));
        detailLine(graphics, left, y, "screen.ultima_kingdoms.ledger.detail.position",
                Component.translatable("screen.ultima_kingdoms.ledger.position3",
                        settlement.anchor().getX(), settlement.anchor().getY(), settlement.anchor().getZ()));
    }

    private Component dimensionName(ResourceLocation dimension) {
        if (dimension.equals(Level.OVERWORLD.location())) {
            return Component.translatable("dimension.ultima_kingdoms.overworld");
        }
        if (dimension.equals(Level.NETHER.location())) {
            return Component.translatable("dimension.ultima_kingdoms.the_nether");
        }
        if (dimension.equals(Level.END.location())) {
            return Component.translatable("dimension.ultima_kingdoms.the_end");
        }
        String translationKey = Util.makeDescriptionId("dimension", dimension);
        if (Language.getInstance().has(translationKey)) return Component.translatable(translationKey);
        return Component.translatable("screen.ultima_kingdoms.ledger.dimension_fallback",
                friendlyName(dimension.getPath()), dimension.getNamespace());
    }

    private static String friendlyName(String path) {
        String[] words = path.replace('/', ' ').replace('_', ' ').strip().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private int detailLine(GuiGraphics graphics, int left, int y, String labelKey, Component value) {
        graphics.drawString(font, Component.translatable(labelKey), left + 13, y, VanillaGui.SECONDARY, false);
        graphics.drawString(font, value, left + 96, y, VanillaGui.TEXT, false);
        return y + 15;
    }

    private Component rowLabel(SettlementSummary settlement) {
        KingdomSummary kingdom = kingdom(settlement.kingdomId());
        Component kingdomName = kingdom == null ? Component.literal(settlement.kingdomId().toString())
                : Component.translatable(kingdom.translationKey());
        return Component.translatable("screen.ultima_kingdoms.ledger.row", settlement.displayName(), kingdomName,
                settlement.anchor().getX(), settlement.anchor().getZ());
    }

    private Component filterLabel() {
        if (filter.isEmpty()) return Component.translatable("screen.ultima_kingdoms.ledger.all_kingdoms");
        KingdomSummary kingdom = kingdom(filter.get());
        return kingdom == null ? Component.literal(filter.get().toString())
                : Component.translatable(kingdom.translationKey());
    }

    private KingdomSummary kingdom(ResourceLocation id) {
        for (KingdomSummary kingdom : kingdoms) {
            if (kingdom.id().equals(id)) return kingdom;
        }
        return null;
    }

    private int resultCount() {
        return page == null ? 0 : Math.min(PAGE_SIZE, page.settlements().size());
    }

    private void updateWidgets() {
        if (previousButton == null) return;
        boolean listMode = selected == null;
        previousButton.visible = listMode;
        nextButton.visible = listMode;
        filterButton.visible = listMode;
        previousButton.active = !loading && offset > 0;
        nextButton.active = !loading && page != null && page.settlements().size() > PAGE_SIZE;
        filterButton.active = !loading && !kingdoms.isEmpty();
        filterButton.setMessage(filterLabel());
        backButton.visible = !listMode;
        backButton.active = !listMode;
        warRoomButton.active = selected != null;
        warRoomButton.visible = selected != null;

        int count = resultCount();
        int maxScroll = Math.max(0, count - visibleRows);
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll));
        for (int slot = 0; slot < rowButtons.size(); slot++) {
            Button row = rowButtons.get(slot);
            int index = scrollIndex + slot;
            boolean show = listMode && !loading && requestError == null && index < count;
            row.visible = show;
            row.active = show;
            if (show) {
                Component label = rowLabel(page.settlements().get(index));
                row.setMessage(label);
                row.setTooltip(Tooltip.create(label));
            }
        }
        scrollUpButton.visible = listMode && !loading && maxScroll > 0;
        scrollDownButton.visible = scrollUpButton.visible;
        scrollUpButton.active = scrollIndex > 0;
        scrollDownButton.active = scrollIndex < maxScroll;
    }

    private int panelTop() { return (height - Math.min(360, height - 12)) / 2; }

    private int panelBottom() { return height - panelTop(); }

    private int panelLeft() {
        return Math.max(5, width / 2 - 250);
    }

    private int panelRight() {
        return Math.min(width - 5, width / 2 + 250);
    }
}
