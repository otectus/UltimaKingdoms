package com.ultimakingdoms.evolution;

import com.ultimakingdoms.compat.recruits.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import java.util.*;
import static com.ultimakingdoms.evolution.RecruitTransferData.*;

/** Explicit bilateral defection/employment settlement; no ambient owner changes or free recruits. */
public final class RecruitTransferService {
    public record TransferView(UUID id,UUID unit,UUID owner,UUID recipient,UUID recipientGroup,String equipment,boolean family,long noticeUntil,
                               long deadline,long revision,Phase phase,String detail,boolean accountingDiverged) {}
    public record ReconciliationReview(UUID transfer,long revision,String fingerprint,String observation) {}
    public static boolean blocksMobilization(MinecraftServer server, UUID unit) {
        var data = RecruitTransferData.get(server);
        return !data.writable() || data.all().stream().anyMatch(t -> t.unit().equals(unit) && !t.terminal());
    }
    private final MinecraftServer server;
    private final RecruitTransferData data;
    public RecruitTransferService(MinecraftServer server) { this.server = server; data = RecruitTransferData.get(server); }
    public List<TransferView> transfers(ServerPlayer viewer) { actor(viewer); return data.all().stream()
            .filter(t->t.owner().equals(viewer.getUUID())||t.recipient().equals(viewer.getUUID()))
            .sorted(Comparator.comparingLong(Transfer::deadline).reversed()).map(this::view).toList(); }
    public List<TransferView> operatorTransfers(ServerPlayer viewer) { actor(viewer); if(!viewer.hasPermissions(2))throw new IllegalArgumentException("Operator reconciliation authority required");
        return data.all().stream().sorted(Comparator.comparingLong(Transfer::deadline).reversed()).map(this::view).toList(); }
    public ReconciliationReview reconciliationReview(ServerPlayer actor,UUID id) { actor(actor);if(!actor.hasPermissions(2))throw new IllegalArgumentException("Operator reconciliation authority required");
        var t=data.get(id).orElseThrow(()->new IllegalArgumentException("Transfer unavailable"));if(t.terminal()||t.snapshot()==null)throw new IllegalArgumentException("No pending native intent to reconcile");
        var review=RecruitsTransfer.review(loaded(t.unit()),t.snapshot(),false);return new ReconciliationReview(t.id(),t.revision(),review.fingerprint(),review.observation()); }
    public String version(ServerPlayer viewer) { return transfers(viewer).stream().map(v->v.id()+":"+v.revision()+":"+v.phase()).collect(java.util.stream.Collectors.joining("|")); }
    private TransferView view(Transfer t) { return new TransferView(t.id(),t.unit(),t.owner(),t.recipient(),t.recipientGroup(),t.equipment(),t.family(),t.noticeUntil(),t.deadline(),t.revision(),t.phase(),t.detail(),t.accountingDiverged()); }
    private void actor(ServerPlayer actor) { if (actor.getServer() != server || actor.hasDisconnected() || !server.isSameThread()) throw new IllegalArgumentException("Connected server player required"); }
    private void enabled() { if (!EvolutionRuntime.dramaEnabled(server)) throw new IllegalArgumentException("This world must explicitly enable political drama"); RecruitsMilitary.ready(server); }
    private Entity loaded(UUID id) {
        for (var level : server.getAllLevels()) { var entity = level.getEntity(id); if (entity != null) return entity; }
        throw new IllegalArgumentException("Recruit unloaded; its chunk was not loaded and its ownership was not inferred");
    }
    private void put(Transfer t) { if (!data.put(server, t)) throw new IllegalArgumentException("Transfer save unavailable; operation refused"); }
    private Transfer own(ServerPlayer actor, UUID id) {
        actor(actor); var t = data.get(id).orElseThrow(() -> new IllegalArgumentException("Transfer unavailable"));
        if (!t.owner().equals(actor.getUUID()) && !t.recipient().equals(actor.getUUID())) throw new IllegalArgumentException("Transfer unavailable"); return t;
    }
    private void family(Transfer t) {
        if (t.family() && com.ultimakingdoms.compat.mca.McaFamilyEvidence.marriage(server, t.owner()).filter(m -> m.second().equals(t.recipient())).isEmpty())
            throw new IllegalArgumentException("Reciprocal native family relationship unavailable; agreement suspended");
    }
    public String propose(ServerPlayer owner, UUID unitId, UUID recipientId, UUID group, boolean family) {
        actor(owner); enabled(); var recipient = server.getPlayerList().getPlayer(recipientId);
        if (recipient == null) throw new IllegalArgumentException("Recipient must be online to preview the agreement");
        var unit = loaded(unitId); var snapshot = RecruitsTransfer.snapshot(unit, owner, recipient, group);
        long now = server.overworld().getGameTime();
        var t = new Transfer(UUID.randomUUID(), unitId, owner.getUUID(), recipientId, group, snapshot.equipment(), family,
                now + 1200, now + 72000, 1, Phase.OFFERED, null, "Employment transfer: equipment travels with this recruit; native wages retain their remaining timer; no payment or troop levy is invented");
        family(t); put(t); return "Private transfer proposal " + t.id() + " revision 1; recipient must consent and 1200 game ticks of notice must elapse.";
    }
    public String consent(ServerPlayer recipient, UUID id, long revision) {
        enabled(); var t = own(recipient, id); family(t);
        if (!RecruitsTransfer.equipment(loaded(t.unit())).equals(t.equipment())) throw new IllegalArgumentException("Equipment changed; decline and negotiate fresh terms");
        put(t.consent(recipient.getUUID(), revision, server.overworld().getGameTime())); return "Recipient consent recorded; source owner must explicitly apply after notice.";
    }
    public String cancel(ServerPlayer actor, UUID id, long revision) {
        var t = own(actor, id);
        if (t.revision() != revision || t.phase() != Phase.OFFERED && t.phase() != Phase.CONSENTED) throw new IllegalArgumentException("Transfer already began; use restore for recoverable native intent");
        put(t.phase(Phase.CANCELLED, null, "Party declined before native release")); return "Transfer cancelled; native ownership is unchanged.";
    }
    public String apply(ServerPlayer owner, UUID id, long revision) {
        var t = own(owner, id); enabled(); family(t);
        if (!t.owner().equals(owner.getUUID()) || t.revision() != revision || t.phase() != Phase.CONSENTED
                || server.overworld().getGameTime() < t.noticeUntil() || server.overworld().getGameTime() >= t.deadline())
            throw new IllegalArgumentException("Source owner, current consent and elapsed notice required");
        var recipient = server.getPlayerList().getPlayer(t.recipient()); if (recipient == null) throw new IllegalArgumentException("Recipient must remain online");
        var unit = loaded(t.unit()); var before = RecruitsTransfer.snapshot(unit, owner, recipient, t.recipientGroup());
        if (!before.equipment().equals(t.equipment())) throw new IllegalArgumentException("Equipment changed; negotiate fresh terms");
        if (!RecruitsTransfer.saveAndConfirm(unit, before, false))
            throw new IllegalArgumentException("Native baseline could not be saved and verified; ownership is unchanged");
        t = t.phase(Phase.APPLYING, before, "Durable intent precedes native release/hire"); put(t);
        try {
            if (RecruitsTransfer.apply(unit, owner, recipient, before)) {
                t = t.phase(Phase.CONFIRMING, before, "Native ownership changed; disk acknowledgment pending"); put(t);
                if (RecruitsTransfer.saveAndConfirm(unit, before, true)) { put(t.phase(Phase.COMPLETE, before, "Native entity, equipment, group, owner counts and scoreboard saves acknowledged")); return "Negotiated transfer completed and durably confirmed."; }
                return "Transfer remains pending native save confirmation; use confirm.";
            }
            t = t.phase(Phase.RESTORING, before, "Native transfer refused; original ownership restoration awaiting disk acknowledgment"); put(t);
            if (RecruitsTransfer.saveAndConfirm(unit, before, false)) put(t.phase(Phase.CANCELLED, before, "Native refusal preserved original ownership and equipment"));
            return "Native transfer refused; inspect the retained restoration receipt.";
        } catch (RuntimeException failure) {
            // Durable APPLYING/CONFIRMING survives a failure even if recording this diagnostic also fails.
            var pending = data.get(id).orElseThrow();
            var recovery = pending.phase(Phase.RESTORING, before, "Native application interrupted; use restore with both parties present");
            put(recovery);
            if (RecruitsTransfer.matches(unit, before, false) && RecruitsTransfer.saveAndConfirm(unit, before, false))
                put(recovery.phase(Phase.CANCELLED, before, "Native exception rolled back and original ownership saved"));
            return "Transfer interrupted: " + failure.getMessage() + ". Original-state recovery intent retained.";
        }
    }
    public String confirm(ServerPlayer actor, UUID id) {
        var t = own(actor, id); if (t.terminal()) return t.detail();
        if (t.snapshot() == null) throw new IllegalArgumentException("No native transfer to confirm");
        var unit = loaded(t.unit());
        boolean changed = t.phase() != Phase.RESTORING;
        if (!RecruitsTransfer.saveAndConfirm(unit, t.snapshot(), changed)) return "Native persistence or identity not confirmed; intent retained for recovery.";
        put(t.phase(changed ? Phase.COMPLETE : Phase.CANCELLED, t.snapshot(), "Native saved state acknowledged")); return "Native transfer outcome durably confirmed.";
    }
    public String restore(ServerPlayer actor, UUID id) {
        var t = own(actor, id);
        if (t.terminal() || t.snapshot() == null) throw new IllegalArgumentException("No pending native transfer to restore");
        var owner = server.getPlayerList().getPlayer(t.owner()); var recipient = server.getPlayerList().getPlayer(t.recipient());
        if (owner == null || recipient == null) throw new IllegalArgumentException("Both original parties must be online for guarded recovery");
        var unit = loaded(t.unit()); put(t.phase(Phase.RESTORING, t.snapshot(), "Explicit original-state recovery requested"));
        RecruitsTransfer.restore(unit, owner, recipient, t.snapshot());
        return confirm(actor, id);
    }
    public List<String> inspect(ServerPlayer actor, UUID id) {
        var t = own(actor, id); return List.of(t.id() + " | " + t.phase() + " | revision " + t.revision(),
                "Recruit " + t.unit() + " to player " + t.recipient() + " group " + t.recipientGroup(),
                "Notice " + t.noticeUntil() + "; deadline " + t.deadline() + "; family introduction " + t.family(), t.detail());
    }
    public String reconciliationPreview(ServerPlayer actor,UUID id) {
        var review=reconciliationReview(actor,id);
        return "Revision "+review.revision()+"; native fingerprint "+review.fingerprint()+"; "+review.observation()+". Verify native accounting before attesting; this does not repair counts.";
    }
    public String reconcile(ServerPlayer actor,UUID id,long revision,String fingerprint,String reason) {
        actor(actor);if(!actor.hasPermissions(2))throw new IllegalArgumentException("Operator reconciliation authority required");
        var t=data.get(id).orElseThrow(()->new IllegalArgumentException("Transfer unavailable"));
        if(t.terminal()||t.snapshot()==null||t.revision()!=revision)throw new IllegalArgumentException("Pending intent changed; preview again");
        var unit=loaded(t.unit());var review=RecruitsTransfer.review(unit,t.snapshot(),false);
        if(!review.fingerprint().equals(fingerprint))throw new IllegalArgumentException("Native state changed; preview and review again");
        var receipt=new Reconciliation(actor.getUUID(),fingerprint,reason,server.overworld().getGameTime(),review.observation());
        RecruitsTransfer.review(unit,t.snapshot(),true);
        put(new Transfer(t.id(),t.unit(),t.owner(),t.recipient(),t.recipientGroup(),t.equipment(),t.family(),t.noticeUntil(),t.deadline(),t.revision()+1,
                Phase.RECONCILED,t.snapshot(),"Operator acknowledged reviewed native state without ownership or accounting writes",t.accountingDiverged(),receipt));
        return "Native state durably acknowledged as RECONCILED; original transfer and divergence evidence retained.";
    }
}
