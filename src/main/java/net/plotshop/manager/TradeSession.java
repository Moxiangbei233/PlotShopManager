package net.plotshop.manager;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /** Rare-fragment group this barrel trades (null for a plain barrel). */
    public String fragGroup;

    /** Exchange fee text from the sign ("free" / "0.5har"), empty for buy/sell shops. */
    public final String exchangeText;

    /** Resolved shop type: "exclusive" / "rare" / "custom" ("" = unresolved/lenient). */
    public String shopType = "";

    /** Expected goods: item name (exclusive/custom) or frag group (rare); "" = lenient. */
    public String expectedGoods = "";

    /** Parsed flat fee for a "custom" shop; null means free. */
    public TradeMath.Price exchangeFee;

    /** Recent local log summaries ("MM-dd HH:mm ok +1hxp"), read when the barrel opened. */
    public final List<String> recent = new ArrayList<>();

    public TradeSession(String world, BlockPos pos, String label, String buyText, String sellText,
                        String exchangeText) {
        this.world = world;
        this.pos = pos;
        this.label = label;
        this.buyText = buyText;
        this.sellText = sellText;
        this.exchangeText = exchangeText == null ? "" : exchangeText;
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
