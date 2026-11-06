package com.flashfin.i18n.tool.journal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flashfin.i18n.tool.sync.UploadEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UploadJournal {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final String planId;
    private final Map<String, UploadEntry> completed;

    private UploadJournal(Path file, String planId, Map<String, UploadEntry> completed) {
        this.file = file;
        this.planId = planId;
        this.completed = completed;
    }

    public static UploadJournal open(Path toolDataDir, String planId) throws JournalException {
        Path file = toolDataDir.resolve("journal").resolve("upload-" + planId + ".json");
        Map<String, UploadEntry> completed = new LinkedHashMap<String, UploadEntry>();
        if (!Files.exists(file)) {
            return new UploadJournal(file, planId, completed);
        }
        try {
            JsonNode root = MAPPER.readTree(new String(Files.readAllBytes(file),
                    StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                throw new JournalException("journal 格式非法: " + file);
            }
            JsonNode entries = root.get("completed");
            if (entries != null && entries.isArray()) {
                for (JsonNode node : entries) {
                    UploadEntry entry = readEntry(node);
                    if (entry != null) {
                        completed.put(entry.id(), entry);
                    }
                }
            }
            return new UploadJournal(file, planId, completed);
        } catch (IOException e) {
            throw new JournalException("读取 journal 失败: " + file, e);
        }
    }

    public boolean isCompleted(UploadEntry entry) {
        return completed.containsKey(entry.id());
    }

    public List<UploadEntry> completedEntries() {
        return new ArrayList<UploadEntry>(completed.values());
    }

    public void markCompleted(UploadEntry entry) throws JournalException {
        completed.put(entry.id(), entry);
        persist();
    }

    public Path getFile() {
        return file;
    }

    private void persist() throws JournalException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("planId", planId);
        root.put("updatedAt", Instant.now().toString());
        ArrayNode entries = root.putArray("completed");
        for (UploadEntry entry : completed.values()) {
            ObjectNode node = entries.addObject();
            node.put("module", entry.getModule());
            node.put("os", entry.getOs());
            node.put("key", entry.getKey());
            node.put("type", entry.getType());
            node.put("language", entry.getLanguage());
            node.put("value", entry.getValue());
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                Files.write(temp, MAPPER.writeValueAsBytes(root));
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new JournalException("写入 journal 失败: " + file, e);
        }
    }

    private static UploadEntry readEntry(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String module = text(node, "module");
        String os = text(node, "os");
        String key = text(node, "key");
        String language = text(node, "language");
        if (module == null || os == null || key == null || language == null) {
            return null;
        }
        return new UploadEntry(module, os, key, text(node, "type"), language,
                text(node, "value"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
