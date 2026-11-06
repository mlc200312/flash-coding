package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.config.ConfigException;
import com.flashfin.i18n.tool.config.ModuleConfig;
import com.flashfin.i18n.tool.config.ModulesConfig;
import com.flashfin.i18n.tool.config.ModulesConfigLoader;
import com.flashfin.i18n.tool.fs.PathGuard;
import com.flashfin.i18n.tool.fs.PathGuardException;
import com.flashfin.i18n.tool.report.ErrorInfo;
import com.flashfin.i18n.tool.report.JsonEnvelope;
import com.flashfin.i18n.tool.scan.ClassScanner;
import com.flashfin.i18n.tool.scan.GeneratedKeys;
import com.flashfin.i18n.tool.scan.KeyGenerator;
import com.flashfin.i18n.tool.scan.KeyRules;
import com.flashfin.i18n.tool.scan.MavenClasspathBuilder;
import com.flashfin.i18n.tool.scan.ModuleClasspathBuilder;
import com.flashfin.i18n.tool.scan.ModuleRegistry;
import com.flashfin.i18n.tool.scan.ModuleScanResult;
import com.flashfin.i18n.tool.scan.ModuleScanner;
import com.flashfin.i18n.tool.scan.ResolvedModule;
import com.flashfin.i18n.tool.scan.ScanException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScanCommand {

    private static final String COMMAND = "scan";

    private final ModulesConfigLoader loader;
    private final ModuleScanner moduleScanner;
    private final Map<String, String> env;

    public ScanCommand(ModulesConfigLoader loader, ModuleClasspathBuilder classpathBuilder,
                       ClassScanner scanner, KeyGenerator keyGenerator, Map<String, String> env) {
        this.loader = loader;
        this.moduleScanner = new ModuleScanner(classpathBuilder, scanner, keyGenerator);
        this.env = env;
    }

    public static ScanCommand defaults(Map<String, String> env) {
        return new ScanCommand(new ModulesConfigLoader(), new MavenClasspathBuilder(),
                new ClassScanner(), new KeyGenerator(KeyRules.defaults()), env);
    }

    public CommandResult execute(CliArguments args) {
        Path configPath = resolveConfigPath(args).toAbsolutePath().normalize();
        ModulesConfig config;
        try {
            config = loader.load(configPath);
        } catch (ConfigException e) {
            return failure(configPath, e.getCode(), e.getMessage(), configHint(e.getCode()));
        }

        PathGuard guard;
        try {
            guard = PathGuard.create(config.getWorkspaceRoot(), env);
        } catch (PathGuardException e) {
            return failure(configPath, e.getCode(), e.getMessage(),
                    "检查 workspaceRoot 与 " + PathGuard.ENV_ALLOWED_ROOTS);
        }

        ModuleRegistry registry = ModuleRegistry.create(config, guard);
        List<ModuleConfig> selected;
        try {
            selected = registry.select(args.getModule(), args.getOs());
        } catch (ScanException e) {
            return failure(configPath, e.getCode(), e.getMessage(), moduleHint(registry));
        }
        if (selected.isEmpty()) {
            return failure(configPath, ScanException.MODULE_NOT_FOUND,
                    "没有匹配的模块: module=" + args.getModule() + ", os=" + args.getOs(),
                    moduleHint(registry));
        }

        List<Map<String, Object>> moduleData = new ArrayList<Map<String, Object>>();
        List<ErrorInfo> errors = new ArrayList<ErrorInfo>();
        List<String> warnings = new ArrayList<String>();

        for (ModuleConfig moduleConfig : selected) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("module", moduleConfig.getName());
            data.put("os", moduleConfig.getOs());
            data.put("packages", moduleConfig.getPackages());
            try {
                moduleData.add(scanModule(registry, moduleConfig, data, warnings));
            } catch (ScanException e) {
                data.put("ok", false);
                data.put("error", errorData(e.getCode(), e.getMessage(), scanHint(e.getCode())));
                moduleData.add(data);
                errors.add(new ErrorInfo(e.getCode(), e.getMessage(), scanHint(e.getCode()), false));
            }
        }

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("configPath", configPath.toString());
        root.put("workspaceRoot", config.getWorkspaceRoot().toString());
        root.put("moduleCount", moduleData.size());
        root.put("modules", moduleData);

        if (!errors.isEmpty()) {
            return new CommandResult(ExitCode.INVALID,
                    JsonEnvelope.failure(COMMAND, root, warnings, errors.get(0)));
        }
        return new CommandResult(ExitCode.OK, JsonEnvelope.success(COMMAND, root, warnings));
    }

    private Map<String, Object> scanModule(ModuleRegistry registry, ModuleConfig moduleConfig,
                                           Map<String, Object> data, List<String> warnings)
            throws ScanException {
        ResolvedModule module = registry.resolve(moduleConfig);
        data.put("repoRoot", module.getRepoRoot().toString());
        data.put("domainPath", module.getDomainRoot().toString());
        data.put("classesDir", module.getClassesDir().toString());

        ModuleScanResult scanned = moduleScanner.scan(module);
        data.put("classpathEntries", scanned.getClasspath().getEntries().size());
        data.put("classpathBuiltOnline", scanned.getClasspath().isBuiltOnline());
        data.put("classesScanned", scanned.getClassesScanned());
        data.put("ok", true);
        data.put("counts", counts(scanned.getKeys()));
        data.put("codeKeys", scanned.getKeys().getCodeKeys());
        data.put("enumKeys", scanned.getKeys().getEnumKeys());
        data.put("duplicates", duplicates(scanned.getKeys()));
        if (!scanned.getClassLoadFailures().isEmpty()) {
            data.put("classLoadFailures", scanned.getClassLoadFailures());
            warnings.add("CLASS_LOAD_FAILED: 模块 " + module.getName() + " 有 "
                    + scanned.getClassLoadFailures().size() + " 个类无法加载，已跳过");
        }
        return data;
    }

    private Map<String, Object> counts(GeneratedKeys keys) {
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        counts.put("code", keys.getCodeKeys().size());
        counts.put("enum", keys.getEnumKeys().size());
        counts.put("duplicate", keys.getDuplicates().size());
        return counts;
    }

    private List<Map<String, Object>> duplicates(GeneratedKeys keys) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (GeneratedKeys.DuplicateKey duplicate : keys.getDuplicates().values()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", duplicate.getKey());
            item.put("type", duplicate.getType());
            item.put("firstSource", duplicate.getFirstSource());
            item.put("duplicateSource", duplicate.getDuplicateSource());
            result.add(item);
        }
        return result;
    }

    private CommandResult failure(Path configPath, String code, String message, String hint) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("configPath", configPath.toString());
        data.put("configOk", false);
        return new CommandResult(ExitCode.INVALID,
                JsonEnvelope.failure(COMMAND, data, new ErrorInfo(code, message, hint, false)));
    }

    private Map<String, Object> errorData(String code, String message, String hint) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        error.put("hint", hint);
        return error;
    }

    private String moduleHint(ModuleRegistry registry) {
        return "可用模块: " + registry.names();
    }

    private String scanHint(String code) {
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

    private String configHint(String code) {
        if (ConfigException.CONFIG_NOT_FOUND.equals(code)) {
            return "使用 --config <modules.yaml> 或设置 " + StatusCommand.ENV_CONFIG;
        }
        if (ConfigException.CONFIG_PARSE_ERROR.equals(code)) {
            return "检查 YAML 语法、缩进与重复键";
        }
        return "按 README 中的 modules.yaml 示例修正配置";
    }

    private Path resolveConfigPath(CliArguments args) {
        if (args != null && args.getConfigPath() != null) {
            return args.getConfigPath();
        }
        String fromEnv = env == null ? null : env.get(StatusCommand.ENV_CONFIG);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return Paths.get(fromEnv.trim());
        }
        return Paths.get("modules.yaml");
    }
}
