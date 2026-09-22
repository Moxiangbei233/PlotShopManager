package net.plotshop.manager;

import java.util.Locale;

/**
 * Maps Monumenta currency item display names to a currency type and tier.
 */
public final class CurrencyMapper {

    public static final class Currency {
        public final String type;       // "xp" | "cs" | "ar"
        public final String tier;       // "hyper" | "compressed" | "base"
        public final double multiplier; // value relative to one compressed unit

        Currency(String type, String tier, double multiplier) {
            this.type = type;
            this.tier = tier;
            this.multiplier = multiplier;
        }
    }

    private CurrencyMapper() {
    }

    /**
     * Classify a Monumenta item name. Returns null when the item is not a known currency.
     */
    public static Currency classify(String itemName) {
        if (itemName == null) {
            return null;
        }
        String name = itemName.toLowerCase(Locale.ROOT).trim();
        switch (name) {
            case "hyperexperience":
                return new Currency("xp", "hyper", 64.0);
            case "concentrated experience":
                return new Currency("xp", "compressed", 1.0);
            case "experience bottle":
                return new Currency("xp", "base", 0.125);
            case "hyper crystalline shard":
                return new Currency("cs", "hyper", 64.0);
            case "compressed crystalline shard":
                return new Currency("cs", "compressed", 1.0);
            case "crystalline shard":
                return new Currency("cs", "base", 0.125);
            case "hyperchromatic archos ring":
                return new Currency("ar", "hyper", 64.0);
            case "archos ring":
                return new Currency("ar", "compressed", 1.0);
            default:
                return null;
        }
    }
}
