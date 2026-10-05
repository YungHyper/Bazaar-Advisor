package com.example.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.example.client.FlipEngine.AuctionFlip;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.impl.client.screen.ScreenExtensions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ExampleModClient implements ClientModInitializer {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final double TAX_RATE = 0.0125;
        private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("bazaar_advisor", "main"));
    private static KeyMapping openKey;
    private static KeyMapping settingsKey;
    private static KeyMapping flipKey;
    private static boolean alertsEnabled = true;
    private static AuctionFlip bestFlip;
    private static int tuneTick = -1;
    private static final float[] TUNE_PITCHES = {1.0f, 1.26f, 1.498f, 2.0f};
    private static final java.util.ArrayDeque<AuctionFlip> recentFlips = new java.util.ArrayDeque<>();
    private static boolean advisorVisible;
    private static boolean editMode;
    private static int booksMode;
    private static boolean hideSuspicious = true;
    private static int sortMode;
    private static double scale = 1.0;
    private static int panelX = 20;
    private static int panelY = 30;
    private static int visibleTradeRows = 3;
    private static List<Trade> trades = List.of();
    private static String status = "Loading Bazaar data...";
    private static Button plusButton;
    private static Button minusButton;
    private static Button showMoreButton;
    private static Button showLessButton;
    private static boolean auctionFlipsEnabled = true;
    private static int auctionFlipCount = 3;
    private static int auctionScanTicks;
    private static boolean auctionScanFailed;
    private static boolean auctionScanInProgress;
    private static boolean auctionScanStarted;
    private static int auctionScanIntervalTicks = 40;
    private static long minimumAuctionProfit = 250_000L;
    private static int lastPageFlipCount;
    private static final Set<String> notifiedAuctionIds = new HashSet<>();
    private static final Map<String, AuctionFlip> pendingFlipRatings = new HashMap<>();

    @Override
    public void onInitializeClient() {
        QuickBuy.flipLookup = ExampleModClient::findRecentFlip;
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bazaar_advisor.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B,
            KEY_CATEGORY));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.bazaar_advisor.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_INSERT,
            KEY_CATEGORY));
        flipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.bazaar_advisor.open_flip", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V,
            KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tickTune(client);
            while (flipKey.consumeClick()) openBestFlip(client);
            while (settingsKey.consumeClick()) client.setScreen(new BazaarSettingsScreen());
            while (openKey.consumeClick() && client.screen != null && isBazaarScreen(client.screen)) {
                advisorVisible = !advisorVisible;
            }
            if (auctionFlipsEnabled && client.player != null && (!auctionScanStarted || ++auctionScanTicks >= auctionScanIntervalTicks)) {
                auctionScanStarted = true;
                auctionScanTicks = 0;
                scanAuctions(false);
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("bazad")
                .executes(context -> {
                    context.getSource().getClient().setScreen(new BazaarSettingsScreen());
                    return 1;
                })
                .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("on").executes(context -> {
                            auctionFlipsEnabled = true;
                            context.getSource().sendFeedback(Component.literal("Bazaar Advisor auction flips: ON").withStyle(ChatFormatting.GREEN));
                            scanAuctions();
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("off").executes(context -> {
                            auctionFlipsEnabled = false;
                            context.getSource().sendFeedback(Component.literal("Bazaar Advisor auction flips: OFF").withStyle(ChatFormatting.YELLOW));
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status").executes(context -> {
                            context.getSource().sendFeedback(Component.literal("Flips " + (auctionFlipsEnabled ? "ON" : "OFF") + " | min " + formatCoins((double) minimumAuctionProfit) + " | interval " + auctionScanIntervalTicks / 20 + "s | pages " + FlipEngine.totalPages + " | tracked items " + FlipEngine.trackedKeys + " | candidates " + lastPageFlipCount).withStyle(ChatFormatting.AQUA));
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("scan").executes(context -> {
                            scanAuctions(true);
                            context.getSource().sendFeedback(Component.literal("Scanning all " + FlipEngine.totalPages + " auction pages").withStyle(ChatFormatting.AQUA));
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("count").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("amount", IntegerArgumentType.integer(1, 10)).executes(context -> {
                            auctionFlipCount = IntegerArgumentType.getInteger(context, "amount");
                            context.getSource().sendFeedback(Component.literal("Auction flip messages: " + auctionFlipCount).withStyle(ChatFormatting.AQUA));
                            if (auctionFlipsEnabled) scanAuctions();
                            return 1;
                        })))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("interval").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("seconds", IntegerArgumentType.integer(2, 60)).executes(context -> {
                            auctionScanIntervalTicks = IntegerArgumentType.getInteger(context, "seconds") * 20;
                            context.getSource().sendFeedback(Component.literal("Auction scan interval: " + auctionScanIntervalTicks / 20 + "s").withStyle(ChatFormatting.AQUA));
                            return 1;
                        })))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("minprofit").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("amount", IntegerArgumentType.integer(1000, 100000000)).executes(context -> {
                            minimumAuctionProfit = IntegerArgumentType.getInteger(context, "amount");
                            context.getSource().sendFeedback(Component.literal("Min auction profit: " + formatCoins((double) minimumAuctionProfit)).withStyle(ChatFormatting.AQUA));
                            if (auctionFlipsEnabled) scanAuctions();
                            return 1;
                        })))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("quickbuy").executes(context -> {
                            QuickBuy.enabled = !QuickBuy.enabled;
                            context.getSource().sendFeedback(Component.literal("Quick-buy screens: " + (QuickBuy.enabled ? "ON" : "OFF")).withStyle(QuickBuy.enabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("alert").executes(context -> {
                            alertsEnabled = !alertsEnabled;
                            context.getSource().sendFeedback(Component.literal("Flip alert sound and popup: " + (alertsEnabled ? "ON" : "OFF")).withStyle(alertsEnabled ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("rate").then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("id", StringArgumentType.word()).then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("rating", IntegerArgumentType.integer(1, 5)).executes(context -> {
                            String id = StringArgumentType.getString(context, "id");
                            int rating = IntegerArgumentType.getInteger(context, "rating");
                            AuctionFlip flip = pendingFlipRatings.remove(id);
                            if (flip == null) {
                                context.getSource().sendError(Component.literal("Unknown or already-rated flip ID: " + id));
                                return 0;
                            }
                            logFlipRating(id, flip, rating);
                            context.getSource().sendFeedback(Component.literal("Saved rating " + rating + "/5 for flip " + id + " locally.").withStyle(ChatFormatting.GREEN));
                            return 1;
                        }))))
        ));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            QuickBuy.attach(screen);
            if (!(screen instanceof AbstractContainerScreen<?>) || !isBazaarScreen(screen)) return;
            loadTrades();
            ScreenExtensions extensions = ScreenExtensions.getExtensions(screen);
            Button advisorButton = Button.builder(Component.literal("Advisor: OFF"), button -> {
                advisorVisible = !advisorVisible;
                button.setMessage(Component.literal(advisorVisible ? "Advisor: ON" : "Advisor: OFF"));
            }).bounds(screen.width - 345, screen.height - 28, 105, 20).build();
            Button editButton = Button.builder(Component.literal("Edit: OFF"), button -> {
                editMode = !editMode;
                button.setMessage(Component.literal(editMode ? "Edit: ON" : "Edit: OFF"));
                plusButton.visible = editMode;
                minusButton.visible = editMode;
            }).bounds(screen.width - 235, screen.height - 28, 105, 20).build();
            plusButton = Button.builder(Component.literal("+"), button -> resizePanel(0.1)).bounds(screen.width - 125, screen.height - 28, 25, 20).build();
            minusButton = Button.builder(Component.literal("-"), button -> resizePanel(-0.1)).bounds(screen.width - 95, screen.height - 28, 25, 20).build();
            Button sortButton = Button.builder(Component.literal("Sort: " + sortLabel()), button -> {
                sortMode = (sortMode + 1) % 4;
                button.setMessage(Component.literal("Sort: " + sortLabel()));
                refreshStatus();
            }).bounds(screen.width - 345, screen.height - 52, 105, 20).build();
            Button booksButton = Button.builder(Component.literal("Books: " + booksLabel()), button -> {
                booksMode = (booksMode + 1) % 3;
                button.setMessage(Component.literal("Books: " + booksLabel()));
                refreshStatus();
            }).bounds(screen.width - 235, screen.height - 52, 105, 20).build();
            Button riskButton = Button.builder(Component.literal("Risk: HIDE"), button -> {
                hideSuspicious = !hideSuspicious;
                button.setMessage(Component.literal(hideSuspicious ? "Risk: HIDE" : "Risk: SHOW"));
                refreshStatus();
            }).bounds(screen.width - 125, screen.height - 52, 115, 20).build();
            showMoreButton = Button.builder(Component.literal("Rows +"), button -> {
                visibleTradeRows = Math.min(8, visibleTradeRows + 1);
                refreshStatus();
            }).bounds(screen.width - 345, screen.height - 76, 70, 20).build();
            showLessButton = Button.builder(Component.literal("Rows -"), button -> {
                visibleTradeRows = Math.max(1, visibleTradeRows - 1);
                refreshStatus();
            }).bounds(screen.width - 270, screen.height - 76, 70, 20).build();
            extensions.fabric_getButtons().add(advisorButton);
            extensions.fabric_getButtons().add(editButton);
            extensions.fabric_getButtons().add(plusButton);
            extensions.fabric_getButtons().add(minusButton);
            extensions.fabric_getButtons().add(sortButton);
            extensions.fabric_getButtons().add(booksButton);
            extensions.fabric_getButtons().add(riskButton);
            extensions.fabric_getButtons().add(showMoreButton);
            extensions.fabric_getButtons().add(showLessButton);
            plusButton.visible = editMode;
            minusButton.visible = editMode;
            ScreenEvents.afterExtract(screen).register((current, context, mouseX, mouseY, delta) -> {
                if (advisorVisible) renderOverlay(context);
            });
            ScreenMouseEvents.beforeMouseDrag(screen).register((current, event, deltaX, deltaY) -> {
                if (editMode && advisorVisible && event.button() == 0 && isInsidePanel(event.x(), event.y())) {
                    panelX += (int) deltaX;
                    panelY += (int) deltaY;
                }
            });
            ScreenMouseEvents.afterMouseScroll(screen).register((current, mouseX, mouseY, scrollX, scrollY, consumed) -> {
                if (editMode && advisorVisible && isInsidePanel(mouseX, mouseY)) resizePanel(scrollY > 0 ? 0.05 : -0.05);
                return consumed;
            });
        });
    }

    private static boolean isBazaarScreen(Screen screen) {
        return screen.getTitle().getString().toLowerCase().contains("bazaar");
    }

    private static String sortLabel() {
        return switch (sortMode) {
            case 1 -> "PROFIT";
            case 2 -> "VOLUME";
            case 3 -> "ROI";
            default -> "FAST";
        };
    }

    private static String booksLabel() {
        return switch (booksMode) {
            case 1 -> "ONLY";
            case 2 -> "HIDE";
            default -> "ALL";
        };
    }

    private static void loadTrades() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BAZAAR_URL)).GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(ExampleModClient::parseTrades)
                .thenAccept(result -> Minecraft.getInstance().execute(() -> {
                    trades = result;
                    refreshStatus();
                }))
                .exceptionally(error -> {
                    Minecraft.getInstance().execute(() -> status = "Bazaar request failed");
                    return null;
                });
    }

    private static void scanAuctions() {
        scanAuctions(true);
    }

    private static void scanAuctions(boolean force) {
        if (!auctionFlipsEnabled || auctionScanInProgress) return;
        auctionScanInProgress = true;
        CompletableFuture.runAsync(() -> {
            try {
                FlipEngine.scan(HTTP, force, minimumAuctionProfit, flips -> Minecraft.getInstance().execute(() -> announceFlips(flips)));
                auctionScanFailed = false;
            } catch (Exception error) {
                if (!auctionScanFailed) {
                    Minecraft.getInstance().execute(() -> sendChat(Component.literal("[Bazaar Advisor] Auction scan failed; retrying.").withStyle(ChatFormatting.RED)));
                }
                auctionScanFailed = true;
            } finally {
                Minecraft.getInstance().execute(() -> auctionScanInProgress = false);
            }
        });
    }

    private static void announceFlips(List<AuctionFlip> flips) {
        lastPageFlipCount = flips.size();
        List<AuctionFlip> freshFlips = flips.stream()
                .filter(flip -> notifiedAuctionIds.add(flip.uuid()))
                .limit(auctionFlipCount)
                .toList();
        if (!freshFlips.isEmpty()) {
            sendChat(Component.literal("[Bazaar Advisor] Fresh BINs with recent sale proof:").withStyle(ChatFormatting.GOLD));
            freshFlips.forEach(ExampleModClient::sendFlipMessage);
            freshFlips.forEach(recentFlips::addFirst);
            while (recentFlips.size() > 40) recentFlips.removeLast();
            bestFlip = freshFlips.get(0);
            if (alertsEnabled) playAlert(Minecraft.getInstance(), bestFlip);
        }
        if (notifiedAuctionIds.size() > 3000) notifiedAuctionIds.clear();
    }

    private static void sendChat(Component message) {
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(message);
    }

    private static void sendFlipMessage(AuctionFlip flip) {
        String feedbackId = UUID.randomUUID().toString().substring(0, 8);
        pendingFlipRatings.put(feedbackId, flip);
        logFlipSuggestion(feedbackId, flip);
        Component hover = Component.literal("Click to open this auction\n").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(flip.recentSaleCount() + " recent sales; realized " + formatCoins(flip.realizedSalePrice()) + " coins").withStyle(ChatFormatting.WHITE));
        Component message = Component.empty()
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/viewauction " + flip.uuid()))
                        .withHoverEvent(new HoverEvent.ShowText(hover)))
                .append(Component.literal("  " + flip.itemName()).withStyle(rarityColor(flip.rarity()), ChatFormatting.BOLD))
                .append(Component.literal("  BUY " + formatCoins(flip.buyPrice())).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  NET +" + formatCoins(flip.estimatedProfit())).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  " + flip.recentSaleCount() + " sales").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(flip.exact() ? " EXACT" : " ~CORE").withStyle(flip.exact() ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .append(Component.literal("  " + flip.listingAgeMs() / 1000 + "s old").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("  [BIN]").withStyle(ChatFormatting.GOLD));
        sendChat(message);
    }

    private static AuctionFlip findRecentFlip(String name, double price) {
        String wanted = name.toLowerCase();
        for (AuctionFlip flip : recentFlips) {
            String candidate = flip.itemName().toLowerCase();
            boolean sameName = candidate.equals(wanted) || wanted.contains(candidate) || candidate.contains(wanted);
            if (sameName && (price < 0 || Math.abs(flip.buyPrice() - price) < 1)) return flip;
        }
        return null;
    }

    private static void playAlert(Minecraft client, AuctionFlip flip) {
        tuneTick = 0;
        client.gui.setTimes(2, 50, 10);
        client.gui.setTitle(Component.literal("+" + formatCoins(flip.estimatedProfit())).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
        client.gui.setSubtitle(Component.literal(flip.itemName() + "  ").withStyle(rarityColor(flip.rarity()))
                .append(Component.literal("[").withStyle(ChatFormatting.GRAY))
                .append(flipKey.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("] to open").withStyle(ChatFormatting.GRAY)));
    }

    private static void tickTune(Minecraft client) {
        if (tuneTick < 0) return;
        if (tuneTick % 3 == 0 && tuneTick / 3 < TUNE_PITCHES.length) {
            float pitch = TUNE_PITCHES[tuneTick / 3];
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, pitch));
            if (tuneTick / 3 == TUNE_PITCHES.length - 1) client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, pitch));
        }
        if (++tuneTick > TUNE_PITCHES.length * 3) tuneTick = -1;
    }

    private static void openBestFlip(Minecraft client) {
        AuctionFlip flip = bestFlip;
        if (client.player == null) return;
        if (flip == null || flip.endTime() <= System.currentTimeMillis()) {
            sendChat(Component.literal("[Bazaar Advisor] No active flip to open.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        client.player.connection.sendCommand("viewauction " + flip.uuid());
    }

    private static Path feedbackLogPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config/bazaar-advisor/flip-feedback.jsonl");
    }

    private static void appendFeedback(JsonObject event) {
        try {
            Path path = feedbackLogPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, event + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            sendChat(Component.literal("[Bazaar Advisor] Could not write local feedback log.").withStyle(ChatFormatting.RED));
        }
    }

    private static void logFlipSuggestion(String id, AuctionFlip flip) {
        JsonObject event = new JsonObject();
        event.addProperty("event", "suggestion");
        event.addProperty("id", id);
        event.addProperty("time", System.currentTimeMillis());
        event.addProperty("item", flip.itemName());
        event.addProperty("rarity", flip.rarity());
        event.addProperty("buyPrice", flip.buyPrice());
        event.addProperty("realizedSalePrice", flip.realizedSalePrice());
        event.addProperty("estimatedProfit", flip.estimatedProfit());
        event.addProperty("expiresAt", flip.endTime());
        event.addProperty("recentSaleCount", flip.recentSaleCount());
        event.addProperty("auctionUuid", flip.uuid());
        event.addProperty("match", flip.exact() ? "exact" : "core");
        event.addProperty("listingAgeSeconds", flip.listingAgeMs() / 1000);
        appendFeedback(event);
    }

    private static void logFlipRating(String id, AuctionFlip flip, int rating) {
        JsonObject event = new JsonObject();
        event.addProperty("event", "rating");
        event.addProperty("id", id);
        event.addProperty("time", System.currentTimeMillis());
        event.addProperty("item", flip.itemName());
        event.addProperty("rating", rating);
        event.addProperty("buyPrice", flip.buyPrice());
        event.addProperty("realizedSalePrice", flip.realizedSalePrice());
        event.addProperty("estimatedProfit", flip.estimatedProfit());
        event.addProperty("expiresAt", flip.endTime());
        event.addProperty("recentSaleCount", flip.recentSaleCount());
        appendFeedback(event);
    }

    private static final class BazaarSettingsScreen extends Screen {
        private int left;
        private int top;
        private Button flipsButton;
        private Button countButton;
        private Button intervalButton;
        private Button profitButton;
        private Button booksButton;
        private Button riskButton;
        private Button sortButton;
        private Button rowsButton;
        private Button alertButton;
        private Button quickBuyButton;

        private BazaarSettingsScreen() {
            super(Component.literal("Bazaar Advisor Settings"));
        }

        @Override
        protected void init() {
            left = width / 2 - 115;
            top = Math.max(33, height / 2 - 105);
            flipsButton = Button.builder(Component.literal("Auction flips: " + (auctionFlipsEnabled ? "ON" : "OFF")), button -> {
                auctionFlipsEnabled = !auctionFlipsEnabled;
                button.setMessage(Component.literal("Auction flips: " + (auctionFlipsEnabled ? "ON" : "OFF")));
                if (auctionFlipsEnabled) scanAuctions();
            }).bounds(left, top, 230, 20).build();
            countButton = Button.builder(Component.literal("Messages per scan: " + auctionFlipCount), button -> {
                auctionFlipCount = auctionFlipCount >= 10 ? 1 : auctionFlipCount + 1;
                button.setMessage(Component.literal("Messages per scan: " + auctionFlipCount));
            }).bounds(left, top + 25, 230, 20).build();
            intervalButton = Button.builder(Component.literal("Scan interval: " + auctionScanIntervalTicks / 20 + "s"), button -> {
                int[] intervals = {2, 3, 5, 10, 15, 30, 60};
                int current = auctionScanIntervalTicks / 20;
                int next = intervals[0];
                for (int i = 0; i < intervals.length; i++) {
                    if (intervals[i] == current) {
                        next = intervals[(i + 1) % intervals.length];
                        break;
                    }
                }
                auctionScanIntervalTicks = next * 20;
                button.setMessage(Component.literal("Scan interval: " + next + "s"));
            }).bounds(left, top + 50, 230, 20).build();
            profitButton = Button.builder(Component.literal("Min profit: " + formatCoins((double) minimumAuctionProfit)), button -> {
                long[] floors = {100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L};
                int current = 0;
                for (int i = 0; i < floors.length; i++) if (floors[i] == minimumAuctionProfit) current = i;
                minimumAuctionProfit = floors[(current + 1) % floors.length];
                button.setMessage(Component.literal("Min profit: " + formatCoins((double) minimumAuctionProfit)));
            }).bounds(left, top + 75, 230, 20).build();
            booksButton = Button.builder(Component.literal("Books filter: " + booksLabel()), button -> {
                booksMode = (booksMode + 1) % 3;
                button.setMessage(Component.literal("Books filter: " + booksLabel()));
            }).bounds(left, top + 100, 230, 20).build();
            riskButton = Button.builder(Component.literal("Hide risky flips: " + (hideSuspicious ? "ON" : "OFF")), button -> {
                hideSuspicious = !hideSuspicious;
                button.setMessage(Component.literal("Hide risky flips: " + (hideSuspicious ? "ON" : "OFF")));
            }).bounds(left, top + 125, 230, 20).build();
            sortButton = Button.builder(Component.literal("Bazaar sort: " + sortLabel()), button -> {
                sortMode = (sortMode + 1) % 4;
                button.setMessage(Component.literal("Bazaar sort: " + sortLabel()));
            }).bounds(left, top + 150, 230, 20).build();
            rowsButton = Button.builder(Component.literal("Visible Bazaar rows: " + visibleTradeRows), button -> {
                visibleTradeRows = visibleTradeRows >= 8 ? 1 : visibleTradeRows + 1;
                button.setMessage(Component.literal("Visible Bazaar rows: " + visibleTradeRows));
            }).bounds(left, top + 175, 230, 20).build();
            alertButton = Button.builder(Component.literal("Flip alert (sound + popup): " + (alertsEnabled ? "ON" : "OFF")), button -> {
                alertsEnabled = !alertsEnabled;
                button.setMessage(Component.literal("Flip alert (sound + popup): " + (alertsEnabled ? "ON" : "OFF")));
            }).bounds(left, top + 200, 230, 20).build();
            quickBuyButton = Button.builder(Component.literal("Quick-buy screens: " + (QuickBuy.enabled ? "ON" : "OFF")), button -> {
                QuickBuy.enabled = !QuickBuy.enabled;
                button.setMessage(Component.literal("Quick-buy screens: " + (QuickBuy.enabled ? "ON" : "OFF")));
            }).bounds(left, top + 225, 230, 20).build();
            addRenderableWidget(flipsButton);
            addRenderableWidget(countButton);
            addRenderableWidget(intervalButton);
            addRenderableWidget(profitButton);
            addRenderableWidget(booksButton);
            addRenderableWidget(riskButton);
            addRenderableWidget(sortButton);
            addRenderableWidget(rowsButton);
            addRenderableWidget(alertButton);
            addRenderableWidget(quickBuyButton);
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(left, top + 255, 230, 20).build());
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 1) {
                int row = (int) Math.floor((event.y() - top) / 25.0);
                if (event.x() >= left && event.x() <= left + 230) {
                    switch (row) {
                        case 1 -> {
                            auctionFlipCount = auctionFlipCount <= 1 ? 10 : auctionFlipCount - 1;
                            countButton.setMessage(Component.literal("Messages per scan: " + auctionFlipCount));
                        }
                        case 2 -> stepInterval(-1);
                        case 3 -> stepProfitFloor(-1);
                        case 4 -> booksMode = (booksMode + 2) % 3;
                        case 5 -> hideSuspicious = !hideSuspicious;
                        case 6 -> sortMode = (sortMode + 3) % 4;
                        case 7 -> visibleTradeRows = Math.max(1, visibleTradeRows - 1);
                        case 8 -> alertsEnabled = !alertsEnabled;
                        case 9 -> QuickBuy.enabled = !QuickBuy.enabled;
                        default -> { return super.mouseClicked(event, doubleClick); }
                    }
                    syncSettingMessages();
                    return true;
                }
            }
            return super.mouseClicked(event, doubleClick);
        }

        private void stepInterval(int direction) {
            int[] values = {2, 3, 5, 10, 15, 30, 60};
            int index = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == auctionScanIntervalTicks / 20) index = i;
            auctionScanIntervalTicks = values[Math.floorMod(index + direction, values.length)] * 20;
        }

        private void stepProfitFloor(int direction) {
            long[] values = {100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L};
            int index = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == minimumAuctionProfit) index = i;
            minimumAuctionProfit = values[Math.floorMod(index + direction, values.length)];
        }

        private void syncSettingMessages() {
            countButton.setMessage(Component.literal("Messages per scan: " + auctionFlipCount));
            intervalButton.setMessage(Component.literal("Scan interval: " + auctionScanIntervalTicks / 20 + "s"));
            profitButton.setMessage(Component.literal("Min profit: " + formatCoins((double) minimumAuctionProfit)));
            booksButton.setMessage(Component.literal("Books filter: " + booksLabel()));
            riskButton.setMessage(Component.literal("Hide risky flips: " + (hideSuspicious ? "ON" : "OFF")));
            sortButton.setMessage(Component.literal("Bazaar sort: " + sortLabel()));
            rowsButton.setMessage(Component.literal("Visible Bazaar rows: " + visibleTradeRows));
            alertButton.setMessage(Component.literal("Flip alert (sound + popup): " + (alertsEnabled ? "ON" : "OFF")));
            quickBuyButton.setMessage(Component.literal("Quick-buy screens: " + (QuickBuy.enabled ? "ON" : "OFF")));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            int left = width / 2 - 130;
            int top = Math.max(8, height / 2 - 130);
            context.fill(left, top, left + 260, top + 310, 0xF0182233);
            context.fill(left, top, left + 260, top + 2, 0xFFA8E6C1);
            context.fill(left + 10, top, left + 12, top + 310, 0xFF76C9A2);
            context.fill(left + 12, top + 26, left + 248, top + 27, 0x553F6C58);
            context.fill(left + 12, top + 126, left + 248, top + 127, 0x553F6C58);
            context.fill(left + 12, top + 251, left + 248, top + 252, 0x553F6C58);
            super.extractRenderState(context, mouseX, mouseY, delta);
            var text = context.textRenderer();
            text.accept(net.minecraft.client.gui.TextAlignment.CENTER, width / 2, top + 8,
                    Component.literal("BAZAAR TRADING DESK").withStyle(ChatFormatting.AQUA));
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private static ChatFormatting rarityColor(String rarity) {
        return switch (rarity) {
            case "COMMON" -> ChatFormatting.WHITE;
            case "UNCOMMON" -> ChatFormatting.GREEN;
            case "RARE" -> ChatFormatting.BLUE;
            case "EPIC" -> ChatFormatting.DARK_PURPLE;
            case "LEGENDARY" -> ChatFormatting.GOLD;
            case "MYTHIC", "SUPREME", "VERY SPECIAL" -> ChatFormatting.LIGHT_PURPLE;
            default -> ChatFormatting.AQUA;
        };
    }

    private static String bazaarName(String product) {
        Map<String, String> knownNames = Map.of(
            "ENCHANTED_LAPIS_LAZULI", "Enchanted Lapis Lazuli",
            "ENCHANTED_REDSTONE", "Enchanted Redstone",
            "ENCHANTED_ENDER_PEARL", "Enchanted Ender Pearl",
            "SUPER_COMPACTOR_3000", "Super Compactor 3000",
            "BOOSTER_COOKIE", "Booster Cookie"
        );
        if (knownNames.containsKey(product)) return knownNames.get(product);
        String name = product.toLowerCase().replace('_', ' ');
        StringBuilder result = new StringBuilder();
        for (String word : name.split(" ")) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static void refreshStatus() {
        status = trades.isEmpty() ? "No Bazaar data yet" : sortLabel() + " | " + filteredTrades().size() + " matches";
    }

    private static void resizePanel(double amount) {
        scale = Math.max(0.7, Math.min(1.35, scale + amount));
    }

    private static boolean isInsidePanel(double x, double y) {
        int panelWidth = (int) (410 * scale);
        int panelHeight = (int) (145 * scale);
        return x >= panelX && x <= panelX + panelWidth && y >= panelY && y <= panelY + panelHeight;
    }

    private static List<Trade> filteredTrades() {
        return trades.stream()
                .filter(trade -> switch (booksMode) {
                    case 1 -> trade.book();
                    case 2 -> !trade.book();
                    default -> true;
                })
                .filter(trade -> !hideSuspicious || !trade.suspicious())
                .sorted((left, right) -> Double.compare(score(right), score(left)))
                .toList();
    }

    private static double score(Trade trade) {
        return switch (sortMode) {
            case 1 -> trade.profit();
            case 2 -> trade.volume();
            case 3 -> trade.roi();
            default -> trade.fastScore();
        };
    }

    private static void renderOverlay(GuiGraphicsExtractor context) {
        context.nextStratum();
        int panelWidth = (int) (410 * scale);
        int panelHeight = (int) (145 * scale);
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xF0182233);
        context.fill(panelX, panelY, panelX + panelWidth, panelY + 2, 0xFFFBBF24);
        context.fill(panelX, panelY + panelHeight - 2, panelX + panelWidth, panelY + panelHeight, 0xFFFBBF24);
        context.fill(panelX, panelY, panelX + 2, panelY + panelHeight, 0xFFFBBF24);
        context.fill(panelX + panelWidth - 2, panelY, panelX + panelWidth, panelY + panelHeight, 0xFFFBBF24);
        var text = context.textRenderer();
        text.accept(net.minecraft.client.gui.TextAlignment.CENTER, panelX + panelWidth / 2, panelY + 10, Component.literal("Bazaar Advisor").withStyle(ChatFormatting.WHITE));
        text.accept(panelX + 12, panelY + 28, Component.literal(status).withStyle(ChatFormatting.GRAY));
        int row = panelY + 46;
        List<Trade> visibleTrades = filteredTrades();
        if (visibleTrades.isEmpty()) {
            text.accept(panelX + 12, row, Component.literal("No trades match these filters.").withStyle(ChatFormatting.YELLOW));
        } else {
            for (Trade trade : visibleTrades.stream().limit(visibleTradeRows).toList()) {
                String line = "%s  +%s  vol %s  %s".formatted(bazaarName(trade.product()), formatCoins(trade.profit()), formatCoins(trade.volume()), formatPercent(trade.roi()));
                ChatFormatting color = trade.suspicious() ? ChatFormatting.RED : trade.roi() >= 0.1 ? ChatFormatting.GREEN : ChatFormatting.AQUA;
                text.accept(panelX + 12, row, Component.literal(line).withStyle(color));
                row += 18;
            }
        }
        text.accept(panelX + 12, panelY + panelHeight - 18, Component.literal(editMode ? "EDIT: drag | wheel or +/- resize" : "Edit GUI enables drag and resize").withStyle(ChatFormatting.GOLD));
        text.accept(panelX + 210, panelY + panelHeight - 18, Component.literal("Rows " + visibleTradeRows).withStyle(ChatFormatting.GRAY));
    }

    private static String formatCoins(double value) {
        if (value >= 1_000_000) return "%.1fm".formatted(value / 1_000_000);
        if (value >= 1_000) return "%.1fk".formatted(value / 1_000);
        return "%.0f".formatted(value);
    }

    private static String formatPercent(double value) {
        return "%.1f%%".formatted(value * 100);
    }

    private static List<Trade> parseTrades(String body) {
        List<Trade> parsed = new ArrayList<>();
        JsonObject products = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("products");
        if (products == null) return parsed;
        products.entrySet().forEach(entry -> {
            JsonObject quickStatus = entry.getValue().getAsJsonObject().getAsJsonObject("quick_status");
            if (quickStatus == null) return;
            double buyOrder = quickStatus.get("buyPrice").getAsDouble();
            double sellOffer = quickStatus.get("sellPrice").getAsDouble();
            long volume = quickStatus.get("buyVolume").getAsLong();
            double profit = (buyOrder - sellOffer) * (1.0 - TAX_RATE);
            if (profit <= 0 || volume <= 0) return;
            double roi = sellOffer > 0 ? profit / sellOffer : 0;
            double spreadRatio = sellOffer > 0 ? (buyOrder - sellOffer) / sellOffer : 0;
            boolean suspicious = spreadRatio >= 0.50 || (spreadRatio >= 0.25 && volume < 5000);
            double liquidity = Math.min(volume, 10000) / 10000.0;
            double fastScore = profit * (0.5 + liquidity) * (1 + roi);
            String product = entry.getKey();
            boolean book = product.contains("BOOK") || product.contains("ENCHANTMENT");
            parsed.add(new Trade(product, sellOffer, volume, profit, fastScore, roi, suspicious, book));
        });
        return parsed;
    }

    private record Trade(String product, double buyPrice, long volume, double profit, double fastScore, double roi, boolean suspicious, boolean book) {}

}
