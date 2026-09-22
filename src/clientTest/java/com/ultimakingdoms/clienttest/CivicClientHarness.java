package com.ultimakingdoms.clienttest;

import com.ultimakingdoms.civic.CivicViews;
import com.ultimakingdoms.client.GuildScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Opt-in two-client acceptance for the authenticated GuildScreen and civic network. */
public final class CivicClientHarness {
    private final Minecraft mc = Minecraft.getInstance();
    private final Path output = Path.of(System.getProperty("ultima.clientTest.output"));
    private final Path shared = Path.of(System.getProperty("ultima.clientTest.shared"));
    private final String role = System.getProperty("ultima.clientTest.role");
    private final long started = System.currentTimeMillis();
    private int stage;
    private int ticks;
    private int entered;
    private boolean connecting;
    private boolean finished;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        ticks++;
        try {
            if (System.currentTimeMillis() - started > 240_000L) {
                throw new AssertionError("Civic multiplayer timeout for " + role + " at stage " + stage);
            }
            if (!connecting && (mc.screen instanceof TitleScreen
                    || mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)) {
                connecting = true;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(2);
                mc.options.guiScale().set(2);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                String address = System.getProperty("ultima.clientTest.server");
                ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address),
                        new ServerData("Civic acceptance", address, false), false);
            }
            if (ticks % 200 == 0) {
                System.out.println("CIVIC_CLIENT role=" + role + " stage=" + stage
                        + " player=" + (mc.player != null) + " ready=" + ready());
            }
            // CivicNetwork intentionally drops requests less than four server ticks apart. Keep
            // a full client-second between screen reads and mutations so startup tick skew cannot
            // turn a valid click into a silently rate-limited request.
            if (mc.player == null || ticks - entered < 20) return;

            switch (stage) {
                case 0 -> {
                    if (!Files.exists(shared.resolve("READY"))) return;
                    mc.setScreen(new GuildScreen(null));
                    next();
                }
                case 1 -> {
                    if (!ready()) return;
                    check(!view().active(), "fresh player starts unaffiliated");
                    checkReadableQualificationEvidence();
                    shot("01-" + role + "-guild-normal.png");
                    click("join");
                    next();
                }
                case 2 -> {
                    if (!ready() || !view().active()) return;
                    checkReadableOutcome("join");
                    mark(role + "-JOINED");
                    next();
                }
                case 3 -> {
                    if (!both("leader-JOINED", "peer-JOINED")) return;
                    if (role.equals("leader")) {
                        click("leave");
                        next();
                    } else if (Files.exists(shared.resolve("leader-LEFT"))) {
                        click("refresh");
                        next();
                    }
                }
                case 4 -> {
                    if (!ready()) return;
                    if (role.equals("leader")) {
                        check(!view().active(), "leader leave affects leader profile");
                        checkReadableOutcome("leave");
                        mark("leader-LEFT");
                        if (!Files.exists(shared.resolve("peer-ISOLATED"))) return;
                        click("join");
                        next();
                    } else {
                        check(view().active(), "leader leave does not alter peer profile");
                        mark("peer-ISOLATED");
                        if (!Files.exists(shared.resolve("leader-REJOINED"))) return;
                        click("leave");
                        next();
                    }
                }
                case 5 -> {
                    if (!ready()) return;
                    if (role.equals("leader")) {
                        check(view().active(), "leader can rejoin with its own profile");
                        checkReadableOutcome("rejoin");
                        mark("leader-REJOINED");
                        if (!Files.exists(shared.resolve("peer-LEFT"))) return;
                        click("refresh");
                        next();
                    } else {
                        check(!view().active(), "peer leave affects peer profile");
                        checkReadableOutcome("leave");
                        mark("peer-LEFT");
                        if (!Files.exists(shared.resolve("leader-ISOLATED"))) return;
                        click("join");
                        next();
                    }
                }
                case 6 -> {
                    if (!ready()) return;
                    if (role.equals("leader")) {
                        check(view().active(), "peer leave does not alter leader profile");
                        mark("leader-ISOLATED");
                    } else {
                        check(view().active(), "peer can rejoin with its own profile");
                        checkReadableOutcome("rejoin");
                        mark("peer-REJOINED");
                    }
                    compactScreenshot();
                    next();
                }
                case 7 -> {
                    if (!ready() || role.equals("leader") && !Files.exists(shared.resolve("peer-REJOINED"))) return;
                    click("hospitality"); next();
                }
                case 8 -> {
                    if (!ready()) return;
                    checkReadableOutcome("unavailable hospitality");
                    check(view().active(), "hospitality refusal preserves requester membership");
                    click("workshop"); next();
                }
                case 9 -> {
                    if (!ready()) return;
                    checkReadableOutcome("unavailable workshop");
                    check(view().active(), "workshop refusal preserves requester membership");
                    Files.writeString(shared.resolve(role + "-PASS.txt"),
                            "PASS GuildScreen/network join-leave-rejoin profile isolation, R2 hospitality/workshop refusal, readable qualification reasons, normal and compact screenshots\n");
                    finished = true;
                    mc.stop();
                }
                default -> throw new AssertionError("Unexpected civic client stage " + stage);
            }
        } catch (Throwable failure) {
            finished = true;
            failure.printStackTrace();
            try {
                Files.writeString(shared.resolve(role + "-FAIL.txt"), failure.toString());
            } catch (Exception ignored) {
            }
            mc.stop();
        }
    }

    private boolean ready() {
        return mc.screen instanceof GuildScreen && field("view") != null && field("pending") == null;
    }

    private CivicViews.View view() {
        return (CivicViews.View) field("view");
    }

    private void checkReadableQualificationEvidence() {
        List<String> lines = view().lines().stream().map(line -> line.getString().trim())
                .filter(line -> !line.isEmpty()).toList();
        check(lines.stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains("qualification")),
                "qualification explanation is present");
        check(lines.stream().noneMatch(line -> line.contains("organization.") || line.contains("civic.")),
                "qualification reasons are localized, not raw keys: " + lines);
    }

    private void checkReadableOutcome(String operation) {
        String outcome = String.valueOf(field("outcome"));
        check(!outcome.isBlank(), operation + " has a visible outcome");
        check(!outcome.contains("organization.") && !outcome.contains("civic."),
                operation + " outcome is localized: " + outcome);
    }

    private void compactScreenshot() {
        mc.getWindow().setGuiScale(mc.getWindow().getHeight() / 240.0);
        mc.screen.resize(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        check(mc.screen.height >= 239 && mc.screen.height <= 241, "compact GuildScreen height");
        for (Object child : mc.screen.children()) {
            if (child instanceof Button button) {
                check(button.getY() >= 0 && button.getY() + button.getHeight() <= mc.screen.height,
                        "compact button remains inside screen: " + button.getMessage().getString());
            }
        }
        shot("02-" + role + "-guild-compact.png");
    }

    private void click(String name) {
        Button button = (Button) field(name);
        check(button.active, name + " button enabled");
        check(mc.screen.mouseClicked(button.getX() + button.getWidth() / 2.0,
                button.getY() + button.getHeight() / 2.0, 0), "clicked " + name);
    }

    private Object field(String name) {
        try {
            Field field = GuildScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(mc.screen);
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
    }

    private void shot(String file) {
        Screenshot.grab(output.toFile(), file, mc.getMainRenderTarget(),
                message -> System.out.println("CIVIC_CLIENT screenshot=" + file + " " + message.getString()));
    }

    private void mark(String name) throws Exception {
        Files.writeString(shared.resolve(name), "ready\n");
    }

    private boolean both(String first, String second) {
        return Files.exists(shared.resolve(first)) && Files.exists(shared.resolve(second));
    }

    private void next() {
        stage++;
        entered = ticks;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("CIVIC_CLIENT PASS " + message);
    }
}
