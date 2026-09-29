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

/**
 * Reads a barrel's container contents to build a {@link Barrel} registration.
 *
 * <p>The same approach as StonkCompanion: the barrel's sign is stored as a sign
 * <em>item</em> inside the barrel (NBT {@code BlockEntityTag.front_text.messages}),
 * the goods item is the non-currency item in the barrel, and currency stacks are
 * ignored because the price already encodes the currency type.</p>
 */
public final class BarrelContentsReader {

    /** Parsed shop sign: a label plus the optional buy/sell prices. */
    public static final class SignInfo {
        public final String label;
        public final String buy;
        public final String sell;

        SignInfo(String label, String buy, String sell) {
            this.label = label;
            this.buy = buy;
            this.sell = sell;
        }
    }

    private BarrelContentsReader() {
    }

    /**
     * Read the shop sign from a barrel screen, returning its label and prices.
     * Returns null when the container holds no sign with a buy/sell price.
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
            String label = lines.get(0).isBlank() ? "" : cleanText(lines.get(0));
            String buy = "";
            String sell = "";
            for (String line : lines) {
                String clean = cleanText(line);
                String lower = clean.toLowerCase(Locale.ROOT);
                int bi = lower.indexOf("buy for");
                int si = lower.indexOf("sell for");
                if (bi >= 0) {
                    buy = cleanPrice(clean.substring(bi + 7));
                }
                else if (si >= 0) {
                    sell = cleanPrice(clean.substring(si + 8));
                }
            }
            if (!buy.isEmpty() || !sell.isEmpty()) {
                return new SignInfo(label, buy, sell);
            }
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
            if (isSignItem(stack)) {
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

        // The goods item is the non-currency, non-sign item with the highest total count.
        Map<String, Integer> goods = new HashMap<>();
        for (ItemStack stack : containerStacks) {
            if (isSignItem(stack)) {
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
        if (item.isEmpty()) {
            item = label;
        }
        if (item.isEmpty()) {
            return null;
        }

        return new Barrel(world, pos.getX(), pos.getY(), pos.getZ(), item, buy, sell);
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
