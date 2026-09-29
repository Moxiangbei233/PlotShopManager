package net.plotshop.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
    private static final int SNAPSHOT_DELAY_TICKS = 3;
    /** Max age of a right-click target before it is considered stale. */
    private static final long CLICK_STALE_MS = 3000;

    private boolean mistradeEnabled = true;
    private boolean fairPriceEnabled = true;
    private boolean logEnabled = true;

    private TradeSession session;
    private int snapshotDelay = -1;
    private boolean snapshotTaken = false;

    private BlockPos pendingPos;
    private String pendingWorld = "";
    private long pendingTime = 0;

    public void register() {
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
            }
        });
    }

    public void tick(MinecraftClient client) {
        if (snapshotDelay < 0) {
            return;
        }
        snapshotDelay--;
        if (snapshotDelay == 0 && session != null && !snapshotTaken) {
            takeSnapshot(client);
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

    public void toggleMistrade(FabricClientCommandSource source) {
        mistradeEnabled = !mistradeEnabled;
        saveConfig();
        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + (mistradeEnabled
                ? "§a误交易检测已开启"
                : "§e误交易检测已关闭")));
    }

    public void toggleFairPrice(FabricClientCommandSource source) {
        fairPriceEnabled = !fairPriceEnabled;
        saveConfig();
        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + (fairPriceEnabled
                ? "§a公平价格估算已开启"
                : "§e公平价格估算已关闭")));
    }

    public void toggleLog(FabricClientCommandSource source) {
        logEnabled = !logEnabled;
        saveConfig();
        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + (logEnabled
                ? "§a行为日志已开启"
                : "§e行为日志已关闭")));
    }

    public void status(FabricClientCommandSource source) {
        String mistrade = mistradeEnabled ? "§a开" : "§c关";
        String fairprice = fairPriceEnabled ? "§a开" : "§c关";
        String log = logEnabled ? "§a开" : "§c关";
        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX
                + "§f误交易检测 §e" + mistrade
                + " §f公平价估算 §e" + fairprice
                + " §f行为日志 §e" + log));
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

        ScreenHandler handler = ((GenericContainerScreen) screen).getScreenHandler();
        BarrelContentsReader.SignInfo sign = BarrelContentsReader.readSign(handler);
        if (sign == null) {
            // Not a shop barrel (no buy/sell sign inside).
            pendingPos = null;
            pendingWorld = "";
            return;
        }

        session = new TradeSession(pendingWorld, pendingPos, sign.label, sign.buy, sign.sell);
        snapshotTaken = false;
        snapshotDelay = SNAPSHOT_DELAY_TICKS;
        pendingPos = null;
        pendingWorld = "";
    }

    private void onScreenClose(Screen screen) {
        if (session == null) {
            return;
        }
        if (screen instanceof GenericContainerScreen && snapshotTaken) {
            finishSession(((GenericContainerScreen) screen).getScreenHandler());
            return;
        }
        // Closed some other screen with a stale session: discard.
        session = null;
        snapshotDelay = -1;
        snapshotTaken = false;
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
        TradeMath.NetChange classified = TradeMath.classify(net, s.stackBarrel);
        TradeMath.Validation validation = TradeMath.validate(classified, s.label, s.buy, s.sell);
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

    // ---- config persistence ------------------------------------------------

    private static final class Config {
        boolean mistrade = true;
        boolean fairprice = true;
        boolean log = true;
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
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(c, writer);
            }
        }
        catch (IOException ignored) {
            // Best effort; settings still apply for this session.
        }
    }
}
