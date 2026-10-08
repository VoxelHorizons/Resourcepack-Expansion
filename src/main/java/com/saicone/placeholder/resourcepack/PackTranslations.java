package com.saicone.placeholder.resourcepack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Read assets/minecraft/lang/*.json from the actual server resource pack.
 * Translation requests never perform file or network I/O on the server thread.
 */
final class PackTranslations {
    // Poll changes to config/local packs at most once every 30 seconds.
    private static final long REFRESH_NANOS = 30_000_000_000L;
    private static final long REMOTE_REFRESH_NANOS = 300_000_000_000L;
    private static final int MAX_ZIP_BYTES = 64 * 1024 * 1024;
    private static final int MAX_LANG_BYTES = 4 * 1024 * 1024;
    private static final String PREFIX = "assets/minecraft/lang/";
    private static final Path LOCAL_PACK = Paths.get("plugins", "PlaceholderAPI", "resourcepack-translations.zip");
    private static final Path CONFIG_FILE = Paths.get("plugins", "PlaceholderAPI", "resourcepack-translations.yml");

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Resourcepack-Translations");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Map<String, String>> translations = Collections.emptyMap();
    private volatile long nextRefresh = 0L;
    private volatile String source = "";
    private volatile String localFingerprint = "";
    private volatile long lastRemoteLoad = 0L;

    String translate(Player player, String key) {
        ensureLoaded();
        String locale = player.getLocale().toLowerCase(Locale.ROOT).replace('-', '_');
        Map<String, Map<String, String>> snapshot = translations;
        String value = lookup(snapshot, locale, key);
        if (value == null) {
            int separator = locale.indexOf('_');
            if (separator > 0) {
                value = lookup(snapshot, locale.substring(0, separator), key);
            }
        }
        if (value == null) value = lookup(snapshot, "en_us", key);
        return value == null ? key : value;
    }

    private static String lookup(Map<String, Map<String, String>> all, String locale, String key) {
        Map<String, String> map = all.get(locale);
        return map == null ? null : map.get(key);
    }

    void ensureLoaded() {
        long now = System.nanoTime();
        if (now - nextRefresh < 0 || !running.compareAndSet(false, true)) return;
        nextRefresh = now + REFRESH_NANOS;
        // Read the server's URL on the calling thread, not from the async loader.
        String serverUrl = Bukkit.getResourcePack();
        loader.execute(() -> {
            try {
                load(serverUrl);
            } catch (Exception e) {
                Bukkit.getLogger().warning("[ResourcepackExpansion] Cannot read translations: " + e.getMessage());
            } finally {
                running.set(false);
            }
        });
    }

    private void load(String serverUrl) throws IOException {
        String configuredPath = configuredPackPath();
        Path local;
        if (!configuredPath.isEmpty()) {
            local = Paths.get(configuredPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(local)) {
                throw new IOException("Configured resource pack not found: " + local);
            }
        } else {
            local = Files.isRegularFile(LOCAL_PACK) ? LOCAL_PACK.toAbsolutePath().normalize() : null;
        }

        String location = local != null ? local.toString() : (serverUrl == null ? "" : serverUrl);
        if (location.isEmpty()) {
            translations = Collections.emptyMap();
            source = "";
            localFingerprint = "";
            return;
        }

        // VoxelCore can replace its ZIP during publishing: notice an updated
        // file without restarting the expansion or redownloading anything.
        String fingerprint = local == null ? "" :
                Files.getLastModifiedTime(local).to(TimeUnit.NANOSECONDS) + ":" + Files.size(local);
        long now = System.nanoTime();
        if (source.equals(location)) {
            if (local != null && fingerprint.equals(localFingerprint)) return;
            if (local == null && now - lastRemoteLoad < REMOTE_REFRESH_NANOS) return;
        }

        try (InputStream stream = local != null ? Files.newInputStream(local) : remoteStream(location);
             ZipInputStream zip = new ZipInputStream(stream, StandardCharsets.UTF_8)) {
            Map<String, Map<String, String>> found = new HashMap<>();
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith(PREFIX) && name.endsWith(".json") && !entry.isDirectory()
                        && name.indexOf('/', PREFIX.length()) < 0) {
                    String locale = name.substring(PREFIX.length(), name.length() - 5).toLowerCase(Locale.ROOT);
                    if (locale.matches("[a-z0-9_]+")) {
                        byte[] bytes = readLimited(zip, MAX_LANG_BYTES);
                        try (Reader reader = new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                            JsonElement parsed = new JsonParser().parse(reader);
                            if (parsed.isJsonObject()) {
                                Map<String, String> values = new HashMap<>();
                                JsonObject object = parsed.getAsJsonObject();
                                for (Map.Entry<String, JsonElement> row : object.entrySet()) {
                                    if (row.getValue().isJsonPrimitive() && row.getValue().getAsJsonPrimitive().isString()) {
                                        values.put(row.getKey(), row.getValue().getAsString());
                                    }
                                }
                                found.put(locale, Collections.unmodifiableMap(values));
                            }
                        }
                    }
                }
                zip.closeEntry();
                if (++count > 20000) throw new IOException("ZIP has too many entries");
            }
            // If a pack is being rebuilt or has no translations, preserve the
            // previous known-good cache rather than replacing it with nothing.
            if (found.isEmpty()) {
                throw new IOException("No assets/minecraft/lang/*.json files found in " + location);
            }
            translations = Collections.unmodifiableMap(found);
            source = location;
            localFingerprint = fingerprint;
            if (local == null) lastRemoteLoad = now;
        }
    }

    private static String configuredPackPath() throws IOException {
        if (!Files.exists(CONFIG_FILE)) {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.write(CONFIG_FILE, (
                    "# Resourcepack Expansion: local resource-pack ZIP path.\\n" +
                    "# Relative paths start at the Minecraft server working directory.\\n" +
                    "# Example: plugins/VoxelCore/build/resource-packs/mc26.2.zip\\n" +
                    "# Leave blank to use resourcepack-translations.zip or the server pack URL.\\n" +
                    "pack-path: ''\\n"
            ).replace("\\n", "\n").getBytes(StandardCharsets.UTF_8));
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(CONFIG_FILE.toFile());
        return yaml.getString("pack-path", "").trim();
    }

    private static InputStream remoteStream(String address) throws IOException {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol()) && !"http".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Resource pack URL must be HTTP(S)");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        InputStream input = connection.getInputStream();
        return new java.io.FilterInputStream(input) {
            int total;
            @Override
            public int read() throws IOException {
                int result = super.read();
                if (result >= 0 && ++total > MAX_ZIP_BYTES) throw new IOException("Resource pack exceeds 64 MiB");
                return result;
            }
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int amount = super.read(b, off, Math.min(len, MAX_ZIP_BYTES - total + 1));
                if (amount > 0 && (total += amount) > MAX_ZIP_BYTES) throw new IOException("Resource pack exceeds 64 MiB");
                return amount;
            }
            @Override
            public void close() throws IOException {
                try { super.close(); } finally { connection.disconnect(); }
            }
        };
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (result.size() + read > limit) throw new IOException("Language file exceeds 4 MiB");
            result.write(buffer, 0, read);
        }
        return result.toByteArray();
    }

    void close() {
        loader.shutdownNow();
    }
}
