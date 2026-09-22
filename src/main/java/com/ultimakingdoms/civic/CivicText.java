package com.ultimakingdoms.civic;

import net.minecraft.network.chat.Component;

/** Human-readable policy outcomes without treating provider text as a command or rich chat. */
public final class CivicText {
    private CivicText() { }
    public static Component reason(String value) {
        if (!value.startsWith("organization.") && !value.startsWith("civic.")) return Component.literal(value);
        int separator=value.indexOf(':');
        if(separator<0) return Component.translatable(value);
        String argument=value.substring(separator+1);
        var id=net.minecraft.resources.ResourceLocation.tryParse(argument);
        if(argument.contains(":")&&id!=null)argument=id.getPath().replace('_',' ').replace('/',' ');
        return Component.translatable(value.substring(0,separator),argument);
    }
}
