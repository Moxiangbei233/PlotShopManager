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
        public double correctMats;                           // goods belonging to the barrel's group
        public double wrongMats;                             // goods NOT in the barrel's group
        public final Map<String, Double> currency = new HashMap<>(); // type -> compressed delta

        public boolean isEmpty() {
            if (Math.abs(mats) > EPS || Math.abs(correctMats) > EPS || Math.abs(wrongMats) > EPS) {
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

    /**
     * Which items count as "correct goods" for a barrel. {@code type} is one of
     * {@code exclusive} (one item), {@code rare} (a fragment plus its rares) or
     * {@code custom} (exchange, same matching as exclusive). A null filter means
     * "unknown/lenient": every non-currency item counts as correct goods.
     */
    public static final class GoodsFilter {
        public final String type;
        public final String expected; // item name (exclusive/custom) or frag group (rare)

        public GoodsFilter(String type, String expected) {
            this.type = type;
            this.expected = expected;
        }

        public boolean matches(String itemName) {
            if (expected == null || expected.isEmpty() || itemName == null) {
                return false;
            }
            // Resolve sign aliases ("white mat" -> "Soul Essence") on both sides.
            String exp = AliasStore.get().resolve(expected);
            String name = AliasStore.get().resolve(itemName);
            if ("exclusive".equals(type)) {
                // Strict single-item matching, even when the item is a fragment.
                return RareFragIndex.baseName(name)
                        .equals(RareFragIndex.baseName(exp));
            }
            // "rare" and "custom" shops accept a whole frag group when the
            // expected item is a fragment or a rare of some group.
            String group = RareFragIndex.get().fragOf(exp);
            if (group != null) {
                return group.equals(RareFragIndex.get().fragOf(name));
            }
            return RareFragIndex.baseName(name)
                    .equals(RareFragIndex.baseName(exp));
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
     *
     * <p>With a non-null {@link GoodsFilter}, non-currency items are split into
     * {@code correctMats} (items the barrel accepts) and {@code wrongMats}
     * (everything else). With a null filter (unknown/lenient) all non-currency
     * items count as correct goods.</p>
     */
    public static NetChange classify(Map<String, Integer> deltas, boolean stackBarrel, GoodsFilter filter) {
        NetChange net = new NetChange();
        for (Map.Entry<String, Integer> e : deltas.entrySet()) {
            CurrencyMapper.Currency c = CurrencyMapper.classify(e.getKey());
            if (c != null) {
                net.currency.merge(c.type, e.getValue() * c.multiplier, Double::sum);
                continue;
            }
            int amount = e.getValue();
            net.mats += amount;
            if (filter == null || filter.matches(e.getKey())) {
                net.correctMats += amount;
            }
            else {
                net.wrongMats += amount;
            }
        }
        if (stackBarrel) {
            net.mats /= 64.0;
            net.correctMats /= 64.0;
            net.wrongMats /= 64.0;
        }
        return net;
    }

    /**
     * Validate a session against the barrel's sign. {@code buy} is the price the
     * barrel pays to the customer (customer takes goods, pays currency);
     * {@code sell} is what the customer pays to stock the barrel.
     *
     * <p>{@code filter} describes which items the barrel accepts (null = lenient,
     * any goods count). {@code exchangeFee} is non-null only for "custom" shops:
     * a flat fee (or 0 for a free exchange) the customer must leave.</p>
     */
    public static Validation validate(NetChange net, String label, Price buy, Price sell,
                                      GoodsFilter filter, Price exchangeFee) {
        if (net.isEmpty()) {
            return new Validation(true, null, "无物品/货币变动", false);
        }
        if (filter != null && "custom".equals(filter.type)) {
            return validateExchange(net, filter, exchangeFee);
        }

        boolean wrongGoods = Math.abs(net.wrongMats) > EPS;
        double goods = net.correctMats;

        if (Math.abs(goods) < EPS) {
            if (wrongGoods) {
                return new Validation(false,
                        "交易有误：放入了错误的物品" + acceptSuffix(filter),
                        "错误物品=" + round2(net.wrongMats), true);
            }
            return new Validation(true, null, "仅货币变动（未涉及物品）", false);
        }

        // goods < 0 means the customer took goods out: that is a purchase, so the
        // customer owes the barrel's sell (ask) price. goods > 0 means the customer
        // stocked goods, so the barrel owes the customer the buy (bid) price.
        boolean bought = goods < 0;
        Price price = bought ? sell : buy;
        String side = bought ? "sell" : "buy";

        if (price == null || price.compressed <= EPS) {
            return new Validation(false,
                    "该桶未标 " + side + " 价，无法核销",
                    "未标价(" + side + ")，goods=" + round2(goods),
                    true);
        }

        double expectedDelta = Math.abs(goods) * price.compressed;
        if (!bought) {
            expectedDelta = -expectedDelta; // customer should take currency out
        }
        double actualDelta = net.currency.getOrDefault(price.type, 0.0);

        double wrongCurrency = 0.0;
        for (Map.Entry<String, Double> e : net.currency.entrySet()) {
            if (!e.getKey().equals(price.type)) {
                wrongCurrency += Math.abs(e.getValue());
            }
        }

        // Tolerance matching StonkCompanion's 0.0005 bound check.
        double delta = expectedDelta - actualDelta;
        if (Math.abs(delta) < 0.0005) {
            delta = 0;
        }

        StringBuilder detail = new StringBuilder()
                .append("goods=").append(round2(goods))
                .append(" side=").append(side)
                .append(" price=").append(round2(price.compressed))
                .append(" expected=").append(round2(expectedDelta))
                .append(" actual=").append(round2(actualDelta));
        if (wrongGoods) {
            detail.append(" wrongItem=").append(round2(net.wrongMats));
        }
        if (wrongCurrency > EPS) {
            detail.append(" wrongCurrency=").append(round2(wrongCurrency));
        }

        boolean deltaOk = Math.abs(delta) < EPS;
        boolean wrongCur = wrongCurrency > EPS;

        if (!wrongGoods && !wrongCur && deltaOk) {
            return new Validation(true, null, detail.append(" ok").toString(), true);
        }

        List<String> issues = new ArrayList<>();
        if (wrongGoods) {
            issues.add("放入了错误的物品" + acceptSuffix(filter));
        }
        if (!deltaOk) {
            String fix = formatCurrency(Math.abs(delta), price.type);
            issues.add(delta > EPS ? "还需放入 " + fix : "应退还 " + fix);
        }
        if (wrongCur) {
            issues.add("使用了错误的货币（本桶应使用 " + price.type + "）");
        }
        return new Validation(false, "交易有误：" + String.join("，且 ", issues),
                detail.toString(), true);
    }

    /**
     * Validate a "custom" exchange barrel: the customer swaps items of the
     * accepted group 1:1 and leaves a flat fee (0 for "exchange for free").
     */
    private static Validation validateExchange(NetChange net, GoodsFilter filter, Price fee) {
        boolean wrongGoods = Math.abs(net.wrongMats) > EPS;
        double goods = net.correctMats;

        String detail = "swap=" + round2(goods) + " fee=" + (fee == null ? "free" : round2(fee.compressed) + fee.type);
        if (wrongGoods) {
            detail += " wrongItem=" + round2(net.wrongMats);
        }

        List<String> issues = new ArrayList<>();
        if (wrongGoods) {
            issues.add("放入了错误的物品" + acceptSuffix(filter));
        }
        if (Math.abs(goods) > EPS) {
            issues.add("互换必须 1:1（放入与取出的数量需相等，当前差额 " + round2(goods) + "）");
        }

        if (fee == null || fee.compressed <= EPS) {
            // Free exchange: no currency should change hands.
            double anyCurrency = 0.0;
            for (double v : net.currency.values()) {
                anyCurrency += Math.abs(v);
            }
            if (anyCurrency > EPS) {
                issues.add("免费互换不应放入货币");
            }
        }
        else {
            double actualFee = net.currency.getOrDefault(fee.type, 0.0);
            double wrongCurrency = 0.0;
            for (Map.Entry<String, Double> e : net.currency.entrySet()) {
                if (!e.getKey().equals(fee.type)) {
                    wrongCurrency += Math.abs(e.getValue());
                }
            }
            double delta = fee.compressed - actualFee;
            if (Math.abs(delta) >= 0.0005) {
                issues.add(delta > 0
                        ? "还需放入手续费 " + formatCurrency(delta, fee.type)
                        : "手续费多放 " + formatCurrency(-delta, fee.type));
            }
            if (wrongCurrency > EPS) {
                issues.add("手续费货币应为 " + fee.type);
            }
        }

        if (issues.isEmpty()) {
            return new Validation(true, null, detail + " ok", true);
        }
        return new Validation(false, "交易有误：" + String.join("，且 ", issues), detail, true);
    }

    /** "（本桶仅收 X 组的 rare/frag）" / "（本桶仅收 X）" or "". */
    private static String acceptSuffix(GoodsFilter filter) {
        if (filter == null || filter.expected == null || filter.expected.isEmpty()) {
            return "";
        }
        return "rare".equals(filter.type)
                ? "（本桶仅收 " + filter.expected + " 组的 rare/frag）"
                : "（本桶仅收 " + filter.expected + "）";
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
