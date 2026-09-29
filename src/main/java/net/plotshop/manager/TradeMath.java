package net.plotshop.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure math helpers for the customer-side trade features: price parsing,
 * net-change classification, mistrade validation and fair-price estimation.
 *
 * <p>Reimplemented from StonkCompanion's {@code convertToBaseUnit},
 * {@code detectFairPrice} and {@code validateTransaction} so this mod has no
 * dependency on StonkCompanion. Currency values are normalised to
 * "compressed" units: one concentrated experience, one compressed crystalline
 * shard or one archos ring.</p>
 */
public final class TradeMath {

    public static final double EPS = 1e-6;

    private TradeMath() {
    }

    /** A parsed sign price, expressed in compressed currency units. */
    public static final class Price {
        public final String type;       // "xp" | "cs" | "ar"
        public final double compressed; // amount in compressed units
        public final String raw;        // original sign text

        Price(String type, double compressed, String raw) {
            this.type = type;
            this.compressed = compressed;
            this.raw = raw;
        }
    }

    /** Net movement of goods and currency over a barrel session. */
    public static final class NetChange {
        public double mats;                                  // net goods (sign excluded), +in/-out
        public final Map<String, Double> currency = new HashMap<>(); // type -> compressed delta

        public boolean isEmpty() {
            if (Math.abs(mats) > EPS) {
                return false;
            }
            for (double v : currency.values()) {
                if (Math.abs(v) > EPS) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Result of validating a session's net change against the sign prices. */
    public static final class Validation {
        public boolean valid;
        public String message;    // chat feedback
        public String logDetail;  // one line for the behaviour log
        public boolean traded;    // an actual goods movement happened

        Validation(boolean valid, String message, String logDetail, boolean traded) {
            this.valid = valid;
            this.message = message;
            this.logDetail = logDetail;
            this.traded = traded;
        }
    }

    /** Result of the fair-price estimate. */
    public static final class FairPrice {
        public final double compressed;
        public final String message;
        public final int direction; // -1 look lower, 0 in range, 1 look higher

        FairPrice(double compressed, String message, int direction) {
            this.compressed = compressed;
            this.message = message;
            this.direction = direction;
        }
    }

    /**
     * Parse a sign price such as "10xp", "2hcs", "5car", "1har" or "3.5cxp"
     * into compressed units. Returns null when the text contains no number and
     * no recognised currency suffix.
     */
    public static Price parsePrice(String text) {
        if (text == null) {
            return null;
        }
        String s = text.toLowerCase(Locale.ROOT).trim();
        if (s.contains("per")) {
            s = s.replace("per", "").trim();
        }

        String number = "";
        String currency = "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                number += c;
            }
            else {
                currency = s.substring(i).trim();
                break;
            }
        }

        String type = currencyTypeOf(currency);
        if (type == null || number.isEmpty()) {
            return null;
        }

        double amount;
        try {
            amount = Double.parseDouble(number);
        }
        catch (NumberFormatException e) {
            return null;
        }

        double compressed;
        if (currency.equals("har") || currency.equals("hxp") || currency.equals("hcs")) {
            compressed = amount * 64.0;
        }
        else if (currency.equals("xp") || currency.equals("cs")) {
            compressed = amount / 8.0;
        }
        else {
            // "cxp" / "ccs" / "car" / "ar" are already compressed units.
            compressed = amount;
        }
        return new Price(type, compressed, text.trim());
    }

    /** Map a bare currency suffix to a currency type, or null if unknown. */
    public static String currencyTypeOf(String suffix) {
        String s = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT).trim();
        if (s.endsWith("ar")) {
            return "ar";
        }
        if (s.endsWith("cs")) {
            return "cs";
        }
        if (s.endsWith("xp")) {
            return "xp";
        }
        return null;
    }

    /** True when a barrel label marks it as a "64x"/"stack" bulk barrel. */
    public static boolean isStackBarrel(String label) {
        if (label == null) {
            return false;
        }
        String s = label.toLowerCase(Locale.ROOT);
        return s.startsWith("64x") || s.contains("stack");
    }

    /**
     * Classify a per-item net change into goods and per-type currency deltas.
     * Sign items must already be excluded by the caller.
     */
    public static NetChange classify(Map<String, Integer> deltas, boolean stackBarrel) {
        NetChange net = new NetChange();
        for (Map.Entry<String, Integer> e : deltas.entrySet()) {
            CurrencyMapper.Currency c = CurrencyMapper.classify(e.getKey());
            if (c != null) {
                net.currency.merge(c.type, e.getValue() * c.multiplier, Double::sum);
            }
            else {
                net.mats += e.getValue();
            }
        }
        if (stackBarrel) {
            net.mats /= 64.0;
        }
        return net;
    }

    /**
     * Validate a session against the barrel's sign. {@code buy} is the price the
     * barrel pays to the customer (customer takes goods, pays currency);
     * {@code sell} is what the customer pays to stock the barrel.
     */
    public static Validation validate(NetChange net, String label, Price buy, Price sell) {
        boolean stackBarrel = isStackBarrel(label);

        if (net.isEmpty()) {
            return new Validation(true, null, "无物品/货币变动", false);
        }
        if (Math.abs(net.mats) < EPS) {
            return new Validation(true, null, "仅货币变动（未涉及物品）", false);
        }

        boolean buying = net.mats < 0; // customer took goods -> barrel buys
        Price price = buying ? buy : sell;
        String side = buying ? "buy" : "sell";

        if (price == null || price.compressed <= EPS) {
            return new Validation(false,
                    "该桶未标 " + side + " 价，无法核销",
                    "未标价(" + side + ")，mats=" + net.mats,
                    true);
        }

        double expectedDelta = Math.abs(net.mats) * price.compressed;
        if (!buying) {
            expectedDelta = -expectedDelta; // customer should take currency out
        }
        double actualDelta = net.currency.getOrDefault(price.type, 0.0);

        double wrongCurrency = 0.0;
        for (Map.Entry<String, Double> e : net.currency.entrySet()) {
            if (!e.getKey().equals(price.type)) {
                wrongCurrency += Math.abs(e.getValue());
            }
        }

        double delta = expectedDelta - actualDelta;
        StringBuilder detail = new StringBuilder()
                .append("mats=").append(round2(net.mats))
                .append(" side=").append(side)
                .append(" price=").append(round2(price.compressed))
                .append(" expected=").append(round2(expectedDelta))
                .append(" actual=").append(round2(actualDelta));

        if (wrongCurrency > EPS) {
            return new Validation(false,
                    "检测到错误货币类型（桶用 " + price.type + "），请人工处理",
                    detail.append(" wrongCurrency=").append(round2(wrongCurrency)).toString(),
                    true);
        }

        if (Math.abs(delta) < EPS) {
            return new Validation(true,
                    null,
                    detail.append(" ok").toString(),
                    true);
        }

        String fix = formatCurrency(Math.abs(delta), price.type);
        if (delta > EPS) {
            return new Validation(false,
                    "交易有误：还需放入 " + fix + " " + price.type,
                    detail.append(" 欠=").append(round2(delta)).toString(),
                    true);
        }
        return new Validation(false,
                "交易有误：多出了 " + fix + " " + price.type + "（应退还）",
                detail.append(" 溢=").append(round2(-delta)).toString(),
                true);
    }

    /**
     * Estimate a fair price from the barrel's current contents and its bid/ask
     * spread, following StonkCompanion's interpolation. Returns null when there
     * is no usable price information.
     */
    public static FairPrice fairPrice(Map<String, Integer> contents, String label, Price buy, Price sell) {
        Price bid = buy != null ? buy : sell;
        Price ask = sell != null ? sell : buy;
        if (bid == null || ask == null) {
            return null;
        }

        double barrelCurrency = 0;
        double barrelMats = 0;
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            CurrencyMapper.Currency c = CurrencyMapper.classify(e.getKey());
            if (c != null) {
                barrelCurrency += e.getValue() * c.multiplier;
            }
            else {
                barrelMats += e.getValue();
            }
        }
        if (isStackBarrel(label)) {
            barrelMats /= 64.0;
        }

        double spread = ask.compressed - bid.compressed;
        double mid = bid.compressed + spread / 2.0;
        double matsInCurrency = mid > EPS ? barrelCurrency / mid : 0;
        double effectiveMats = barrelMats + matsInCurrency;
        if (effectiveMats <= EPS) {
            return null;
        }

        double demandModifier = matsInCurrency / effectiveMats;
        double interpolated = bid.compressed + demandModifier * spread;

        int direction = 0;
        String message;
        if (barrelCurrency < bid.compressed || demandModifier <= 0.005) {
            direction = -1;
            message = "看更低的桶";
        }
        else if (demandModifier >= 0.995) {
            direction = 1;
            message = "看更高的桶";
        }
        else {
            message = formatCurrency(interpolated, bid.type);
        }

        return new FairPrice(interpolated, message, direction);
    }

    /**
     * Format a compressed currency amount as a readable string, e.g.
     * "1hxp+2cxp", "3ccs" or "12xp" (base tier).
     */
    public static String formatCurrency(double compressed, String type) {
        boolean neg = compressed < 0;
        double abs = Math.abs(compressed);
        int hyper = (int) Math.floor(abs / 64.0);
        double rest = abs - hyper * 64.0;

        List<String> parts = new ArrayList<>();
        if (hyper > 0) {
            parts.add(hyper + hyperName(type));
        }
        if ("ar".equals(type)) {
            if (rest > EPS) {
                parts.add(round2(rest) + "ar");
            }
        }
        else {
            double base = rest * 8.0;
            if (base >= 1.0 - EPS) {
                if (Math.abs(base - Math.round(base)) < EPS) {
                    parts.add(Math.round(base) + baseName(type));
                }
                else {
                    parts.add(round2(base) + baseName(type));
                }
            }
            else if (rest > EPS) {
                parts.add(round2(rest) + compressedName(type));
            }
        }

        if (parts.isEmpty()) {
            parts.add("0" + ("ar".equals(type) ? "ar" : compressedName(type)));
        }
        return (neg ? "-" : "") + String.join("+", parts);
    }

    private static String hyperName(String type) {
        switch (type) {
            case "xp": return "hxp";
            case "cs": return "hcs";
            case "ar": return "har";
            default: return type;
        }
    }

    private static String compressedName(String type) {
        switch (type) {
            case "xp": return "cxp";
            case "cs": return "ccs";
            case "ar": return "ar";
            default: return type;
        }
    }

    private static String baseName(String type) {
        switch (type) {
            case "xp": return "xp";
            case "cs": return "cs";
            default: return type;
        }
    }

    private static String round2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
