package com.flashfin.i18n.tool.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModulesConfigLoader {

    private static final List<String> DEFAULT_LANGUAGES = Collections.unmodifiableList(
            Arrays.asList("zh_CN", "en", "th", "en_id"));

    public ModulesConfig load(Path configPath) throws ConfigException {
        if (configPath == null) {
            throw new ConfigException(ConfigException.CONFIG_NOT_FOUND, "未指定配置文件");
        }
        if (!Files.isRegularFile(configPath)) {
            throw new ConfigException(ConfigException.CONFIG_NOT_FOUND, "配置文件不存在: " + configPath);
        }

        String content;
        try {
            content = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(ConfigException.CONFIG_NOT_FOUND, "无法读取配置文件: " + configPath, e);
        }

        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            loaded = new Yaml(new SafeConstructor(options)).load(content);
        } catch (YAMLException e) {
            throw new ConfigException(ConfigException.CONFIG_PARSE_ERROR, "YAML 解析失败: " + e.getMessage(), e);
        }

        Map<String, Object> root = asMap(loaded, "root");
        if (root.isEmpty()) {
            throw invalid("配置文件为空");
        }

        int version = requiredVersion(root);
        List<String> languages = parseLanguages(root);
        PlatformConfig platform = parsePlatform(root);
        Map<String, ModuleConfig> modules = parseModules(root);
        Path workspaceRoot = parseWorkspaceRoot(root, configPath);

        return new ModulesConfig(version, languages, platform, modules, workspaceRoot);
    }

    private Path parseWorkspaceRoot(Map<String, Object> root, Path configPath) throws ConfigException {
        String raw = optionalString(root, "workspaceRoot", ".", "workspaceRoot");
        Path base = configPath.toAbsolutePath().normalize().getParent();
        if (base == null) {
            base = Paths.get(".").toAbsolutePath().normalize();
        }
        Path value = Paths.get(raw);
        Path resolved = (value.isAbsolute() ? value : base.resolve(value)).normalize();
        if (!Files.isDirectory(resolved)) {
            throw invalid("workspaceRoot 不存在或不是目录: " + resolved);
        }
        return resolved;
    }

    private int requiredVersion(Map<String, Object> root) throws ConfigException {
        Object value = root.get("version");
        if (!(value instanceof Number) || ((Number) value).intValue() != 1) {
            throw invalid("version 必须为 1");
        }
        return 1;
    }

    private List<String> parseLanguages(Map<String, Object> root) throws ConfigException {
        Object value = root.get("languages");
        if (value == null) {
            return new ArrayList<String>(DEFAULT_LANGUAGES);
        }
        List<String> raw = asStringList(value, "languages");
        if (raw.isEmpty()) {
            throw invalid("languages 不能为空");
        }
        List<String> result = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String language : raw) {
            String trimmed = language.trim();
            if (trimmed.isEmpty()) {
                throw invalid("languages 包含空值");
            }
            if (!seen.add(trimmed)) {
                throw invalid("languages 包含重复项: " + trimmed);
            }
            result.add(trimmed);
        }
        return result;
    }

    private PlatformConfig parsePlatform(Map<String, Object> root) throws ConfigException {
        Map<String, Object> platform = asMap(root.get("platform"), "platform");
        String saveUrl = requiredUrl(platform, "saveUrl", "platform.saveUrl");
        String dictionaryBase = requiredUrl(platform, "dictionaryBase", "platform.dictionaryBase");
        int timeoutMs = optionalInt(platform, "timeoutMs", "platform.timeoutMs", 30000, 100, 600000);
        int maxRetries = optionalInt(platform, "maxRetries", "platform.maxRetries", 3, 0, 10);
        return new PlatformConfig(saveUrl, dictionaryBase, timeoutMs, maxRetries);
    }

    private Map<String, ModuleConfig> parseModules(Map<String, Object> root) throws ConfigException {
        Map<String, Object> modules = asMap(root.get("modules"), "modules");
        if (modules.isEmpty()) {
            throw invalid("modules 不能为空");
        }
        Map<String, ModuleConfig> result = new LinkedHashMap<String, ModuleConfig>();
        for (Map.Entry<String, Object> entry : modules.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            if (name.isEmpty()) {
                throw invalid("modules 包含空模块名");
            }
            String path = "modules." + name;
            Map<String, Object> module = asMap(entry.getValue(), path);

            String repoRoot = requiredString(module, "repoRoot", path + ".repoRoot");
            String domainPath = requiredString(module, "domainPath", path + ".domainPath");
            String os = requiredString(module, "os", path + ".os");
            if (!ModuleConfig.SUPPORTED_OS.contains(os)) {
                throw invalid(path + ".os 不受支持: " + os + "，可选值 " + ModuleConfig.SUPPORTED_OS);
            }
            List<String> packages = asStringList(module.get("packages"), path + ".packages");
            if (packages.isEmpty()) {
                throw invalid(path + ".packages 不能为空");
            }
            String codeFile = requiredString(module, "codeFile", path + ".codeFile");
            String enumFile = requiredString(module, "enumFile", path + ".enumFile");

            result.put(name, new ModuleConfig(name, repoRoot, domainPath, os, packages, codeFile, enumFile));
        }
        return result;
    }

    private Map<String, Object> asMap(Object value, String path) throws ConfigException {
        if (!(value instanceof Map)) {
            throw invalid(path + " 必须是对象");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw invalid(path + " 包含非字符串键");
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private List<String> asStringList(Object value, String path) throws ConfigException {
        if (!(value instanceof List)) {
            throw invalid(path + " 必须是数组");
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) {
                throw invalid(path + " 只能包含字符串");
            }
            result.add((String) item);
        }
        return result;
    }

    private String requiredString(Map<String, Object> map, String key, String path) throws ConfigException {
        Object value = map.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw invalid(path + " 不能为空");
        }
        return ((String) value).trim();
    }

    private String optionalString(Map<String, Object> map, String key, String defaultValue,
                                  String path) throws ConfigException {
        if (!map.containsKey(key) || map.get(key) == null) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw invalid(path + " 不能为空");
        }
        return ((String) value).trim();
    }

    private String requiredUrl(Map<String, Object> map, String key, String path) throws ConfigException {
        String value = requiredString(map, key, path);
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw invalid(path + " 不是合法 URL: " + value);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw invalid(path + " 必须使用 http/https: " + value);
        }
        if (uri.getHost() == null) {
            throw invalid(path + " 缺少主机名: " + value);
        }
        if (uri.getUserInfo() != null) {
            throw invalid(path + " 不得包含用户名或密码");
        }
        return value;
    }

    private int optionalInt(Map<String, Object> map, String key, String path,
                            int defaultValue, int min, int max) throws ConfigException {
        if (!map.containsKey(key) || map.get(key) == null) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (!(value instanceof Number)) {
            throw invalid(path + " 必须是整数");
        }
        double asDouble = ((Number) value).doubleValue();
        int asInt = ((Number) value).intValue();
        if (asDouble != asInt) {
            throw invalid(path + " 必须是整数");
        }
        if (asInt < min || asInt > max) {
            throw invalid(path + " 必须在 " + min + " 到 " + max + " 之间");
        }
        return asInt;
    }

    private ConfigException invalid(String message) {
        return new ConfigException(ConfigException.CONFIG_INVALID, message);
    }
}
