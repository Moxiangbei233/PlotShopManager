package net.plotshop.manager;

/**
 * A registered shop barrel. Persisted to barrels.json via Gson, so all fields
 * are public and a no-arg constructor is required.
 */
public class Barrel {
    public String world = "";
    public int x;
    public int y;
    public int z;
    /** Sign-marked commodity name, e.g. "concentrated experience". */
    public String item = "";
    /** Sign-marked buy price, e.g. "10xp"; empty when not buying. */
    public String buyPrice = "";
    /** Sign-marked sell price, e.g. "5cs"; empty when not selling. */
    public String sellPrice = "";
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

    public String key() {
        return world + ":" + x + "," + y + "," + z;
    }
}
