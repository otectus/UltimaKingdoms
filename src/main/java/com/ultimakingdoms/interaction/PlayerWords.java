package com.ultimakingdoms.interaction;

import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Common-side English fallback for labels returned by servers, without loading client language classes.
 * Result text is never rewritten by pattern matching: services are expected to name records, not identifiers.
 */
public final class PlayerWords {
    private static final Map<String,String> WORDS=new HashMap<>();
    private static final int LIMIT=16000;
    static {try(var stream=PlayerWords.class.getResourceAsStream("/assets/ultima_kingdoms/lang/en_us.json")){if(stream!=null)JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject().entrySet().forEach(e->WORDS.put(e.getKey(),e.getValue().getAsString()));}catch(Exception ignored){}}
    public static String text(String value){return WORDS.getOrDefault(value,value);}
    /** True when the value is a bundled translation key rather than free text. */
    public static boolean isKey(String value){return value!=null&&WORDS.containsKey(value);}
    /** Plain text for a handler result: joined paragraphs, keys resolved, selected record values shown by their labels, bounded. */
    public static String safe(Object value,ActionRegistry.Context context){
        String result=value instanceof Collection<?> list?String.join("\n\n",list.stream().map(Object::toString).toList()):String.valueOf(value);
        result=text(result);
        result=labels(result,context);
        return result.length()>LIMIT?result.substring(0,LIMIT)+"\nMore details are available by selecting a narrower task.":result;
    }
    /** Component form of a handler result: each paragraph that is a bundled key becomes translatable, everything else stays literal. */
    public static Component component(Object value,ActionRegistry.Context context){
        List<String> paragraphs=value instanceof Collection<?> list?list.stream().map(Object::toString).toList():List.of(String.valueOf(value));
        MutableComponent out=Component.empty();int length=0;boolean first=true;
        for(String paragraph:paragraphs){
            if(!first)out.append("\n\n");first=false;
            if(length+paragraph.length()>LIMIT){out.append(ActionRegistry.tr("interaction.ultima_kingdoms.truncated","More details are available by selecting a narrower task."));break;}
            length+=paragraph.length();
            out.append(isKey(paragraph)?ActionRegistry.tr(paragraph,WORDS.get(paragraph)):Component.literal(labels(paragraph,context)));
        }
        return out;
    }
    private static String labels(String result,ActionRegistry.Context context){
        for(var entry:context.values().entrySet()){var choice=context.choice(entry.getKey());if(choice!=null&&!choice.value().isBlank())result=result.replace(choice.value(),choice.label());}
        return result;
    }
    private PlayerWords(){}
}
