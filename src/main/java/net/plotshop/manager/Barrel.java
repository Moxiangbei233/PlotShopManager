package net.plotshop.manager;

/**
 * A registered shop barrel. Persisted to barrels.json via Gson, so all fields
 * are public and a no-arg constructor is required.
 */
public class Barrel {
    /** Shop type: "exclusive" (one item only), "rare" (frag + its rares) or
     * "custom" (exchange, with an optional flat fee). */
    public String type = "exclusive";
    public String world = "";
    public int x;
    public int y;
    public int z;
    /** Sign-marked commodity name, e.g. "concentrated experience". */
    public String item = "";
    /** Sign alias (what the sign actually says) when it differs from {@code item}. */
    public String alias = "";
    /** Sign-marked buy price, e.g. "10xp"; empty when not buying. */
    public String buyPrice = "";
    /** Sign-marked sell price, e.g. "5cs"; empty when not selling. */
    public String sellPrice = "";
    /** Exchange fee for "custom" shops: "free" or e.g. "0.5har"; empty otherwise. */
    public String exchangeFee = "";
    /** Barrel tier number 1..N read from the sign, for price discrimination. */
    public int tier;
    /** Epoch millis when this barrel's records were last fetched; 0 = never. */
    public long lastScanTime;

    public Barrel() {
    }

    public Barrel(String world, int x, int y, int z, String item, String buyPrice, String sellPrice) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.item = item;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
    }

    /** Human-readable Chinese name of the shop type, for chat and the HUD. */
    public String typeLabel() {
        switch (type == null ? "" : type) {
            case "rare": return "rare通用";
            case "custom": return "互换";
            default: return "专属";
        }
    }

    public String key() {
        return world + ":" + x + "," + y + "," + z;
    }
}
