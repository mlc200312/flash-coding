package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.config.ModuleConfig;
import com.flashfin.i18n.tool.messages.LocalMessages;
import com.flashfin.i18n.tool.messages.LocalMessagesException;
import com.flashfin.i18n.tool.messages.LocalMessagesLoader;
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
import com.flashfin.i18n.tool.validate.ValidationIssue;
import com.flashfin.i18n.tool.validate.ValidationIssueSeverity;
import com.flashfin.i18n.tool.validate.ValidationResult;
import com.flashfin.i18n.tool.validate.Validator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ValidateCommand {

    private static final String COMMAND = "validate";

    private final ModuleScanner moduleScanner;
    private final LocalMessagesLoader messagesLoader;
    private final Validator validator;
    private final Map<String, String> env;

    public ValidateCommand(ModuleScanner moduleScanner, LocalMessagesLoader messagesLoader,
                           Validator validator, Map<String, String> env) {
        this.moduleScanner = moduleScanner;
        this.messagesLoader = messagesLoader;
        this.validator = validator;
        this.env = env;
    }

    public static ValidateCommand defaults(Map<String, String> env) {
        return new ValidateCommand(
                new ModuleScanner(new MavenClasspathBuilder(), new ClassScanner(),
                        new KeyGenerator(KeyRules.defaults())),
                new LocalMessagesLoader(),
                new Validator(),
                env);
    }

    public CommandResult execute(CliArguments args) {
        ModuleCommandSupport.Setup setup = ModuleCommandSupport.prepare(args, env, COMMAND);
        if (setup.hasError()) {
            return setup.getError();
        }

        List<String> languages;
        try {
            languages = ModuleCommandSupport.selectedLanguages(args, setup.getConfig());
        } catch (CliException e) {
            return ModuleCommandSupport.failure(COMMAND, setup.getConfigPath(), "INVALID_ARGUMENT",
                    e.getMessage(), "配置中可用语言: " + setup.getConfig().getLanguages());
        }

        List<Map<String, Object>> moduleData = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> allIssues = new ArrayList<Map<String, Object>>();
        List<String> warnings = new ArrayList<String>();
        List<ErrorInfo> errors = new ArrayList<ErrorInfo>();
        int keysCheckedTotal = 0;
        int errorTotal = 0;
        int warningTotal = 0;
        boolean allValid = true;

        for (ModuleConfig moduleConfig : setup.getSelected()) {
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("module", moduleConfig.getName());
            data.put("os", moduleConfig.getOs());
            data.put("packages", moduleConfig.getPackages());
            try {
                ResolvedModule module = setup.getRegistry().resolve(moduleConfig);
                data.put("repoRoot", module.getRepoRoot().toString());
                data.put("domainPath", module.getDomainRoot().toString());
                ModuleScanResult scanned = moduleScanner.scan(module);
                data.put("classesScanned", scanned.getClassesScanned());
                data.put("counts", ModuleCommandSupport.counts(scanned.getKeys()));
                if (!scanned.getClassLoadFailures().isEmpty()) {
                    warnings.add("CLASS_LOAD_FAILED: 模块 " + moduleConfig.getName() + " 有 "
                            + scanned.getClassLoadFailures().size() + " 个类无法加载，已跳过");
                }

                Map<String, LocalMessages> local = loadLocal(module, languages, warnings);
                data.put("localFiles", localFiles(local));

                ValidationResult validation = validator.validate(scanned.getKeys(), local, languages);
                int errorsInModule = validation.countBySeverity(ValidationIssueSeverity.ERROR);
                int warningsInModule = validation.countBySeverity(ValidationIssueSeverity.WARNING);
                data.put("ok", true);
                data.put("keysChecked", validation.getKeysChecked());
                data.put("valid", validation.isValid());
                data.put("errorCount", errorsInModule);
                data.put("warningCount", warningsInModule);
                data.put("issues", issueEntries(moduleConfig.getName(), validation.getIssues()));
                moduleData.add(data);

                allIssues.addAll(issueEntries(moduleConfig.getName(), validation.getIssues()));
                keysCheckedTotal += validation.getKeysChecked();
                errorTotal += errorsInModule;
                warningTotal += warningsInModule;
                allValid = allValid && validation.isValid();
            } catch (ScanException e) {
                data.put("ok", false);
                data.put("valid", false);
                data.put("error", ModuleCommandSupport.errorData(e.getCode(), e.getMessage(),
                        ModuleCommandSupport.scanHint(e.getCode())));
                moduleData.add(data);
                errors.add(new ErrorInfo(e.getCode(), e.getMessage(),
                        ModuleCommandSupport.scanHint(e.getCode()), false));
                allValid = false;
            } catch (LocalMessagesException e) {
                data.put("ok", false);
                data.put("valid", false);
                data.put("error", ModuleCommandSupport.errorData(e.getCode(), e.getMessage(),
                        "确认模块 src/main/resources/i18n 下存在 UTF-8 编码的 properties 文件"));
                moduleData.add(data);
                errors.add(new ErrorInfo(e.getCode(), e.getMessage(),
                        "确认模块 src/main/resources/i18n 下存在 UTF-8 编码的 properties 文件", false));
                allValid = false;
            }
        }

        Map<String, Object> totals = new LinkedHashMap<String, Object>();
        totals.put("keysChecked", keysCheckedTotal);
        totals.put("errors", errorTotal);
        totals.put("warnings", warningTotal);

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("configPath", setup.getConfigPath().toString());
        root.put("workspaceRoot", setup.getConfig().getWorkspaceRoot().toString());
        root.put("languages", languages);
        root.put("moduleCount", moduleData.size());
        root.put("valid", allValid && errors.isEmpty());
        root.put("totals", totals);
        root.put("issues", allIssues);
        root.put("modules", moduleData);

        if (!errors.isEmpty()) {
            return new CommandResult(ExitCode.INVALID,
                    JsonEnvelope.failure(COMMAND, root, warnings, errors.get(0)));
        }
        return new CommandResult(ExitCode.OK, JsonEnvelope.success(COMMAND, root, warnings));
    }

    private Map<String, LocalMessages> loadLocal(ResolvedModule module, List<String> languages,
                                                 List<String> warnings)
            throws LocalMessagesException {
        Map<String, LocalMessages> result = new LinkedHashMap<String, LocalMessages>();
        for (String language : languages) {
            LocalMessages messages = messagesLoader.load(module, language);
            result.put(language, messages);
            if (!messages.getMissingFiles().isEmpty()) {
                warnings.add("LOCAL_MESSAGES_MISSING: 模块 " + module.getName() + " 语言 "
                        + language + " 有 " + messages.getMissingFiles().size()
                        + " 个 properties 文件缺失");
            }
        }
        return result;
    }

    private Map<String, Object> localFiles(Map<String, LocalMessages> local) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, LocalMessages> entry : local.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("loaded", entry.getValue().getLoadedFiles());
            item.put("missing", entry.getValue().getMissingFiles());
            result.put(entry.getKey(), item);
        }
        return result;
    }

    private List<Map<String, Object>> issueEntries(String module, List<ValidationIssue> issues) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ValidationIssue issue : issues) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("module", module);
            item.put("type", issue.getType());
            item.put("severity", issue.getSeverity());
            item.put("key", issue.getKey());
            item.put("message", issue.getMessage());
            item.put("languages", issue.getLanguages());
            item.put("expected", issue.getExpected());
            item.put("actual", issue.getActual());
            result.add(item);
        }
        return result;
    }
}
