package com.example.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Auction flip detection. Must only be driven by one scan at a time (state is not synchronized). */
final class FlipEngine {
    record AuctionFlip(String itemName, String rarity, String uuid, double buyPrice, double realizedSalePrice,
                       int recentSaleCount, double estimatedProfit, long endTime, boolean exact, long listingAgeMs, double score) {}

    private record SaleRecord(double price, long timestamp) {}

    private record Listing(String uuid, double price, String itemName, String rarity, long start, long end, ItemKeys.Keys keys) {}

    private record Estimate(double target, double reference, int sales, boolean exact) {}

    private static final String PAGE_URL = "https://api.hypixel.net/v2/skyblock/auctions?page=";
    private static final String ENDED_URL = "https://api.hypixel.net/v2/skyblock/auctions_ended";
    private static final long SALE_HISTORY_MS = 3 * 3_600_000L;
    private static final long MAX_LISTING_AGE_MS = 15 * 60_000L;
    private static final long MIN_TIME_LEFT_MS = 20_000L;
    private static final long FRESH_MS = 3 * 60_000L;
    private static final int MAX_SALES_PER_KEY = 60;
    private static final int RECENT_SALES_USED = 7;
    private static final double RESALE_BUFFER = 0.98;
    private static final double UNDERCUT = 0.99;
    private static final double EXACT_MIN_ROI = 0.06;
    private static final double CORE_MIN_ROI = 0.15;
    private static final double SINGLE_SALE_MIN_ROI = 0.20;
    private static final double CORE_MAX_SPREAD = 1.6;

    private static final Map<String, List<SaleRecord>> exactSales = new HashMap<>();
    private static final Map<String, List<SaleRecord>> coreSales = new HashMap<>();
    private static final Set<String> seenSales = new HashSet<>();
    private static final Map<String, ItemKeys.Keys> keyCache = new HashMap<>();
    private static List<Listing> otherPages = List.of();
    private static long lastPageUpdate;
    private static long lastEndedUpdate;

    static volatile int totalPages = 1;
    static volatile int trackedKeys;

    private FlipEngine() {}

    /** Polls page 0 and recent sales; sweeps the remaining pages only when the API data actually changed. */
    static void scan(HttpClient http, boolean force, long minProfit, Consumer<List<AuctionFlip>> sink) throws Exception {
        CompletableFuture<String> pageFuture = fetch(http, PAGE_URL + 0);
        CompletableFuture<String> endedFuture = fetch(http, ENDED_URL);
        JsonObject page0 = parse(pageFuture.get());
        JsonObject ended = parse(endedFuture.get());
        long pageUpdated = page0.has("lastUpdated") ? page0.get("lastUpdated").getAsLong() : 0;
        long endedUpdated = ended.has("lastUpdated") ? ended.get("lastUpdated").getAsLong() : 0;
        boolean pageChanged = pageUpdated != lastPageUpdate;
        if (!force && !pageChanged && endedUpdated == lastEndedUpdate) return;
        lastPageUpdate = pageUpdated;
        lastEndedUpdate = endedUpdated;
        long reference = Math.max(pageUpdated, endedUpdated);
        totalPages = page0.has("totalPages") ? page0.get("totalPages").getAsInt() : totalPages;
        ingestSales(ended, reference);

        List<Listing> fresh = parseListings(page0);
        List<Listing> market = new ArrayList<>(otherPages);
        market.addAll(fresh);
        sink.accept(evaluate(fresh, market, reference, minProfit));

        if (!pageChanged && !force) return;
        try {
            sweepRemainingPages(http, fresh, reference, minProfit, sink);
        } catch (Exception error) {
            lastPageUpdate = 0;
            throw error;
        }
    }

    private static void sweepRemainingPages(HttpClient http, List<Listing> fresh, long reference, long minProfit,
                                            Consumer<List<AuctionFlip>> sink) throws Exception {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int page = 1; page < totalPages; page++) futures.add(fetch(http, PAGE_URL + page));
        List<Listing> others = new ArrayList<>();
        for (CompletableFuture<String> future : futures) others.addAll(parseListings(parse(future.get())));
        otherPages = others;
        List<Listing> all = new ArrayList<>(others);
        all.addAll(fresh);
        sink.accept(evaluate(all, all, reference, minProfit));
        if (keyCache.size() > 150_000) keyCache.clear();
    }

    private static CompletableFuture<String> fetch(HttpClient http, String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(HttpResponse::body);
    }

    private static JsonObject parse(String body) throws IOException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("success") || !root.get("success").getAsBoolean()) throw new IOException("Hypixel API error");
        return root;
    }

    private static ItemKeys.Keys decode(String encoded) {
        try {
            byte[] compressed = Base64.getMimeDecoder().decode(encoded);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(compressed), NbtAccounter.create(8_000_000L));
            return ItemKeys.of(root);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void ingestSales(JsonObject ended, long reference) {
        if (ended.has("auctions")) {
            for (JsonElement element : ended.getAsJsonArray("auctions")) {
                JsonObject sale = element.getAsJsonObject();
                if (!sale.has("bin") || !sale.get("bin").getAsBoolean()) continue;
                String id = sale.has("auction_id") ? sale.get("auction_id").getAsString() : "";
                String bytes = sale.has("item_bytes") ? sale.get("item_bytes").getAsString() : "";
                double price = sale.has("price") ? sale.get("price").getAsDouble() : 0;
                long timestamp = sale.has("timestamp") ? sale.get("timestamp").getAsLong() : 0;
                if (id.isEmpty() || bytes.isEmpty() || price <= 0 || timestamp <= 0 || !seenSales.add(id)) continue;
                ItemKeys.Keys keys = decode(bytes);
                if (keys == null) continue;
                SaleRecord record = new SaleRecord(price, timestamp);
                exactSales.computeIfAbsent(keys.exact(), key -> new ArrayList<>()).add(record);
                if (keys.core() != null) coreSales.computeIfAbsent(keys.core(), key -> new ArrayList<>()).add(record);
            }
        }
        prune(exactSales, reference);
        prune(coreSales, reference);
        if (seenSales.size() > 100_000) seenSales.clear();
        trackedKeys = exactSales.size();
    }

    private static void prune(Map<String, List<SaleRecord>> sales, long reference) {
        sales.values().removeIf(list -> {
            list.removeIf(record -> reference - record.timestamp() > SALE_HISTORY_MS);
            list.sort(Comparator.comparingLong(SaleRecord::timestamp));
            while (list.size() > MAX_SALES_PER_KEY) list.remove(0);
            return list.isEmpty();
        });
    }

    private static List<Listing> parseListings(JsonObject page) {
        List<Listing> listings = new ArrayList<>();
        if (!page.has("auctions")) return listings;
        for (JsonElement element : page.getAsJsonArray("auctions")) {
            JsonObject auction = element.getAsJsonObject();
            if (!auction.has("bin") || !auction.get("bin").getAsBoolean()) continue;
            if (auction.has("highest_bid_amount") && auction.get("highest_bid_amount").getAsDouble() > 0) continue;
            String uuid = auction.has("uuid") ? auction.get("uuid").getAsString() : "";
            double price = auction.has("starting_bid") ? auction.get("starting_bid").getAsDouble() : 0;
            long start = auction.has("start") ? auction.get("start").getAsLong() : 0;
            long end = auction.has("end") ? auction.get("end").getAsLong() : 0;
            if (uuid.isEmpty() || price <= 0 || start <= 0) continue;
            ItemKeys.Keys keys = keyCache.get(uuid);
            if (keys == null) {
                keys = decode(auction.has("item_bytes") ? auction.get("item_bytes").getAsString() : "");
                if (keys == null) continue;
                keyCache.put(uuid, keys);
            }
            String name = auction.has("item_name") ? auction.get("item_name").getAsString() : "Unknown item";
            String lore = auction.has("item_lore") ? auction.get("item_lore").getAsString() : "";
            listings.add(new Listing(uuid, price, name, rarity(lore), start, end, keys));
        }
        return listings;
    }

    private static List<AuctionFlip> evaluate(List<Listing> candidates, List<Listing> market, long reference, long minProfit) {
        Map<String, List<Listing>> exactIndex = new HashMap<>();
        Map<String, List<Listing>> coreIndex = new HashMap<>();
        for (Listing listing : market) {
            if (exactSales.containsKey(listing.keys().exact())) {
                exactIndex.computeIfAbsent(listing.keys().exact(), key -> new ArrayList<>()).add(listing);
            }
            String core = listing.keys().core();
            if (core != null && coreSales.containsKey(core)) coreIndex.computeIfAbsent(core, key -> new ArrayList<>()).add(listing);
        }
        long now = System.currentTimeMillis();
        List<AuctionFlip> flips = new ArrayList<>();
        for (Listing listing : candidates) {
            long age = reference - listing.start();
            if (age < -60_000 || age > MAX_LISTING_AGE_MS || listing.end() - now < MIN_TIME_LEFT_MS) continue;
            Estimate estimate = estimate(listing, exactIndex, coreIndex);
            if (estimate == null) continue;
            double profit = netProceeds(estimate.target() * RESALE_BUFFER) - listing.price();
            double minRoi = !estimate.exact() ? CORE_MIN_ROI : estimate.sales() == 1 ? SINGLE_SALE_MIN_ROI : EXACT_MIN_ROI;
            if (profit < minProfit || profit / listing.price() < minRoi) continue;
            double confidence = Math.min(1.0, Math.sqrt(estimate.sales() / 3.0)) * (estimate.exact() ? 1.0 : 0.6);
            double score = profit * confidence * (age <= FRESH_MS ? 1.3 : 1.0);
            flips.add(new AuctionFlip(listing.itemName(), listing.rarity(), listing.uuid(), listing.price(), estimate.reference(),
                estimate.sales(), profit, listing.end(), estimate.exact(), Math.max(0, age), score));
        }
        flips.sort(Comparator.comparingDouble(AuctionFlip::score).reversed());
        return flips;
    }

    private static Estimate estimate(Listing listing, Map<String, List<Listing>> exactIndex, Map<String, List<Listing>> coreIndex) {
        List<SaleRecord> exact = exactSales.get(listing.keys().exact());
        if (exact != null && !exact.isEmpty()) {
            double reference = median(exact.subList(Math.max(0, exact.size() - RECENT_SALES_USED), exact.size()));
            double target = reference * (exact.size() >= 3 ? 1.0 : exact.size() == 2 ? 0.97 : 0.94);
            Double rival = cheapestOther(exactIndex.get(listing.keys().exact()), listing.uuid());
            if (rival != null) target = Math.min(target, rival * UNDERCUT);
            return new Estimate(target, reference, exact.size(), true);
        }
        String core = listing.keys().core();
        List<SaleRecord> comps = core == null ? null : coreSales.get(core);
        if (comps == null || comps.size() < 3) return null;
        List<Double> prices = comps.stream().map(SaleRecord::price).sorted().toList();
        double low = prices.get((prices.size() - 1) / 4);
        double high = prices.get((prices.size() - 1) * 3 / 4);
        if (high > low * CORE_MAX_SPREAD) return null;
        double target = low * 0.97;
        Double rival = cheapestOther(coreIndex.get(core), listing.uuid());
        if (rival != null) target = Math.min(target, rival * UNDERCUT);
        return new Estimate(target, median(comps.subList(0, comps.size())), comps.size(), false);
    }

    private static Double cheapestOther(List<Listing> group, String uuid) {
        if (group == null) return null;
        double best = Double.MAX_VALUE;
        for (Listing other : group) if (!other.uuid().equals(uuid) && other.price() < best) best = other.price();
        return best == Double.MAX_VALUE ? null : best;
    }

    private static double median(List<SaleRecord> sales) {
        List<Double> sorted = sales.stream().map(SaleRecord::price).sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }

    /** Auction House listing fee (1%/2%/2.5% by price) plus the 1% claim tax on sales of 1M and up. */
    static double netProceeds(double sale) {
        double listingFee = sale < 10_000_000 ? 0.01 : sale < 100_000_000 ? 0.02 : 0.025;
        double claimTax = sale >= 1_000_000 ? 0.01 : 0;
        return sale * (1.0 - listingFee - claimTax);
    }

    private static String rarity(String lore) {
        String clean = lore.replaceAll("§.", "").toUpperCase(java.util.Locale.ROOT);
        for (String rarity : List.of("VERY SPECIAL", "SUPREME", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON")) {
            if (clean.contains(rarity)) return rarity;
        }
        return "";
    }
}
