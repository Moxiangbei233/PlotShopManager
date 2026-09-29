package net.plotshop.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.plotshop.manager.mixin.HandledScreenAccessor;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer-side trade assistant. Listens for the barrel screen to open, takes a
 * snapshot of the barrel contents, and on close diffs the contents to detect
 * mistrades, estimate a fair price and append a local behaviour log.
 *
 * <p>This is an independent reimplementation of StonkCompanion's buyer features
 * that uses {@link ScreenEvents} instead of mixins and does not require
 * StonkCompanion to be installed.</p>
 */
public final class TradeMonitor {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Ticks to wait after the barrel screen opens before snapshotting contents. */
    private static final int SNAPSHOT_DELAY_TICKS = 10;
    /** Max age of a right-click target before it is considered stale. */
    private static final long CLICK_STALE_MS = 3000;
    /** Width of the on-screen trade HUD. */
    private static final int HUD_WIDTH = 165;
    /** How many recent log entries to show in the HUD. */
    private static final int RECENT_LIMIT = 5;
    /** How many ticks to keep retrying the sign read before giving up on a barrel. */
    private static final int SIGN_WAIT_TICKS = 30;

    private boolean mistradeEnabled = true;
    private boolean fairPriceEnabled = true;
    private boolean logEnabled = true;
    private boolean hudEnabled = true;

    private TradeSession session;
    private int snapshotDelay = -1;
    private boolean snapshotTaken = false;
    /** Remaining ticks to wait for the container contents/sign to sync from the server. */
    private int signWaitTicks = 0;

    private BarrelStore barrelStore;

    private BlockPos pendingPos;
    private String pendingWorld = "";
    private long pendingTime = 0;

    public void register(BarrelStore store) {
        this.barrelStore = store;
        loadConfig();

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player == null || world == null || hitResult == null) {
                return ActionResult.PASS;
            }
            pendingPos = hitResult.getBlockPos();
            pendingWorld = world.getRegistryKey().getValue().toString();
            pendingTime = System.currentTimeMillis();
            return ActionResult.PASS;
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            onScreenOpen(client, screen);
            if (screen instanceof GenericContainerScreen) {
                ScreenEvents.remove(screen).register(this::onScreenClose);
                ScreenEvents.afterRender(screen).register(this::onAfterRender);
            }
        });
    }

    public void tick(MinecraftClient client) {
        // Phase 1: the barrel screen just opened but the container may not have
        // synced from the server yet (Monumenta is high-latency). Retry the sign
        // read for a few ticks instead of giving up immediately, otherwise no
        // session is created and neither the HUD nor the mistrade check runs.
        if (session == null && signWaitTicks > 0) {
            if (client == null || client.player == null
                    || !(client.currentScreen instanceof GenericContainerScreen)) {
                clearPending();
                return;
            }
            ScreenHandler handler = ((GenericContainerScreen) client.currentScreen).getScreenHandler();
            BarrelContentsReader.SignInfo sign = BarrelContentsReader.readSign(handler);
            if (sign != null) {
                session = new TradeSession(pendingWorld, pendingPos, sign.label, sign.buy, sign.sell,
                        sign.exchange);
                session.recent.addAll(TradeLog.readRecent(pendingPos, RECENT_LIMIT));
                snapshotDelay = SNAPSHOT_DELAY_TICKS;
                snapshotTaken = false;
                signWaitTicks = 0;
                pendingPos = null;
                pendingWorld = "";
            }
            else if (--signWaitTicks <= 0) {
                // Timed out: this is not a shop barrel (no buy/sell sign).
                clearPending();
            }
            return;
        }

        // Phase 2: wait a few ticks for the contents to settle, then snapshot.
        // The mistrade check itself runs once on screen close, matching
        // StonkCompanion, so mid-trade intermediate states never spam chat.
        if (snapshotDelay >= 0) {
            snapshotDelay--;
            if (snapshotDelay == 0 && session != null && !snapshotTaken) {
                takeSnapshot(client);
            }
        }
    }

    // ---- command handlers -------------------------------------------------

    public boolean mistradeEnabled() {
        return mistradeEnabled;
    }

    public boolean fairPriceEnabled() {
        return fairPriceEnabled;
    }

    public boolean logEnabled() {
        return logEnabled;
    }

    public boolean hudEnabled() {
        return hudEnabled;
    }

    public void setMistrade(boolean enabled) {
        mistradeEnabled = enabled;
        saveConfig();
    }

    public void setFairPrice(boolean enabled) {
        fairPriceEnabled = enabled;
        saveConfig();
    }

    public void setLog(boolean enabled) {
        logEnabled = enabled;
        saveConfig();
    }

    public void setHud(boolean enabled) {
        hudEnabled = enabled;
        saveConfig();
    }

    public void toggleMistrade(FabricClientCommandSource source) {
        setMistrade(!mistradeEnabled);
        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + (mistradeEnabled
                ? "§a误交易检测已开启"
                : "§e误交易检测已关闭")));
    }

    public void toggleFairPrice(FabricClientCommandSource source) {
        setFairPrice(!fairPriceEnabled);
        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + (fairPriceEnabled
                ? "§a公平价格估算已开启"
                : "§e公平价格估算已关闭")));
    }

    public void toggleLog(FabricClientCommandSource source) {
        setLog(!logEnabled);
        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + (logEnabled
                ? "§a行为日志已开启"
                : "§e行为日志已关闭")));
    }

    public void toggleHud(FabricClientCommandSource source) {
        setHud(!hudEnabled);
        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + (hudEnabled
                ? "§a交易 HUD 已开启"
                : "§e交易 HUD 已关闭")));
    }

    public void status(FabricClientCommandSource source) {
        String mistrade = mistradeEnabled ? "§a开" : "§c关";
        String fairprice = fairPriceEnabled ? "§a开" : "§c关";
        String log = logEnabled ? "§a开" : "§c关";
        String hud = hudEnabled ? "§a开" : "§c关";
        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX
                + "§f误交易检测 §e" + mistrade
                + " §f公平价估算 §e" + fairprice
                + " §f行为日志 §e" + log
                + " §f交易HUD §e" + hud));
    }

    // ---- screen lifecycle --------------------------------------------------

    private void onScreenOpen(MinecraftClient client, Screen screen) {
        if (!(screen instanceof GenericContainerScreen) || client == null
                || client.world == null || pendingPos == null) {
            return;
        }
        if (System.currentTimeMillis() - pendingTime > CLICK_STALE_MS) {
            pendingPos = null;
            pendingWorld = "";
            return;
        }
        // Only track barrels; a chest/hopper right-clicked in between overwrites
        // pendingPos, so re-checking the block type here prevents false sessions.
        if (!client.world.getBlockState(pendingPos).isOf(Blocks.BARREL)) {
            pendingPos = null;
            pendingWorld = "";
            return;
        }

        // The container contents and sign may not have synced yet, so defer the
        // sign read to tick() and only create the session once the sign is visible.
        session = null;
        snapshotTaken = false;
        snapshotDelay = -1;
        signWaitTicks = SIGN_WAIT_TICKS;
    }

    private void onScreenClose(Screen screen) {
        if (screen instanceof GenericContainerScreen && session != null && snapshotTaken) {
            finishSession(((GenericContainerScreen) screen).getScreenHandler());
            return;
        }
        // Closed some other screen, or closed before a snapshot was taken: discard.
        session = null;
        snapshotDelay = -1;
        snapshotTaken = false;
        signWaitTicks = 0;
        pendingPos = null;
        pendingWorld = "";
    }

    /** Resets any pending barrel tracking (screen closed or sign never appeared). */
    private void clearPending() {
        session = null;
        snapshotDelay = -1;
        snapshotTaken = false;
        signWaitTicks = 0;
        pendingPos = null;
        pendingWorld = "";
    }

    private void takeSnapshot(MinecraftClient client) {
        snapshotDelay = -1;
        if (session == null || client == null || client.player == null) {
            return;
        }
        if (!(client.currentScreen instanceof GenericContainerScreen)) {
            session = null;
            return;
        }
        session.snapshot.clear();
        session.snapshot.putAll(BarrelContentsReader.readContents(client.player.currentScreenHandler));
        resolveShop(session, null);
        snapshotTaken = true;
    }

    private void finishSession(ScreenHandler handler) {
        TradeSession s = session;
        session = null;
        snapshotDelay = -1;
        snapshotTaken = false;
        if (s == null || !s.hasPricing()) {
            return;
        }

        Map<String, Integer> current = BarrelContentsReader.readContents(handler);
        Map<String, Integer> net = diff(s.snapshot, current);
        // Re-resolve with the current contents so an empty-on-open barrel is handled.
        resolveShop(s, current);
        TradeMath.GoodsFilter filter = s.expectedGoods.isEmpty()
                ? null
                : new TradeMath.GoodsFilter(s.shopType, s.expectedGoods);
        TradeMath.NetChange classified = TradeMath.classify(net, s.stackBarrel, filter);
        TradeMath.Validation validation = TradeMath.validate(classified, s.label, s.buy, s.sell,
                filter, s.exchangeFee);
        TradeMath.FairPrice fair = fairPriceEnabled
                ? TradeMath.fairPrice(current, s.label, s.buy, s.sell)
                : null;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            if (mistradeEnabled && validation.traded) {
                if (validation.valid) {
                    client.player.sendMessage(Text.literal(PlotShopManagerClient.PREFIX
                            + "§a交易正确 ✓ §7" + s.shortPos() + " §f" + s.label), false);
                }
                else {
                    client.player.sendMessage(Text.literal(PlotShopManagerClient.PREFIX
                            + "§c" + validation.message + " §7(" + s.shortPos() + ")"), false);
                }
            }
            if (fair != null && !fair.message.isEmpty()) {
                client.player.sendMessage(Text.literal(PlotShopManagerClient.PREFIX
                        + "§e公平价 §f" + fair.message + " §7(" + s.shortPos() + ")"), false);
            }
        }

        if (logEnabled && (validation.traded || !classified.isEmpty())) {
            TradeLog.write(s, net, classified, validation, fair);
        }
    }

    private void onAfterRender(Screen screen, DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (!hudEnabled || session == null || !(screen instanceof HandledScreen<?>)) {
            return;
        }
        renderHud((HandledScreen<?>) screen, ctx);
    }

    private void renderHud(HandledScreen<?> screen, DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
        int screenX = accessor.getX();
        int screenY = accessor.getY();

        TradeSession s = session;
        List<String> recent = s.recent;
        int lineHeight = client.textRenderer.fontHeight + 2;

        // Rough row count for the panel height: logo + label, prices, the
        // "recent" header/entries, plus a couple of separator rows.
        int rows = 2
                + ((!s.shopType.isEmpty() || !s.expectedGoods.isEmpty()) ? 1 : 0)
                + (s.buy != null ? 1 : 0)
                + (s.sell != null ? 1 : 0)
                + 1
                + Math.max(1, recent.size())
                + 2;
        int height = 8 + rows * lineHeight;

        int x = screenX - HUD_WIDTH - 4;
        int y = screenY;
        if (x < 2) {
            x = 2;
        }

        ctx.fill(x, y, x + HUD_WIDTH, y + height, client.options.getTextBackgroundColor(0.35f));

        int cx = x + 5;
        int centerX = x + HUD_WIDTH / 2;
        int cy = y + 4;

        // Centered brand header.
        ctx.drawCenteredTextWithShadow(client.textRenderer,
                Text.literal("§e§lPlot§a§lShop §7§l助手"), centerX, cy, 0xFFFFFF);
        cy += lineHeight;
        ctx.drawHorizontalLine(x + 1, x + HUD_WIDTH - 1, cy, 0xFF00FFFF);
        cy += 4;

        String label = s.label == null || s.label.isEmpty() ? "商店" : s.label;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§f" + label), cx, cy, 0xFFFFFF);
        cy += lineHeight;

        if (!s.shopType.isEmpty() || !s.expectedGoods.isEmpty()) {
            String typeCn;
            switch (s.shopType) {
                case "rare": typeCn = "rare通用"; break;
                case "custom": typeCn = "互换"; break;
                default: typeCn = "专属";
            }
            String line = "§d" + typeCn + " §f" + s.expectedGoods;
            if ("custom".equals(s.shopType)) {
                line += (s.exchangeFee == null) ? " §7(免费)" : " §7(手续费 " + s.exchangeText + ")";
            }
            ctx.drawTextWithShadow(client.textRenderer, Text.literal(line), cx, cy, 0xFFFFFF);
            cy += lineHeight;
        }

        if (s.buy != null) {
            ctx.drawTextWithShadow(client.textRenderer,
                    Text.literal("§2● 买 §f" + s.buyText), cx, cy, 0xFFFFFF);
            cy += lineHeight;
        }
        if (s.sell != null) {
            ctx.drawTextWithShadow(client.textRenderer,
                    Text.literal("§4● 卖 §f" + s.sellText), cx, cy, 0xFFFFFF);
            cy += lineHeight;
        }

        ctx.drawHorizontalLine(x + 1, x + HUD_WIDTH - 1, cy, 0xFF00FFFF);
        cy += 4;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal("§b最近行为"), cx, cy, 0xFFFFFF);
        cy += lineHeight;

        if (recent.isEmpty()) {
            ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7(暂无)"), cx, cy, 0xFFFFFF);
            cy += lineHeight;
        }
        else {
            for (String line : recent) {
                ctx.drawTextWithShadow(client.textRenderer, Text.literal("§7" + line), cx, cy, 0xFFFFFF);
                cy += lineHeight;
            }
        }

        ctx.drawHorizontalLine(x + 1, x + HUD_WIDTH - 1, cy, 0xFF00FFFF);
    }

    private static Map<String, Integer> diff(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> net = new HashMap<>();
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            net.put(e.getKey(), -e.getValue());
        }
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            net.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        net.entrySet().removeIf(e -> e.getValue() == 0);
        return net;
    }

    /**
     * Resolve the session's shop type and expected goods. A registered barrel is
     * authoritative; otherwise the sign and dominant contents are used to guess:
     * exchange signs become "custom", frag/rare goods become "rare", anything
     * else stays lenient (no strict item check).
     */
    private void resolveShop(TradeSession s, Map<String, Integer> current) {
        if (barrelStore != null) {
            Barrel registered = barrelStore.find(s.world, s.pos.getX(), s.pos.getY(), s.pos.getZ());
            if (registered != null && !registered.item.isEmpty()) {
                applyRegistered(s, registered);
                return;
            }
        }

        if (!s.exchangeText.isEmpty()) {
            s.shopType = "custom";
            String dominant = dominantGoods(s.snapshot);
            if (dominant == null && current != null) {
                dominant = dominantGoods(current);
            }
            s.expectedGoods = dominant != null ? dominant : s.label;
            s.exchangeFee = parseFee(s.exchangeText);
            s.fragGroup = null;
            return;
        }

        String dominant = dominantGoods(s.snapshot);
        if (dominant == null && current != null) {
            dominant = dominantGoods(current);
        }
        String candidate = AliasStore.get().resolve(dominant != null ? dominant : s.label);
        String group = RareFragIndex.get().fragOf(candidate);
        if (group != null) {
            s.shopType = "rare";
            s.expectedGoods = group;
            s.fragGroup = group;
            s.exchangeFee = null;
        }
        else {
            s.shopType = "";
            s.expectedGoods = "";
            s.fragGroup = null;
            s.exchangeFee = null;
        }
    }

    private void applyRegistered(TradeSession s, Barrel registered) {
        String type = registered.type == null || registered.type.isEmpty()
                ? "exclusive" : registered.type;
        s.shopType = type;
        String resolvedItem = AliasStore.get().resolve(registered.item);
        if ("custom".equals(type)) {
            s.expectedGoods = resolvedItem;
            s.exchangeFee = parseFee(registered.exchangeFee);
            s.fragGroup = null;
        }
        else if ("rare".equals(type)) {
            String group = RareFragIndex.get().fragOf(resolvedItem);
            s.expectedGoods = group != null ? group : resolvedItem;
            s.fragGroup = s.expectedGoods;
            s.exchangeFee = null;
        }
        else {
            s.expectedGoods = resolvedItem;
            s.fragGroup = null;
            s.exchangeFee = null;
        }
    }

    /** Parse an exchange fee: "free"/"" -> null (free), "0.5har" -> a Price. */
    private static TradeMath.Price parseFee(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("free")) {
            return null;
        }
        return TradeMath.parsePrice(t);
    }

    private static String dominantGoods(Map<String, Integer> contents) {
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : contents.entrySet()) {
            if (CurrencyMapper.classify(e.getKey()) != null) {
                continue;
            }
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    // ---- config persistence ------------------------------------------------

    private static final class Config {
        boolean mistrade = true;
        boolean fairprice = true;
        boolean log = true;
        boolean hud = true;
    }

    private Path configFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("plotshop-manager").resolve("trade.json");
    }

    private void loadConfig() {
        Path file = configFile();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Config c = GSON.fromJson(reader, Config.class);
            if (c != null) {
                mistradeEnabled = c.mistrade;
                fairPriceEnabled = c.fairprice;
                logEnabled = c.log;
                hudEnabled = c.hud;
            }
        }
        catch (IOException ignored) {
            // Corrupt file: fall back to defaults.
        }
    }

    private void saveConfig() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            Config c = new Config();
            c.mistrade = mistradeEnabled;
            c.fairprice = fairPriceEnabled;
            c.log = logEnabled;
            c.hud = hudEnabled;
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(c, writer);
            }
        }
        catch (IOException ignored) {
            // Best effort; settings still apply for this session.
        }
    }
}
