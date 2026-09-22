package dev.otectus.mcaquests.network;

import dev.otectus.mcaquests.McaQuests;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.NetworkDirection;

import java.util.Optional;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Our own Forge {@link SimpleChannel} (independent of MCA's "cobalt" network). Registered during
 * common setup. All quest packets flow through here (spec section 20).
 */
public final class QuestNetwork {

    // Bumped to 16 — item delivery. QuestDeliverC2SPacket is new, CardObjective carries what has been
    // handed over, what the player is carrying, whether this villager may take it and why not,
    // QuestCard names the active copy and its own per-card state, and the menu packet carries the
    // result line. Every one of those is a shape change in a packet a 1.6.4 client would decode as the
    // old one: it would read the delivery fields as the start of the next objective and draw nonsense,
    // so client and server must match.
    // (15 was GuidanceKind gaining INSTRUCTION, a destination that is a line of text and no
    // geometry. The kind's ordinal is on the wire, and an older client would decode the new one as
    // LOCATION and draw a marker on 0,0,0 in a world it is not standing in, so the two must match.)
    // (14 was GuidanceTarget carrying the target entity's bounding-box height, so the marker could
    // anchor its glyph on the body of an entity the client cannot currently see rather than at the
    // transmitted feet position.)
    // (13 was a destination for every quest, not just the marked one. QuestGuidanceS2CPacket
    // now carries a GuidanceSnapshot (one ActiveGuidance per quest, plus the index of the one the
    // marker stands on) rather than a single optional target, GuidanceTarget says whether a position
    // is somebody's last known whereabouts, and QuestLogEntry.TargetHint is gone — it was a second,
    // dimensionless way of saying the same thing and only ever named a villager.)
    // (12 was objective guidance. QuestGuidanceS2CPacket and QuestTrackC2SPacket were new,
    // CardObjective carries an objective's state as an enum rather than a single "unavailable" flag,
    // and QuestLogEntry says which quest the player is following.) (11 was v1.5.0's interface: cards
    // gained per-objective progress, reward preview stacks and a difficulty band; 10 was
    // QuestLogEntry carrying whether a quest is suspended, so the log could say a quest is waiting on
    // a mod that is no longer installed instead of showing a counter that nothing can advance; 9 was
    // per-player quest-target highlighting — HighlightTargetsS2CPacket was new and QuestLogEntry
    // gained the bound target's name and position for the HUD's direction cue.
    // (8 was the journal's View Deeds link (§29.7): JournalVillageEntry gained the village's dimension
    // and id, JournalSyncS2CPacket gained whether MCA: Reputation is canonical, and
    // OpenStandingC2SPacket was new; 7 was v1.1.0: the built-in pack moved to translation keys;
    // 6 was the abandon-from-log packet and a giver UUID on QuestLogEntry; 5 was task M5.1: the FTB
    // editor known-ids sync packet; 4 was v0.8.0: the "village needs help" situation toast packet;
    // 3 was v0.7.0: the reputation tier-up toast and journal request/sync packets; 2 was v0.4.0: the
    // community-project menu/log/contribute packets.)
    // The channel handshake requires matching client+server (save data is unaffected).
    private static final String PROTOCOL_VERSION = "17";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(McaQuests.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private QuestNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(nextId++, OpenQuestMenuC2SPacket.class,
                OpenQuestMenuC2SPacket::encode, OpenQuestMenuC2SPacket::decode, OpenQuestMenuC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, QuestDecisionC2SPacket.class,
                QuestDecisionC2SPacket::encode, QuestDecisionC2SPacket::decode, QuestDecisionC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, QuestTurnInC2SPacket.class,
                QuestTurnInC2SPacket::encode, QuestTurnInC2SPacket::decode, QuestTurnInC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, QuestAbandonC2SPacket.class,
                QuestAbandonC2SPacket::encode, QuestAbandonC2SPacket::decode, QuestAbandonC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, QuestMenuDataS2CPacket.class,
                QuestMenuDataS2CPacket::encode, QuestMenuDataS2CPacket::decode, QuestMenuDataS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, QuestLogSyncS2CPacket.class,
                QuestLogSyncS2CPacket::encode, QuestLogSyncS2CPacket::decode, QuestLogSyncS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, QuestReadyToastS2CPacket.class,
                QuestReadyToastS2CPacket::encode, QuestReadyToastS2CPacket::decode, QuestReadyToastS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // v0.4.0 — community projects.
        CHANNEL.registerMessage(nextId++, ProjectContributeC2SPacket.class,
                ProjectContributeC2SPacket::encode, ProjectContributeC2SPacket::decode, ProjectContributeC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, ProjectMenuDataS2CPacket.class,
                ProjectMenuDataS2CPacket::encode, ProjectMenuDataS2CPacket::decode, ProjectMenuDataS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, ProjectLogSyncS2CPacket.class,
                ProjectLogSyncS2CPacket::encode, ProjectLogSyncS2CPacket::decode, ProjectLogSyncS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, ProjectPhaseToastS2CPacket.class,
                ProjectPhaseToastS2CPacket::encode, ProjectPhaseToastS2CPacket::decode, ProjectPhaseToastS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // v0.7.0 — progression: tier-up toast + journal request/sync.
        CHANNEL.registerMessage(nextId++, ReputationTierToastS2CPacket.class,
                ReputationTierToastS2CPacket::encode, ReputationTierToastS2CPacket::decode, ReputationTierToastS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, RequestJournalC2SPacket.class,
                RequestJournalC2SPacket::encode, RequestJournalC2SPacket::decode, RequestJournalC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, JournalSyncS2CPacket.class,
                JournalSyncS2CPacket::encode, JournalSyncS2CPacket::decode, JournalSyncS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // v0.8.0 — Living Village: situation "needs help" toast.
        CHANNEL.registerMessage(nextId++, SituationToastS2CPacket.class,
                SituationToastS2CPacket::encode, SituationToastS2CPacket::decode, SituationToastS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // Task M5.1 — FTB Quests editor known-ids sync (registered unconditionally; only the send is
        // gated on FTB Quests being loaded + syncFtbqEditorIds, see FtbqEditorIdsSync).
        CHANNEL.registerMessage(nextId++, FtbqEditorIdsS2CPacket.class,
                FtbqEditorIdsS2CPacket::encode, FtbqEditorIdsS2CPacket::decode, FtbqEditorIdsS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // Abandon from the quest log — no villager interaction required, so a quest whose giver is gone
        // is still droppable.
        CHANNEL.registerMessage(nextId++, QuestAbandonFromLogC2SPacket.class,
                QuestAbandonFromLogC2SPacket::encode, QuestAbandonFromLogC2SPacket::decode,
                QuestAbandonFromLogC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // §29.7 — the journal's View Deeds link into MCA: Reputation's standing screen. Registered
        // unconditionally like every packet; the handler no-ops unless Reputation is canonical.
        CHANNEL.registerMessage(nextId++, OpenStandingC2SPacket.class,
                OpenStandingC2SPacket::encode, OpenStandingC2SPacket::decode,
                OpenStandingC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // Per-player quest-target highlighting — the glow is drawn client-side for the quest owner only,
        // so one player's markers are never visible to everyone else on the server.
        CHANNEL.registerMessage(nextId++, HighlightTargetsS2CPacket.class,
                HighlightTargetsS2CPacket::encode, HighlightTargetsS2CPacket::decode,
                HighlightTargetsS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // v1.5.0 — objective guidance. The marker the player follows, and which quest they follow.
        // Appended, because ids here are positional: inserting anywhere above renumbers every packet
        // after it, and a client one build behind would decode a project contribution as a toast.
        CHANNEL.registerMessage(nextId++, QuestGuidanceS2CPacket.class,
                QuestGuidanceS2CPacket::encode, QuestGuidanceS2CPacket::decode,
                QuestGuidanceS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, QuestTrackC2SPacket.class,
                QuestTrackC2SPacket::encode, QuestTrackC2SPacket::decode,
                QuestTrackC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // v1.6.5 — the Deliver action on a quest card. Appended, like everything above it: the ids are
        // positional, so inserting anywhere earlier renumbers every packet after it.
        CHANNEL.registerMessage(nextId++, QuestDeliverC2SPacket.class,
                QuestDeliverC2SPacket::encode, QuestDeliverC2SPacket::decode,
                QuestDeliverC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId++, ExternalMapPointsS2CPacket.class,
                ExternalMapPointsS2CPacket::encode, ExternalMapPointsS2CPacket::decode,
                ExternalMapPointsS2CPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
