package net.plotshop.manager;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * State for one open barrel: its position, sign info and a snapshot of the
 * barrel contents taken shortly after the screen opened. On screen close the
 * current contents are diffed against this snapshot to derive the net change.
 */
public final class TradeSession {

    public final String world;
    public final BlockPos pos;
    public final String label;
    public final String buyText;
    public final String sellText;
    public final TradeMath.Price buy;
    public final TradeMath.Price sell;
    public final boolean stackBarrel;

    /** item display name -> count, as seen in the barrel right after opening. */
    public final Map<String, Integer> snapshot = new HashMap<>();

    public TradeSession(String world, BlockPos pos, String label, String buyText, String sellText) {
        this.world = world;
        this.pos = pos;
        this.label = label;
        this.buyText = buyText;
        this.sellText = sellText;
        this.buy = TradeMath.parsePrice(buyText);
        this.sell = TradeMath.parsePrice(sellText);
        this.stackBarrel = TradeMath.isStackBarrel(label);
    }

    public String key() {
        return world + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public String shortPos() {
        return "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public boolean hasPricing() {
        return buy != null || sell != null;
    }
}
