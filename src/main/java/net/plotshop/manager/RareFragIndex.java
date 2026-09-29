package net.plotshop.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads {@code rare_frag_map.json} (generated from the Monumenta item
 * dictionary) and resolves item names to their rare-fragment group, so a frag
 * barrel can accept either the fragment itself or any rare belonging to it as
 * interchangeable goods.
 */
public final class RareFragIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("plotshop-manager");

    /** Masterwork (star) suffix: "Name-3" -> base "Name". Levels observed: 0..6. */
    private static final Pattern MW_SUFFIX = Pattern.compile("^(.*)-(\\d{1,2})$");
    private static final int MW_MAX_LEVEL = 6;

    private static RareFragIndex instance;

    private final Map<String, String> rareToFrag = new HashMap<>();
    private final Set<String> frags = new HashSet<>();

    private RareFragIndex() {
        load();
    }

    public static RareFragIndex get() {
        if (instance == null) {
            instance = new RareFragIndex();
        }
        return instance;
    }

    /** Strip a trailing masterwork "-N" suffix (N in 0..6) from an item name. */
    public static String baseName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        Matcher m = MW_SUFFIX.matcher(trimmed);
        if (m.matches()) {
            try {
                if (Integer.parseInt(m.group(2)) <= MW_MAX_LEVEL) {
                    return m.group(1);
                }
            }
            catch (NumberFormatException ignored) {
                // Not a numeric suffix; fall through.
            }
        }
        return trimmed;
    }

    /**
     * The frag group name when {@code itemName} is a fragment or a rare in the
     * dictionary, otherwise null. Fragment names match exactly; rare names are
     * matched after stripping the masterwork suffix.
     */
    public String fragOf(String itemName) {
        if (itemName == null) {
            return null;
        }
        String name = itemName.trim();
        if (frags.contains(name)) {
            return name;
        }
        return rareToFrag.get(baseName(name));
    }

    /** True when {@code name} is a rare fragment. */
    public boolean isFrag(String name) {
        return name != null && frags.contains(name.trim());
    }

    /** True when {@code name} is a rare that belongs to some fragment group. */
    public boolean isRare(String name) {
        return name != null && rareToFrag.containsKey(baseName(name));
    }

    private void load() {
        try (InputStream in = getClass().getResourceAsStream("/rare_frag_map.json")) {
            if (in == null) {
                LOGGER.warn("rare_frag_map.json missing; frag-group recognition disabled.");
                return;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject mapping = root.getAsJsonObject("rare_to_frag");
                for (Map.Entry<String, JsonElement> e : mapping.entrySet()) {
                    rareToFrag.put(e.getKey(), e.getValue().getAsString());
                }
                JsonObject fragments = root.getAsJsonObject("fragments");
                for (String frag : fragments.keySet()) {
                    frags.add(frag);
                }
            }
            LOGGER.info("Loaded rare/frag index: {} fragments, {} rares.",
                    frags.size(), rareToFrag.size());
        }
        catch (Exception e) {
            LOGGER.error("Failed to load rare_frag_map.json.", e);
        }
    }
}
