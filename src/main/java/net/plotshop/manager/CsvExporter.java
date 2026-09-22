package net.plotshop.manager;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes transaction records to CSV files, one file per barrel, plus a
 * barrels.csv registry that ties each barrel to its sign prices.
 */
public final class CsvExporter {

    private static final String[] HEADER = {
            "timestamp", "player", "item", "material", "amount", "direction",
            "currency_type", "currency_tier", "barrel_x", "barrel_y", "barrel_z", "world"
    };

    private static final String[] REGISTRY_HEADER = {
            "world", "x", "y", "z", "item", "buy_price", "sell_price", "record_count"
    };

    private CsvExporter() {
    }

    public static void export(List<TransactionRecord> records, List<Barrel> barrels,
                              FabricClientCommandSource source) {
        if (records.isEmpty()) {
            source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + "§c没有可导出的记录，先执行 §e/shop scan§c 或 §e/shop lookup"));
            return;
        }

        Path dir = FabricLoader.getInstance().getConfigDir()
                .resolve("plotshop-manager").resolve("exports");
        try {
            Files.createDirectories(dir);
        }
        catch (IOException e) {
            source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + "§c创建导出目录失败: " + e.getMessage()));
            return;
        }

        Map<String, List<TransactionRecord>> byBarrel = new LinkedHashMap<>();
        for (TransactionRecord record : records) {
            byBarrel.computeIfAbsent(record.barrelKey(), key -> new ArrayList<>()).add(record);
        }

        Map<String, Barrel> barrelByKey = new HashMap<>();
        for (Barrel barrel : barrels) {
            barrelByKey.put(barrel.key(), barrel);
        }

        int fileCount = 0;
        for (Map.Entry<String, List<TransactionRecord>> entry : byBarrel.entrySet()) {
            Barrel barrel = barrelByKey.get(entry.getKey());
            String item = barrel == null || barrel.item.isEmpty() ? "" : barrel.item;
            Path file = dir.resolve("barrel_" + coordPart(entry.getKey()) + "__" + safeName(item) + ".csv");
            try {
                writeCsv(file, entry.getValue());
                fileCount++;
            }
            catch (IOException e) {
                source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + "§c写入失败 "
                        + file.getFileName() + ": " + e.getMessage()));
            }
        }

        writeRegistry(dir, byBarrel, barrelByKey);

        source.sendFeedback(Text.literal(PlotShopManagerClient.PREFIX + "§a已导出 §e"
                + records.size() + " §f条记录到 §e" + fileCount + " §f个CSV文件："));
        source.sendFeedback(Text.literal("§8" + dir));
    }

    private static String coordPart(String barrelKey) {
        // Barrel keys are "world:x,y,z" but the world id itself contains a colon
        // (e.g. "monumenta:plots"), so strip every ':' — not just the first.
        String noWorld = barrelKey.substring(barrelKey.indexOf(':') + 1);
        return noWorld.replace(',', '_').replace(':', '_');
    }

    private static void writeRegistry(Path dir, Map<String, List<TransactionRecord>> byBarrel,
                                      Map<String, Barrel> barrelByKey) {
        Path file = dir.resolve("barrels.csv");
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", REGISTRY_HEADER)).append("\r\n");
        for (Map.Entry<String, List<TransactionRecord>> entry : byBarrel.entrySet()) {
            Barrel barrel = barrelByKey.get(entry.getKey());
            sb.append(escape(barrel == null ? "" : barrel.world)).append(',')
                    .append(barrel == null ? "" : barrel.x).append(',')
                    .append(barrel == null ? "" : barrel.y).append(',')
                    .append(barrel == null ? "" : barrel.z).append(',')
                    .append(escape(barrel == null ? "" : barrel.item)).append(',')
                    .append(escape(barrel == null ? "" : barrel.buyPrice)).append(',')
                    .append(escape(barrel == null ? "" : barrel.sellPrice)).append(',')
                    .append(entry.getValue().size())
                    .append("\r\n");
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(sb.toString());
        }
        catch (IOException ignored) {
            // Non-fatal; the per-barrel files are the primary output.
        }
    }

    private static void writeCsv(Path file, List<TransactionRecord> records) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", HEADER)).append("\r\n");
        for (TransactionRecord r : records) {
            sb.append(escape(r.timestampText)).append(',')
                    .append(escape(r.player)).append(',')
                    .append(escape(r.itemName)).append(',')
                    .append(escape(r.material)).append(',')
                    .append(r.amount).append(',')
                    .append(escape(r.direction())).append(',')
                    .append(escape(r.currencyType)).append(',')
                    .append(escape(r.currencyTier)).append(',')
                    .append(r.barrelX).append(',')
                    .append(r.barrelY).append(',')
                    .append(r.barrelZ).append(',')
                    .append(escape(r.world))
                    .append("\r\n");
        }

        // UTF-8 BOM so Windows Excel opens the file with correct encoding.
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write(sb.toString());
        }
    }

    /** Turn arbitrary sign text into a filesystem-safe file name fragment. */
    private static String safeName(String value) {
        if (value == null || value.isEmpty()) {
            return "unregistered";
        }
        String out = value.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
        return out.length() > 40 ? out.substring(0, 40) : out;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
