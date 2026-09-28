package com.ultimakingdoms.interaction;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.function.Function;

/** Shared, server-owned task definitions. GUI tasks call services, never command dispatch. */
public final class ActionRegistry {
    public record Choice(String value,String label,String detail,Map<String,String> metadata) {
        public Choice { metadata=Map.copyOf(metadata); }
        public Choice(String value,String label){this(value,label,"",Map.of());}
        public Choice(String value,String label,String detail){this(value,label,detail,Map.of());}
    }
    public enum Kind { CHOICE, MULTI, TEXT, NUMBER }
    /** {@code targetKind} names the shared selector (for empty-state hints); it is empty for inline option lists. */
    public record Field(String key,String label,String help,Kind kind,String initial,boolean optional,Function<Context,List<Choice>> options,String targetKind) {
        public Field(String key,String label,String help,Kind kind,String initial,boolean optional,Function<Context,List<Choice>> options){this(key,label,help,kind,initial,optional,options,"");}
    }
    public record Pending(java.util.concurrent.CompletionStage<?> completion,String message) {}
    @FunctionalInterface public interface Handler { Object run(Context context) throws Exception; }
    public record Task(String id,String category,String title,String help,boolean operator,boolean consequential,List<Field> fields,Function<Context,String> version,Handler handler) {
        public Task { fields=List.copyOf(fields); }
        /** Translation key for this task's title; packs translate it, the English title is the fallback. */
        public String titleKey(){return "task.ultima_kingdoms."+id+".title";}
        public String helpKey(){return "task.ultima_kingdoms."+id+".help";}
        public String categoryKey(){return "task.ultima_kingdoms.category."+slug(category);}
    }
    public static final class Context {
        public final ServerPlayer player;
        private final Map<String,String> values;
        private final Map<String,Choice> selected;
        public Context(ServerPlayer player,Map<String,String> values,Map<String,Choice> selected){this.player=player;this.values=Map.copyOf(values);this.selected=Map.copyOf(selected);}
        public net.minecraft.server.MinecraftServer server(){return player.getServer();}
        public String text(String key){return values.getOrDefault(key,"");}
        public UUID uuid(String key){return UUID.fromString(text(key));}
        public net.minecraft.resources.ResourceLocation id(String key){return new net.minecraft.resources.ResourceLocation(text(key));}
        public long number(String key){return Long.parseLong(text(key));}
        public int integer(String key){return Math.toIntExact(number(key));}
        public boolean bool(String key){return Boolean.parseBoolean(text(key));}
        public Choice choice(String key){return selected.get(key);}
        public String meta(String key,String name){var choice=selected.get(key);if(choice==null||!choice.metadata().containsKey(name))throw new IllegalArgumentException("Select the record again.");return choice.metadata().get(name);}
        public long revision(String key){return Long.parseLong(meta(key,"revision"));}
        public List<Choice> choices(String kind){return ActionRegistry.choices(kind,this);}
        public Map<String,String> values(){return values;}
    }
    private static final Map<String,Task> TASKS=new LinkedHashMap<>();
    private static final Map<String,Function<Context,List<Choice>>> TARGETS=new LinkedHashMap<>();
    private static final Map<String,Function<Context,String>> TARGET_HINTS=new LinkedHashMap<>();
    public static void add(Task task){if(TASKS.putIfAbsent(task.id(),task)!=null)throw new IllegalArgumentException("Duplicate task "+task.id());}
    public static void targets(String kind,Function<Context,List<Choice>> provider){if(TARGETS.putIfAbsent(kind,provider)!=null)throw new IllegalArgumentException("Duplicate target kind "+kind);}
    /** Explains an empty selector of this kind in plain words (what to do so that choices appear). */
    public static void targetHint(String kind,String hint){TARGET_HINTS.put(kind,c->hint);}
    public static void targetHint(String kind,Function<Context,String> hint){TARGET_HINTS.put(kind,hint);}
    public static Optional<String> targetHint(String kind,Context context){var hint=TARGET_HINTS.get(kind);return hint==null?Optional.empty():Optional.ofNullable(hint.apply(context));}
    public static List<Choice> choices(String kind,Context context){var provider=TARGETS.get(kind);return provider==null?List.of():List.copyOf(provider.apply(context));}
    public static Set<String> targetKinds(){return Set.copyOf(TARGETS.keySet());}
    public static List<Task> tasks(ServerPlayer player){return TASKS.values().stream().filter(t->!t.operator()||player.hasPermissions(2)).toList();}
    public static Task task(String id,ServerPlayer player){var task=TASKS.get(id);if(task==null||task.operator()&&!player.hasPermissions(2))throw new IllegalArgumentException("This task is unavailable to you.");return task;}
    public static Optional<Task> find(String id){return Optional.ofNullable(TASKS.get(id));}
    public static Field pick(String key,String label,String kind){return new Field(key,label,"interaction.ultima_kingdoms.help.pick",Kind.CHOICE,"",false,c->c.choices(kind),kind);}
    public static Field pick(String key,String label,Function<Context,List<Choice>> options){return new Field(key,label,"interaction.ultima_kingdoms.help.pick",Kind.CHOICE,"",false,options,"");}
    public static Field multi(String key,String label,Function<Context,List<Choice>> options){return new Field(key,label,"interaction.ultima_kingdoms.help.multi",Kind.MULTI,"",false,options,"");}
    public static Field optionalMulti(String key,String label,Function<Context,List<Choice>> options){return new Field(key,label,"interaction.ultima_kingdoms.help.optional_multi",Kind.MULTI,"",true,options,"");}
    public static Field text(String key,String label){return new Field(key,label,"",Kind.TEXT,"",false,c->List.of(),"");}
    public static Field text(String key,String label,String initial,boolean optional){return new Field(key,label,"",Kind.TEXT,initial,optional,c->List.of(),"");}
    public static Field number(String key,String label,long initial){return new Field(key,label,"interaction.ultima_kingdoms.help.number",Kind.NUMBER,Long.toString(initial),false,c->List.of(),"");}
    public static Field toggle(String key,String label){return pick(key,label,c->List.of(new Choice("true","Yes"),new Choice("false","No")));}
    public static List<Choice> enums(Enum<?>[] values){return Arrays.stream(values).map(v->new Choice(v.name().toLowerCase(Locale.ROOT),words(v.name()))).toList();}
    public static String words(String value){String s=value.replace('_',' ').toLowerCase(Locale.ROOT);return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);}
    /** Lower-case key fragment for a human category or label. */
    public static String slug(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");}
    /** Translatable text with the English source as fallback, so untranslated packs still read correctly. */
    public static Component tr(String key,String fallback,Object... args){return Component.translatableWithFallback(key,fallback,args);}
    private ActionRegistry(){}
}
