package net.plotshop.manager;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Client-only barrel shop manager for the Monumenta server.
 *
 * <p>Register each shop barrel with {@code /shop register <item|buy|sell>} (read
 * from the sign), then stand at the plot center and run {@code /shop scan} to
 * query every nearby registered barrel through CoreProtect and store one CSV per
 * barrel via {@code /shop export}.</p>
 */
public class PlotShopManagerClient implements ClientModInitializer {

    public static final String MOD_ID = "plotshop-manager";
    public static final String PREFIX = "§7[§ePlot§aShop§7] ";

    private static final String DEFAULT_TIME = "7d";
    /** Farthest corner of an 11x11 guild plot from its center block. */
    private static final int GUILD_PLOT_RADIUS = 8;
    /** Farthest corner of a 7x7 normal plot from its center block. */
    private static final int NORMAL_PLOT_RADIUS = 5;
    private static final Pattern TIME_PATTERN = Pattern.compile("[0-9]+[smhd]");

    private final List<TransactionRecord> records = new ArrayList<>();
    private final Set<String> seenKeys = new HashSet<>();
    private final BarrelStore barrelStore = new BarrelStore();
    private final BarrelScanner scanner;

    private BlockPos lastBarrelPos = null;
    private String lastBarrelWorld = "";
    private int recordsBeforeScan = 0;

    private KeyBinding quickRegisterKey;
    private boolean quickRegister = false;
    private BlockPos quickTargetPos = null;
    private String quickTargetWorld = "";
    private int quickWaitTicks = 0;

    public PlotShopManagerClient() {
        scanner = new BarrelScanner(
                command -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.getNetworkHandler() != null) {
                        client.getNetworkHandler().sendChatCommand(command);
                    }
                },
                new BarrelScanner.Callback() {
                    @Override
                    public void onBarrelStart(Barrel barrel, int index, int total) {
                        sendFeedback(Text.literal(PREFIX + "§e[" + index + "/" + total + "] §f查询桶 §b@"
                                + barrel.x + "," + barrel.y + "," + barrel.z
                                + " §f物品 §a" + (barrel.item.isEmpty() ? "?" : barrel.item)
                                + " §f买 §a" + (barrel.buyPrice.isEmpty() ? "-" : barrel.buyPrice)
                                + " §f卖 §a" + (barrel.sellPrice.isEmpty() ? "-" : barrel.sellPrice)));
                    }

                    @Override
                    public void onBarrelDone(Barrel barrel, boolean success) {
                        if (!success || barrel == null) {
                            return;
                        }
                        barrel.lastScanTime = System.currentTimeMillis();
                        barrelStore.save();
                    }

                    @Override
                    public void onFinished(int scanned) {
                        int gained = records.size() - recordsBeforeScan;
                        sendFeedback(Text.literal(PREFIX + "§a扫描完成 §f处理 §b" + scanned + " §f个桶，新增 §b"
                                + gained + " §f条记录（共 §b" + records.size() + " §f条）。用 §e/shop export §f导出CSV。"));
                    }
                },
                message -> sendFeedback(Text.literal(message))
        );
    }

    @Override
    public void onInitializeClient() {
        barrelStore.load();
        quickRegisterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plotshop.quickregister",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "key.categories.plotshop"
        ));

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!quickRegister || player == null || world == null || hitResult == null) {
                return ActionResult.PASS;
            }
            if (!world.getBlockState(hitResult.getBlockPos()).isOf(Blocks.BARREL)) {
                return ActionResult.PASS;
            }
            quickTargetPos = hitResult.getBlockPos();
            quickTargetWorld = world.getRegistryKey().getValue().toString();
            quickWaitTicks = 0;
            return ActionResult.PASS;
        });

        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            scanner.onMessage(message);
            parseAndStore(message);
            return message;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            scanner.tick();
            tickQuickRegister(client);
        });
        registerCommands();
    }

    private void sendFeedback(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(text, false);
        }
    }

    private void toggleQuickRegister() {
        quickRegister = !quickRegister;
        quickTargetPos = null;
        quickTargetWorld = "";
        quickWaitTicks = 0;
        String key = quickRegisterKey != null
                ? quickRegisterKey.getBoundKeyLocalizedText().getString()
                : "K";
        sendFeedback(Text.literal(PREFIX + (quickRegister
                ? "§a快速注册已开启§f：右键点击一个桶即可自动读取告示牌与内容并注册，再按 §e" + key + " §f关闭。"
                : "§e快速注册已关闭。")));
    }

    private void tickQuickRegister(MinecraftClient client) {
        if (client == null || client.player == null || quickRegisterKey == null) {
            return;
        }
        if (quickRegisterKey.wasPressed()) {
            toggleQuickRegister();
            return;
        }
        if (!quickRegister || quickTargetPos == null) {
            return;
        }

        if (!(client.currentScreen instanceof GenericContainerScreen)) {
            // The screen may open a tick or two after the UseBlock event fires.
            quickWaitTicks++;
            if (quickWaitTicks > 40) {
                sendFeedback(Text.literal(PREFIX + "§c未打开桶界面，快速注册取消。请确认右键的是桶且已开启快速注册。"));
                quickTargetPos = null;
                quickTargetWorld = "";
                quickWaitTicks = 0;
            }
            return;
        }
        if (client.player.currentScreenHandler == null) {
            return;
        }

        Barrel barrel = BarrelContentsReader.read(client.player.currentScreenHandler, quickTargetPos, quickTargetWorld);
        if (barrel == null) {
            quickWaitTicks++;
            if (quickWaitTicks > 60) {
                sendFeedback(Text.literal(PREFIX + "§c无法从该桶读取价格（未找到 buy for / sell for），请用 §e/shop register §f手动注册。"));
                quickTargetPos = null;
                quickTargetWorld = "";
                quickWaitTicks = 0;
            }
            return;
        }

        barrelStore.upsert(barrel);
        barrelStore.save();
        sendFeedback(Text.literal(PREFIX + "§a已快速注册桶 §b@" + barrel.x + "," + barrel.y + "," + barrel.z
                + " §f物品 §a" + barrel.item
                + " §f买 §a" + (barrel.buyPrice.isEmpty() ? "-" : barrel.buyPrice)
                + " §f卖 §a" + (barrel.sellPrice.isEmpty() ? "-" : barrel.sellPrice)
                + " §f。右键下一个桶继续。"));
        quickTargetPos = null;
        quickTargetWorld = "";
        quickWaitTicks = 0;
    }

    private void parseAndStore(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            return;
        }

        Barrel scanning = scanner.isScanning() ? scanner.current() : null;
        BlockPos barrel;
        String world;
        if (scanning != null) {
            barrel = new BlockPos(scanning.x, scanning.y, scanning.z);
            world = scanning.world;
        }
        else if (lastBarrelPos != null) {
            barrel = lastBarrelPos;
            world = lastBarrelWorld;
        }
        else {
            barrel = client.player.getBlockPos();
            world = client.world.getRegistryKey().getValue().toString();
        }

        TransactionRecord record = CoChatParser.parse(message, barrel, world);
        if (record == null) {
            return;
        }

        // Avoid double-counting when the same lookup is issued more than once.
        String key = record.timestampText + "|" + record.player + "|" + record.itemName
                + "|" + record.amount + "|" + record.direction()
                + "|" + record.barrelX + "," + record.barrelY + "," + record.barrelZ
                + "|" + record.world;
        if (!seenKeys.add(key)) {
            return;
        }

        records.add(record);
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("shop")
                    .then(ClientCommandManager.literal("lookup")
                            .executes(context -> {
                                lookup(context.getSource(), DEFAULT_TIME);
                                return 1;
                            })
                            .then(ClientCommandManager.argument("time", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        lookup(context.getSource(), StringArgumentType.getString(context, "time"));
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("register")
                            .then(ClientCommandManager.argument("spec", StringArgumentType.greedyString())
                                    .executes(context -> {
                                        register(context.getSource(), StringArgumentType.getString(context, "spec"));
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("unregister")
                            .executes(context -> {
                                unregister(context.getSource());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("list")
                            .executes(context -> {
                                list(context.getSource());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("scan")
                            .then(ClientCommandManager.literal("guildplot")
                                    .executes(context -> {
                                        scan(context.getSource(), GUILD_PLOT_RADIUS, null);
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("time", StringArgumentType.greedyString())
                                            .executes(context -> {
                                                scan(context.getSource(), GUILD_PLOT_RADIUS,
                                                        StringArgumentType.getString(context, "time"));
                                                return 1;
                                            })))
                            .then(ClientCommandManager.literal("normalplot")
                                    .executes(context -> {
                                        scan(context.getSource(), NORMAL_PLOT_RADIUS, null);
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("time", StringArgumentType.greedyString())
                                            .executes(context -> {
                                                scan(context.getSource(), NORMAL_PLOT_RADIUS,
                                                        StringArgumentType.getString(context, "time"));
                                                return 1;
                                            }))))
                    .then(ClientCommandManager.literal("stop")
                            .executes(context -> {
                                stop(context.getSource());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("status")
                            .executes(context -> {
                                status(context.getSource());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("last")
                            .executes(context -> {
                                last(context.getSource());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("export")
                            .executes(context -> {
                                CsvExporter.export(records, barrelStore.all(), context.getSource());
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("clear")
                            .executes(context -> {
                                clear(context.getSource());
                                return 1;
                            })));
        });
    }

    private void lookup(FabricClientCommandSource source, String time) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!gameReady(source, client)) {
            return;
        }

        String t = time == null ? "" : time.trim();
        if (!TIME_PATTERN.matcher(t).matches()) {
            source.sendFeedback(Text.literal(PREFIX + "§c时间格式无效，例如 §e7d§c、§e1h§c、§e30m"));
            return;
        }

        lastBarrelPos = getLookedAtBlock(client);
        if (lastBarrelPos == null) {
            lastBarrelPos = client.player.getBlockPos();
        }
        lastBarrelWorld = client.world.getRegistryKey().getValue().toString();

        String command = "co lookup r:1 t:" + t + " a:container";
        client.getNetworkHandler().sendChatCommand(command);

        source.sendFeedback(Text.literal(PREFIX + "§a已发送 §e/" + command));
        source.sendFeedback(Text.literal(PREFIX + "§a目标桶 §b" + lastBarrelPos.toShortString()
                + " §a(§7" + lastBarrelWorld + "§a)"));
    }

    private void register(FabricClientCommandSource source, String spec) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!gameReady(source, client)) {
            return;
        }

        String[] parts = spec.split("\\|");
        String item = parts.length > 0 ? parts[0].trim() : "";
        String buy = parts.length > 1 ? parts[1].trim() : "";
        String sell = parts.length > 2 ? parts[2].trim() : "";
        if (item.isEmpty()) {
            source.sendFeedback(Text.literal(PREFIX + "§c格式：§e/shop register 物品|买入价|卖出价§c，例如 §e/shop register 经验瓶|10xp|5xp"));
            return;
        }

        BlockPos pos = getLookedAtBlock(client);
        if (pos == null) {
            source.sendFeedback(Text.literal(PREFIX + "§c请看向要登记的桶再执行此指令"));
            return;
        }

        String world = client.world.getRegistryKey().getValue().toString();
        Barrel barrel = new Barrel(world, pos.getX(), pos.getY(), pos.getZ(), item, buy, sell);
        barrelStore.upsert(barrel);
        barrelStore.save();

        source.sendFeedback(Text.literal(PREFIX + "§a已登记桶 §b@" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + " §f物品 §a" + item
                + " §f买 §a" + (buy.isEmpty() ? "-" : buy)
                + " §f卖 §a" + (sell.isEmpty() ? "-" : sell)));

        boolean isBarrel = client.world.getBlockState(pos).isOf(Blocks.BARREL);
        if (!isBarrel) {
            source.sendFeedback(Text.literal(PREFIX + "§e提示：目标方块不是桶，请确认坐标是否正确"));
        }
    }

    private void unregister(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!gameReady(source, client)) {
            return;
        }

        BlockPos pos = getLookedAtBlock(client);
        if (pos == null) {
            source.sendFeedback(Text.literal(PREFIX + "§c请看向要移除登记的桶再执行此指令"));
            return;
        }

        String world = client.world.getRegistryKey().getValue().toString();
        String key = world + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        if (barrelStore.remove(key)) {
            barrelStore.save();
            source.sendFeedback(Text.literal(PREFIX + "§a已移除登记 §b@" + pos.getX() + "," + pos.getY() + "," + pos.getZ()));
        }
        else {
            source.sendFeedback(Text.literal(PREFIX + "§c该位置没有登记记录"));
        }
    }

    private void list(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!gameReady(source, client)) {
            return;
        }

        List<Barrel> barrels = new ArrayList<>(barrelStore.all());
        if (barrels.isEmpty()) {
            source.sendFeedback(Text.literal(PREFIX + "§c还没有登记任何桶，用 §e/shop register 物品|买入价|卖出价 §c登记"));
            return;
        }

        String world = client.world.getRegistryKey().getValue().toString();
        BlockPos feet = client.player.getBlockPos();
        barrels.sort(Comparator.comparingDouble(b -> horizontalDistanceSq(b, world, feet)));

        StringBuilder sb = new StringBuilder(PREFIX + "§a已登记 §e" + barrels.size() + " §a个桶：");
        for (Barrel b : barrels) {
            double dist = Math.sqrt(horizontalDistanceSq(b, world, feet));
            sb.append('\n').append("§8  ").append(b.x).append(',').append(b.y).append(',').append(b.z)
                    .append(" §7[").append(b.world).append("] §f").append(b.item)
                    .append(" §a买").append(b.buyPrice.isEmpty() ? "-" : b.buyPrice)
                    .append(" §a卖").append(b.sellPrice.isEmpty() ? "-" : b.sellPrice)
                    .append(" §7距离").append(String.format("%.1f", dist));
        }
        source.sendFeedback(Text.literal(sb.toString()));
    }

    private void scan(FabricClientCommandSource source, int radius, String time) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!gameReady(source, client)) {
            return;
        }

        final String override = time == null ? "" : time.trim();
        if (!override.isEmpty() && !TIME_PATTERN.matcher(override).matches()) {
            source.sendFeedback(Text.literal(PREFIX + "§c时间格式无效，例如 §e7d§c、§e1h§c、§e30m"));
            return;
        }

        if (scanner.isScanning()) {
            source.sendFeedback(Text.literal(PREFIX + "§c已有扫描进行中，用 §e/shop stop §c停止后再开始"));
            return;
        }

        String world = client.world.getRegistryKey().getValue().toString();
        BlockPos feet = client.player.getBlockPos();

        List<Barrel> nearby = new ArrayList<>();
        for (Barrel b : barrelStore.all()) {
            if (!b.world.equals(world)) {
                continue;
            }
            double dist = Math.sqrt(horizontalDistanceSq(b, world, feet));
            if (dist <= radius) {
                nearby.add(b);
            }
        }
        nearby.sort(Comparator.comparingDouble(b -> horizontalDistanceSq(b, world, feet)));

        if (nearby.isEmpty()) {
            source.sendFeedback(Text.literal(PREFIX + "§c半径 " + radius + " 内没有已登记的桶，用 §e/shop list §c查看"));
            return;
        }

        recordsBeforeScan = records.size();
        BarrelScanner.TimeResolver resolver = barrel -> {
            if (!override.isEmpty()) {
                return override;
            }
            if (barrel.lastScanTime > 0) {
                return elapsedDuration(System.currentTimeMillis() - barrel.lastScanTime);
            }
            return DEFAULT_TIME;
        };
        scanner.start(nearby, resolver);
        String timeLabel = override.isEmpty() ? "增量(自动)" : override;
        source.sendFeedback(Text.literal(PREFIX + "§a开始扫描 §e" + nearby.size() + " §a个桶（半径 §e"
                + radius + "§a，时间 §e" + timeLabel + "§a），完成后用 §e/shop export §a导出。"));
    }

    private void stop(FabricClientCommandSource source) {
        if (scanner.isScanning()) {
            scanner.stop();
            source.sendFeedback(Text.literal(PREFIX + "§a已停止扫描"));
        }
        else {
            source.sendFeedback(Text.literal(PREFIX + "§c当前没有进行中的扫描"));
        }
    }

    private void status(FabricClientCommandSource source) {
        StringBuilder sb = new StringBuilder(PREFIX + "§a已记录 §e" + records.size() + " §a条交易，已登记 §e"
                + barrelStore.all().size() + " §a个桶");
        if (scanner.isScanning() && scanner.current() != null) {
            sb.append("，扫描中 §e").append(scanner.scanned()).append('/').append(scanner.total());
        }
        if (lastBarrelPos != null) {
            sb.append("，当前桶 §b").append(lastBarrelPos.toShortString())
                    .append(" §7(").append(lastBarrelWorld).append(')');
        }
        source.sendFeedback(Text.literal(sb.toString()));
    }

    private void last(FabricClientCommandSource source) {
        if (records.isEmpty()) {
            source.sendFeedback(Text.literal(PREFIX + "§c还没有解析到任何记录，先执行 §e/shop scan§c 或 §e/shop lookup"));
            return;
        }
        TransactionRecord r = records.get(records.size() - 1);
        source.sendFeedback(Text.literal(PREFIX + "§f最近一条记录："));
        source.sendFeedback(Text.literal("§7  时间 §f" + r.timestampText
                + " §7玩家 §f" + r.player
                + " §7动作 §f" + r.direction()
                + " x" + r.amount
                + " §7物品 §f" + r.itemName
                + " §7(§8" + r.material + "§7)"));
        if (!r.currencyType.isEmpty()) {
            source.sendFeedback(Text.literal("§7  货币 §f" + r.currencyType
                    + " §7层级 §f" + r.currencyTier));
        }
    }

    private void clear(FabricClientCommandSource source) {
        scanner.stop();
        int count = records.size();
        records.clear();
        seenKeys.clear();
        lastBarrelPos = null;
        lastBarrelWorld = "";
        recordsBeforeScan = 0;
        source.sendFeedback(Text.literal(PREFIX + "§a已清空 §e" + count + " §a条记录"));
    }

    private boolean gameReady(FabricClientCommandSource source, MinecraftClient client) {
        if (client == null || client.player == null || client.world == null
                || client.getNetworkHandler() == null) {
            source.sendFeedback(Text.literal(PREFIX + "§c无法获取游戏状态"));
            return false;
        }
        return true;
    }

    private double horizontalDistanceSq(Barrel b, String world, BlockPos feet) {
        int dx = b.x - feet.getX();
        int dz = b.z - feet.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Formats elapsed millis as a CoreProtect single-unit time string, rounding up
     * and padding by one minute so no transaction is missed at the cutoff.
     */
    private static String elapsedDuration(long elapsedMs) {
        long seconds = Math.max(60, elapsedMs / 1000 + 60);
        if (seconds >= 86400) {
            return ((seconds + 86399) / 86400) + "d";
        }
        if (seconds >= 3600) {
            return ((seconds + 3599) / 3600) + "h";
        }
        return ((seconds + 59) / 60) + "m";
    }

    /**
     * The block the player is looking at, or null when not aiming at a block.
     */
    private BlockPos getLookedAtBlock(MinecraftClient client) {
        HitResult hit = client.player.raycast(6.0, 0.0F, false);
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hit).getBlockPos();
        }
        return null;
    }
}
