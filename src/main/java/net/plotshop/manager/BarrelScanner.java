package net.plotshop.manager;

import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sequential scanner that issues one CoreProtect lookup per registered barrel.
 *
 * <p>Per barrel it sends {@code co lookup c:X,Y,Z r:1 t:<time> a:container rows:100}
 * and then tracks the response in chat. A page footer ({@code Page X/Y}) drives
 * pagination via {@code co l <page>}; the absence of a footer means the whole
 * result fit on one page. Records are not parsed here — the owning client reads
 * {@link #current()} to attribute parsed records to the barrel being queried.</p>
 */
public class BarrelScanner {

    public interface Sender {
        void send(String command);
    }

    public interface Callback {
        void onBarrelStart(Barrel barrel, int index, int total);
        /** Fired when a barrel's lookup finishes; {@code success} is false on timeout/db-busy skips. */
        void onBarrelDone(Barrel barrel, boolean success);
        void onFinished(int scanned);
    }

    /** Resolves the CoreProtect lookup time for a barrel, enabling per-barrel incremental scans. */
    public interface TimeResolver {
        String timeFor(Barrel barrel);
    }

    private static final Pattern FOOTER_PATTERN = Pattern.compile("Page (\\d+)/(\\d+)");
    private static final long HEADER_TIMEOUT_MS = 6000;
    private static final long QUIET_PERIOD_MS = 1500;

    private final Sender sender;
    private final Callback callback;
    private final Consumer<String> feedback;
    private final Deque<Barrel> queue = new ArrayDeque<>();

    private boolean scanning = false;
    private Barrel current = null;
    private int scanned = 0;
    private int total = 0;
    private TimeResolver resolver;
    private boolean barrelSucceeded = false;

    private long querySentAt = 0;
    private long lastChatAt = 0;
    private boolean sawHeader = false;

    public BarrelScanner(Sender sender, Callback callback, Consumer<String> feedback) {
        this.sender = sender;
        this.callback = callback;
        this.feedback = feedback;
    }

    public boolean isScanning() {
        return scanning;
    }

    /** The barrel currently being queried; null between/after scans. */
    public Barrel current() {
        return current;
    }

    /** Number of barrels finished so far during the current scan. */
    public int scanned() {
        return scanned;
    }

    /** Total barrels in the current scan. */
    public int total() {
        return total;
    }

    public void start(List<Barrel> barrels, TimeResolver resolver) {
        queue.clear();
        queue.addAll(barrels);
        this.resolver = resolver;
        this.scanned = 0;
        this.total = barrels.size();
        this.scanning = true;
        this.current = null;
        advanceBarrel();
    }

    public void stop() {
        scanning = false;
        queue.clear();
        current = null;
    }

    /** Call once per client tick; advances the queue on timeouts / quiet periods. */
    public void tick() {
        if (!scanning || current == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!sawHeader) {
            if (now - querySentAt > HEADER_TIMEOUT_MS) {
                feedback.accept("§7[§ePlot§aShop§7] §c桶 @" + current.x + "," + current.y + "," + current.z
                        + " §c无响应，已跳过");
                advanceBarrel();
            }
        }
        else if (now - lastChatAt > QUIET_PERIOD_MS) {
            // No footer means all rows fit on one page; silence marks completion.
            barrelSucceeded = true;
            advanceBarrel();
        }
    }

    /** Feed every received game message here while scanning. */
    public void onMessage(Text message) {
        if (!scanning || current == null) {
            return;
        }
        String plain = message.getString();
        long now = System.currentTimeMillis();

        if (plain.contains("Lookup Results")) {
            sawHeader = true;
            lastChatAt = now;
            return;
        }

        if (plain.contains("Database busy") || plain.contains("Database locked")) {
            feedback.accept("§7[§ePlot§aShop§7] §c桶 @" + current.x + "," + current.y + "," + current.z
                    + " §c数据库繁忙，已跳过");
            advanceBarrel();
            return;
        }

        if (plain.contains("No results") || plain.contains("No data")
                || plain.contains("No transactions") || plain.contains("No interactions")
                || plain.contains("No messages")) {
            barrelSucceeded = true;
            advanceBarrel();
            return;
        }

        Matcher m = FOOTER_PATTERN.matcher(plain);
        if (m.find()) {
            int page = Integer.parseInt(m.group(1));
            int pages = Integer.parseInt(m.group(2));
            if (page < pages) {
                querySentAt = now;
                lastChatAt = now;
                sawHeader = false;
                sender.send("co l " + (page + 1));
            }
            else {
                barrelSucceeded = true;
                advanceBarrel();
            }
            return;
        }

        if (sawHeader && (plain.contains("added") || plain.contains("removed"))) {
            lastChatAt = now;
        }
    }

    private void advanceBarrel() {
        if (current != null && callback != null) {
            callback.onBarrelDone(current, barrelSucceeded);
        }
        scanned++;
        if (queue.isEmpty()) {
            scanning = false;
            current = null;
            if (callback != null) {
                callback.onFinished(scanned);
            }
            return;
        }
        current = queue.poll();
        barrelSucceeded = false;
        sawHeader = false;
        lastChatAt = 0;
        querySentAt = System.currentTimeMillis();
        if (callback != null) {
            callback.onBarrelStart(current, scanned, total);
        }
        String queryTime = resolver != null ? resolver.timeFor(current) : "7d";
        sender.send("co lookup c:" + current.x + "," + current.y + "," + current.z
                + " r:1 t:" + queryTime + " a:container rows:100");
    }
}
