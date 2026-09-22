package com.ultimakingdoms.clienttest;

import com.google.gson.GsonBuilder;
import com.ultimakingdoms.api.SettlementBounds;
import com.ultimakingdoms.api.SettlementCandidate;
import com.ultimakingdoms.api.SettlementView;
import com.ultimakingdoms.api.UltimaKingdomsApi;
import com.ultimakingdoms.client.WarRoomScreen;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.WarfareConfig;
import com.ultimakingdoms.warfare.WarfareNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** R3-only continuation for the isolated copied-world full-pack acceptance run. */
public final class FullPackR3Acceptance {
    private static final int WARMUP_TICKS = 40;
    private static final int SAMPLE_TICKS = 120;
    private static final String SCREENSHOT = "r3-war-room.png";

    private enum Mode { ENABLED, DISABLED, SCREEN, COMPLETE }
    private record Target(UUID settlement, String name) { }

    private final Path output;
    private final StringBuilder report;
    private final Runnable finish;
    private final List<Long> enabledSamples = new ArrayList<>(SAMPLE_TICKS);
    private final List<Long> disabledSamples = new ArrayList<>(SAMPLE_TICKS);
    private final Map<String, Object> forcedChunks = new LinkedHashMap<>();
    private MinecraftServer server;
    private UUID playerId;
    private boolean originalEnabled;
    private volatile Mode mode = Mode.ENABLED;
    private int warmup;
    private long tickStarted;
    private volatile Target target;
    private boolean opened, snapshotValidated, screenshotRequested;
    private int clientTicks, openedAt, snapshotValidatedAt, screenshotAt;
    private GuiStyleAcceptance guiStyle;

    public FullPackR3Acceptance(Path output, StringBuilder report, Runnable finish) {
        this.output = Objects.requireNonNull(output);
        this.report = Objects.requireNonNull(report);
        this.finish = Objects.requireNonNull(finish);
    }

    /** Starts on the integrated server thread after the ordinary full-pack assertions complete. */
    public void start(MinecraftServer server, ServerPlayer player) {
        if (!server.isSameThread()) throw new IllegalStateException("R3 benchmark must start on the server thread");
        this.server = server;
        this.playerId = player.getUUID();
        this.originalEnabled = WarfareConfig.ENABLED.get();
        if (!originalEnabled) throw new AssertionError("R3 master toggle must be enabled before benchmark");
        forcedChunks.put("before_default_on", forced(server));
        System.out.println("R3_PACK benchmark starting: 40 warmup + 120 measured natural server ticks per mode");
    }

    /** Measures actual integrated-server START-to-END tick duration without injecting benchmark work. */
    public void serverTick(TickEvent.ServerTickEvent event) throws Exception {
        if (server == null || event.getServer() != server || mode == Mode.SCREEN || mode == Mode.COMPLETE) return;
        if (event.phase == TickEvent.Phase.START) {
            tickStarted = System.nanoTime();
            return;
        }
        if (tickStarted == 0L) return;
        long elapsed = Math.max(0L, System.nanoTime() - tickStarted);
        tickStarted = 0L;
        if (warmup++ < WARMUP_TICKS) return;
        List<Long> samples = mode == Mode.ENABLED ? enabledSamples : disabledSamples;
        samples.add(elapsed);
        if (samples.size() < SAMPLE_TICKS) return;

        if (mode == Mode.ENABLED) {
            forcedChunks.put("after_default_on", forced(server));
            WarfareConfig.ENABLED.set(false);
            mode = Mode.DISABLED;
            warmup = 0;
            System.out.println("R3_PACK benchmark default-on samples complete; master temporarily disabled");
            return;
        }

        forcedChunks.put("after_master_disabled", forced(server));
        restore();
        if (!WarfareConfig.ENABLED.get()) throw new AssertionError("R3 master toggle was not restored");
        writeBenchmark();
        prepareLocalWarRoom();
        mode = Mode.SCREEN;
    }

    /** Drives the real client screen and waits for the asynchronous screenshot file before passing. */
    public void clientTick(Minecraft minecraft) throws Exception {
        if(guiStyle!=null) {
            if(guiStyle.tick(minecraft)){report.append("GUI style: ledger/detail, guild, kingdom/form and blueprint header captured at scales 2, 3 and 4; keyboard/wheel scrolling and form draft retention verified\n");mode=Mode.COMPLETE;finish.run();}
            return;
        }
        // Initial MCA character creation pauses the integrated server; measure running world ticks.
        if(server!=null&&(mode==Mode.ENABLED||mode==Mode.DISABLED)) {
            if(minecraft.screen!=null)minecraft.setScreen(null);
            return;
        }
        if (mode != Mode.SCREEN || target == null) return;
        clientTicks++;
        if (!opened) {
            if (minecraft.player == null || minecraft.level == null) return;
            minecraft.setScreen(new WarRoomScreen(null, target.settlement(), target.name()));
            opened = true;
            openedAt = clientTicks;
            return;
        }
        if (!(minecraft.screen instanceof WarRoomScreen screen))
            throw new AssertionError("WarRoomScreen closed before R3 capture: " + minecraft.screen);
        WarfareNetwork.Snapshot snapshot = (WarfareNetwork.Snapshot) field(screen, "snapshot");
        if (snapshot == null) {
            if (clientTicks - openedAt > 200) throw new AssertionError("WarRoomScreen snapshot timed out");
            return;
        }
        @SuppressWarnings("unchecked")
        List<net.minecraft.client.gui.components.Button> actions =
                (List<net.minecraft.client.gui.components.Button>) field(screen, "actions");
        if (!snapshot.settlement().equals(target.settlement()))
            throw new AssertionError("War-room snapshot returned another settlement");
        if (snapshot.commands().size() != 6 || actions.size() != 6)
            throw new AssertionError("Expected snapshot and six action buttons, got "
                    + snapshot.commands().size() + "/" + actions.size());
        Set<String> rendered = actions.stream().map(button -> button.getMessage().getString())
                .collect(java.util.stream.Collectors.toSet());
        if (!rendered.equals(snapshot.commands().stream().map(WarfareNetwork.Command::label)
                .collect(java.util.stream.Collectors.toSet())))
            throw new AssertionError("War-room action buttons do not match the authorized snapshot");
        if (!snapshotValidated) {
            snapshotValidated = true;
            snapshotValidatedAt = clientTicks;
            System.out.println("R3_PACK WarRoomScreen received authorized snapshot and six actions");
            return;
        }
        if (clientTicks - snapshotValidatedAt < 3) return;

        Path screenshot = output.resolve("screenshots").resolve(SCREENSHOT);
        if (!screenshotRequested) {
            Files.deleteIfExists(screenshot);
            Screenshot.grab(output.toFile(), SCREENSHOT, minecraft.getMainRenderTarget(),
                    message -> System.out.println("R3_PACK screenshot=" + SCREENSHOT + " " + message.getString()));
            screenshotRequested = true;
            screenshotAt = clientTicks;
            return;
        }
        if (Files.isRegularFile(screenshot) && Files.size(screenshot) > 0L) {
            report.append("R3 natural-tick benchmark: r3-tick-benchmark.json (raw samples, no pass threshold)\n");
            report.append("R3 WarRoomScreen: actual authorized snapshot, six actions, screenshots/")
                    .append(SCREENSHOT).append('\n');
            if(Boolean.getBoolean("ultima.clientTest.guiStyle"))guiStyle=new GuiStyleAcceptance(output);
            else {mode = Mode.COMPLETE;finish.run();}
        } else if (clientTicks - screenshotAt > 200) {
            throw new AssertionError("R3 WarRoomScreen screenshot was not written");
        }
    }

    /** Idempotent failure fence used by the outer harness before it terminates the copied-world client. */
    public void restore() {
        if (server != null && WarfareConfig.ENABLED.get() != originalEnabled)
            WarfareConfig.ENABLED.set(originalEnabled);
    }

    private void prepareLocalWarRoom() {
        ServerPlayer player = Objects.requireNonNull(server.getPlayerList().getPlayer(playerId),
                "full-pack player disconnected during R3 benchmark");
        var kingdoms = UltimaKingdomsApi.get(server);
        SettlementView settlement = kingdoms.getSettlementAt(player.serverLevel(), player.blockPosition()).orElse(null);
        if (settlement == null) {
            var kingdom = kingdoms.getKingdoms().stream().findFirst().orElseThrow();
            var position = player.blockPosition();
            settlement = kingdoms.registerCandidate(player.serverLevel(), SettlementCandidate.external(
                    player.serverLevel().dimension(), position, 12, SettlementBounds.around(position, 12),
                    new ResourceLocation("ultima_client_test", "r3_full_pack"),
                    "isolated-war-room-" + player.getUUID(), kingdom.id(), Map.of(), "R3 Isolated War Room"));
        }
        SettlementKnowledge knowledge = SettlementKnowledge.get(server);
        UUID settlementId = settlement.id();
        knowledge.discover(player.getUUID(), settlement.id());
        if (!knowledge.knows(player.getUUID(), settlement.id())
                || kingdoms.getSettlementAt(player.serverLevel(), player.blockPosition())
                .filter(local -> local.id().equals(settlementId)).isEmpty())
            throw new AssertionError("War-room fixture is not both discovered and local");
        server.saveEverything(false, true, true);
        target = new Target(settlement.id(), settlement.displayName());
    }

    private void writeBenchmark() throws Exception {
        if (enabledSamples.size() != SAMPLE_TICKS || disabledSamples.size() != SAMPLE_TICKS)
            throw new AssertionError("Incomplete R3 natural tick samples");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("measurement", "integrated_server_tick_start_to_end");
        json.put("warmup_ticks_per_mode", WARMUP_TICKS);
        json.put("sample_ticks_per_mode", SAMPLE_TICKS);
        json.put("master_restored_enabled", WarfareConfig.ENABLED.get());
        json.put("default_on", statistics(enabledSamples));
        json.put("master_disabled", statistics(disabledSamples));
        json.put("forced_chunks", forcedChunks);
        Files.writeString(output.resolve("r3-tick-benchmark.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(json));
    }

    private static Map<String, Object> statistics(List<Long> source) {
        long[] sorted = source.stream().mapToLong(Long::longValue).sorted().toArray();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("p50_ms", milliseconds(percentile(sorted, 0.50D)));
        result.put("p95_ms", milliseconds(percentile(sorted, 0.95D)));
        result.put("max_ms", milliseconds(sorted[sorted.length - 1]));
        result.put("raw_samples_ns", List.copyOf(source));
        return result;
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = Math.max(0, Math.min(sorted.length - 1,
                (int) Math.ceil(percentile * sorted.length) - 1));
        return sorted[index];
    }

    private static double milliseconds(long nanoseconds) {
        return nanoseconds / 1_000_000.0D;
    }

    private static Map<String, List<Long>> forced(MinecraftServer server) {
        Map<String, List<Long>> result = new TreeMap<>();
        server.getAllLevels().forEach(level -> {
            TreeSet<Long> chunks = new TreeSet<>(Set.copyOf(level.getForcedChunks()));
            result.put(level.dimension().location().toString(), List.copyOf(chunks));
        });
        return result;
    }

    private static Object field(Object instance, String name) {
        try {
            Field field = WarRoomScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }
}
