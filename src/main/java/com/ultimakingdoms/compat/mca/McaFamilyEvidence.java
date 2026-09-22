package com.ultimakingdoms.compat.mca;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Read-only MCA family facts. Missing/unloaded people never become deceased by inference. */
public final class McaFamilyEvidence {
    public record Marriage(UUID first, UUID second) { }
    public static Optional<Marriage> marriage(MinecraftServer server, UUID person) {
        if (!server.isSameThread()) throw new IllegalStateException("Family evidence requires server thread");
        try {
            String root = McaAccess.root(); if (root == null || root.isBlank()) return Optional.empty();
            Class<?> treeType = Class.forName(root + ".server.world.data.FamilyTree");
            Object tree = treeType.getMethod("get", ServerLevel.class).invoke(null, server.overworld());
            Object first = ((Optional<?>) treeType.getMethod("getOrEmpty", UUID.class).invoke(tree, person)).orElse(null);
            if (first == null || deceased(first) || !married(first)) return Optional.empty();
            UUID spouse = (UUID) first.getClass().getMethod("partner").invoke(first);
            if (spouse == null || spouse.equals(person)) return Optional.empty();
            Object second = ((Optional<?>) treeType.getMethod("getOrEmpty", UUID.class).invoke(tree, spouse)).orElse(null);
            if (second == null || deceased(second) || !married(second) || !person.equals(second.getClass().getMethod("partner").invoke(second))) return Optional.empty();
            return Optional.of(new Marriage(person, spouse));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) { return Optional.empty(); }
    }
    private static boolean deceased(Object node) throws ReflectiveOperationException { return (boolean) node.getClass().getMethod("isDeceased").invoke(node); }
    private static boolean married(Object node) throws ReflectiveOperationException {
        Object state = node.getClass().getMethod("getRelationshipState").invoke(node);
        return (boolean) state.getClass().getMethod("isMarried").invoke(state);
    }
    private McaFamilyEvidence() { }
}
