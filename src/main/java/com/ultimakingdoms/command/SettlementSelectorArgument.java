package com.ultimakingdoms.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.List;

/** Accepts unquoted UUIDs/resource slugs and quoted human-readable settlement names. */
public final class SettlementSelectorArgument implements ArgumentType<String> {
    private static final Collection<String> EXAMPLES = List.of(
            "ultima_kingdoms:bellmeadow",
            "a2d45b49-946f-43fc-a9e0-17985b7026ce",
            "\"New Bellmeadow\""
    );
    private static final SimpleCommandExceptionType MISSING = new SimpleCommandExceptionType(
            Component.translatable("command.ultima_kingdoms.error.village_selector_missing"));

    private SettlementSelectorArgument() {
    }

    public static SettlementSelectorArgument settlement() {
        return new SettlementSelectorArgument();
    }

    public static String getSettlement(CommandContext<?> context, String name) {
        return context.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (!reader.canRead()) throw MISSING.createWithContext(reader);
        if (StringReader.isQuotedStringStart(reader.peek())) return reader.readQuotedString();
        int start = reader.getCursor();
        while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
        if (reader.getCursor() == start) throw MISSING.createWithContext(reader);
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
