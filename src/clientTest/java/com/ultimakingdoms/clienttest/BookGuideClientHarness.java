package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.client.KingdomGuideScreen;
import com.ultimakingdoms.client.MenuTextPanel;
import com.ultimakingdoms.guide.KingdomGuide;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Isolated packaged-client acceptance for the real Book of Kingdoms item and reader. */
public final class BookGuideClientHarness {
    private static final ResourceLocation BOOK_ID = new ResourceLocation("ultima_kingdoms", "book_of_kingdoms");
    private static final List<String> SCREENSHOTS = List.of(
            "01-book-open.png", "02-warfare-chapter.png", "03-search-result.png",
            "04-book-compact.png", "05-book-compact-scrolled.png", "06-book-reopened.png");

    private final Minecraft mc = Minecraft.getInstance();
    private final Path output = Path.of(System.getProperty("ultima.clientTest.output"));
    private final long started = System.currentTimeMillis();
    private int stage, ticks, entered;
    private boolean creating;
    private volatile boolean finished;
    private volatile boolean seeded, recipeChecked;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        ticks++;
        try {
            if (System.currentTimeMillis() - started > 240_000L)
                throw new AssertionError("Book guide client timeout at stage " + stage + " screen=" + mc.screen);
            if (!creating && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) createWorld();
            if (!creating || ticks - entered < 30) return;

            switch (stage) {
                case 0 -> {
                    if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
                    mc.setScreen(null);
                    mc.getSingleplayerServer().execute(this::seedBookAndCheckRecipe);
                    next();
                }
                case 1 -> {
                    Item book = book();
                    if (!seeded || mc.player == null || !mc.player.getMainHandItem().is(book) || mc.gameMode == null) return;
                    check(recipeChecked, "Integrated server recipe registry contains the Book of Kingdoms output");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    next();
                }
                case 2 -> {
                    KingdomGuideScreen screen = guide();
                    List<KingdomGuide.Entry> entries = entries(screen);
                    check(entries.size() >= 65, "Packaged guide loads all 65 substantive topics: " + entries.size());
                    check(entries.stream().anyMatch(e -> e.id().equals("start_here")), "Getting-started topic loaded");
                    check(entries.stream().anyMatch(e -> e.id().equals("warfare_ownership_and_setup")), "Warfare topics loaded");
                    check(entries.stream().anyMatch(e -> e.id().equals("evolution_world_opt_in")), "Evolution topics loaded");
                    check(((List<?>) field(KingdomGuideScreen.class, screen, "problems")).isEmpty(), "All packaged guide resources parsed");
                    check("start_here".equals(field(KingdomGuideScreen.class, screen, "selected")), "Book opens at Start here");
                    shot("01-book-open.png");
                    click("Chapters");
                    next();
                }
                case 3 -> {
                    KingdomGuideScreen screen = guide();
                    check((Boolean) field(KingdomGuideScreen.class, screen, "chapters"), "Chapter chooser opened by mouse");
                    click("Conflict & exploration");
                    next();
                }
                case 4 -> {
                    KingdomGuideScreen screen = guide();
                    check("warfare".equals(field(KingdomGuideScreen.class, screen, "category")), "Warfare chapter filter selected");
                    check(matches(screen).size() == 13 && matches(screen).stream().allMatch(e -> e.category().equals("warfare")),
                            "Warfare chapter lists only its 13 packaged topics");
                    click(firstTopicButton(screen));
                    next();
                }
                case 5 -> {
                    KingdomGuideScreen screen = guide();
                    check("warfare_ownership_and_setup".equals(field(KingdomGuideScreen.class, screen, "selected")),
                            "Topic row opens the complete warfare article");
                    shot("02-warfare-chapter.png");
                    click("Next");
                    next();
                }
                case 6 -> {
                    KingdomGuideScreen screen = guide();
                    check("claim_mapping_and_binding".equals(field(KingdomGuideScreen.class, screen, "selected")),
                            "Next advances within the filtered chapter");
                    EditBox originalSearch = focusSearch(screen);
                    typeFocused(screen, "guild");
                    check(field(KingdomGuideScreen.class, screen, "search") == originalSearch,
                            "Search widget survives result rebuilds without losing cursor state");
                    check("guild".equals(field(KingdomGuideScreen.class, screen, "query")), "Initial keyboard search entered");
                    check(((Screen) screen).keyPressed(GLFW.GLFW_KEY_HOME, 0, 0), "Home moves the search cursor to the beginning");
                    typeFocused(screen, "my ");
                    check("my guild".equals(field(KingdomGuideScreen.class, screen, "query")),
                            "Mid-text insertion is preserved across live result rebuilds");
                    EditBox search = (EditBox) field(KingdomGuideScreen.class, screen, "search");
                    search.setCursorPosition(0);
                    search.setHighlightPos(search.getValue().length());
                    typeFocused(screen, "protection pacts duties");
                    check("protection pacts duties".equals(field(KingdomGuideScreen.class, screen, "query")),
                            "Keyboard input replaces the selected search text");
                    check(matches(screen).size() == 1 && matches(screen).get(0).id().equals("protection_pacts_and_duties"),
                            "Search finds the intended advanced workflow");
                    next();
                }
                case 7 -> {
                    KingdomGuideScreen screen = guide();
                    click(firstTopicButton(screen));
                    check("protection_pacts_and_duties".equals(field(KingdomGuideScreen.class, screen, "selected")),
                            "Search result opens its article");
                    shot("03-search-result.png");
                    mc.getWindow().setGuiScale(mc.getWindow().getHeight() / 240.0D);
                    ((Screen) screen).resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                    next();
                }
                case 8 -> {
                    KingdomGuideScreen screen = guide();
                    Screen vanilla = screen;
                    check(vanilla.height >= 240 && vanilla.height <= 241, "Compact reader is 240 GUI pixels high");
                    for (var child : vanilla.children()) if (child instanceof AbstractWidget widget && widget.visible)
                        check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getX() + widget.getWidth() <= vanilla.width
                                        && widget.getY() + widget.getHeight() <= vanilla.height,
                                "Compact widget stays within viewport: " + widget.getMessage().getString());
                    MenuTextPanel article = article(screen);
                    AbstractWidget articleWidget = article;
                    shot("04-book-compact.png");
                    vanilla.setFocused(article);
                    check(vanilla.keyPressed(GLFW.GLFW_KEY_END, 0, 0), "Article accepts End keyboard scrolling");
                    check(scroll(article) > 0, "End reaches later article content");
                    check(vanilla.keyPressed(GLFW.GLFW_KEY_HOME, 0, 0) && scroll(article) == 0, "Home returns to article start");
                    check(vanilla.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0) && scroll(article) > 0, "Page Down advances article");
                    int pageScroll = scroll(article);
                    check(vanilla.mouseScrolled(articleWidget.getX() + 20.0D, articleWidget.getY() + 40.0D, -1.0D),
                            "Mouse wheel is handled inside the article");
                    check(scroll(article) > pageScroll, "Mouse wheel advances beyond keyboard page");
                    next();
                }
                case 9 -> {
                    KingdomGuideScreen screen = guide();
                    check(scroll(article(screen)) > 0, "Scrolled article position remains visible");
                    shot("05-book-compact-scrolled.png");
                    check(((Screen) screen).keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0), "Escape is handled by the reader");
                    check(mc.screen == null, "Escape closes the item-opened reader");
                    mc.options.guiScale().set(2);
                    mc.resizeDisplay();
                    next();
                }
                case 10 -> {
                    if (mc.screen != null || mc.player == null || mc.gameMode == null) return;
                    check(mc.player.getMainHandItem().is(book()), "Book remains held after closing the reader");
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    next();
                }
                case 11 -> {
                    KingdomGuideScreen screen = guide();
                    check("protection_pacts_and_duties".equals(field(KingdomGuideScreen.class, screen, "selected")),
                            "Reopening through item use remembers the last-read topic");
                    check("".equals(field(KingdomGuideScreen.class, screen, "query")), "Reopening starts with a clear search field");
                    shot("06-book-reopened.png");
                    click("Close");
                    check(mc.screen == null, "Close button returns to play");
                    next();
                }
                case 12 -> finish();
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void createWorld() {
        creating = true;
        mc.options.pauseOnLostFocus = false;
        mc.options.renderDistance().set(3);
        mc.options.guiScale().set(2);
        mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        mc.resizeDisplay();
        mc.createWorldOpenFlows().createFreshLevel("book-guide-acceptance-" + started,
                new LevelSettings("Book guide acceptance", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(721946L, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(WorldPresets.FLAT).createWorldDimensions());
        log("Created isolated flat integrated world");
    }

    private void seedBookAndCheckRecipe() {
        try {
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayers().get(0);
            Item book = book();
            var recipe = server.getRecipeManager().byKey(BOOK_ID)
                    .orElseThrow(() -> new AssertionError("Missing recipe " + BOOK_ID));
            check(recipe.getType() == RecipeType.CRAFTING, "Book recipe is registered as crafting");
            ItemStack result = recipe.getResultItem(server.registryAccess());
            check(result.is(book) && result.getCount() == 1, "Book recipe produces one registered guide item");
            check(recipe.getIngredients().stream().anyMatch(i -> i.test(new ItemStack(Items.BOOK)))
                            && recipe.getIngredients().stream().anyMatch(i -> i.test(new ItemStack(Items.PAPER))),
                    "Book recipe requires a book and paper");
            recipeChecked = true;
            player.getInventory().setItem(0, new ItemStack(book));
            player.getInventory().selected = 0;
            player.containerMenu.broadcastChanges();
            seeded = true;
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private Item book() {
        Item value = ForgeRegistries.ITEMS.getValue(BOOK_ID);
        if (value == null || value == Items.AIR) throw new AssertionError("Book item is not registered");
        return value;
    }

    private KingdomGuideScreen guide() {
        if (!(mc.screen instanceof KingdomGuideScreen screen))
            throw new AssertionError("Expected real KingdomGuideScreen, found " + mc.screen);
        return screen;
    }

    @SuppressWarnings("unchecked")
    private List<KingdomGuide.Entry> entries(KingdomGuideScreen screen) {
        return (List<KingdomGuide.Entry>) field(KingdomGuideScreen.class, screen, "entries");
    }

    @SuppressWarnings("unchecked")
    private List<KingdomGuide.Entry> matches(KingdomGuideScreen screen) {
        return (List<KingdomGuide.Entry>) field(KingdomGuideScreen.class, screen, "matches");
    }

    private MenuTextPanel article(KingdomGuideScreen screen) {
        return (MenuTextPanel) field(KingdomGuideScreen.class, screen, "article");
    }

    private Button firstTopicButton(KingdomGuideScreen screen) {
        int sidebar = (Integer) field(KingdomGuideScreen.class, screen, "sidebar");
        int left = (Integer) field(KingdomGuideScreen.class, screen, "left");
        return ((Screen) screen).children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getX() >= left && button.getX() < left + sidebar && button.getY() >= 80)
                .min(java.util.Comparator.comparingInt(Button::getY))
                .orElseThrow(() -> new AssertionError("No visible topic row: " + buttons()));
    }

    private EditBox focusSearch(KingdomGuideScreen screen) {
        EditBox search = (EditBox) field(KingdomGuideScreen.class, screen, "search");
        check(((Screen) screen).mouseClicked(search.getX() + 8.0D, search.getY() + search.getHeight() / 2.0D, 0),
                "Search field accepts mouse focus");
        return search;
    }

    private void typeFocused(KingdomGuideScreen screen, String text) {
        for (char c : text.toCharArray()) {
            EditBox current = (EditBox) field(KingdomGuideScreen.class, screen, "search");
            ((Screen) screen).setFocused(current);
            if (!((Screen) screen).charTyped(c, 0)) throw new AssertionError("Search rejected character " + c);
        }
    }

    private void click(String label) {
        Button button = mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.getMessage().getString().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Missing button " + label + ": " + buttons()));
        click(button);
    }

    private void click(Button button) {
        check(button.active, "Button enabled: " + button.getMessage().getString());
        check(mc.screen.mouseClicked(button.getX() + button.getWidth() / 2.0D,
                button.getY() + button.getHeight() / 2.0D, 0), "Mouse click handled: " + button.getMessage().getString());
    }

    private List<String> buttons() {
        if (mc.screen == null) return List.of();
        return mc.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .map(button -> button.getMessage().getString()).toList();
    }

    private int scroll(MenuTextPanel panel) {
        return (Integer) field(MenuTextPanel.class, panel, "scroll");
    }

    private Object field(Class<?> type, Object instance, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    private void shot(String name) {
        Screenshot.grab(output.toFile(), name, mc.getMainRenderTarget(),
                message -> log("Screenshot " + name + ": " + message.getString()));
    }

    private void finish() throws Exception {
        for (String name : SCREENSHOTS) {
            Path image = output.resolve("screenshots").resolve(name);
            check(Files.isRegularFile(image) && Files.size(image) > 0, "Screenshot written: " + name);
        }
        Files.createDirectories(output);
        Files.writeString(output.resolve("BOOK_GUIDE_PASS.txt"),
                "PASS isolated packaged client: registered recipe/output, actual item use, packaged resource parsing, chapter/result/next mouse navigation, multiword keyboard search, compact layout, keyboard and wheel article scrolling, screenshots, close and item-use reopen with last-read topic.\n");
        finished = true;
        log("PASS all Book of Kingdoms client checks");
        mc.stop();
    }

    private void next() {
        stage++;
        entered = ticks;
        log("stage=" + stage);
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        log("PASS " + message);
    }

    private void log(String message) {
        System.out.println("BOOK_GUIDE_CLIENT " + message);
    }

    private void fail(Throwable failure) {
        if (finished) return;
        finished = true;
        failure.printStackTrace();
        try {
            Files.createDirectories(output);
            Files.writeString(output.resolve("BOOK_GUIDE_FAIL.txt"), "stage=" + stage + "\n" + failure + "\n");
        } catch (Exception writingFailure) {
            writingFailure.printStackTrace();
        }
        mc.execute(mc::stop);
    }
}
