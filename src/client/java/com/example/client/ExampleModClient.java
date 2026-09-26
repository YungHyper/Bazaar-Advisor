package com.example.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ExampleModClient implements ClientModInitializer {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final double TAX_RATE = 0.0125;
    private static KeyMapping openKey;
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
    private static boolean auctionScanInProgress;
    private static boolean auctionScanStarted;
    private static int auctionScanIntervalTicks = 100;
    private static int auctionPage;
    private static int auctionTotalPages = 1;
    private static long minimumAuctionProfit = 500_000L;
    private static final long MAX_LISTING_AGE_MS = 600_000L;
    private static final long MAX_SALE_AGE_MS = 120_000L;
    private static final double MIN_PROFIT_RATIO = 0.10;
    private static final int AUCTION_PAGES_PER_SCAN = 3;
    private static int lastPageFlipCount;
    private static final Set<String> notifiedAuctionIds = new HashSet<>();

    @Override
    public void onInitializeClient() {
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.bazaar_advisor.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bazaar_advisor", "main"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick() && client.screen != null && isBazaarScreen(client.screen)) {
                advisorVisible = !advisorVisible;
            }
            if (auctionFlipsEnabled && client.player != null && (!auctionScanStarted || ++auctionScanTicks >= auctionScanIntervalTicks)) {
                auctionScanStarted = true;
                auctionScanTicks = 0;
                scanAuctions();
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            LiteralArgumentBuilder.<FabricClientCommandSource>literal("bazad")
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
                            context.getSource().sendFeedback(Component.literal("Flips " + (auctionFlipsEnabled ? "ON" : "OFF") + " | min " + formatCoins((double) minimumAuctionProfit) + " | interval " + auctionScanIntervalTicks / 20 + "s | page " + (auctionPage + 1) + "/" + auctionTotalPages + " | candidates " + lastPageFlipCount).withStyle(ChatFormatting.AQUA));
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("scan").executes(context -> {
                            scanAuctions();
                            context.getSource().sendFeedback(Component.literal("Scanning auction page " + (auctionPage + 1) + "/" + auctionTotalPages).withStyle(ChatFormatting.AQUA));
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("count").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("amount", IntegerArgumentType.integer(1, 10)).executes(context -> {
                            auctionFlipCount = IntegerArgumentType.getInteger(context, "amount");
                            context.getSource().sendFeedback(Component.literal("Auction flip messages: " + auctionFlipCount).withStyle(ChatFormatting.AQUA));
                            if (auctionFlipsEnabled) scanAuctions();
                            return 1;
                        })))
                        .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("interval").then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("seconds", IntegerArgumentType.integer(5, 60)).executes(context -> {
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
        ));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
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
        if (!auctionFlipsEnabled || auctionScanInProgress) return;
        auctionScanInProgress = true;
        int knownPages = Math.max(1, auctionTotalPages);
        int batchSize = Math.min(AUCTION_PAGES_PER_SCAN, knownPages);
        List<Integer> requestedPages = new ArrayList<>();
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        for (int offset = 0; offset < batchSize; offset++) {
            int page = (auctionPage + offset) % knownPages;
            requestedPages.add(page);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/skyblock/auctions?page=" + page)).GET().build();
            requests.add(HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        HttpRequest endedRequest = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/skyblock/auctions_ended")).GET().build();
        requests.add(HTTP.sendAsync(endedRequest, HttpResponse.BodyHandlers.ofString()));
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
            .thenApply(ignored -> {
                List<String> activePages = requests.subList(0, batchSize).stream()
                    .map(CompletableFuture::join).map(HttpResponse::body).toList();
                String endedSales = requests.get(batchSize).join().body();
                return parseAuctionResponses(activePages, endedSales);
            })
                .thenAccept(flips -> Minecraft.getInstance().execute(() -> {
                    lastPageFlipCount = flips.flips().size();
                    List<AuctionFlip> freshFlips = flips.flips().stream()
                            .filter(flip -> notifiedAuctionIds.add(flip.uuid()))
                            .limit(auctionFlipCount)
                            .toList();
                    if (!freshFlips.isEmpty()) {
                        sendChat(Component.literal("[Bazaar Advisor] Fresh BINs with recent sale proof:").withStyle(ChatFormatting.GOLD));
                        freshFlips.forEach(ExampleModClient::sendFlipMessage);
                    }
                        if (notifiedAuctionIds.size() > 500) notifiedAuctionIds.clear();
                        auctionTotalPages = flips.totalPages();
                    auctionPage = (auctionPage + requestedPages.size()) % Math.max(1, flips.totalPages());
                    auctionTotalPages = flips.totalPages();
                    auctionScanInProgress = false;
                }))
                .exceptionally(error -> {
                    auctionScanInProgress = false;
                    Minecraft.getInstance().execute(() -> sendChat(Component.literal("[Bazaar Advisor] Auction scan failed.").withStyle(ChatFormatting.RED)));
                    return null;
                });
    }

    private static void sendChat(Component message) {
        Minecraft.getInstance().gui.getChat().addClientSystemMessage(message);
    }

    private static void sendFlipMessage(AuctionFlip flip) {
        Component message = Component.literal("  " + flip.itemName() + "  ")
            .withStyle(rarityColor(flip.rarity()))
                .append(Component.literal("BUY " + formatCoins(flip.buyPrice()) + "  ").withStyle(style -> style
                        .withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent.RunCommand("/viewauction " + flip.uuid()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Recent sale of this exact item: " + formatCoins(flip.realizedSalePrice()) + " coins").withStyle(ChatFormatting.WHITE)))))
                .append(Component.literal("EST +" + formatCoins(flip.estimatedProfit())).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("  [BIN]").withStyle(ChatFormatting.GOLD));
        sendChat(message);
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

    private static AuctionResponse parseAuctionResponses(List<String> bodies, String endedBody) {
        Map<String, List<Double>> realizedSales = new HashMap<>();
        long now = System.currentTimeMillis();
        JsonObject endedRoot = JsonParser.parseString(endedBody).getAsJsonObject();
        if (endedRoot.has("auctions")) {
            endedRoot.getAsJsonArray("auctions").forEach(element -> {
                JsonObject sale = element.getAsJsonObject();
                if (!sale.has("bin") || !sale.get("bin").getAsBoolean()) return;
                String itemBytes = sale.has("item_bytes") ? sale.get("item_bytes").getAsString() : "";
                long timestamp = sale.has("timestamp") ? sale.get("timestamp").getAsLong() : 0;
                double price = sale.has("price") ? sale.get("price").getAsDouble() : 0;
                long age = now - timestamp;
                if (itemBytes.isEmpty() || price <= 0 || timestamp <= 0 || age < 0 || age > MAX_SALE_AGE_MS) return;
                String itemSignature = normalizedItemSignature(itemBytes);
                if (!itemSignature.isEmpty()) realizedSales.computeIfAbsent(itemSignature, key -> new ArrayList<>()).add(price);
            });
        }

        Map<String, List<AuctionListing>> groups = new HashMap<>();
        int totalPages = 1;
        for (String body : bodies) {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            totalPages = Math.max(totalPages, root.has("totalPages") ? root.get("totalPages").getAsInt() : 1);
            if (!root.has("auctions")) continue;
            root.getAsJsonArray("auctions").forEach(element -> {
                JsonObject auction = element.getAsJsonObject();
                if (!auction.has("bin") || !auction.get("bin").getAsBoolean()) return;
                String itemName = auction.has("item_name") ? auction.get("item_name").getAsString() : "Unknown item";
                String lore = auction.has("item_lore") ? auction.get("item_lore").getAsString() : "";
                String itemBytes = auction.has("item_bytes") ? auction.get("item_bytes").getAsString() : "";
                String uuid = auction.has("uuid") ? auction.get("uuid").getAsString() : "";
                double price = auction.has("starting_bid") ? auction.get("starting_bid").getAsDouble() : 0;
                long startTime = auction.has("start") ? auction.get("start").getAsLong() : 0;
                if (!uuid.isEmpty() && price > 0 && startTime > 0) {
                    String itemSignature = normalizedItemSignature(itemBytes);
                    if (!itemSignature.isEmpty()) groups.computeIfAbsent(itemSignature, key -> new ArrayList<>()).add(new AuctionListing(uuid, price, itemName, rarityFromLore(lore), startTime, itemSignature));
                }
            });
        }
        List<AuctionFlip> flips = new ArrayList<>();
        groups.forEach((itemName, listings) -> {
            listings.sort(Comparator.comparingDouble(AuctionListing::price));
            for (AuctionListing listing : listings) {
                long listingAge = now - listing.startTime();
                if (listingAge < 0 || listingAge > MAX_LISTING_AGE_MS) continue;
                List<Double> salePrices = realizedSales.get(listing.itemSignature());
                if (salePrices == null || salePrices.isEmpty()) continue;
                double realizedSalePrice = median(salePrices);
                double estimatedProfit = realizedSalePrice * (1.0 - TAX_RATE) - listing.price();
                if (estimatedProfit >= minimumAuctionProfit && estimatedProfit / listing.price() >= MIN_PROFIT_RATIO) {
                    flips.add(new AuctionFlip(listing.itemName(), listing.rarity(), listing.uuid(), listing.price(), realizedSalePrice, estimatedProfit));
                }
            }
        });
        flips.sort(Comparator.comparingDouble(AuctionFlip::estimatedProfit).reversed());
        return new AuctionResponse(flips, totalPages);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }

    private static String normalizedItemSignature(String encodedItem) {
        try {
            byte[] compressed = Base64.getMimeDecoder().decode(encodedItem);
            CompoundTag item = NbtIo.readCompressed(new ByteArrayInputStream(compressed), NbtAccounter.create(8_000_000L));
            stripInstanceFields(item);
            return canonicalNbt(item);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void stripInstanceFields(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            for (String key : new ArrayList<>(compound.keySet())) {
                String normalizedKey = key.toLowerCase();
                if (normalizedKey.equals("uuid") || normalizedKey.equals("uid") || normalizedKey.equals("timestamp")
                        || normalizedKey.equals("auction_id") || normalizedKey.equals("auction_uuid")) {
                    compound.remove(key);
                } else {
                    stripInstanceFields(compound.get(key));
                }
            }
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) stripInstanceFields(child);
        }
    }

    private static String canonicalNbt(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return compound.keySet().stream().sorted()
                    .map(key -> key + ":" + canonicalNbt(compound.get(key)))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (tag instanceof ListTag list) {
            return list.stream().map(ExampleModClient::canonicalNbt)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        return tag.toString();
    }

    private static String rarityFromLore(String lore) {
        String clean = lore.replaceAll("§.", "").toUpperCase();
        for (String rarity : List.of("VERY SPECIAL", "SUPREME", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON")) {
            if (clean.contains(rarity)) return rarity;
        }
        return "";
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

    private record AuctionListing(String uuid, double price, String itemName, String rarity, long startTime, String itemSignature) {}

    private record AuctionFlip(String itemName, String rarity, String uuid, double buyPrice, double realizedSalePrice, double estimatedProfit) {}

    private record AuctionResponse(List<AuctionFlip> flips, int totalPages) {}
}
