package com.ultimakingdoms.client;

import com.ultimakingdoms.guide.KingdomGuide;
import com.ultimakingdoms.guide.KingdomGuide.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.io.*;
import java.util.*;

/** Searchable chapters and a keyboard-readable article; no commands execute from this book. */
public final class KingdomGuideScreen extends Screen {
    private static String lastRead="start_here";
    private final Screen parent;
    private final List<Entry> entries;
    private final List<String> problems=new ArrayList<>();
    private String query="",category="",selected=lastRead;
    private boolean chapters=true,updatingSearch;
    private int offset;
    private EditBox search;
    private MenuTextPanel article;
    private List<Entry> matches=List.of();
    private int left,panelWidth,sidebar,rows;
    public KingdomGuideScreen(Screen parent){
        super(Component.translatable("item.ultima_kingdoms.book_of_kingdoms"));this.parent=parent;
        var map=new LinkedHashMap<String,Entry>();load("en_us",map);
        String language=Minecraft.getInstance().getLanguageManager().getSelected();if(!language.equals("en_us"))load(language,map);
        entries=map.values().stream().sorted(Comparator.comparingInt(e->KingdomGuide.CATEGORIES.indexOf(e.category()))).toList();
    }
    private void load(String language,Map<String,Entry> map){
        var manager=Minecraft.getInstance().getResourceManager();
        var resources=manager.listResources("guide/"+language,id->id.getNamespace().equals("ultima_kingdoms")&&id.getPath().endsWith(".json"));
        resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(64).forEach(resource->{
            try(var stream=resource.getValue().open();var reader=new java.io.InputStreamReader(new BoundedInputStream(stream),java.nio.charset.StandardCharsets.UTF_8)){
                var batch=KingdomGuide.read(reader);if(map.size()+batch.stream().filter(e->!map.containsKey(e.id())).count()>512)throw new IOException("Guide entry budget exceeded");
                batch.forEach(e->map.put(e.id(),e));
            }catch(Exception failure){problems.add(resource.getKey().toString());com.mojang.logging.LogUtils.getLogger().warn("Could not read guide resource {}",resource.getKey(),failure);}
        });
    }
    private static final class BoundedInputStream extends FilterInputStream {
        private int remaining=1_000_000;
        BoundedInputStream(InputStream in){super(in);}
        @Override public int read()throws IOException{if(remaining--<=0)throw new IOException("Guide resource too large");return super.read();}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(remaining<=0)throw new IOException("Guide resource too large");int n=in.read(b,off,Math.min(len,remaining));if(n>0)remaining-=n;return n;}
    }
    private static Component label(String key,Object... args){return Component.translatable("guide.ultima_kingdoms."+key,args);}
    @Override protected void init(){
        panelWidth=Math.min(900,width-16);left=(width-panelWidth)/2;sidebar=Math.min(210,Math.max(110,panelWidth/3));rows=Math.max(1,(height-125)/21);
        matches=entries.stream().filter(e->(category.isEmpty()||e.category().equals(category))&&e.matches(query)).toList();
        if(search==null){
            search=new EditBox(font,left+10,34,panelWidth-sidebar-24,20,label("search"));
            search.setMaxLength(100);search.setHint(label("search"));
            search.setResponder(value->{if(updatingSearch)return;query=value;category="";chapters=false;offset=0;rebuildWidgets();setFocused(search);});
        }
        search.setX(left+10);search.setY(34);search.setWidth(panelWidth-sidebar-24);
        if(!search.getValue().equals(query)){updatingSearch=true;try{search.setValue(query);}finally{updatingSearch=false;}}
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(label("chapters"),b->{chapters=true;offset=0;rebuildWidgets();}).bounds(left+panelWidth-sidebar-8,34,sidebar-2,20).build());
        int contentsSize=chapters?KingdomGuide.CATEGORIES.size()+1:matches.size();
        offset=Math.min(offset,Math.max(0,((Math.max(1,contentsSize)-1)/rows)*rows));
        if(chapters){
            var choices=new ArrayList<String>();choices.add("");choices.addAll(KingdomGuide.CATEGORIES);
            for(int i=0;i<rows&&i+offset<choices.size();i++){
                String choice=choices.get(i+offset);
                addRenderableWidget(Button.builder(choice.isEmpty()?label("all_topics"):label("category."+choice),b->{category=choice;query="";chapters=false;offset=0;rebuildWidgets();})
                        .bounds(left+10,80+i*21,sidebar-12,20).build());
            }
            listNavigation(choices.size());
        }else{
            for(int i=0;i<rows&&i+offset<matches.size();i++){
                var entry=matches.get(i+offset);String clipped=font.plainSubstrByWidth(entry.title(),sidebar-26);
                if(!clipped.equals(entry.title()))clipped=font.plainSubstrByWidth(entry.title(),sidebar-36)+"...";
                var button=addRenderableWidget(Button.builder(Component.literal(clipped),b->select(entry)).bounds(left+10,80+i*21,sidebar-12,20)
                        .tooltip(Tooltip.create(Component.literal(entry.title()+" — "+entry.summary()))).build());button.active=!entry.id().equals(selected);
            }
            listNavigation(matches.size());
        }
        Entry current=entries.stream().filter(e->e.id().equals(selected)).findFirst().orElse(entries.isEmpty()?null:entries.get(0));
        article=addRenderableWidget(new MenuTextPanel(left+sidebar+5,62,panelWidth-sidebar-15,height-103,current==null?getTitle():Component.literal(current.title())));
        var text=new ArrayList<Component>();
        if(current!=null){text.add(Component.literal(current.summary()));current.body().forEach(p->text.add(Component.literal(p)));
            if(!current.commands().isEmpty()){text.add(label("commands"));current.commands().forEach(c->text.add(Component.literal(c)));text.add(label("command_help"));}}
        else text.add(label("unavailable"));
        if(!problems.isEmpty())text.add(label("resource_error"));article.setContent(text);
        int x=left+sidebar+5,w=panelWidth-sidebar-15,buttonWidth=(w-8)/3;
        var ordered=matches.isEmpty()?entries:matches;int index=current==null?-1:ordered.indexOf(current);
        var prev=addRenderableWidget(Button.builder(label("previous_topic"),b->select(ordered.get(index-1))).bounds(x,height-32,buttonWidth,20).build());prev.active=index>0;
        var next=addRenderableWidget(Button.builder(label("next_topic"),b->select(ordered.get(index+1))).bounds(x+buttonWidth+4,height-32,buttonWidth,20).build());next.active=index>=0&&index+1<ordered.size();
        addRenderableWidget(Button.builder(label("close"),b->onClose()).bounds(x+2*(buttonWidth+4),height-32,buttonWidth,20).build());
    }
    private void listNavigation(int size){
        offset=Math.min(offset,Math.max(0,((Math.max(1,size)-1)/rows)*rows));
        addRenderableWidget(Button.builder(Component.literal("<"),b->{offset=Math.max(0,offset-rows);rebuildWidgets();}).bounds(left+10,height-32,28,20).tooltip(Tooltip.create(label("previous_contents"))).build()).active=offset>0;
        addRenderableWidget(Button.builder(Component.literal(">"),b->{offset+=rows;rebuildWidgets();}).bounds(left+sidebar-30,height-32,28,20).tooltip(Tooltip.create(label("next_contents"))).build()).active=offset+rows<size;
    }
    private void select(Entry entry){selected=entry.id();lastRead=selected;rebuildWidgets();setFocused(article);}
    @Override public void render(GuiGraphics graphics,int mx,int my,float partial){
        renderBackground(graphics);VanillaGui.panel(graphics,left,8,panelWidth,height-16);
        VanillaGui.title(graphics,font,getTitle(),width/2,17,panelWidth-24);
        VanillaGui.title(graphics,font,chapters?label("choose_chapter"):label("results",matches.size()),left+sidebar/2,65,sidebar-16);
        if(!chapters&&matches.isEmpty())graphics.drawWordWrap(font,label("no_results"),left+12,82,sidebar-18,VanillaGui.TEXT);
        super.render(graphics,mx,my,partial);
    }
    @Override public void tick(){search.tick();}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
