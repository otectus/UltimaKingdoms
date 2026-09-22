package com.ultimakingdoms.compat.recruits;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** Server-side validation of the audited native UI packet, which otherwise trusts client faction IDs. */
public final class RecruitsPacketGuard {
    private RecruitsPacketGuard() { }
    public static boolean authorize(Object packet, NetworkEvent.Context context) {
        ServerPlayer sender = context.getSender();
        if (sender == null || sender.getServer() == null || !sender.getServer().isSameThread()) return false;
        try {
            var own = packet.getClass().getDeclaredField("ownTeam"); own.setAccessible(true);
            var other = packet.getClass().getDeclaredField("otherTeam"); other.setAccessible(true);
            var value = packet.getClass().getDeclaredField("status"); value.setAccessible(true);
            String from = (String)own.get(packet), to = (String)other.get(packet); byte status = value.getByte(packet);
            return status >= 0 && status <= 2 && from != null && to != null && !from.equals(to)
                    && RecruitsMilitary.commands(sender, from) && RecruitsMilitary.faction(sender.getServer(), to).isPresent();
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
    public static boolean authorizeTreaty(Object packet, NetworkEvent.Context context) {
        ServerPlayer sender = context.getSender();
        if (sender == null || sender.getServer() == null || !sender.getServer().isSameThread()) return false;
        try {
            var field = packet.getClass().getDeclaredField("recruit"); field.setAccessible(true);
            var messenger = sender.serverLevel().getEntity((java.util.UUID)field.get(packet));
            if (messenger == null || !messenger.getClass().getName().equals("com.talhanation.recruits.entities.MessengerEntity")
                    || messenger.distanceToSqr(sender) > 256 || sender.getTeam() == null || !RecruitsMilitary.commands(sender, sender.getTeam().getName())) return false;
            Object target = messenger.getClass().getMethod("getTargetPlayerInfo").invoke(messenger);
            return target != null && sender.getUUID().equals(target.getClass().getMethod("getUUID").invoke(target))
                    && (boolean)messenger.getClass().getMethod("isTreatyMessenger").invoke(messenger);
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
}
