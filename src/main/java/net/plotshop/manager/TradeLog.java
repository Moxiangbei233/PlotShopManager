package net.plotshop.manager;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Appends customer behaviour logs, one CSV per barrel, under
 * {@code config/plotshop-manager/trade_logs/}. Every closed barrel screen that
 * involved item movement (or a detected mistrade) is recorded so the player can
 * review their own trading behaviour locally.
 */
public final class TradeLog {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] HEADER = {
            "timestamp", "world", "x", "y", "z", "label", "buy", "sell",
            "goods_change", "currency_change", "result", "detail"
    };

    private TradeLog() {
    }

    public static Path write(TradeSession session, Map<String, Integer> net,
                             TradeMath.NetChange classified, TradeMath.Validation validation,
                             TradeMath.FairPrice fair) {
        Path dir = FabricLoader.getInstance().getConfigDir()
                .resolve("plotshop-manager").resolve("trade_logs");
        try {
            Files.createDirectories(dir);
        }
        catch (IOException ignored) {
            return null;
        }

        String goods = serialize(net);
        String currency = serializeCurrency(classified);
        String result = validation.valid ? "ok" : "mistrade";
        String detail = validation.logDetail == null ? "" : validation.logDetail;
        if (fair != null) {
            detail = (detail.isEmpty() ? "" : detail + " | ") + "fair=" + fair.message;
        }

        StringBuilder row = new StringBuilder();
        row.append(escape(TIMESTAMP.format(LocalDateTime.now()))).append(',')
                .append(escape(session.world)).append(',')
                .append(session.pos.getX()).append(',')
                .append(session.pos.getY()).append(',')
                .append(session.pos.getZ()).append(',')
                .append(escape(session.label)).append(',')
                .append(escape(session.buyText)).append(',')
                .append(escape(session.sellText)).append(',')
                .append(escape(goods)).append(',')
                .append(escape(currency)).append(',')
                .append(escape(result)).append(',')
                .append(escape(detail));

        Path file = dir.resolve(fileName(session.pos));
        boolean isNew = !Files.exists(file);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (isNew) {
                writer.write('\uFEFF');
                writer.write(String.join(",", HEADER));
                writer.write("\r\n");
            }
            writer.write(row.toString());
            writer.write("\r\n");
        }
        catch (IOException ignored) {
            return null;
        }
        return file;
    }

    private static String fileName(net.minecraft.util.math.BlockPos pos) {
        return "x" + pos.getX() + "_y" + pos.getY() + "_z" + pos.getZ() + ".csv";
    }

    private static String serialize(Map<String, Integer> net) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : net.entrySet()) {
            if (e.getValue() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    private static String serializeCurrency(TradeMath.NetChange classified) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : classified.currency.entrySet()) {
            if (Math.abs(e.getValue()) < TradeMath.EPS) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey()).append(':').append(String.format(java.util.Locale.ROOT, "%.2f", e.getValue()));
        }
        return sb.toString();
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
