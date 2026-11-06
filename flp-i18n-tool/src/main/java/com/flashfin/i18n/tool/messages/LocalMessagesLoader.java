package com.flashfin.i18n.tool.messages;

import com.flashfin.i18n.tool.dictionary.LanguageCodes;
import com.flashfin.i18n.tool.scan.ResolvedModule;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class LocalMessagesLoader {

    public LocalMessages load(ResolvedModule module, String language)
            throws LocalMessagesException {
        String suffix = LanguageCodes.propertiesSuffix(language);
        Path i18nDir = module.getDomainRoot().resolve("src/main/resources/i18n");
        Map<String, String> values = new LinkedHashMap<String, String>();
        List<String> loaded = new ArrayList<String>();
        List<String> missing = new ArrayList<String>();

        if (!Files.isDirectory(i18nDir)) {
            missing.add(i18nDir.toString());
            return new LocalMessages(language, values, loaded, missing);
        }

        loadFile(i18nDir, module.getConfig().getCodeFile() + "_" + suffix + ".properties",
                values, loaded, missing);
        loadFile(i18nDir, module.getConfig().getEnumFile() + "_" + suffix + ".properties",
                values, loaded, missing);
        if ("zh_CN".equals(LanguageCodes.canonical(language))) {
            loadFile(i18nDir, module.getConfig().getCodeFile() + ".properties",
                    values, loaded, missing);
            loadFile(i18nDir, module.getConfig().getEnumFile() + ".properties",
                    values, loaded, missing);
        }
        return new LocalMessages(language, values, loaded, missing);
    }

    private void loadFile(Path dir, String fileName, Map<String, String> values,
                          List<String> loaded, List<String> missing)
            throws LocalMessagesException {
        Path file = dir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            missing.add(file.toString());
            return;
        }
        loadProperties(file, values);
        loaded.add(file.toString());
    }

    private void loadProperties(Path file, Map<String, String> values)
            throws LocalMessagesException {
        Properties properties = new Properties();
        Reader reader = null;
        try {
            reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            throw new LocalMessagesException(LocalMessagesException.LOCAL_MESSAGES_READ_FAILED,
                    "读取本地多语言文件失败: " + file, e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // read-only file
                }
            }
        }
        for (String key : properties.stringPropertyNames()) {
            if (!values.containsKey(key)) {
                values.put(key, properties.getProperty(key));
            }
        }
    }
}
