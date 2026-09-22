package com.ultimakingdoms.interaction;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import java.util.*;
import static com.ultimakingdoms.interaction.ActionRegistry.*;

/** Human-readable command selectors share the GUI's actor-filtered record catalogues. */
public final class NamedTargets {
    public static RequiredArgumentBuilder<CommandSourceStack,String> argument(String key,String kind){return Commands.argument(key,com.ultimakingdoms.command.SettlementSelectorArgument.settlement()).suggests((c,b)->{
        try{return SharedSuggestionProvider.suggest(named(choices(kind,context(c,key))).stream().map(Choice::label).map(StringArgumentType::escapeIfRequired),b);}catch(Exception ignored){return b.buildFuture();}});}
    public static UUID uuid(CommandContext<CommandSourceStack> c,String key,String kind) throws com.mojang.brigadier.exceptions.CommandSyntaxException {return UUID.fromString(value(c,key,kind));}
    public static String value(CommandContext<CommandSourceStack> c,String key,String kind) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        try{return resolveValue(c,key,kind);}catch(IllegalArgumentException|IllegalStateException failure){throw new com.mojang.brigadier.exceptions.DynamicCommandExceptionType(v->net.minecraft.network.chat.Component.literal(String.valueOf(v))).create(failure.getMessage());}
    }
    private static String resolveValue(CommandContext<CommandSourceStack> c,String key,String kind){
        String raw=StringArgumentType.getString(c,key);if(kind.equals("request")){try{UUID.fromString(raw);return raw;}catch(IllegalArgumentException ignored){return UUID.nameUUIDFromBytes((c.getSource().getPlayer().getUUID()+":"+raw).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}}try{UUID.fromString(raw);return raw;}catch(IllegalArgumentException ignored){}
        return resolve(choices(kind,context(c,key)),raw).value();
    }
    public static Choice resolve(List<Choice> choices,String raw){
        var named=named(choices);var matches=named.stream().filter(v->v.value().equals(raw)||v.label().equalsIgnoreCase(raw)||slug(v.label()).equalsIgnoreCase(raw)).toList();
        if(matches.size()==1)return matches.get(0);
        var original=choices.stream().filter(v->v.label().equalsIgnoreCase(raw)).toList();if(original.size()==1)return original.get(0);
        throw new IllegalArgumentException(original.size()>1?"Several records have that name. Use a complete suggested name.":"No available record has that name. Use the suggested names or the Kingdoms menu.");
    }
    public static List<Choice> named(List<Choice> values){
        var counts=new HashMap<String,Integer>();values.forEach(v->counts.merge(v.label().toLowerCase(Locale.ROOT),1,Integer::sum));var out=new ArrayList<Choice>();
        for(var v:values){String key=v.label().toLowerCase(Locale.ROOT);out.add(new Choice(v.value(),counts.get(key)>1?v.label()+" ("+stableSuffix(v.value())+")":v.label(),v.detail(),v.metadata()));}return out;
    }
    private static String stableSuffix(String value){return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().substring(0,8);}
    private static String slug(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");}
    private static Context context(CommandContext<CommandSourceStack> command,String requested){
        InteractionTasks.init();var player=command.getSource().getPlayer();if(player==null)throw new IllegalArgumentException("A player is required for private named selectors.");
        var values=new LinkedHashMap<String,String>();var selected=new LinkedHashMap<String,Choice>();
        for(String key:List.of("kingdom","organization","recipient","source","target","merge","scenario","pact","transfer")){
            if(key.equals(requested))continue;String raw;
            try{raw=StringArgumentType.getString(command,key);}catch(Exception ignored){try{raw=net.minecraft.commands.arguments.ResourceLocationArgument.getId(command,key).toString();}catch(Exception missing){continue;}}
            String kind=switch(key){case "recipient"->"player";case "source","target"->"organization";default->key;};
            try{var choice=resolve(choices(kind,new Context(player,values,selected)),raw);values.put(key,choice.value());selected.put(key,choice);}catch(Exception ignored){values.put(key,raw);}
        }
        return new Context(player,values,selected);
    }
    private NamedTargets(){}
}
