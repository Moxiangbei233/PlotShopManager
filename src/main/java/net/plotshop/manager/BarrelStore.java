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
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory registry of registered barrels, persisted as JSON in the mod's
 * config folder so registrations survive restarts.
 */
public class BarrelStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Barrel>>() {}.getType();

    private final List<Barrel> barrels = new ArrayList<>();

    public List<Barrel> all() {
        return barrels;
    }

    public void load() {
        Path file = file();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Barrel> loaded = GSON.fromJson(reader, LIST_TYPE);
            barrels.clear();
            if (loaded != null) {
                barrels.addAll(loaded);
            }
        }
        catch (IOException ignored) {
            // A corrupt file simply starts empty; next save will overwrite it.
        }
    }

    public void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(barrels, writer);
            }
        }
        catch (IOException ignored) {
            // Best effort; the in-memory registry still works for this session.
        }
    }

    public void upsert(Barrel barrel) {
        String key = barrel.key();
        for (int i = 0; i < barrels.size(); i++) {
            if (barrels.get(i).key().equals(key)) {
                Barrel existing = barrels.get(i);
                // Re-registering (e.g. a price change) must not reset the last scan time.
                if (barrel.lastScanTime == 0) {
                    barrel.lastScanTime = existing.lastScanTime;
                }
                barrels.set(i, barrel);
                return;
            }
        }
        barrels.add(barrel);
    }

    public boolean remove(String key) {
        return barrels.removeIf(barrel -> barrel.key().equals(key));
    }

    private Path file() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("plotshop-manager").resolve("barrels.json");
    }
}
