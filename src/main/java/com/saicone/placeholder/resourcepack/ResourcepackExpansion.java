package com.saicone.placeholder.resourcepack;

import me.clip.placeholderapi.expansion.Cacheable;
import me.clip.placeholderapi.expansion.Cleanable;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.packs.ResourcePack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ResourcepackExpansion extends PlaceholderExpansion implements Listener, Cacheable, Cleanable {

    private final PackTranslations translations = new PackTranslations();

    private static final String CACHE_FILE = "resourcepack-expansion-cache.csv.tmp";

    private final Map<UUID, PlayerResourcePackStatusEvent.Status> states = new HashMap<>();
    private final Path cacheFile = Paths.get(System.getProperty("java.io.tmpdir")).resolve(CACHE_FILE);

    public ResourcepackExpansion() {
        // Register listener
        Bukkit.getPluginManager().registerEvents(this, getPlaceholderAPI());
        // Load cached states
        try {
            loadCache();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadCache() throws IOException {
        if (Files.exists(cacheFile)) {
            try (BufferedReader reader = Files.newBufferedReader(cacheFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final int index = line.indexOf(',');
                    if (index < 0) {
                        continue;
                    }
                    final UUID uniqueId = UUID.fromString(line.substring(0, index).trim());
                    // Ignore offline players
                    if (Bukkit.getPlayer(uniqueId) == null) {
                        continue;
                    }
                    final PlayerResourcePackStatusEvent.Status status = PlayerResourcePackStatusEvent.Status.valueOf(line.substring(index + 1).trim());
                    states.put(uniqueId, status);
                }
            }
        }
    }

    public void saveCache() throws IOException {
        if (states.isEmpty()) {
            if (Files.exists(cacheFile)) {
                Files.delete(cacheFile);
            }
            return;
        } else if (!Files.exists(cacheFile)) {
            Files.createFile(cacheFile);
            cacheFile.toFile().deleteOnExit();
        }
        try (BufferedWriter writer = Files.newBufferedWriter(cacheFile)) {
            for (Map.Entry<UUID, PlayerResourcePackStatusEvent.Status> entry : states.entrySet()) {
                writer.write(entry.getKey().toString());
                writer.write(",");
                writer.write(entry.getValue().name());
                writer.newLine();
            }
        }
    }

    @Override
    public void clear() {
        // Unregister listener
        HandlerList.unregisterAll(this);
        // Save cached states
        try {
            saveCache();
        } catch (IOException e) {
            e.printStackTrace();
        }
        states.clear();
        translations.close();
    }

    @Override
    public void cleanup(Player player) {
        states.remove(player.getUniqueId());
    }

    @Override
    public @NotNull String getIdentifier() {
        return "resourcepack";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Rubenicos";
    }

    @Override
    public @NotNull String getVersion() {
        return "${version}";
    }

    @EventHandler
    public void onStatusUpdate(PlayerResourcePackStatusEvent event) {
        states.put(event.getPlayer().getUniqueId(), event.getStatus());
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("locale")) {
            return player instanceof Player ? ((Player) player).getLocale() : "en_us";
        }
        if (params.regionMatches(true, 0, "translate_", 0, 10)) {
            if (!(player instanceof Player)) return params.substring(10);
            String key = params.substring(10);
            return key.isEmpty() ? "" : translations.translate((Player) player, key);
        }
        switch (params.toLowerCase()) {
            case "loaded":
                return String.valueOf(states.get(player.getUniqueId()) == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
            case "status":
                final PlayerResourcePackStatusEvent.Status status = states.get(player.getUniqueId());
                return status != null ? status.name() : "UNKNOWN";
            case "id":
                final ResourcePack pack = Bukkit.getServerResourcePack();
                return pack == null ? "null" : String.valueOf(pack.getId());
            case "url":
                return Bukkit.getResourcePack();
            case "hash":
                return Bukkit.getResourcePackHash();
            case "prompt":
                return Bukkit.getResourcePackPrompt();
            case "required":
                return String.valueOf(Bukkit.isResourcePackRequired());
            default:
                return "invalid params";
        }
    }
}
