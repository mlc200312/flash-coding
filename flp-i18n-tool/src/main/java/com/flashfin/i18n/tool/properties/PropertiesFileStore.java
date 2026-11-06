package com.flashfin.i18n.tool.properties;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;

public final class PropertiesFileStore {

    public PropertiesDocument read(Path file) throws PropertiesFileException {
        if (!Files.exists(file)) {
            return PropertiesDocument.empty();
        }
        try {
            return PropertiesDocument.parse(new String(Files.readAllBytes(file),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PropertiesFileException(PropertiesFileException.READ_FAILED,
                    "读取 properties 失败: " + file, e);
        }
    }

    /**
     * Writes the document atomically after backing up the previous content. The written
     * file is parsed again and compared with the expected active values.
     *
     * @return whether the file changed and where the previous content was backed up
     */
    public PropertiesWriteResult write(Path file, PropertiesDocument document,
                                       BackupSession backup, String module, Path moduleRoot)
            throws PropertiesFileException {
        String rendered = document.render();
        String previous = null;
        if (Files.isRegularFile(file)) {
            try {
                previous = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new PropertiesFileException(PropertiesFileException.READ_FAILED,
                        "读取 properties 失败: " + file, e);
            }
        }
        if (rendered.equals(previous)) {
            return new PropertiesWriteResult(false, null);
        }

        Path backupFile = null;
        try {
            backupFile = backup.backup(file, module, moduleRoot);
        } catch (IOException e) {
            throw new PropertiesFileException(PropertiesFileException.WRITE_FAILED,
                    "备份 properties 失败: " + file, e);
        }

        try {
            atomicWrite(file, rendered);
            verify(file, document.getActiveValues());
            return new PropertiesWriteResult(true, backupFile);
        } catch (PropertiesFileException e) {
            restore(file, backupFile, previous);
            throw e;
        } catch (IOException e) {
            restore(file, backupFile, previous);
            throw new PropertiesFileException(PropertiesFileException.WRITE_FAILED,
                    "写入 properties 失败: " + file, e);
        }
    }

    private void atomicWrite(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void verify(Path file, Map<String, String> expected)
            throws PropertiesFileException {
        Properties properties = new Properties();
        Reader reader = null;
        try {
            reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            throw new PropertiesFileException(PropertiesFileException.VERIFY_FAILED,
                    "写入后解析 properties 失败: " + file, e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actual = properties.getProperty(entry.getKey());
            if (actual == null || !actual.equals(entry.getValue())) {
                throw new PropertiesFileException(PropertiesFileException.VERIFY_FAILED,
                        "写入后校验失败: " + file + " key=" + entry.getKey(), null);
            }
        }
        for (String key : properties.stringPropertyNames()) {
            if (!expected.containsKey(key)) {
                throw new PropertiesFileException(PropertiesFileException.VERIFY_FAILED,
                        "写入后出现多余 key: " + file + " key=" + key, null);
            }
        }
    }

    private void restore(Path file, Path backupFile, String previous) {
        try {
            if (backupFile != null && Files.isRegularFile(backupFile)) {
                Files.copy(backupFile, file, StandardCopyOption.REPLACE_EXISTING);
            } else if (previous == null) {
                Files.deleteIfExists(file);
            } else {
                Files.write(file, previous.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // write failure is already reported; restore is best effort
        }
    }
}
