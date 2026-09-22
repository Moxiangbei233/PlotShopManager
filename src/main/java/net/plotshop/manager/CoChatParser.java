package net.plotshop.manager;

import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses CoreProtect "/co lookup a:container" chat records.
 *
 * <p>The visible line looks like:</p>
 * <pre>2.00 hours ago + Steve added x5 experience_bottle.</pre>
 * where the absolute timestamp ("yyyy-MM-dd HH:mm:ss z") lives in the time-ago
 * component's hover, and the Monumenta display name lives in the item component's
 * hover (extracted from its "literal{...}" representation, same approach as
 * StonkCompanion).
 */
public final class CoChatParser {

    // "[+/-] player added|removed xN itemname"
    private static final Pattern RECORD_PATTERN =
            Pattern.compile("([+\\-])\\s+(\\S+)\\s+(added|removed)\\s+x(\\d+)\\s+([^.]*)");

    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?: [A-Za-z]{2,5})?)");

    private CoChatParser() {
    }

    /**
     * Attempt to parse a CoreProtect container record. Returns null when the message
     * is not a container record (header, page navigation, rolled back, etc.).
     */
    public static TransactionRecord parse(Text message, BlockPos barrel, String world) {
        if (message == null || barrel == null) {
            return null;
        }

        // Same detection heuristics as StonkCompanion (relies on legacy § codes being
        // preserved inside the component literals by CoreProtect's Spigot sender).
        String raw = message.toString();
        boolean hasTag = raw.contains("§c-") || raw.contains("§a+");
        boolean hasVerb = raw.contains("added") || raw.contains("removed");
        if (!hasTag || !hasVerb || raw.contains("-----") || raw.contains("§m")) {
            return null;
        }

        String clean = message.getString().replaceAll("§.", "");
        Matcher m = RECORD_PATTERN.matcher(clean);
        if (!m.find()) {
            return null;
        }

        String sign = m.group(1);
        String player = m.group(2).trim();
        String verb = m.group(3);
        int amount;
        try {
            amount = Integer.parseInt(m.group(4));
        }
        catch (NumberFormatException e) {
            return null;
        }
        String baseName = m.group(5).trim();

        boolean deposited = verb.equals("added");

        List<Text> hovers = new ArrayList<>();
        collectHovers(message, hovers);

        String timestamp = "";
        String itemName = null;
        String itemRepr = null;
        for (Text hover : hovers) {
            String repr = hover.toString();
            Matcher tm = TIMESTAMP_PATTERN.matcher(repr);
            if (tm.find()) {
                if (timestamp.isEmpty()) {
                    timestamp = tm.group(1);
                }
                continue;
            }
            if (itemName == null && repr.startsWith("literal{")) {
                itemName = extractLiteral(repr);
                itemRepr = repr;
            }
        }

        if (itemName == null || itemName.isBlank()) {
            itemName = baseName;
        }
        else {
            itemName = applyMonumentaFixes(itemName, itemRepr);
        }

        TransactionRecord record = new TransactionRecord();
        record.timestampText = timestamp;
        record.player = player;
        record.itemName = itemName.trim();
        record.material = baseName;
        record.amount = amount;
        record.deposited = deposited;
        record.barrelX = barrel.getX();
        record.barrelY = barrel.getY();
        record.barrelZ = barrel.getZ();
        record.world = world;

        CurrencyMapper.Currency currency = CurrencyMapper.classify(record.itemName);
        if (currency != null) {
            record.currencyType = currency.type;
            record.currencyTier = currency.tier;
        }

        return record;
    }

    private static void collectHovers(Text node, List<Text> out) {
        HoverEvent hover = node.getStyle().getHoverEvent();
        if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
            Text value = hover.getValue(HoverEvent.Action.SHOW_TEXT);
            if (value != null) {
                out.add(value);
            }
        }
        for (Text sibling : node.getSiblings()) {
            collectHovers(sibling, out);
        }
    }

    private static String extractLiteral(String repr) {
        int start = repr.indexOf("literal{");
        if (start < 0) {
            return "";
        }
        String s = repr.substring(start + "literal{".length());
        int end = s.indexOf('}');
        if (end < 0) {
            end = s.length();
        }
        return s.substring(0, end).replaceAll("§.", "").trim();
    }

    /**
     * Monumenta-specific hover quirks, copied from StonkCompanion's proven parser.
     */
    private static String applyMonumentaFixes(String name, String hoverRepr) {
        if (hoverRepr == null) {
            return name;
        }

        // The item's first literal is just "I"; the real name lives in a styled sibling.
        if (name.equals("I") && hoverRepr.contains("nversion Aegis")) {
            return "Inversion Aegis";
        }

        // Tesseract of Knowledge (u) embeds the stored anvil count in another literal.
        if (name.equals("Tesseract of Knowledge (u)") && hoverRepr.contains("literal{Stored anvils: }")) {
            String marker = "literal{Stored anvils: }";
            int idx = hoverRepr.indexOf(marker);
            int end = hoverRepr.indexOf('}', idx + marker.length());
            if (idx >= 0 && end > idx) {
                String anvils = hoverRepr.substring(idx + marker.length(), end);
                return "Tesseract of Knowledge (u) (Contains: " + anvils + " Anvils)";
            }
        }

        return name;
    }
}
