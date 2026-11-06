package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.config.ModuleConfig;
import com.flashfin.i18n.tool.dictionary.DictionaryClient;
import com.flashfin.i18n.tool.dictionary.DictionaryClientFactory;
import com.flashfin.i18n.tool.dictionary.LanguageCodes;
import com.flashfin.i18n.tool.dictionary.ModuleDictionaries;
import com.flashfin.i18n.tool.dictionary.RemoteDictionary;
import com.flashfin.i18n.tool.git.GitGuard;
import com.flashfin.i18n.tool.git.GitStatus;
import com.flashfin.i18n.tool.properties.BackupSession;
import com.flashfin.i18n.tool.properties.PropertiesDocument;
import com.flashfin.i18n.tool.properties.PropertiesFileException;
import com.flashfin.i18n.tool.properties.PropertiesFileStore;
import com.flashfin.i18n.tool.properties.PropertiesWriteResult;
import com.flashfin.i18n.tool.report.ErrorInfo;
import com.flashfin.i18n.tool.report.JsonEnvelope;
import com.flashfin.i18n.tool.scan.ClassScanner;
import com.flashfin.i18n.tool.scan.KeyGenerator;
import com.flashfin.i18n.tool.scan.KeyRules;
import com.flashfin.i18n.tool.scan.MavenClasspathBuilder;
import com.flashfin.i18n.tool.scan.ModuleScanResult;
import com.flashfin.i18n.tool.scan.ModuleScanner;
import com.flashfin.i18n.tool.scan.ResolvedModule;
import com.flashfin.i18n.tool.scan.ScanException;
import com.flashfin.i18n.tool.sync.DownloadPlan;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class DownloadCommand {

    private static final String COMMAND = "download";

    private final ModuleScanner moduleScanner;
    private final DictionaryClientFactory dictionaryClientFactory;
    private final PropertiesFileStore fileStore;
    private final GitGuard gitGuard;
    private final Map<String, String> env;

    public DownloadCommand(ModuleScanner moduleScanner,
                           DictionaryClientFactory dictionaryClientFactory,
                           PropertiesFileStore fileStore, GitGuard gitGuard,
                           Map<String, String> env) {
        this.moduleScanner = moduleScanner;
        this.dictionaryClientFactory = dictionaryClientFactory;
        this.fileStore = fileStore;
        this.gitGuard = gitGuard;
        this.env = env;
    }

    public static DownloadCommand defaults(Map<String, String> env) {
        return new DownloadCommand(
                new ModuleScanner(new MavenClasspathBuilder(), new ClassScanner(),
                        new KeyGenerator(KeyRules.defaults())),
                DictionaryClientFactory.http(),
                new PropertiesFileStore(),
                new GitGuard(),
                env);
    }

    public CommandResult execute(CliArguments args) {
        ModuleCommandSupport.Setup setup = ModuleCommandSupport.prepare(args, env, COMMAND);
        if (setup.hasError()) {
            return setup.getError();
        }
        if (args.getLimit() != null || args.getRateMs() != null) {
            return ModuleCommandSupport.failure(COMMAND, setup.getConfigPath(), "INVALID_ARGUMENT",
                    "--limit 与 --rate-ms 仅用于 upload", "--limit/--rate-ms 不适用于 download");
        }

        List<String> languages;
        try {
            languages = ModuleCommandSupport.selectedLanguages(args, setup.getConfig());
        } catch (CliException e) {
            return ModuleCommandSupport.failure(COMMAND, setup.getConfigPath(), "INVALID_ARGUMENT",
                    e.getMessage(), "配置中可用语言: " + setup.getConfig().getLanguages());
        }

        BuildResult build = buildPlan(setup, languages);
        if (!build.errors.isEmpty()) {
            return new CommandResult(build.exitCode, JsonEnvelope.failure(COMMAND, build.output,
                    build.warnings, build.errors.get(0)));
        }

        if (!args.isApply()) {
            build.output.put("apply", false);
            return new CommandResult(ExitCode.OK,
                    JsonEnvelope.success(COMMAND, build.output, build.warnings));
        }
        return apply(args, setup, build);
    }

    private CommandResult apply(CliArguments args, ModuleCommandSupport.Setup setup,
                                BuildResult build) {
        build.output.put("apply", true);
        if (!build.plan.getPlanId().equalsIgnoreCase(args.getPlanId())) {
            build.output.put("requestedPlanId", args.getPlanId());
            build.output.put("computedPlanId", build.plan.getPlanId());
            return failure(build, ExitCode.WRITE_REJECTED, "PLAN_STALE",
                    "计划已变化，planId 不匹配",
                    "重新运行不带 --apply 的 download 生成新的 planId");
        }
        if (build.plan.getFiles().isEmpty()) {
            build.output.put("changedFiles", new ArrayList<Map<String, Object>>());
            return new CommandResult(ExitCode.OK,
                    JsonEnvelope.success(COMMAND, build.output, build.warnings));
        }

        String guardError = checkGit(build, args.isForce(), build.warnings);
        if (guardError != null) {
            return failure(build, ExitCode.WRITE_REJECTED, GitStatus.GIT_DIRTY, guardError,
                    "提交目标仓库变更后重试，或使用 --force 明确跳过守卫");
        }

        BackupSession backup;
        try {
            backup = BackupSession.create(ModuleCommandSupport.toolDataDir(setup.getConfigPath()));
        } catch (IOException e) {
            return failure(build, ExitCode.WRITE_REJECTED, "BACKUP_FAILED",
                    "创建备份目录失败: " + e.getClass().getSimpleName(),
                    "检查 .i18n-tool 目录权限");
        }

        List<Map<String, Object>> changed = new ArrayList<Map<String, Object>>();
        int operationCount = 0;
        for (DownloadPlan.FilePlan file : build.plan.getFiles()) {
            ResolvedModule module = build.modules.get(file.getModule());
            if (module == null) {
                return failure(build, ExitCode.INVALID, "MODULE_NOT_FOUND",
                        "找不到模块: " + file.getModule(), "检查配置文件");
            }
            for (DownloadPlan.Operation operation : file.getOperations()) {
                applyOperation(file.getDocument(), operation);
                operationCount++;
            }
            try {
                PropertiesWriteResult result = fileStore.write(file.getFile(), file.getDocument(),
                        backup, file.getModule(), module.getRepoRoot());
                if (result.isChanged()) {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("module", file.getModule());
                    item.put("file", file.getRelativePath());
                    item.put("operations", file.getOperations().size());
                    item.put("backup", result.getBackup() == null ? null
                            : result.getBackup().toString());
                    changed.add(item);
                }
            } catch (PropertiesFileException e) {
                build.output.put("changedFiles", changed);
                build.output.put("appliedOperations", operationCount);
                int exitCode = PropertiesFileException.READ_FAILED.equals(e.getCode())
                        ? ExitCode.INVALID : ExitCode.WRITE_REJECTED;
                return failure(build, exitCode, e.getCode(), e.getMessage(),
                        "检查文件权限与备份目录；失败文件已从备份恢复");
            }
        }

        build.output.put("changedFiles", changed);
        build.output.put("appliedOperations", operationCount);
        build.output.put("backupDir", backup.getDirectory().toString());
        return new CommandResult(ExitCode.OK,
                JsonEnvelope.success(COMMAND, build.output, build.warnings));
    }

    private void applyOperation(PropertiesDocument document, DownloadPlan.Operation operation) {
        if (DownloadPlan.COMMENT.equals(operation.getAction())) {
            document.addCommented(operation.getKey());
        } else {
            document.setValue(operation.getKey(), operation.getValue());
        }
    }

    private BuildResult buildPlan(ModuleCommandSupport.Setup setup, List<String> languages) {
        BuildResult build = new BuildResult();
        DictionaryClient client = dictionaryClientFactory.create(setup.getConfig().getPlatform());
        Map<String, RemoteDictionary> cache = new LinkedHashMap<String, RemoteDictionary>();
        List<DownloadPlan.FilePlan> files = new ArrayList<DownloadPlan.FilePlan>();

        for (ModuleConfig moduleConfig : setup.getSelected()) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("module", moduleConfig.getName());
            data.put("os", moduleConfig.getOs());
            data.put("packages", moduleConfig.getPackages());
            try {
                ResolvedModule module = setup.getRegistry().resolve(moduleConfig);
                build.modules.put(moduleConfig.getName(), module);
                data.put("repoRoot", module.getRepoRoot().toString());
                data.put("domainPath", module.getDomainRoot().toString());
                ModuleScanResult scanned = moduleScanner.scan(module);
                data.put("classesScanned", scanned.getClassesScanned());
                data.put("counts", ModuleCommandSupport.counts(scanned.getKeys()));
                if (!scanned.getClassLoadFailures().isEmpty()) {
                    build.warnings.add("CLASS_LOAD_FAILED: 模块 " + moduleConfig.getName()
                            + " 有 " + scanned.getClassLoadFailures().size() + " 个类无法加载，已跳过");
                }

                RemoteDictionaries remote = fetchRemote(client, moduleConfig, languages, cache);
                if (remote.error != null) {
                    data.put("ok", false);
                    data.put("error", ModuleCommandSupport.errorData("REMOTE_UNAVAILABLE",
                            remote.error, "检查 CDN 可达性、os 与语言代码；恢复后重试"));
                    build.moduleData.add(data);
                    build.errors.add(new ErrorInfo("REMOTE_UNAVAILABLE", remote.error,
                            "检查 CDN 可达性、os 与语言代码；恢复后重试", true));
                    build.exitCode = ExitCode.REMOTE_ERROR;
                    continue;
                }

                Path i18nDir = module.getDomainRoot().resolve("src/main/resources/i18n");
                int moduleFiles = 0;
                for (String language : languages) {
                    String suffix = LanguageCodes.propertiesSuffix(language);
                    RemoteDictionary code = remote.code.get(language);
                    RemoteDictionary enums = remote.enums.get(language);
                    moduleFiles += addFilePlan(module, files, moduleConfig.getName(), i18nDir,
                            moduleConfig.getCodeFile() + "_" + suffix + ".properties",
                            scanned.getKeys().getCodeKeys().keySet(), code, build.warnings);
                    moduleFiles += addFilePlan(module, files, moduleConfig.getName(), i18nDir,
                            moduleConfig.getEnumFile() + "_" + suffix + ".properties",
                            scanned.getKeys().getEnumKeys().keySet(), enums, build.warnings);
                }
                data.put("ok", true);
                data.put("plannedFiles", moduleFiles);
                build.moduleData.add(data);
            } catch (ScanException e) {
                data.put("ok", false);
                data.put("error", ModuleCommandSupport.errorData(e.getCode(), e.getMessage(),
                        ModuleCommandSupport.scanHint(e.getCode())));
                build.moduleData.add(data);
                build.errors.add(new ErrorInfo(e.getCode(), e.getMessage(),
                        ModuleCommandSupport.scanHint(e.getCode()), false));
                build.exitCode = ExitCode.INVALID;
            } catch (PropertiesFileException e) {
                data.put("ok", false);
                data.put("error", ModuleCommandSupport.errorData(e.getCode(), e.getMessage(),
                        "确认 properties 文件为 UTF-8 编码"));
                build.moduleData.add(data);
                build.errors.add(new ErrorInfo(e.getCode(), e.getMessage(),
                        "确认 properties 文件为 UTF-8 编码", false));
                build.exitCode = ExitCode.INVALID;
            }
        }

        build.plan = DownloadPlan.create(
                setup.getConfig().getPlatform().getDictionaryBase(), languages, files);
        build.output.put("configPath", setup.getConfigPath().toString());
        build.output.put("workspaceRoot", setup.getConfig().getWorkspaceRoot().toString());
        build.output.put("languages", languages);
        build.output.put("modules", build.moduleData);
        build.output.put("planId", build.plan.getPlanId());
        build.output.put("plan", build.plan.toOutput());
        return build;
    }

    private int addFilePlan(ResolvedModule module, List<DownloadPlan.FilePlan> files,
                            String moduleName, Path i18nDir, String fileName,
                            Set<String> generatedKeys, RemoteDictionary dictionary,
                            List<String> warnings)
            throws PropertiesFileException {
        Path file = i18nDir.resolve(fileName);
        PropertiesDocument document = fileStore.read(file);
        List<DownloadPlan.Operation> operations = new ArrayList<DownloadPlan.Operation>();

        for (String key : document.getEntryKeys()) {
            String remoteValue = dictionary.getValues().get(key);
            if (!ModuleCommandSupport.isBlank(remoteValue)
                    && document.needsValueUpdate(key, remoteValue)) {
                operations.add(new DownloadPlan.Operation(DownloadPlan.UPDATE, key, remoteValue));
            }
        }
        for (String key : new TreeSet<String>(generatedKeys)) {
            if (!isWritableKey(key)) {
                warnings.add("INVALID_KEY_SKIPPED: 模块 " + moduleName + " key=" + key
                        + " 无法写入 properties，已跳过");
                continue;
            }
            if (document.hasKey(key)) {
                continue;
            }
            String remoteValue = dictionary.getValues().get(key);
            if (ModuleCommandSupport.isBlank(remoteValue)) {
                operations.add(new DownloadPlan.Operation(DownloadPlan.COMMENT, key, null));
            } else {
                operations.add(new DownloadPlan.Operation(DownloadPlan.ADD, key, remoteValue));
            }
        }

        if (operations.isEmpty()) {
            return 0;
        }
        String relative = module.getRepoRoot().relativize(file).toString().replace('\\', '/');
        files.add(new DownloadPlan.FilePlan(moduleName, file, relative, document, operations));
        return 1;
    }

    private RemoteDictionaries fetchRemote(DictionaryClient client, ModuleConfig moduleConfig,
                                           List<String> languages,
                                           Map<String, RemoteDictionary> cache) {
        String enumOs = ModuleDictionaries.enumOs(moduleConfig.getOs());
        RemoteDictionaries result = new RemoteDictionaries();
        for (String language : languages) {
            RemoteDictionary code = fetch(client, moduleConfig.getOs(), language, cache);
            RemoteDictionary enums = enumOs.equals(moduleConfig.getOs())
                    ? code : fetch(client, enumOs, language, cache);
            if (!code.isAvailable() || !enums.isAvailable()) {
                RemoteDictionary failed = !code.isAvailable() ? code : enums;
                result.error = "模块 " + moduleConfig.getName() + " 语言 " + language
                        + " 字典不可用: " + failed.getErrorMessage();
                return result;
            }
            result.code.put(language, code);
            result.enums.put(language, enums);
        }
        return result;
    }

    private RemoteDictionary fetch(DictionaryClient client, String os, String language,
                                   Map<String, RemoteDictionary> cache) {
        String key = os + "|" + language;
        RemoteDictionary cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        RemoteDictionary fetched = client.fetch(os, language);
        cache.put(key, fetched);
        return fetched;
    }

    private String checkGit(BuildResult build, boolean force, List<String> warnings) {
        List<String> modules = new ArrayList<String>();
        for (DownloadPlan.FilePlan file : build.plan.getFiles()) {
            if (!modules.contains(file.getModule())) {
                modules.add(file.getModule());
            }
        }
        for (String module : modules) {
            ResolvedModule resolved = build.modules.get(module);
            if (resolved == null) {
                continue;
            }
            GitStatus status = gitGuard.check(resolved.getRepoRoot());
            if (status.isClean()) {
                continue;
            }
            if (!force) {
                return status.getMessage() + "，module=" + module
                        + "，repo=" + resolved.getRepoRoot();
            }
            String warning = "GIT_FORCE_OVERRIDE: module=" + module
                    + "，repo=" + resolved.getRepoRoot() + "，变更 "
                    + status.getEntries().size() + " 项";
            warnings.add(warning);
            System.err.println("WARN: " + warning);
        }
        return null;
    }

    private static boolean isWritableKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        char first = key.charAt(0);
        if (first == '#' || first == '!') {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == ' ' || c == '\t' || c == '\f' || c == '\n' || c == '\r'
                    || c == '=' || c == ':') {
                return false;
            }
        }
        return true;
    }

    private CommandResult failure(BuildResult build, int exitCode, String code, String message,
                                  String hint) {
        return new CommandResult(exitCode, JsonEnvelope.failure(COMMAND, build.output,
                build.warnings, new ErrorInfo(code, message, hint, false)));
    }

    private static final class BuildResult {

        private DownloadPlan plan;
        private final Map<String, ResolvedModule> modules =
                new LinkedHashMap<String, ResolvedModule>();
        private final List<Map<String, Object>> moduleData = new ArrayList<Map<String, Object>>();
        private final Map<String, Object> output = new LinkedHashMap<String, Object>();
        private final List<String> warnings = new ArrayList<String>();
        private final List<ErrorInfo> errors = new ArrayList<ErrorInfo>();
        private int exitCode = ExitCode.OK;
    }

    private static final class RemoteDictionaries {

        private final Map<String, RemoteDictionary> code =
                new LinkedHashMap<String, RemoteDictionary>();
        private final Map<String, RemoteDictionary> enums =
                new LinkedHashMap<String, RemoteDictionary>();
        private String error;
    }
}
