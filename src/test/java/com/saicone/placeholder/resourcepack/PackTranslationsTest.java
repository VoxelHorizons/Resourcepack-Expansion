package com.saicone.placeholder.resourcepack;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class PackTranslationsTest {
    private static Map<String, Map<String, String>> read(Map<String, String> languages) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : languages.entrySet()) {
                zip.putNextEntry(new ZipEntry("assets/minecraft/lang/" + file.getKey() + ".json"));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()),
                StandardCharsets.UTF_8)) {
            return PackTranslations.readTranslations(zip);
        }
    }

    @Test public void newSnapshotIncludesAddedAndChangedKeysButNotRemovedKeys() throws Exception {
        Map<String, String> oldPack = new LinkedHashMap<>();
        oldPack.put("en_us", "{\"menu.title\":\"Old title\",\"menu.removed\":\"Remove me\"}");
        Map<String, Map<String, String>> oldSnapshot = read(oldPack);

        Map<String, String> updatedPack = new LinkedHashMap<>();
        updatedPack.put("en_us", "{\"menu.title\":\"Updated title\",\"menu.added\":\"New key\"}");
        Map<String, Map<String, String>> updatedSnapshot = read(updatedPack);

        assertEquals("Old title", oldSnapshot.get("en_us").get("menu.title"));
        assertEquals("Updated title", updatedSnapshot.get("en_us").get("menu.title"));
        assertEquals("New key", updatedSnapshot.get("en_us").get("menu.added"));
        assertFalse(updatedSnapshot.get("en_us").containsKey("menu.removed"));
    }

    @Test public void newSnapshotDropsRemovedLocaleFiles() throws Exception {
        Map<String, String> oldPack = new LinkedHashMap<>();
        oldPack.put("en_us", "{\"title\":\"English\"}");
        oldPack.put("es_es", "{\"title\":\"Español\"}");

        Map<String, String> updatedPack = new LinkedHashMap<>();
        updatedPack.put("en_us", "{\"title\":\"Updated\"}");

        assertTrue(read(oldPack).containsKey("es_es"));
        assertFalse(read(updatedPack).containsKey("es_es"));
    }
}
