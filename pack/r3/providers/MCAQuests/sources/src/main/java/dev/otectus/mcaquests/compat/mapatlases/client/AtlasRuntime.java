package dev.otectus.mcaquests.compat.mapatlases.client;

import dev.otectus.mcaquests.McaQuests;
import dev.otectus.mcaquests.McaQuestsConfig;
import dev.otectus.mcaquests.client.QuestWaypointSync;
import dev.otectus.mcaquests.client.map.*;
import dev.otectus.mcaquests.compat.*;
import dev.otectus.mcaquests.compat.mapatlases.*;
import dev.otectus.mcaquests.api.ExternalMapPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import java.lang.ref.WeakReference;
import java.util.*;

/** Client-only session and native action coordinator. No automatic action writes a native pin. */
public final class AtlasRuntime implements MapAtlasesWaypointBackend.Actions {
    private static AtlasRuntime instance;
    private final AtlasMarkerStore store;
    private final AtlasNativeBinding binding;
    private Pending pending;
    private long rejectLateUntil;
    private String failure = "";
    private long liveRevision = -1;
    private final Map<String, LiveTarget> liveTargets = new HashMap<>();
    private record LiveTarget(int id, UUID uuid, long epoch) { }
    private final Set<String> loggedFailures = new HashSet<>();
    private record Pending(String key, long epoch, long expires, long revision,
                           WeakReference<ItemStack> atlas, WeakReference<Screen> waiting) { }
    private AtlasRuntime(AtlasMarkerStore store, AtlasNativeBinding binding) { this.store = store; this.binding = binding; }
    public static MapWaypointBackend create() {
        String version = ModList.get().getModContainerById("map_atlases")
                .map(m -> m.getModInfo().getVersion().toString()).orElse("");
        AtlasMarkerStore store = new AtlasMarkerStore();
        if (!AtlasHookManifest.VERSION.equals(version)) {
            AtlasHookState.failed("unsupported atlas version " + version);
            return new MapAtlasesWaypointBackend(store, version, null);
        }
        try {
            // Selecting hooks happens before loading the native binding; postApply establishes actual installation.
            Class.forName(AtlasHookManifest.TARGET);
            if (!AtlasHookState.applied()) return new MapAtlasesWaypointBackend(store, version, null);
            instance = new AtlasRuntime(store, new AtlasNativeBinding());
            MinecraftForge.EVENT_BUS.register(new AtlasScreenEvents());
            return new MapAtlasesWaypointBackend(store, version, instance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            AtlasHookState.failed("atlas binding: " + error.getClass().getSimpleName());
            McaQuests.LOGGER.warn("[MCA: Quests] Map Atlases binding unavailable", error);
            return new MapAtlasesWaypointBackend(store, version, null);
        }
    }
    public static AtlasRuntime get() { return instance; }
    public static void acceptExternal(String owner, List<ExternalMapPoint> points) {
        AtlasRuntime runtime=instance;if(runtime==null)return;List<WaypointSpec> specs=new ArrayList<>(points.size());
        for(ExternalMapPoint point:points)specs.add(new WaypointSpec("external/"+owner+"/"+point.key(),point.position(),point.dimension(),point.label(),
                point.kind()==ExternalMapPoint.Kind.ROUTE?dev.otectus.mcaquests.quest.guidance.GuidanceKind.LOCATION:dev.otectus.mcaquests.quest.guidance.GuidanceKind.STRUCTURE,
                WaypointSpec.Ownership.AUTOMATIC,new WaypointPresentation(point.approximate(),point.lastKnown(),8,false,true,"",false)));
        runtime.store.replaceExternal(owner,specs);
    }
    public AtlasMarkerStore store() { return store; }
    public AtlasNativeBinding binding() { return binding; }
    public Screen context() { return MapContextScreen.unwrap(Minecraft.getInstance().screen); }
    @Override public boolean pinsSupported() { return binding.pinsSupported(); }
    @Override public boolean navigationSupported() { return binding.navigationSupported(); }
    @Override public MapActionAvailability availability(WaypointSpec spec, boolean pin) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return no("no_session");
            if (pin ? !McaQuestsConfig.CLIENT.mapAtlasesPins.get() : !McaQuestsConfig.CLIENT.mapAtlasesNavigation.get()) return no("disabled");
            if (pin ? !pinsSupported() : !navigationSupported()) return no(pin ? "unsupported_pins" : "unsupported_navigation");
            if (pending != null) return no("synchronizing");
            Screen context = context();
            if (binding.busy(context)) return no("native_tool");
            ItemStack atlas = binding.atlas(context);
            if (atlas.isEmpty()) return no("no_atlas");
            if (!pin && !binding.isScreen(context)) return MapActionAvailability.ready(); // native request supplies missing map data
            if (pin && !binding.isScreen(context)) return no("open_atlas");
            if (pin && !binding.pinsEnabled()) return no("native_pins_disabled");
            if (pin && !binding.pinStyleAvailable()) return no("pin_style");
            return locate(spec, context, atlas, pin).availability;
        } catch (RuntimeException | LinkageError error) {
            return no(pin ? "unsupported_pins" : "unsupported_navigation");
        }
    }
    private record Selection(AtlasNativeBinding.Tile tile, MapActionAvailability availability) { }
    private Selection locate(WaypointSpec spec, Screen context, ItemStack atlas, boolean selectedOnly) {
        Object selected = binding.selected(context, atlas, spec.dimension());
        List<AtlasNativeBinding.Tile> tiles = binding.maps(context, atlas).stream().map(binding::tile)
                .sorted(Comparator.comparing((AtlasNativeBinding.Tile t) -> !t.slice().equals(selected))
                        .thenComparing(t -> t.height() != null)
                        .thenComparingLong(t -> t.height() == null ? 0 : Math.abs((long)t.height() - spec.pos().getY()))
                        .thenComparing(AtlasNativeBinding.Tile::type)
                        .thenComparingInt(t -> t.data().centerX).thenComparingInt(t -> t.data().centerZ)).toList();
        String reason = "no_coverage";
        for (var tile : tiles) {
            if (selectedOnly && !tile.slice().equals(selected)) continue;
            if (!tile.data().dimension.equals(spec.dimension())) continue;
            if (!AtlasProjection.owns(AtlasProjection.project(spec.pos().getX() + 0.5, spec.pos().getZ() + 0.5,
                    tile.data().centerX, tile.data().centerZ, tile.data().scale))) continue;
            reason = AtlasQuestOverlay.policy(spec, tile);
            // Unknown height may use the existing selection or an existing top layer, never guess a cave floor.
            if (!spec.presentation().reliableHeight() && tile.height() != null && !tile.slice().equals(selected)) {
                reason = "uncertain_height"; continue;
            }
            if (reason.equals("ready")) return new Selection(tile, MapActionAvailability.ready());
        }
        return new Selection(null, no(reason));
    }
    @Override public MapMutationResult pin(WaypointSpec spec) {
        if (!availability(spec, true).available()) return MapMutationResult.UNSUPPORTED;
        try {
            Screen context = context();
            Selection selected = locate(spec, context, binding.atlas(context), true);
            if (selected.tile == null) return MapMutationResult.UNSUPPORTED;
            String label = spec.label().replaceAll("[\\p{Cntrl}]", " ");
            if (label.codePointCount(0, label.length()) > 128) label = label.substring(0, label.offsetByCodePoints(0, 128));
            return binding.savePin(selected.tile, spec.pos().getX(), spec.pos().getZ(), label)
                    ? MapMutationResult.APPLIED : MapMutationResult.FAILED;
        } catch (RuntimeException | LinkageError error) { failed("pin", error); return MapMutationResult.FAILED; }
    }
    @Override public MapMutationResult navigate(WaypointSpec spec) {
        if (!availability(spec, false).available()) return MapMutationResult.UNSUPPORTED;
        Minecraft mc = Minecraft.getInstance();
        try {
            Screen context = context();
            ItemStack atlas = binding.atlas(context);
            if (binding.isScreen(context)) {
                Selection selected = locate(spec, context, atlas, false);
                if (selected.tile == null) { reason(selected.availability.reason()); return MapMutationResult.UNSUPPORTED; }
                // setScreen initializes native widgets anew; focus after that initialization.
                if (mc.screen != context) mc.setScreen(context);
                binding.focus(context, selected.tile, spec.pos().getX(), spec.pos().getZ());
                return MapMutationResult.APPLIED;
            }
            AtlasWaitingScreen waiting = new AtlasWaitingScreen(mc.screen, this);
            rejectLateUntil = 0;
            pending = new Pending(spec.key(), store.snapshot().epoch(), System.currentTimeMillis() + 10000,
                    dev.otectus.mcaquests.client.ClientGuidanceData.revision(), new WeakReference<>(atlas), new WeakReference<>(waiting));
            mc.setScreen(waiting);
            binding.requestOpen();
            return MapMutationResult.APPLIED;
        } catch (RuntimeException | LinkageError error) {
            cancel(); failed("navigation", error); return MapMutationResult.FAILED;
        }
    }
    @Override public void tick() {
        refreshLiveTargets();
        if (pending == null) return;
        Minecraft mc = Minecraft.getInstance();
        Pending request = pending;
        boolean invalid = mc.level == null || mc.player == null || request.epoch != store.snapshot().epoch()
                || System.currentTimeMillis() > request.expires || MapActionScreen.current(request.key).isEmpty();
        try {
            if (invalid || request.atlas.get() == null || binding.activeAtlas() != request.atlas.get()) {
                cancel();
                if (mc.screen == request.waiting.get()) mc.screen.onClose();
                reason("cancelled"); return;
            }
            if (binding.isScreen(mc.screen)) {
                pending = null; // exactly once; availability and re-resolution now see current data
                if (binding.atlas(mc.screen) != request.atlas.get()) { reason("cancelled"); return; }
                MapActionScreen.current(request.key).ifPresent(g -> {
                    WaypointSpec current = QuestWaypointSync.specification(g, WaypointSpec.Ownership.AUTOMATIC);
                    Selection selected = locate(current, mc.screen, binding.atlas(mc.screen), false);
                    if (selected.tile != null) binding.focus(mc.screen, selected.tile, current.pos().getX(), current.pos().getZ());
                    else reason(selected.availability.reason());
                });
            } else if (mc.screen != request.waiting.get()) cancel();
        } catch (RuntimeException | LinkageError error) { cancel(); failed("navigation", error); }
    }
    public void cancel() {
        if (pending != null) rejectLateUntil = pending.expires;
        pending = null;
    }
    public boolean rejectLateScreen(Screen screen) {
        if (pending == null && System.currentTimeMillis() < rejectLateUntil && binding.isScreen(screen)) {
            rejectLateUntil = 0; return true;
        }
        return false;
    }
    @Override public void reset() {
        cancel();
        liveTargets.clear(); liveRevision = -1;
        AtlasQuestOverlay.reset(); AtlasHeldOverlay.reset(); AtlasHookState.resetObservations();
    }

    private void refreshLiveTargets() {
        long revision = dev.otectus.mcaquests.client.ClientGuidanceData.revision();
        if (revision == liveRevision) return;
        liveRevision = revision; liveTargets.clear();
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (var guidance : dev.otectus.mcaquests.client.ClientGuidanceData.all()) {
            var target = guidance.target();
            if (target.approximate() || target.lastKnown() || target.entityId().isEmpty()
                    || !target.dimension().equals(mc.level.dimension())) continue;
            var entity = mc.level.getEntity(target.entityId().getAsInt());
            // The giver UUID is authoritative and already transmitted. For targets without a proven
            // UUID relationship we deliberately retain the latest server position.
            if (entity != null && entity.getUUID().equals(guidance.villagerUuid()))
                liveTargets.put(guidance.questId() + "/" + guidance.villagerUuid(),
                        new LiveTarget(entity.getId(), entity.getUUID(), store.snapshot().epoch()));
        }
    }

    public net.minecraft.world.phys.Vec3 position(WaypointSpec spec) {
        var fallback = net.minecraft.world.phys.Vec3.atCenterOf(spec.pos());
        LiveTarget live = liveTargets.get(spec.key());
        var mc = Minecraft.getInstance();
        if (live == null || mc.level == null || live.epoch != store.snapshot().epoch()
                || !spec.dimension().equals(mc.level.dimension()) || spec.presentation().lastKnown()
                || spec.presentation().approximate()) return fallback;
        var entity = mc.level.getEntity(live.id);
        if (entity == null || entity.isRemoved() || !entity.getUUID().equals(live.uuid)
                || entity.position().distanceToSqr(fallback) > 64 * 64) return fallback;
        return entity.getPosition(mc.getFrameTime());
    }
    public void failed(String operation, Throwable error) {
        failure = operation + ":" + error.getClass().getSimpleName();
        if (loggedFailures.add(failure)) McaQuests.LOGGER.warn("[MCA: Quests] Map Atlases {}", failure);
    }
    private static MapActionAvailability no(String reason) { return MapActionAvailability.unavailable(reason); }
    public static void reason(String reason) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.translatable("mcaquests.atlas.reason." + reason), true);
    }
    @Override public List<Component> details() {
        var mc = Minecraft.getInstance();
        boolean hasAtlas = false;
        try { hasAtlas = mc.level != null && !binding.atlas(context()).isEmpty(); }
        catch (RuntimeException | LinkageError ignored) { }
        return List.of(Component.translatable("mcaquests.atlas.status", AtlasHookManifest.VERSION,
                        store.keys().size(), AtlasQuestOverlay.visible(false), AtlasQuestOverlay.visible(true)),
                Component.translatable("mcaquests.atlas.status_features", pinsSupported(), navigationSupported(),
                        McaQuestsConfig.CLIENT.mapWaypoints.get() && McaQuestsConfig.CLIENT.mapAtlasesWaypoints.get()),
                Component.translatable("mcaquests.atlas.reason." + (pending != null ? "synchronizing" : hasAtlas ? "ready" : "no_atlas")),
                Component.translatable("mcaquests.atlas.status_suppression", AtlasQuestOverlay.suppressionSummary()),
                Component.translatable("mcaquests.atlas.status_held", AtlasHookState.handApplied(), AtlasHeldOverlay.visible()),
                Component.translatable("mcaquests.atlas.status_failure", failure.isEmpty() ? "—" : failure));
    }
}
