package com.ultimakingdoms.interaction;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Common-side English fallback for labels returned by servers, without loading client language classes. */
public final class PlayerWords {
    private static final Map<String,String> WORDS=new HashMap<>();
    static {try(var stream=PlayerWords.class.getResourceAsStream("/assets/ultima_kingdoms/lang/en_us.json")){if(stream!=null)JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject().entrySet().forEach(e->WORDS.put(e.getKey(),e.getValue().getAsString()));}catch(Exception ignored){}}
    public static String text(String value){return WORDS.getOrDefault(value,value);}
    public static String safe(Object value,ActionRegistry.Context context){
        String result=value instanceof Collection<?> list?String.join("\n\n",list.stream().map(Object::toString).toList()):String.valueOf(value);
        result=text(result);
        result=result.replaceAll("/ultima[^\\n]*","the Kingdoms task menu");
        for(var entry:context.values().entrySet()){var choice=context.choice(entry.getKey());if(choice!=null&&!choice.value().isBlank())result=result.replace(choice.value(),choice.label());}
        result=result.replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}","saved record");
        return result.length()>16000?result.substring(0,16000)+"\nMore details are available by selecting a narrower task.":result;
    }
    private PlayerWords(){}
}
