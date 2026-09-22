package net.plotshop.manager;

/**
 * A single parsed CoreProtect container transaction, attributed to a barrel.
 */
public class TransactionRecord {
    /** Absolute timestamp as shown by CoreProtect, e.g. "2024-01-31 14:05:03 CST". */
    public String timestampText = "";
    /** Player who performed the transaction. */
    public String player = "";
    /** Monumenta display name (from the item hover), e.g. "concentrated experience". */
    public String itemName = "";
    /** Base Minecraft item id (from the visible text), e.g. "experience_bottle". */
    public String material = "";
    /** Number of items moved. */
    public int amount = 0;
    /** true = item was added into the barrel (+); false = removed from the barrel (-). */
    public boolean deposited = false;
    /** "xp" / "cs" / "ar", empty when the item is not a recognized currency. */
    public String currencyType = "";
    /** "hyper" / "compressed" / "base", empty when not a currency. */
    public String currencyTier = "";
    public int barrelX;
    public int barrelY;
    public int barrelZ;
    public String world = "";

    public String direction() {
        return deposited ? "added" : "removed";
    }

    public String barrelKey() {
        return world + ":" + barrelX + "," + barrelY + "," + barrelZ;
    }
}
