package net.plotshop.manager;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a barrel's container contents to build a {@link Barrel} registration.
 *
 * <p>The same approach as StonkCompanion: the barrel's sign is stored as a sign
 * <em>item</em> inside the barrel (NBT {@code BlockEntityTag.front_text.messages}),
 * the goods item is the non-currency item in the barrel, and currency stacks are
 * ignored because the price already encodes the currency type.</p>
 *
 * <p>Sign layout (4 lines): lines 1-2 hold the item name with an optional barrel
 * number ({@code #N} or a bare trailing number) and an optional {@code ======}
 * separator; line 3 is {@code buy for <price>} or {@code exchange for <fee>};
 * line 4 is {@code sell for <price>}. Old layouts with prices on any line are
 * still accepted.</p>
 */
public final class BarrelContentsReader {

    /** Trailing barrel number with a "#" marker, e.g. "#3". */
    private static final Pattern HASH_NUMBER = Pattern.compile("#\\s*(\\d{1,2})\\s*$");
    /** Trailing bare barrel number, only used when a "=" separator is present. */
    private static final Pattern BARE_NUMBER = Pattern.compile("(\\d{1,2})\\s*$");

    /** Parsed shop sign: a label, tier number and optional buy/sell/exchange info. */
    public static final class SignInfo {
        public final String label;
        public final String buy;
        public final String sell;
        /** Exchange fee text ("free" or e.g. "0.5har"), empty when not an exchange. */
        public final String exchange;
        /** Barrel tier number 1..N, or 0 when the sign carries no number. */
        public final int tier;

        SignInfo(String label, String buy, String sell, String exchange, int tier) {
            this.label = label;
            this.buy = buy;
            this.sell = sell;
            this.exchange = exchange;
            this.tier = tier;
        }
    }

    private BarrelContentsReader() {
    }

    /**
     * Read the shop sign from a barrel screen, returning its label, tier and
     * prices. Returns null when the container holds no sign with a recognised
     * price or exchange keyword.
     */
    public static SignInfo readSign(ScreenHandler handler) {
        if (handler == null) {
            return null;
        }
        for (ItemStack stack : containerStacks(handler)) {
            if (!isSignItem(stack)) {
                continue;
            }
            List<String> lines = signLines(stack);
            if (lines.isEmpty()) {
                continue;
            }

            String line0 = lines.get(0) == null ? "" : cleanText(lines.get(0));
            String line1 = lines.size() > 1 ? cleanText(lines.get(1)) : "";

            String buy = "";
            String sell = "";
            String exchange = "";
            for (String line : lines) {
                String clean = cleanText(line);
                String lower = clean.toLowerCase(Locale.ROOT);
                String exchangeFound = extractExchange(clean, lower);
                int bi = lower.indexOf("buy for");
                int si = lower.indexOf("sell for");
                if (exchangeFound != null) {
                    exchange = exchangeFound;
                }
                else if (bi >= 0) {
                    buy = cleanPrice(clean.substring(bi + 7));
                }
                else if (si >= 0) {
                    sell = cleanPrice(clean.substring(si + 8));
                }
            }

            if (buy.isEmpty() && sell.isEmpty() && exchange.isEmpty()) {
                continue;
            }

            // Item name lives on lines 1-2; join them unless a price keyword is on
            // one of them (old single-line-label layout).
            String raw;
            if (hasKeyword(line0) || hasKeyword(line1) || line1.isEmpty()) {
                raw = line0;
            }
            else {
                raw = (line0 + " " + line1).trim();
            }

            String[] labelTier = splitLabel(raw);
            return new SignInfo(labelTier[0], buy, sell, exchange,
                    Integer.parseInt(labelTier[1]));
        }
        return null;
    }

    /**
     * Strip "=" separators and a barrel number from a label. A "#N" marker is
     * always treated as the barrel number; a bare trailing number is only treated
     * as the barrel number when a "=" separator is present, so item names that
     * themselves end in a digit (e.g. "Corrupted Key 5") are kept.
     */
    private static String[] splitLabel(String raw) {
        String s = raw.trim();
        boolean hasSeparator = s.contains("=");
        s = s.replaceAll("=+", " ").replaceAll("\\s+", " ").trim();

        int tier = 0;
        Matcher hash = HASH_NUMBER.matcher(s);
        if (hash.find()) {
            tier = parseTier(hash.group(1));
            if (tier > 0) {
                s = s.substring(0, hash.start()).trim();
            }
        }
        else if (hasSeparator) {
            Matcher bare = BARE_NUMBER.matcher(s);
            if (bare.find()) {
                tier = parseTier(bare.group(1));
                if (tier > 0) {
                    s = s.substring(0, bare.start()).trim();
                }
            }
        }

        s = s.replaceAll("\\s+", " ").trim();
        return new String[] { s, String.valueOf(tier) };
    }

    private static int parseTier(String digits) {
        try {
            int tier = Integer.parseInt(digits);
            return tier > 0 && tier < 100 ? tier : 0;
        }
        catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean hasKeyword(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("buy for") || lower.contains("sell for")
                || lower.contains("exchange for") || lower.contains("exch for")
                || lower.contains("swap for");
    }

    /** Extract an exchange fee from "exchange for X" / "exch for X" / "swap for X". */
    private static String extractExchange(String clean, String lower) {
        int ei = lower.indexOf("exchange for");
        if (ei >= 0) {
            return cleanPrice(clean.substring(ei + 12));
        }
        ei = lower.indexOf("exch for");
        if (ei >= 0) {
            return cleanPrice(clean.substring(ei + 8));
        }
        ei = lower.indexOf("swap for");
        if (ei >= 0) {
            return cleanPrice(clean.substring(ei + 8));
        }
        return null;
    }

    /**
     * Read every non-sign item in the container as a name -> count map. Currency
     * stacks are included so the caller can classify them. Sign items are skipped.
     */
    public static Map<String, Integer> readContents(ScreenHandler handler) {
        Map<String, Integer> contents = new HashMap<>();
        if (handler == null) {
            return contents;
        }
        for (ItemStack stack : containerStacks(handler)) {
            if (stack.isEmpty() || isSignItem(stack)) {
                continue;
            }
            String name = itemDisplayName(stack);
            if (name.isEmpty()) {
                continue;
            }
            contents.merge(name, stack.getCount(), Integer::sum);
        }
        return contents;
    }

    private static List<ItemStack> containerStacks(ScreenHandler handler) {
        List<ItemStack> containerStacks = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot != null && slot.inventory != null && !(slot.inventory instanceof PlayerInventory)) {
                containerStacks.add(slot.getStack());
            }
        }
        return containerStacks;
    }

    /**
     * Parse the currently open barrel screen into a registration. Returns null when
     * no sign with a buy/sell price could be found (or nothing identifiable).
     */
    public static Barrel read(ScreenHandler handler, BlockPos pos, String world) {
        if (handler == null || pos == null || world == null) {
            return null;
        }

        List<ItemStack> containerStacks = containerStacks(handler);

        SignInfo sign = readSign(handler);
        if (sign == null) {
            return null;
        }
        String label = sign.label;
        String buy = sign.buy;
        String sell = sign.sell;

        // The goods item is the non-currency, non-sign item with the highest total
        // count. Empty slots are skipped so a currency-only barrel does not
        // register the empty slot as the item "Air".
        Map<String, Integer> goods = new HashMap<>();
        for (ItemStack stack : containerStacks) {
            if (stack.isEmpty() || isSignItem(stack)) {
                continue;
            }
            String name = itemDisplayName(stack);
            if (name.isEmpty()) {
                continue;
            }
            if (CurrencyMapper.classify(name) != null) {
                continue;
            }
            goods.merge(name, stack.getCount(), Integer::sum);
        }

        String item = "";
        int best = -1;
        for (Map.Entry<String, Integer> entry : goods.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                item = entry.getKey();
            }
        }

        String signName = label == null ? "" : label.trim();
        // No goods inside: fall back to the sign name, resolving known aliases.
        if (item.isEmpty()) {
            item = AliasStore.get().resolve(signName);
        }
        if (item.isEmpty()) {
            return null;
        }

        // Determine the shop type from the sign and the dominant goods item.
        String type;
        if (!sign.exchange.isEmpty()) {
            type = "custom";
        }
        else if (RareFragIndex.get().fragOf(AliasStore.get().resolve(item)) != null) {
            type = "rare";
        }
        else {
            type = "exclusive";
        }

        Barrel barrel = new Barrel(world, pos.getX(), pos.getY(), pos.getZ(), item, buy, sell);
        barrel.type = type;
        barrel.exchangeFee = sign.exchange;
        barrel.tier = sign.tier;
        // Remember the sign alias and learn the association for future lookups.
        if (!signName.isEmpty() && !signName.equalsIgnoreCase(item)) {
            barrel.alias = signName;
            AliasStore.get().put(signName, item);
        }
        return barrel;
    }

    public static boolean isSignItem(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains("BlockEntityTag")) {
            return false;
        }
        return stack.getItem().getTranslationKey().endsWith("sign");
    }

    public static List<String> signLines(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains("BlockEntityTag")) {
            return new ArrayList<>();
        }
        NbtCompound bet = nbt.getCompound("BlockEntityTag");
        List<String> front = readSide(bet, "front_text");
        if (hasPricing(front)) {
            return front;
        }
        List<String> back = readSide(bet, "back_text");
        if (hasPricing(back)) {
            return back;
        }
        return !front.isEmpty() ? front : back;
    }

    private static List<String> readSide(NbtCompound bet, String side) {
        List<String> out = new ArrayList<>();
        if (!bet.contains(side)) {
            return out;
        }
        NbtCompound text = bet.getCompound(side);
        if (!text.contains("messages")) {
            return out;
        }
        NbtList messages = text.getList("messages", NbtElement.STRING_TYPE);
        for (int i = 0; i < messages.size(); i++) {
            out.add(plain(messages.get(i).asString()));
        }
        return out;
    }

    private static String plain(String json) {
        try {
            return Text.Serialization.fromJson(json).getString();
        }
        catch (Exception ignored) {
            return json;
        }
    }

    private static boolean hasPricing(List<String> lines) {
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("buy for") || lower.contains("sell for")) {
                return true;
            }
        }
        return false;
    }

    private static String cleanText(String s) {
        return s == null ? "" : s.replaceAll("§.", "").trim();
    }

    private static String cleanPrice(String s) {
        String t = cleanText(s);
        // Strip trailing punctuation the shop owner may have typed after the price.
        return t.replaceAll("[.!。，,]+$", "").trim();
    }

    public static String itemDisplayName(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt != null && nbt.contains("Monumenta") && nbt.contains("plain")) {
            NbtCompound plain = nbt.getCompound("plain");
            if (plain.contains("display")) {
                NbtCompound display = plain.getCompound("display");
                if (display.contains("Name")) {
                    String name = cleanText(display.getString("Name"));
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
        }
        return cleanText(stack.getName().getString());
    }
}
