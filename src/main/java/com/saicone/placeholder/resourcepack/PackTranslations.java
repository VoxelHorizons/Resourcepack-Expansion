package com.saicone.placeholder.resourcepack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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
    private static final long REFRESH_NANOS = 300_000_000_000L;
    private static final int MAX_ZIP_BYTES = 64 * 1024 * 1024;
    private static final int MAX_LANG_BYTES = 4 * 1024 * 1024;
    private static final String PREFIX = "assets/minecraft/lang/";
    private static final Path LOCAL_PACK = Paths.get("plugins", "PlaceholderAPI", "resourcepack-translations.zip");

    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Resourcepack-Translations");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Map<String, Map<String, String>> translations = Collections.emptyMap();
    private volatile long nextRefresh = 0L;
    private volatile String source = "";

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
        loader.execute(() -> {
            try {
                load();
            } catch (Exception e) {
                Bukkit.getLogger().warning("[ResourcepackExpansion] Cannot read translations: " + e.getMessage());
            } finally {
                running.set(false);
            }
        });
    }

    private void load() throws IOException {
        String current = Bukkit.getResourcePack();
        if (current == null) current = "";
        String location = Files.isRegularFile(LOCAL_PACK) ? LOCAL_PACK.toAbsolutePath().toString() : current;
        if (location.isEmpty()) {
            translations = Collections.emptyMap();
            source = "";
            return;
        }
        try (InputStream stream = Files.isRegularFile(LOCAL_PACK)
                ? Files.newInputStream(LOCAL_PACK) : remoteStream(current);
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
            translations = Collections.unmodifiableMap(found);
            source = location;
        }
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
