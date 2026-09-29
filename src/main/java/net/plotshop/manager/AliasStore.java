package net.plotshop.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps sign aliases to the real item names they stand for (e.g. "white mat"
 * -&gt; "Soul Essence"). Learned automatically when registering a barrel whose
 * sign name differs from the item actually stored inside it, and editable via
 * {@code /shop alias}. Persisted as {@code config/plotshop-manager/aliases.json}.
 */
public final class AliasStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();

    private static AliasStore instance;

    private final Map<String, String> aliases = new LinkedHashMap<>();

    private AliasStore() {
        load();
    }

    public static AliasStore get() {
        if (instance == null) {
            instance = new AliasStore();
        }
        return instance;
    }

    /** Resolve an alias to the real item name; returns {@code name} if unknown. */
    public String resolve(String name) {
        if (name == null) {
            return null;
        }
        String actual = aliases.get(name.trim());
        return actual != null ? actual : name;
    }

    /** Register (or update) an alias mapping. */
    public void put(String alias, String actual) {
        if (alias == null || actual == null) {
            return;
        }
        String a = alias.trim();
        String b = actual.trim();
        if (a.isEmpty() || b.isEmpty() || a.equalsIgnoreCase(b)) {
            return;
        }
        if (!b.equals(aliases.get(a))) {
            aliases.put(a, b);
            save();
        }
    }

    public Map<String, String> all() {
        return aliases;
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("plotshop-manager").resolve("aliases.json");
    }

    private void load() {
        Path file = file();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
            aliases.clear();
            if (loaded != null) {
                aliases.putAll(loaded);
            }
        }
        catch (IOException ignored) {
            // A corrupt file simply starts empty.
        }
    }

    private void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(aliases, writer);
            }
        }
        catch (IOException ignored) {
            // Best effort; the in-memory map still works for this session.
        }
    }
}
