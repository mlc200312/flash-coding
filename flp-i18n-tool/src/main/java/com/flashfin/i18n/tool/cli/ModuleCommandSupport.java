package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.config.ConfigException;
import com.flashfin.i18n.tool.config.ModuleConfig;
import com.flashfin.i18n.tool.config.ModulesConfig;
import com.flashfin.i18n.tool.config.ModulesConfigLoader;
import com.flashfin.i18n.tool.dictionary.LanguageCodes;
import com.flashfin.i18n.tool.fs.PathGuard;
import com.flashfin.i18n.tool.fs.PathGuardException;
import com.flashfin.i18n.tool.report.ErrorInfo;
import com.flashfin.i18n.tool.report.JsonEnvelope;
import com.flashfin.i18n.tool.scan.GeneratedKeys;
import com.flashfin.i18n.tool.scan.ModuleRegistry;
import com.flashfin.i18n.tool.scan.ScanException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ModuleCommandSupport {

    private ModuleCommandSupport() {
    }

    static Setup prepare(CliArguments args, Map<String, String> env, String command) {
        Path configPath = resolveConfigPath(args, env).toAbsolutePath().normalize();
        ModulesConfig config;
        try {
            config = new ModulesConfigLoader().load(configPath);
        } catch (ConfigException e) {
            return Setup.error(failure(command, configPath, e.getCode(), e.getMessage(),
                    configHint(e.getCode())));
        }

        PathGuard guard;
        try {
            guard = PathGuard.create(config.getWorkspaceRoot(), env);
        } catch (PathGuardException e) {
            return Setup.error(failure(command, configPath, e.getCode(), e.getMessage(),
                    "检查 workspaceRoot 与 " + PathGuard.ENV_ALLOWED_ROOTS));
        }

        ModuleRegistry registry = ModuleRegistry.create(config, guard);
        List<ModuleConfig> selected;
        try {
            selected = registry.select(args.getModule(), args.getOs());
        } catch (ScanException e) {
            return Setup.error(failure(command, configPath, e.getCode(), e.getMessage(),
                    moduleHint(registry)));
        }
        if (selected.isEmpty()) {
            return Setup.error(failure(command, configPath, ScanException.MODULE_NOT_FOUND,
                    "没有匹配的模块: module=" + args.getModule() + ", os=" + args.getOs(),
                    moduleHint(registry)));
        }
        return Setup.ok(configPath, config, registry, selected);
    }

    static List<String> selectedLanguages(CliArguments args, ModulesConfig config)
            throws CliException {
        List<String> configured = new ArrayList<String>();
        for (String language : config.getLanguages()) {
            configured.add(LanguageCodes.canonical(language));
        }
        List<String> requested = args.getLanguages();
        if (requested.isEmpty()) {
            return configured;
        }
        List<String> result = new ArrayList<String>();
        for (String language : requested) {
            String canonical = LanguageCodes.canonical(language);
            if (!configured.contains(canonical)) {
                throw new CliException("未知语言: " + language + "，配置中可用语言: "
                        + config.getLanguages());
            }
            if (!result.contains(canonical)) {
                result.add(canonical);
            }
        }
        return result;
    }

    static CommandResult failure(String command, Path configPath, String code, String message,
                                 String hint) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("configPath", configPath.toString());
        data.put("configOk", false);
        return new CommandResult(ExitCode.INVALID,
                JsonEnvelope.failure(command, data, new ErrorInfo(code, message, hint, false)));
    }

    static Map<String, Object> errorData(String code, String message, String hint) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        error.put("hint", hint);
        return error;
    }

    static Map<String, Object> counts(GeneratedKeys keys) {
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        counts.put("code", keys.getCodeKeys().size());
        counts.put("enum", keys.getEnumKeys().size());
        counts.put("duplicate", keys.getDuplicates().size());
        return counts;
    }

    static String moduleHint(ModuleRegistry registry) {
        return "可用模块: " + registry.names();
    }

    static Path toolDataDir(Path configPath) {
        Path parent = configPath.getParent();
        return (parent == null ? Paths.get(".") : parent).resolve(".i18n-tool");
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String configHint(String code) {
        if (ConfigException.CONFIG_NOT_FOUND.equals(code)) {
            return "使用 --config <modules.yaml> 或设置 " + StatusCommand.ENV_CONFIG;
        }
        if (ConfigException.CONFIG_PARSE_ERROR.equals(code)) {
            return "检查 YAML 语法、缩进与重复键";
        }
        return "按 README 中的 modules.yaml 示例修正配置";
    }

    static String scanHint(String code) {
        if (ScanException.CLASSES_NOT_FOUND.equals(code)) {
            return "先在对应业务仓库执行 mvn -o compile 生成 target/classes";
        }
        if (ScanException.CLASSPATH_BUILD_FAILED.equals(code)) {
            return "确认已安装 Maven，且模块 pom.xml 依赖可解析（离线失败时会自动联网重试）";
        }
        if (ScanException.FRAMEWORK_CLASS_MISSING.equals(code)) {
            return "检查模块依赖与框架版本，确认 classpath 包含 kaffatech-mocha";
        }
        return "检查 modules.yaml 中的模块路径与包名";
    }

    private static Path resolveConfigPath(CliArguments args, Map<String, String> env) {
        if (args != null && args.getConfigPath() != null) {
            return args.getConfigPath();
        }
        String fromEnv = env == null ? null : env.get(StatusCommand.ENV_CONFIG);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return Paths.get(fromEnv.trim());
        }
        return Paths.get("modules.yaml");
    }

    static final class Setup {

        private final CommandResult error;
        private final Path configPath;
        private final ModulesConfig config;
        private final ModuleRegistry registry;
        private final List<ModuleConfig> selected;

        private Setup(CommandResult error, Path configPath, ModulesConfig config,
                      ModuleRegistry registry, List<ModuleConfig> selected) {
            this.error = error;
            this.configPath = configPath;
            this.config = config;
            this.registry = registry;
            this.selected = selected;
        }

        private static Setup error(CommandResult error) {
            return new Setup(error, null, null, null, null);
        }

        private static Setup ok(Path configPath, ModulesConfig config, ModuleRegistry registry,
                                List<ModuleConfig> selected) {
            return new Setup(null, configPath, config, registry, selected);
        }

        boolean hasError() {
            return error != null;
        }

        CommandResult getError() {
            return error;
        }

        Path getConfigPath() {
            return configPath;
        }

        ModulesConfig getConfig() {
            return config;
        }

        ModuleRegistry getRegistry() {
            return registry;
        }

        List<ModuleConfig> getSelected() {
            return selected;
        }
    }
}
