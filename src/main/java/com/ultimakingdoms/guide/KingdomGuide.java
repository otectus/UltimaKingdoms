package com.ultimakingdoms.guide;

import com.google.gson.*;
import java.io.Reader;
import java.util.*;

/** Static, resource-pack-overridable knowledge. Never reads player or world state. */
public final class KingdomGuide {
    public static final List<String> CATEGORIES=List.of("basics","settlements","civic","politics","warfare","evolution","operators","reference");
    public record Entry(String id,String category,String title,String summary,List<String> body,List<String> commands) {
        public Entry {
            if(id==null||!id.matches("[a-z0-9_]{1,80}")||!CATEGORIES.contains(category))throw new IllegalArgumentException("Invalid guide identity");
            text(title,100);text(summary,400);
            body=List.copyOf(body);commands=List.copyOf(commands);
            if(body.isEmpty()||body.size()>80||commands.size()>80)throw new IllegalArgumentException("Invalid guide length");
            body.forEach(p->text(p,4000));commands.forEach(c->{text(c,1000);if(!c.startsWith("/"))throw new IllegalArgumentException("Guide command must begin with /");});
        }
        public boolean matches(String query){
            String haystack=title+" "+summary+" "+String.join(" ",body)+" "+String.join(" ",commands);
            String lower=haystack.toLowerCase(Locale.ROOT);
            return Arrays.stream(query.toLowerCase(Locale.ROOT).strip().split("\\s+")).allMatch(lower::contains);
        }
    }
    public static List<Entry> read(Reader reader){
        JsonObject object=JsonParser.parseReader(reader).getAsJsonObject();
        var entries=object.getAsJsonArray("entries");
        if(entries==null||entries.size()>128)throw new IllegalArgumentException("Guide file has too many entries");
        var result=new ArrayList<Entry>();var ids=new HashSet<String>();
        for(var value:entries){var e=value.getAsJsonObject();
            var entry=new Entry(required(e,"id"),required(e,"category"),required(e,"title"),required(e,"summary"),strings(e,"body"),strings(e,"commands"));
            if(!ids.add(entry.id()))throw new IllegalArgumentException("Duplicate guide entry "+entry.id());result.add(entry);
        }
        return List.copyOf(result);
    }
    private static String required(JsonObject object,String key){var value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing text: "+key);return value.getAsString();}
    private static List<String> strings(JsonObject object,String key){var values=object.getAsJsonArray(key);if(values==null)throw new IllegalArgumentException("Missing "+key);return java.util.stream.StreamSupport.stream(values.spliterator(),false).map(JsonElement::getAsString).toList();}
    private static void text(String value,int limit){if(value==null||value.isBlank()||value.length()>limit||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid guide text");}
    private KingdomGuide(){}
}
