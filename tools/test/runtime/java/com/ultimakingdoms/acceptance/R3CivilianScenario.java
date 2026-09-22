package com.ultimakingdoms.acceptance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.ultimakingdoms.api.factions.organization.OrganizationApi;
import com.ultimakingdoms.api.politics.Politics;
import com.ultimakingdoms.api.politics.UltimaPoliticsApi;
import com.ultimakingdoms.api.warfare.WarfareApi;
import com.ultimakingdoms.civic.CivicRuntime;
import com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge;
import com.ultimakingdoms.knowledge.SettlementKnowledge;
import com.ultimakingdoms.warfare.WarfareRuntime;
import com.ultimakingdoms.warfare.contracts.CivilianContractKind;
import com.ultimakingdoms.warfare.contracts.CivilianContractService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.util.FakePlayer;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.ultimakingdoms.acceptance.IntegrationScenario.check;

/** Packaged R3 civilian loop using native Townstead, MCA, Recruits, MCAQuests and Crime state. */
final class R3CivilianScenario {
    private static final String A = "ultima_kingdoms:serenum";
    private static final String B = "ultima_kingdoms:lunari";
    private static final int VILLAGE = 98_701;
    private static final UUID PLAYER = UUID.fromString("73000000-0000-0000-0000-000000000001");
    private static final ResourceLocation ORGANIZATION = new ResourceLocation("ultima_kingdoms:lamplighters");
    private static final ResourceLocation RELIEF = new ResourceLocation("ultima:warfare/relief");
    private static final ResourceLocation RECEIPT_CONSUMER =
            new ResourceLocation("ultima_kingdoms:regional_civic_network");
    private static final String FIXTURE = "r3-civilian-fixture.json";
    private static final String RELIEF_JSON = """
            {
              "format_version": 1,
              "id": "ultima:warfare/relief",
              "institutional_commission": true,
              "weight": 1,
              "category": "Civilian Relief",
              "difficulty": "easy",
              "priority": 10,
              "repeat": { "type": "once" },
              "giver": { "adult_only": true },
              "conditions": { "type": "mcaquests:institutional_service_available" },
              "title": { "text": "Essential Food Relief" },
              "dialogue": {
                "offer": { "text": "Bring sixteen loaves for local civilian relief. Anyone eligible for relief may serve, without joining a guild or faction. Payment: six emeralds." },
                "accept": { "text": "Your issuer, settlement, and relief terms are now fixed. Bring the bread back to this contact." },
                "decline": { "text": "Relief remains voluntary." },
                "in_progress": { "text": "Sixteen loaves will complete the shipment. Provider absence pauses this contract without consuming it." },
                "ready": { "text": "The relief shipment is ready for this issuer to receive." },
                "complete": { "text": "The shipment is received. Six emeralds are paid by the native quest provider." }
              },
              "objectives": [ { "type": "mcaquests:item_delivery", "item": "minecraft:bread", "count": 16, "consume": true } ],
              "rewards": [ { "type": "mcaquests:item", "item": "minecraft:emerald", "count": 6 } ],
              "turn_in": { "mode": "original_giver" }
            }
            """;

    private R3CivilianScenario() { }

    static void run(MinecraftServer server, String phase, BiConsumer<Long, Runnable> schedule,
                    Consumer<String> finish) throws Exception {
        if (phase.equals("r3-civilian-restart")) {
            restart(server, schedule, finish);
            return;
        }
        check(phase.equals("r3-civilian"), "R3 civilian scenario is routed only by its dedicated phase");
        var player = new FakePlayer(server.overworld(), new GameProfile(PLAYER, "R3ReliefWorker"));
        var operator = new FakePlayer(server.overworld(), new GameProfile(UUID.randomUUID(), "R3CivilianSteward")) {
            @Override public boolean hasPermissions(int level) { return level <= 2; }
        };
        server.getProfileCache().add(operator.getGameProfile());
        player.getInventory().clearContent();

        BlockPos pos = new BlockPos(7_600, -60, 7_600);
        var place = CivicLoopScenario.place(server, VILLAGE, pos, "R3 Civilian Relief");
        player.moveTo(pos.getX() + 1, pos.getY(), pos.getZ());
        operator.moveTo(pos.getX() + 1, pos.getY(), pos.getZ());
        SettlementKnowledge.get(server).discover(player.getUUID(), place.settlement().id());
        SettlementKnowledge.get(server).discover(operator.getUUID(), place.settlement().id());

        occupy(server, operator, place.settlement().id(), pos);
        var control = WarfareApi.get(server).orElseThrow().control(place.settlement().id()).orElseThrow();
        check(control.civicKingdom().equals(A) && control.recognizedKingdom().equals(A),
                "native occupation preserves the settlement's recognized civilian sovereignty");
        check(control.nativeController().equals("r3_civilian_occupier")
                        && control.availability().equals("available"),
                "actual persisted Recruits controller supplies available R3 control evidence");

        var politics = UltimaPoliticsApi.get(server);
        R2InstitutionScenario.success(politics.execute(operator, R2InstitutionScenario.request(politics,
                Politics.Action.BOOTSTRAP, A, A + "_charter", place.settlement().id().toString(),
                new Politics.Person(operator.getUUID(), Politics.Kind.PLAYER), "", "", BlockPos.ZERO)));
        schedule.accept(40L, () -> {
            try {
                exercise(server, player, operator, place, finish);
            } catch (Throwable failure) {
                failure.printStackTrace();
                finish.accept("FAIL " + failure);
            }
        });
    }

    private static void exercise(MinecraftServer server, ServerPlayer player, ServerPlayer operator,
                                 CivicLoopScenario.Place place, Consumer<String> finish) throws Exception {
        var politics = UltimaPoliticsApi.get(server);
        var civic = CivicRuntime.get(server);
        var recognition = R2InstitutionScenario.success(politics.execute(operator,
                R2InstitutionScenario.request(politics, Politics.Action.RECOGNIZE, A,
                        "ultima_kingdoms:guild", "", null, "", "", place.settlement().anchor())));
        check(civic.charter(operator, ORGANIZATION, UUID.fromString(recognition.recordId())).success(),
                "recognized relief institution charters against the actual Townstead workshop");
        UUID institution = UUID.fromString(recognition.recordId());
        UUID chapter = civic.knownChapters(operator, 0).stream()
                .filter(view -> view.institution().equals(institution)).findFirst().orElseThrow().id();
        check(civic.appoint(operator, chapter, place.npc()).success(),
                "actual adult MCA resident becomes the recognized civilian contact");
        check(civic.speakerContext(player, place.npc()).orElseThrow().servicesAvailable(),
                "neutral player can reach the operational recognized contact");
        check(OrganizationApi.get(server).ownSnapshot(player).memberships().isEmpty(),
                "relief worker begins without guild or faction membership");

        R2CrimeFixture.setup(player, place.npc());
        Object policy = policy(server, player, VILLAGE);
        check((boolean) IntegrationScenario.call(policy, "occupied")
                        && IntegrationScenario.call(policy, "civilLaw").toString().equals("CONTINUES")
                        && IntegrationScenario.call(policy, "availability").toString().equals("AVAILABLE"),
                "Crime receives available occupied jurisdiction with continuing local civilian law");
        check(!mayEnforce(place.npc(), player),
                "occupation alone does not authorize violence against a civilian");
        R2CrimeFixture.reportTheft(player, place.npc(), false);
        check(!mayEnforce(place.npc(), player),
                "unreported local suspicion remains insufficient for civilian enforcement");
        R2CrimeFixture.reportTheft(player, place.npc(), true);
        check(mayEnforce(place.npc(), player),
                "actual accepted local Crime evidence permits local enforcement under occupation");

        Object definition = reliefDefinition();
        NativeQuestScenario.register(definition);
        Object data = ((Optional<?>) NativeQuestScenario.invoke("state.QuestCapabilities", "get", player))
                .orElseThrow();
        check(!(boolean) NativeQuestScenario.invoke("quest.QuestManager", "accept", player,
                        place.npc(), RELIEF),
                "institutional relief cannot be accepted without an owner-issued native binding");

        var offer = CivilianContractService.offer(player, place.npc(), CivilianContractKind.RELIEF);
        check(offer.success(), "neutral relief remains available despite local Crime evidence: " + offer.reason());
        String binding = offer.binding().orElseThrow();
        check(binding.startsWith("r3:"), "native offer carries the canonical R3 contract binding");
        check((boolean) NativeQuestScenario.invoke("quest.QuestManager", "accept", player,
                        place.npc(), RELIEF),
                "native MCAQuests acceptance commits the immutable civilian contract instance");
        Object active = ((List<?>) NativeQuestScenario.call(data, "active")).get(0);
        UUID instance = (UUID) NativeQuestScenario.call(active, "instance");
        check(binding.equals(NativeQuestScenario.call(active, "institutionalBinding")),
                "accepted native instance retains the owner-issued R3 terms binding");

        player.getInventory().add(new ItemStack(Items.BREAD, 16));
        NativeQuestScenario.complete(player, place.npc(), definition, active, data);
        check(player.getInventory().countItem(Items.BREAD) == 0
                        && player.getInventory().countItem(Items.EMERALD) == 6,
                "native objective consumes sixteen bread and native reward pays exactly six emeralds");
        check(CivilianContractService.proof(server, player.getUUID(), place.settlement().id(),
                        CivilianContractKind.RELIEF).isEmpty(),
                "native reward alone does not invent an owner-side service proof");

        QuestCompletionBridge.consume(player);
        QuestCompletionBridge.consume(player);
        var proof = CivilianContractService.proof(server, player.getUUID(), place.settlement().id(),
                CivilianContractKind.RELIEF).orElseThrow();
        check(proof.receipt().equals(instance) && proof.giver().equals(place.npc().getUUID())
                        && proof.institution().equals(institution)
                        && proof.nativeController().equals("r3_civilian_occupier"),
                "durable proof retains the native receipt, giver, institution and occupied controller");
        check(CivilianContractService.hasService(server, player.getUUID(), place.settlement().id(),
                        CivilianContractKind.RELIEF),
                "campaign prerequisite reads the receipt-backed civilian service proof");
        check(completionReceipts(player).isEmpty(),
                "provider receipt is acknowledged only after the owner proof commits");

        int emeralds = player.getInventory().countItem(Items.EMERALD);
        var replay = CivilianContractService.offer(player, place.npc(), CivilianContractKind.RELIEF);
        check(!replay.success() && replay.reason().equals("warfare.contract_already_served"),
                "completed civilian service cannot open a second reward-bearing offer");
        check(!(boolean) NativeQuestScenario.invoke("quest.QuestManager", "completeQuest", player,
                        place.npc(), definition, active, data)
                        && player.getInventory().countItem(Items.EMERALD) == emeralds,
                "replayed native completion cannot pay or record service twice");
        check(OrganizationApi.get(server).ownSnapshot(player).memberships().isEmpty(),
                "completed relief leaves the worker neutral and unaffiliated");

        server.saveEverything(false, true, true);
        writeFixture(server, proof);
        finish.accept("PASS integration R3 civilian: occupied local law, neutral native relief, durable acknowledged once-only proof");
    }

    private static void restart(MinecraftServer server, BiConsumer<Long, Runnable> schedule,
                                Consumer<String> finish) throws Exception {
        JsonObject fixture = JsonParser.parseString(Files.readString(server.getWorldPath(LevelResource.ROOT)
                .resolve(FIXTURE))).getAsJsonObject();
        var player = new FakePlayer(server.overworld(), new GameProfile(PLAYER, "R3ReliefWorker"));
        CompoundTag savedPlayer = NbtIo.readCompressed(server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(PLAYER + ".dat").toFile());
        player.load(savedPlayer);
        schedule.accept(40L, () -> {
            try {
                UUID settlement = uuid(fixture, "settlement");
                UUID epoch = uuid(fixture, "providerEpoch");
                UUID receipt = uuid(fixture, "receipt");
                var proof = CivilianContractService.proof(server, PLAYER, settlement,
                        CivilianContractKind.RELIEF).orElseThrow();
                check(proof.contract().equals(uuid(fixture, "contract"))
                                && proof.providerEpoch().equals(epoch) && proof.receipt().equals(receipt)
                                && proof.player().equals(PLAYER) && proof.giver().equals(uuid(fixture, "giver"))
                                && proof.institution().equals(uuid(fixture, "institution"))
                                && proof.organization().equals(fixture.get("organization").getAsString()),
                        "restart reloads the exact receipt-backed proof with frozen issuer and institution");
                check(proof.recognizedKingdom().equals(fixture.get("recognizedKingdom").getAsString())
                                && proof.nativeController().equals(fixture.get("nativeController").getAsString())
                                && proof.autonomy().equals(fixture.get("autonomy").getAsString())
                                && proof.controlSequence() == fixture.get("controlSequence").getAsLong()
                                && proof.completedAt() == fixture.get("completedAt").getAsLong(),
                        "restart preserves the accepted contract's frozen control terms");
                check(CivilianContractService.hasService(server, PLAYER, settlement, CivilianContractKind.RELIEF),
                        "campaign prerequisite remains available after process restart");
                check(savedAcknowledged(savedPlayer, epoch, receipt),
                        "provider player NBT retains the durable owner acknowledgement tombstone");
                check(completionReceipts(player).isEmpty(),
                        "acknowledged native receipt is not redelivered after player capability reload");
                QuestCompletionBridge.consume(player);
                check(completionReceipts(player).isEmpty()
                                && CivilianContractService.proof(server, PLAYER, settlement,
                                CivilianContractKind.RELIEF).orElseThrow().equals(proof),
                        "restart polling deduplicates the native receipt without changing the owner proof");
                finish.accept("PASS integration R3 civilian restart: persisted frozen proof and native acknowledgement dedupe");
            } catch (Throwable failure) {
                failure.printStackTrace();
                finish.accept("FAIL " + failure);
            }
        });
    }

    private static void writeFixture(MinecraftServer server, CivilianContractService.ServiceProof proof)
            throws Exception {
        JsonObject fixture = new JsonObject();
        fixture.addProperty("contract", proof.contract().toString());
        fixture.addProperty("providerEpoch", proof.providerEpoch().toString());
        fixture.addProperty("receipt", proof.receipt().toString());
        fixture.addProperty("giver", proof.giver().toString());
        fixture.addProperty("institution", proof.institution().toString());
        fixture.addProperty("settlement", proof.settlement().toString());
        fixture.addProperty("organization", proof.organization());
        fixture.addProperty("recognizedKingdom", proof.recognizedKingdom());
        fixture.addProperty("nativeController", proof.nativeController());
        fixture.addProperty("autonomy", proof.autonomy());
        fixture.addProperty("controlSequence", proof.controlSequence());
        fixture.addProperty("completedAt", proof.completedAt());
        Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve(FIXTURE), fixture.toString());
    }

    private static UUID uuid(JsonObject fixture, String key) {
        return UUID.fromString(fixture.get(key).getAsString());
    }

    private static boolean savedAcknowledged(CompoundTag player, UUID epoch, UUID receipt) throws Exception {
        check(player.contains("ForgeCaps", Tag.TAG_COMPOUND), "saved player has Forge capability data");
        CompoundTag caps = player.getCompound("ForgeCaps");
        check(caps.contains("mcaquests:player_quests", Tag.TAG_COMPOUND),
                "saved player has native MCAQuests state");
        Class<?> type = Class.forName(NativeQuestScenario.PREFIX + "state.PlayerQuestData");
        Object data = type.getConstructor().newInstance();
        type.getMethod("load", CompoundTag.class).invoke(data, caps.getCompound("mcaquests:player_quests"));
        return (boolean) type.getMethod("completionReceiptAcknowledged", ResourceLocation.class,
                UUID.class, UUID.class).invoke(data, RECEIPT_CONSUMER, epoch, receipt);
    }

    private static void occupy(MinecraftServer server, ServerPlayer operator, UUID settlement,
                               BlockPos pos) throws Exception {
        Object factions = Class.forName("com.talhanation.recruits.FactionEvents")
                .getField("recruitsFactionManager").get(null);
        IntegrationScenario.call(factions, "addTeam", "r3_civilian_occupier", "r3_civilian_occupier",
                UUID.randomUUID(), "r3_civilian_occupier_leader", new CompoundTag(), (byte) 1,
                ChatFormatting.BLUE);
        Object faction = IntegrationScenario.call(factions, "getFactionByStringID", "r3_civilian_occupier");
        Object claims = Class.forName("com.talhanation.recruits.ClaimEvents")
                .getField("recruitsClaimManager").get(null);
        Class<?> claimType = Class.forName("com.talhanation.recruits.world.RecruitsClaim");
        Object claim = claimType.getConstructor(String.class, faction.getClass())
                .newInstance("R3 Civilian Occupation", faction);
        ChunkPos chunk = new ChunkPos(pos);
        IntegrationScenario.call(claim, "setCenter", chunk);
        IntegrationScenario.call(claim, "addChunk", chunk);
        IntegrationScenario.call(claims, "addOrUpdateClaim", server.overworld(), claim);
        WarfareRuntime runtime = WarfareRuntime.get(server);
        runtime.mapHere(operator, new ResourceLocation(B));
        IntegrationScenario.call(claims, "save", server.overworld());
        IntegrationScenario.call(factions, "save", server.overworld());
        server.saveEverything(false, true, true);
        runtime.bindHere(operator, settlement);
    }

    private static Object reliefDefinition() throws Exception {
        Codec<?> codec = (Codec<?>) Class.forName(NativeQuestScenario.PREFIX + "quest.QuestDefinition")
                .getField("CODEC").get(null);
        return codec.parse(JsonOps.INSTANCE, JsonParser.parseString(RELIEF_JSON))
                .getOrThrow(false, message -> { throw new AssertionError(message); });
    }

    private static Object policy(MinecraftServer server, ServerPlayer player, int village) throws Exception {
        Class<?> communityType = Class.forName("dev.otectus.mcacrime.api.model.CrimeCommunityKey");
        Object community = communityType.getConstructor(ResourceLocation.class, int.class)
                .newInstance(server.overworld().dimension().location(), village);
        Class<?> api = Class.forName("dev.otectus.mcacrime.api.JurisdictionPolicyApi");
        return ((Optional<?>) api.getMethod("policy", MinecraftServer.class, communityType, UUID.class)
                .invoke(null, server, community, player.getUUID())).orElseThrow();
    }

    private static boolean mayEnforce(Object responder, LivingEntity target) throws Exception {
        Method method = Class.forName("dev.otectus.mcacrime.api.JurisdictionPolicyApi")
                .getMethod("mayEnforce", LivingEntity.class, LivingEntity.class);
        return (boolean) method.invoke(null, (LivingEntity) responder, target);
    }

    private static List<?> completionReceipts(ServerPlayer player) throws Exception {
        return (List<?>) NativeQuestScenario.invoke("api.McaQuestsApi", "readCompletionReceipts",
                player, RECEIPT_CONSUMER, Integer.valueOf(8));
    }
}
