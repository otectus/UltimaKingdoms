package com.ultimakingdoms.civic;

import com.ultimakingdoms.api.factions.organization.*;
import com.ultimakingdoms.compat.recruits.RecruitsObservation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import net.minecraft.network.chat.Component;

/** Bounded, own-player presentation shared by commands and the ledger. */
public final class CivicViews {
    public record View(String organization, String nameKey, int index, int total,
                       boolean active, List<Component> lines) {
        public View {
            lines = lines.stream().limit(32).map(Component::copy).map(c -> (Component)c).toList();
        }
    }
    private CivicViews() { }

    private static void text(List<Component> lines, String value) { lines.add(Component.literal(value.substring(0, Math.min(512,value.length())))); }

    private static String questStatus(String status) {
        return switch(status) {
            case "disabled" -> "Guild quest service is disabled by this server.";
            case "ready", "available" -> "Verified quest service is available.";
            case "absent", "unsupported_completion_api" -> "Verified quest service needs a compatible MCA Quests installation.";
            case "pending_save" -> "Your latest quest service is waiting for a durable save.";
            default -> "Quest service is waiting for recovery; your pending work is retained.";
        };
    }

    public static View own(ServerPlayer player, int index) {
        OrganizationService service = OrganizationApi.get(player.getServer());
        var snapshot = service.ownSnapshot(player);
        var ids = new TreeSet<ResourceLocation>(Comparator.comparing(ResourceLocation::toString));
        service.definitions().forEach(d -> ids.add(d.id()));
        snapshot.memberships().forEach(m -> ids.add(m.organizationId()));
        if (index < 0 || index > 4096) throw new IllegalArgumentException("Invalid guild page");
        if (ids.isEmpty()) return new View("", "civic.ultima_kingdoms.no_organizations", 0, 0, false,
                List.of(Component.literal("No organizations are currently defined.")));
        int actual = Math.min(index, ids.size() - 1);
        ResourceLocation id = new ArrayList<>(ids).get(actual);
        var definition = service.definition(id);
        var membership = snapshot.memberships().stream().filter(m -> m.organizationId().equals(id)).findFirst();
        boolean active = membership.map(m -> m.status() == OrganizationMembershipSnapshot.Status.ACTIVE).orElse(false);
        long standing = membership.map(OrganizationMembershipSnapshot::standing).orElse(0L);
        List<Component> lines = new ArrayList<>();
        text(lines, "Membership: " + membership.map(m -> m.status().name().toLowerCase(Locale.ROOT)).orElse("unaffiliated"));
        text(lines, "Guild standing: " + standing + " | Verified deeds: " + membership.map(OrganizationMembershipSnapshot::deedCount).orElse(0));
        text(lines, "Rank: " + membership.flatMap(OrganizationMembershipSnapshot::rank).map(r -> r.getPath().replace('_',' ')).orElse("none"));
        definition.ifPresent(d -> d.ranks().stream().filter(r -> r.minimumStanding() > standing)
                .min(Comparator.comparingLong(OrganizationRankView::minimumStanding))
                .ifPresent(r -> text(lines, "Next rank: " + r.id().getPath().replace('_',' ') + " (" + (r.minimumStanding() - standing) + " more standing)")));
        text(lines, "You may leave and rejoin; your recorded service is retained.");
        text(lines, "Kingdom citizenship and military allegiance are separate.");
        if (definition.isEmpty()) text(lines, "Definition unavailable: membership is retained; joining and permissions are suspended.");
        var civic=CivicRuntime.get(player.getServer());
        var contact=civic.nearbyContact(player,id);
        if(contact.isPresent())civic.speakerContext(player,contact.get()).ifPresent(context->{
            text(lines, "Nearby contact: " + contact.get().getName().getString());
            context.reasons().stream().limit(3).forEach(reason->lines.add(CivicText.reason(reason)));
        });
        else text(lines, "Visit a guild contact to request an introduction or browse commissions.");
        if(CivicConfig.INSTITUTIONAL_SERVICES.get()&&contact.isPresent()) {
            var legal=com.ultimakingdoms.compat.crime.InstitutionalCrimeBridge.workshop(player,contact.get());
            if(!legal.allowed())lines.add(CivicText.reason(legal.reason()));
            legal.restitution().forEach(line->text(lines,"Your local restitution: "+line));
            lines.add(CivicText.reason("civic.workshop_terms"));
        }
        text(lines, questStatus(com.ultimakingdoms.compat.quests.receipts.QuestCompletionBridge.status(player)));
        definition.ifPresent(d -> d.services().stream().limit(4).forEach(rule -> {
            var explanation=service.explainOwn(player,id,rule.permission());
            var message=Component.literal("Qualification for " + rule.permission().getPath().replace('_',' ') + ": ");
            explanation.reasons().stream().limit(3).forEach(reason -> message.append(CivicText.reason(reason)).append(" "));
            lines.add(message);
        }));
        membership.ifPresent(m -> m.recentEvidence().stream().limit(4).forEach(e -> text(lines, "Recorded service: " + e.questId().substring(e.questId().lastIndexOf('/')+1).replace('_',' '))));
        service.ownHistory(player,4).stream().filter(e->e.organization().equals(id)).forEach(e->
                text(lines,"History: "+e.action().replace('_',' ')+" (day "+(e.gameTime()/24000)+")"));
        var military = RecruitsObservation.here(player);
        if (military.status() == RecruitsObservation.Status.AVAILABLE && !military.claimId().isEmpty())
            text(lines, "Military claim here: " + military.ownerId() + (military.underSiege() ? " (under siege)" : ""));
        if (com.ultimakingdoms.warfare.WarfareConfig.ENABLED.get())
            text(lines, "Local occupation and control history: /ultima warfare here. Civilian guild services keep their own rules.");
        return new View(id.toString(), definition.map(OrganizationDefinitionView::nameKey).orElse(id.toString()),
                actual, ids.size(), active, lines);
    }
}
