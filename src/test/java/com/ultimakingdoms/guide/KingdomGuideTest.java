package com.ultimakingdoms.guide;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KingdomGuideTest {
    private List<KingdomGuide.Entry> shipped() throws IOException {
        var result=new ArrayList<KingdomGuide.Entry>();
        try(var files=Files.list(Path.of("src/main/resources/assets/ultima_kingdoms/guide/en_us"))){
            for(var path:files.filter(p->p.toString().endsWith(".json")).toList())try(var reader=Files.newBufferedReader(path)){result.addAll(KingdomGuide.read(reader));}
        }
        return result;
    }
    @Test void shippedBookCoversEveryDomainWithoutDuplicateIdentities()throws Exception{
        var entries=shipped();assertTrue(entries.size()>=60,"The full guide must retain its subject coverage");
        assertEquals(entries.size(),entries.stream().map(KingdomGuide.Entry::id).distinct().count());
        assertEquals(new HashSet<>(KingdomGuide.CATEGORIES),new HashSet<>(entries.stream().map(KingdomGuide.Entry::category).toList()));
        assertTrue(entries.stream().anyMatch(e->e.id().equals("start_here")));
        for(String topic:List.of("reconciliation","family","merger","regency","receipt","independent","workshop","migration"))
            assertTrue(entries.stream().anyMatch(e->e.matches(topic)),"Missing topic: "+topic);
    }
    @Test void searchFindsCommandsAndRequiresEveryTerm()throws Exception{
        var entry=shipped().stream().filter(e->e.id().equals("command_basics")).findFirst().orElseThrow();
        assertTrue(entry.matches("REVISION TERMS"));assertFalse(entry.matches("revision unfindableterm"));
        assertTrue(shipped().stream().anyMatch(e->e.matches("/ultima-transfer reconcile")));
    }
    @Test void invalidResourceCannotInjectMalformedTopicOrOversizedText(){
        assertThrows(IllegalArgumentException.class,()->new KingdomGuide.Entry("../bad","basics","Title","Summary",List.of("Body"),List.of()));
        assertThrows(IllegalArgumentException.class,()->new KingdomGuide.Entry("safe","missing","Title","Summary",List.of("Body"),List.of()));
        assertThrows(IllegalArgumentException.class,()->new KingdomGuide.Entry("safe","basics","Title","Summary",List.of("x".repeat(4001)),List.of()));
        assertThrows(IllegalArgumentException.class,()->KingdomGuide.read(new StringReader("{\"entries\":[{\"id\":\"bad\"}]}")));
    }
}
