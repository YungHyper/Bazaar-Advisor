package com.example.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** Builds item identity keys from decoded Hypixel item NBT: an exact key and a coarser value-driving "core" key. */
final class ItemKeys {
    /** {@code core} is null when the item has nothing beyond its id that drives price. */
    record Keys(String exact, String core) {}

    private static final Set<String> NOISE = Set.of(
        "uuid", "uid", "timestamp", "auction_id", "auction_uuid", "seller_uuid", "owner_uuid", "profile_id",
        "instance_id", "creation_time", "created_at", "last_updated", "origintag", "spawnedfor", "blocks_walked",
        "mined_crops", "farmed_cultivating", "champion_combat_xp", "compact_blocks", "collected_coins",
        "drill_fuel", "pickonimbus_durability", "player_deaths", "stats_book", "sack_pss", "boss_tier");

    private static final List<String> CORE_FIELDS = List.of(
        "modifier", "upgrade_level", "dungeon_item_level", "rarity_upgrades", "skin", "dye_item",
        "talisman_enrichment", "power_ability_scroll", "art_of_war_count", "wood_singularity_count",
        "farming_for_dummies_count", "tuned_transmission", "ethermerge", "jalapeno_count", "polarvoid",
        "baseStatBoostPercentage", "hot_potato_count");

    // Cumulative per-level pet XP steps (Common starts at index 0; each higher rarity skips ahead).
    private static final int[] PET_XP = {
        100, 110, 120, 130, 145, 160, 175, 190, 210, 230, 250, 275, 300, 330, 360, 400, 440, 490, 540, 600, 660, 730,
        800, 880, 960, 1050, 1150, 1260, 1380, 1510, 1650, 1800, 1960, 2130, 2310, 2500, 2700, 2920, 3160, 3420, 3700,
        4000, 4350, 4750, 5200, 5700, 6300, 7000, 7800, 8700, 9700, 10800, 12000, 13300, 14700, 16200, 17800, 19500,
        21300, 23200, 25200, 27400, 29800, 32400, 35200, 38200, 41400, 44800, 48400, 52200, 56200, 60400, 64800,
        69400, 74200, 79200, 84700, 90700, 97200, 104200, 111700, 119700, 128200, 137200, 146700, 156700, 167700,
        179700, 192700, 206700, 221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700, 411700,
        441700, 476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700, 1016700, 1101700, 1191700,
        1286700, 1386700, 1496700, 1616700, 1746700, 1886700};
    private static final Map<String, Integer> PET_OFFSET = Map.of(
        "COMMON", 0, "UNCOMMON", 6, "RARE", 11, "EPIC", 16, "LEGENDARY", 20, "MYTHIC", 20);

    private ItemKeys() {}

    static Keys of(CompoundTag root) {
        ListTag items = root.getListOrEmpty("i");
        if (items.isEmpty()) return null;
        CompoundTag tag = items.get(0).asCompound().orElse(null);
        if (tag == null) return null;
        CompoundTag attributes = tag.getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes");
        String id = attributes.getStringOr("id", "");
        if (id.isEmpty()) {
            CompoundTag plain = tag.copy();
            strip(plain);
            return new Keys(hash(canonical(plain)), null);
        }
        if (id.equals("PET")) return petKeys(attributes);
        CompoundTag copy = attributes.copy();
        strip(copy);
        String core = id.equals("ENCHANTED_BOOK") ? null : coreKey(id, attributes);
        return new Keys(hash(canonical(copy)), core == null ? null : hash(core));
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Keys petKeys(CompoundTag attributes) {
        JsonObject pet;
        try {
            pet = JsonParser.parseString(attributes.getStringOr("petInfo", "{}")).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        String type = text(pet, "type");
        String tier = text(pet, "tier");
        String held = text(pet, "heldItem");
        String skin = text(pet, "skin");
        double exp = pet.has("exp") ? pet.get("exp").getAsDouble() : 0;
        boolean candy = pet.has("candyUsed") && pet.get("candyUsed").getAsInt() > 0;
        int level = petLevel(tier, exp);
        String base = "PET|" + type + "|" + tier + "|" + held + "|" + skin;
        return new Keys(hash(base + "|L" + level + "|c" + (candy ? 1 : 0)), hash(base + "|B" + levelBand(level)));
    }

    static int petLevel(String tier, double exp) {
        int offset = PET_OFFSET.getOrDefault(tier, 0);
        double remaining = exp;
        int level = 1;
        for (int i = offset; i < offset + 99 && i < PET_XP.length; i++) {
            if (remaining < PET_XP[i]) break;
            remaining -= PET_XP[i];
            level++;
        }
        return level;
    }

    private static String levelBand(int level) {
        if (level <= 1) return "1";
        if (level < 10) return "2-9";
        if (level < 25) return "10-24";
        if (level < 50) return "25-49";
        if (level < 75) return "50-74";
        if (level < 90) return "75-89";
        if (level < 100) return "90-99";
        return "100";
    }

    private static String coreKey(String id, CompoundTag attributes) {
        StringBuilder key = new StringBuilder(id);
        int base = key.length();
        for (String field : CORE_FIELDS) {
            String value = scalar(attributes, field);
            if (value == null) continue;
            if (field.equals("hot_potato_count")) value = potatoBucket(value);
            else if (field.equals("baseStatBoostPercentage")) value = String.valueOf(number(value) / 10 * 10);
            key.append('|').append(field).append('=').append(value);
        }
        CompoundTag enchants = attributes.getCompoundOrEmpty("enchantments");
        if (!enchants.isEmpty()) {
            String ultimate = enchants.keySet().stream().filter(name -> name.startsWith("ultimate_")).sorted()
                .map(name -> name + ":" + scalar(enchants, name)).collect(Collectors.joining(","));
            if (!ultimate.isEmpty()) key.append("|ult=").append(ultimate);
            key.append("|ec=").append(enchants.size() / 5);
        }
        CompoundTag gems = attributes.getCompoundOrEmpty("gems");
        if (!gems.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String slot : gems.keySet()) {
                if (slot.equals("unlocked_slots") || slot.endsWith("_gem")) continue;
                Tag value = gems.get(slot);
                String quality = value.asString().orElseGet(() -> value.asCompound().map(c -> c.getStringOr("quality", "")).orElse(""));
                int cut = slot.lastIndexOf('_');
                parts.add((cut > 0 ? slot.substring(0, cut) : slot) + ":" + quality);
            }
            parts.sort(null);
            if (!parts.isEmpty()) key.append("|gems=").append(String.join(",", parts));
        }
        ListTag scrolls = attributes.getListOrEmpty("ability_scroll");
        if (!scrolls.isEmpty()) key.append("|scr=").append(canonical(scrolls));
        return key.length() == base ? null : key.toString();
    }

    private static String potatoBucket(String value) {
        int count = number(value);
        return count <= 0 ? "0" : count <= 10 ? "hpb" : "fuming";
    }

    private static int number(String value) {
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String scalar(CompoundTag compound, String key) {
        Tag tag = compound.get(key);
        if (tag == null) return null;
        return tag.asNumber().map(Number::toString).or(tag::asString).orElse(null);
    }

    private static String text(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static boolean isNoise(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return NOISE.contains(lower) || lower.endsWith("_kills") || lower.endsWith("_xp") || lower.endsWith("_runs");
    }

    private static void strip(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            for (String key : new ArrayList<>(compound.keySet())) {
                if (isNoise(key)) compound.remove(key);
                else strip(compound.get(key));
            }
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) strip(child);
        }
    }

    private static String canonical(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return compound.keySet().stream().sorted()
                .map(key -> key + ":" + canonical(compound.get(key)))
                .collect(Collectors.joining(",", "{", "}"));
        }
        if (tag instanceof ListTag list) {
            return list.stream().map(ItemKeys::canonical).sorted().collect(Collectors.joining(",", "[", "]"));
        }
        return tag.toString();
    }
}
