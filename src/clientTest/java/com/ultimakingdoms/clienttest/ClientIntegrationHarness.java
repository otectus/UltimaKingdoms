package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.api.*;
import com.ultimakingdoms.client.ClientPresentationState;
import com.ultimakingdoms.client.VillageLedgerScreen;
import com.ultimakingdoms.network.LedgerPagePacket;
import com.ultimakingdoms.network.OverlayPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Real integrated-server/network/render smoke test, opt-in and never shipped. */
@Mod("ultima_client_test")
public final class ClientIntegrationHarness {
    private final Minecraft mc = Minecraft.getInstance();
    private final Path output = Path.of(System.getProperty("ultima.clientTest.output"));
    private final boolean focused = Boolean.getBoolean("ultima.clientTest.focused");
    private final boolean commandsOnly = Boolean.getBoolean("ultima.clientTest.commandsOnly");
    private int stage, ticks, entered;
    private boolean creating, failed, dimensionSeen;
    private volatile boolean seeded;
    private long started = System.currentTimeMillis();

    public ClientIntegrationHarness() { MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || failed) return;
        ticks++;
        try {
            if (Files.exists(output.resolve("STOP"))) throw new AssertionError("Test run stopped by coordinator");
            if (System.currentTimeMillis() - started > 240_000) throw new AssertionError("Timeout at stage " + stage);
            if (!creating && mc.screen instanceof TitleScreen) {
                creating = true;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(5);
                mc.options.guiScale().set(2);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
                mc.resizeDisplay();
                mc.createWorldOpenFlows().createFreshLevel("client-validation-" + started,
                        new LevelSettings("Ultima client validation", GameType.CREATIVE, false,
                                Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(8675309, false, false), registry -> registry
                                .registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
                log("Created isolated flat integrated world");
            }
            if (!creating || ticks - entered < 35) return;
            switch (stage) {
                case 0 -> {
                    if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
                    if (commandsOnly) {
                        if (mc.getConnection().getCommands().getRoot().getChild("ultima") == null) return;
                        checkCommandTree();
                        stage = 14; entered = ticks; return;
                    }
                    mc.getSingleplayerServer().execute(() -> {
                        try {
                            var server = mc.getSingleplayerServer();
                            var player = server.getPlayerList().getPlayers().get(0);
                            var level = server.overworld();
                            var service = UltimaKingdomsApi.get(server);
                            var kingdoms = service.getKingdoms().stream().sorted(Comparator.comparing(k -> k.id().toString())).toList();
                            BlockPos origin = player.blockPosition();
                            for (int i = 0; i < 25; i++) {
                                BlockPos pos = i == 0 ? origin : origin.offset(i * 200, 0, 0);
                                var candidate = SettlementCandidate.external(level.dimension(), pos, 48,
                                        SettlementBounds.around(pos, 48), new ResourceLocation("ultima_client_test", "seed"),
                                        "village-" + i, kingdoms.get(i % kingdoms.size()).id(), Map.of(),
                                        i == 0 ? "Amberford" : String.format("Test Village %02d", i));
                                service.registerCandidate(level, candidate);
                            }
                            player.getInventory().setItem(0, new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS
                                    .getValue(new ResourceLocation("ultima_kingdoms", "village_ledger")))));
                            player.getInventory().selected = 0;
                            player.containerMenu.broadcastChanges();
                            seeded = true;
                        } catch (Throwable problem) { fail(problem); }
                    });
                    next();
                }
                case 1 -> {
                    if (!seeded || overlay().settlement().isEmpty() || mc.screen != null) return;
                    check(overlay().settlement().orElseThrow().displayName().equals("Amberford"), "Natural entry overlay received");
                    check((Integer) field(ClientPresentationState.class, null, "overlayTicks") > 0, "Overlay countdown active");
                    shot("01-entry-overlay.png");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    next();
                }
                case 2 -> {
                    if (!ready()) return;
                    check(page().offset() == 0 && page().settlements().size() == 21, "Real ledger item and network first page, sentinel present");
                    if (focused) {
                        click((Button) ((List<?>) field(VillageLedgerScreen.class, mc.screen, "rowButtons")).get(0));
                        stage = 6; entered = ticks; return;
                    }
                    check(!button("Previous").active && button("Next").active, "First-page navigation state");
                    shot("02-ledger-first-page.png"); click("Next"); next();
                }
                case 3 -> {
                    if (!ready()) return;
                    check(page().offset() == 20 && page().settlements().size() == 5, "Next button fetched final page");
                    check(button("Previous").active && !button("Next").active, "Final-page navigation state");
                    shot("03-ledger-last-page.png"); click("Previous"); next();
                }
                case 4 -> {
                    if (!ready()) return;
                    check(page().offset() == 0, "Previous button returns first page");
                    click("All Kingdoms"); next();
                }
                case 5 -> {
                    if (!ready()) return;
                    check(page().kingdomId().isPresent(), "Kingdom filter packet response");
                    check(page().settlements().stream().allMatch(s -> s.kingdomId().equals(page().kingdomId().orElseThrow())), "Filtered rows match chosen kingdom");
                    shot("04-ledger-filtered.png");
                    click((Button) ((List<?>) field(VillageLedgerScreen.class, mc.screen, "rowButtons")).get(0));
                    check(field(VillageLedgerScreen.class, mc.screen, "selected") != null, "Actual row click opens settlement details");
                    next();
                }
                case 6 -> {
                    shot("05-ledger-details.png");
                    click((Button) field(VillageLedgerScreen.class, mc.screen, "backButton"));
                    check(field(VillageLedgerScreen.class, mc.screen, "selected") == null, "Back restores settlement list");
                    if (focused) { stage = 10; entered = ticks; return; }
                    click((Button) field(VillageLedgerScreen.class, mc.screen, "filterButton"));
                    mc.screen.keyPressed(256, 0, 0);
                    check(mc.screen == null, "Escape closes ledger");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    log("Sent two same-tick real ledger uses to exercise request throttling and superseded screen replies");
                    next();
                }
                case 7 -> {
                    if (!ready()) return;
                    check(page().offset() == 0 && page().kingdomId().isPresent(), "Rapid reopening recovers and preserves selected kingdom");
                    check(field(VillageLedgerScreen.class, mc.screen, "requestError") == null, "Rapid requests complete without error");
                    click((Button) field(VillageLedgerScreen.class, mc.screen, "filterButton"));
                    next();
                }
                case 8 -> {
                    if (!ready()) return;
                    if (page().kingdomId().isPresent()) {
                        click((Button) field(VillageLedgerScreen.class, mc.screen, "filterButton"));
                        entered = ticks;
                        return;
                    }
                    check(page().settlements().size() == 21, "Cycling kingdom filter returns complete paged list");
                    mc.getWindow().setGuiScale(mc.getWindow().getHeight() / 240.0);
                    mc.screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                    next();
                }
                case 9 -> {
                    if (!ready()) return;
                    check(mc.screen.height >= 240 && mc.screen.height <= 241, "Compact viewport is 240 GUI pixels high");
                    List<?> rows = (List<?>) field(VillageLedgerScreen.class, mc.screen, "rowButtons");
                    check(rows.size() < 20 && rows.size() > 0, "Compact viewport limits visible rows");
                    for (Object row : rows) {
                        Button b = (Button) row;
                        check(b.getY() + b.getHeight() < mc.screen.height - 27, "Row remains above navigation footer");
                    }
                    shot("06-ledger-compact.png");
                    click((Button) field(VillageLedgerScreen.class, mc.screen, "scrollDownButton"));
                    check((Integer) field(VillageLedgerScreen.class, mc.screen, "scrollIndex") == 1, "Compact list scroll button advances rows");
                    mc.screen.mouseScrolled(100, 100, -1);
                    check((Integer) field(VillageLedgerScreen.class, mc.screen, "scrollIndex") == 2, "Mouse wheel advances compact list");
                    next();
                }
                case 10 -> {
                    if (!focused) shot("07-ledger-compact-scrolled.png");
                    mc.resizeDisplay();
                    mc.screen.onClose();
                    serverTask(() -> {
                        var server = mc.getSingleplayerServer();
                        server.getPlayerList().getPlayers().get(0).teleportTo(server.getLevel(Level.NETHER), 0.5, 80, 0.5, 0, 0);
                    });
                    next();
                }
                case 11 -> {
                    if (mc.level == null || !mc.level.dimension().equals(Level.NETHER)) return;
                    if (!dimensionSeen) { dimensionSeen = true; entered = ticks; return; }
                    check(field(ClientPresentationState.class, null, "ledgerPage") == null, "Dimension transition clears ledger cache");
                    check(overlay().settlement().isEmpty(), "Dimension transition clears overlay");
                    serverTask(() -> {
                        var server = mc.getSingleplayerServer();
                        var player = server.getPlayerList().getPlayers().get(0);
                        var service = UltimaKingdomsApi.get(server);
                        service.registerCandidate(player.serverLevel(), SettlementCandidate.manual(Level.NETHER,
                                player.blockPosition(), 48, new ResourceLocation("ultima_client_test", "seed"), "nether-village", "Emberwatch"));
                    });
                    next();
                }
                case 12 -> {
                    if (overlay().settlement().isEmpty()) return;
                    OverlayPacket actualPacket = overlay();
                    check(actualPacket.dimension().orElseThrow().equals(Level.NETHER.location()), "Real server Nether entry overlay received");
                    Field marker = ClientPresentationState.class.getDeclaredField("clientDimension");
                    marker.setAccessible(true);
                    marker.set(null, Level.OVERWORLD.location());
                    ClientPresentationState.receiveOverlay(actualPacket);
                    ClientPresentationState.onClientTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
                    check(overlay().settlement().isPresent(), "Matching new-dimension packet survives END tick with stale old-dimension marker");
                    mc.level.disconnect(); mc.clearLevel(); mc.setScreen(new TitleScreen()); next();
                }
                case 13 -> {
                    check(field(ClientPresentationState.class, null, "ledgerPage") == null && overlay().settlement().isEmpty(), "Disconnect clears presentation state");
                    Files.writeString(output.resolve("PASS.txt"), focused
                            ? "PASS: focused final details screenshot/back, integrated client/item/network, overlay, real dimension/logout cache cleanup, accepted-overlay dimension race\n"
                            : "PASS: integrated client, real item/network ledger, paging, filtering, detail selection/back, rapid close/reopen recovery, compact viewport scrolling, overlay, dimension/logout cache cleanup, accepted-overlay dimension race\n");
                    log("PASS all client integration checks"); failed = true; mc.stop();
                }
                case 14 -> {
                    check(mc.getConnection() != null && mc.getConnection().getConnection().isConnected(),
                            "Client remains connected after command-tree deserialization and parsing");
                    mc.level.disconnect(); mc.clearLevel(); mc.setScreen(new TitleScreen());
                    Files.writeString(output.resolve("COMMANDS_PASS.txt"),
                            "PASS: real integrated-server connection and command-tree deserialization; custom settlement argument registered on client; unquoted namespaced kingdom IDs/slugs and quoted display names parse completely; clean disconnect\n");
                    log("PASS command synchronization checks"); failed = true; mc.stop();
                }
            }
        } catch (Throwable problem) { fail(problem); }
    }

    private void next() { stage++; entered = ticks; log("Stage " + stage + " at client tick " + ticks); }
    private void checkCommandTree() {
        var connection = mc.getConnection();
        var dispatcher = connection.getCommands();
        var villageNode = dispatcher.getRoot().getChild("ultima").getChild("village").getChild("info").getChild("village");
        check(villageNode instanceof com.mojang.brigadier.tree.ArgumentCommandNode<?, ?> argument
                        && argument.getType() instanceof com.ultimakingdoms.command.SettlementSelectorArgument,
                "Downloaded command tree contains registered custom settlement argument type");
        String[] commands = {
                "ultima kingdom info ultima_kingdoms:serenum",
                "ultima village info ultima_kingdoms:amberford",
                "ultima village info \"New Amberford\"",
                "ultima village setkingdom ultima_kingdoms:amberford ultima_kingdoms:lunari",
                "ultima village rename \"New Amberford\" Renamed Village",
                "ultima village merge ultima_kingdoms:amberford ultima_kingdoms:bellmeadow"
        };
        for (String command : commands) {
            var parsed = dispatcher.parse(command, connection.getSuggestionsProvider());
            check(parsed.getExceptions().isEmpty() && !parsed.getReader().canRead()
                    && parsed.getContext().getCommand() != null, "Client dispatcher fully parses: " + command);
        }
        String slugCommand = commands[1];
        String nameCommand = commands[2];
        check(dispatcher.parse(slugCommand, connection.getSuggestionsProvider()).getContext().build(slugCommand)
                        .getArgument("village", String.class).equals("ultima_kingdoms:amberford"),
                "Unquoted namespaced settlement selector retains the complete namespace and path");
        check(dispatcher.parse(nameCommand, connection.getSuggestionsProvider()).getContext().build(nameCommand)
                        .getArgument("village", String.class).equals("New Amberford"),
                "Quoted settlement selector retains the complete display name");
    }
    private void serverTask(Runnable task) { mc.getSingleplayerServer().execute(() -> {
        try { task.run(); } catch (Throwable problem) { fail(problem); }
    }); }
    private LedgerPagePacket page() { return (LedgerPagePacket) field(VillageLedgerScreen.class, mc.screen, "page"); }
    private OverlayPacket overlay() { return (OverlayPacket) field(ClientPresentationState.class, null, "currentOverlay"); }
    private boolean ready() { return mc.screen instanceof VillageLedgerScreen && !(Boolean) field(VillageLedgerScreen.class, mc.screen, "loading"); }
    private Button button(String label) { return mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(b -> b.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new AssertionError("Missing button " + label)); }
    private void click(String label) { click(button(label)); }
    private void click(Button b) { String label = b.getMessage().getString(); check(b.active, label + " enabled");
        check(mc.screen.mouseClicked(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, 0), label + " actual mouse click handled"); }
    private Object field(Class<?> type, Object instance, String name) { try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(instance); }
        catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); } }
    private void shot(String name) { Screenshot.grab(output.toFile(), name, mc.getMainRenderTarget(), message -> log("Screenshot " + name + ": " + message.getString())); }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); log("PASS " + message); }
    private void log(String message) { System.out.println("ULTIMA_CLIENT_TEST " + message); }
    private void fail(Throwable problem) { failed = true; problem.printStackTrace(); try { Files.createDirectories(output); Files.writeString(output.resolve("FAIL.txt"), "stage=" + stage + "\n" + problem); }
        catch (Exception writingFailure) { writingFailure.printStackTrace(); } mc.execute(mc::stop); }
}
