package com.flashfin.i18n.tool.sync;

import com.flashfin.i18n.tool.cli.CliArguments;
import com.flashfin.i18n.tool.cli.CommandResult;
import com.flashfin.i18n.tool.cli.DownloadCommand;
import com.flashfin.i18n.tool.cli.ExitCode;
import com.flashfin.i18n.tool.cli.UploadCommand;
import com.flashfin.i18n.tool.config.PlatformConfig;
import com.flashfin.i18n.tool.dictionary.DictionaryClient;
import com.flashfin.i18n.tool.dictionary.DictionaryClientFactory;
import com.flashfin.i18n.tool.dictionary.RemoteDictionary;
import com.flashfin.i18n.tool.git.GitGuard;
import com.flashfin.i18n.tool.git.GitStatus;
import com.flashfin.i18n.tool.messages.LocalMessagesLoader;
import com.flashfin.i18n.tool.platform.PlatformClient;
import com.flashfin.i18n.tool.platform.PlatformClientFactory;
import com.flashfin.i18n.tool.platform.PlatformSaveResult;
import com.flashfin.i18n.tool.properties.PropertiesFileStore;
import com.flashfin.i18n.tool.scan.ClassScanner;
import com.flashfin.i18n.tool.scan.ClasspathResult;
import com.flashfin.i18n.tool.scan.KeyGenerator;
import com.flashfin.i18n.tool.scan.KeyRules;
import com.flashfin.i18n.tool.scan.ModuleClasspathBuilder;
import com.flashfin.i18n.tool.scan.ModuleScanner;
import com.flashfin.i18n.tool.scan.ResolvedModule;
import com.flashfin.i18n.tool.scan.ScanException;
import com.flashfin.i18n.tool.security.TokenInspector;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SyncEndToEndTest {

    private static final String CODE_FILE = "DemoCodeMessages_en.properties";
    private static final String ENUM_FILE = "DemoEnumerableMessages_en.properties";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final Map<String, Map<String, String>> remote =
            new LinkedHashMap<String, Map<String, String>>();
    private final List<UploadEntry> saved = new ArrayList<UploadEntry>();
    private final List<String> tokens = new ArrayList<String>();

    private Path workspace;
    private Path repo;
    private Path config;

    @Test
    @SuppressWarnings("unchecked")
    public void uploadThenDownloadIsIdempotent() throws Exception {
        prepareWorkspace();

        CommandResult uploadDryRun = upload(emptyEnv(), "upload", "--config", config.toString(),
                "--languages", "en");
        assertEquals(ExitCode.OK, uploadDryRun.getExitCode());
        Map<String, Object> uploadData = data(uploadDryRun);
        String uploadPlanId = (String) uploadData.get("planId");
        assertEquals(64, uploadPlanId.length());
        Map<String, Object> uploadPlan = (Map<String, Object>) uploadData.get("plan");
        assertEquals(1, uploadPlan.get("entryCount"));
        List<Map<String, Object>> entries = (List<Map<String, Object>>) uploadPlan.get("entries");
        assertEquals("demo", entries.get(0).get("module"));
        assertEquals("flp-code", entries.get(0).get("os"));
        assertEquals("code_100", entries.get(0).get("key"));
        assertEquals("Old error", entries.get(0).get("value"));
        assertTrue(saved.isEmpty());

        CommandResult uploadApply = upload(envWithToken(), "upload", "--config", config.toString(),
                "--apply", "--plan-id", uploadPlanId, "--rate-ms", "0");
        assertEquals(ExitCode.OK, uploadApply.getExitCode());
        Map<String, Object> appliedUpload = data(uploadApply);
        assertEquals(1, appliedUpload.get("applied"));
        assertEquals(0, appliedUpload.get("failed"));
        assertEquals(1, saved.size());
        assertEquals("code_100", saved.get(0).getKey());
        assertEquals(1, tokens.size());
        assertEquals("test-token", tokens.get(0));
        assertEquals("Old error", dictionary("flp-code", "en").get("code_100"));

        CommandResult uploadSecondRun = upload(emptyEnv(), "upload", "--config", config.toString(),
                "--languages", "en");
        Map<String, Object> secondUploadData = data(uploadSecondRun);
        Map<String, Object> secondUploadPlan = (Map<String, Object>) secondUploadData.get("plan");
        assertEquals(0, secondUploadPlan.get("entryCount"));
        String secondUploadPlanId = (String) secondUploadData.get("planId");
        assertFalse(uploadPlanId.equals(secondUploadPlanId));

        CommandResult emptyUploadApply = upload(emptyEnv(), "upload", "--config",
                config.toString(), "--apply", "--plan-id", secondUploadPlanId);
        assertEquals(ExitCode.OK, emptyUploadApply.getExitCode());
        assertEquals(0, data(emptyUploadApply).get("applied"));
        assertEquals(1, saved.size());

        dictionary("flp-code", "en").put("code_100", "系统错误");
        dictionary("flp-code", "en").put("code_9", "系统提示");
        dictionary("flp-enum", "en").put("SampleStatusEnum_2", "已停用");

        CommandResult downloadDryRun = download(emptyEnv(), "download", "--config",
                config.toString(), "--languages", "en");
        assertEquals(ExitCode.OK, downloadDryRun.getExitCode());
        Map<String, Object> downloadData = data(downloadDryRun);
        String downloadPlanId = (String) downloadData.get("planId");
        Map<String, Object> downloadPlan = (Map<String, Object>) downloadData.get("plan");
        assertEquals(2, downloadPlan.get("fileCount"));
        assertEquals(7, downloadPlan.get("operationCount"));

        CommandResult downloadApply = download(emptyEnv(), "download", "--config",
                config.toString(), "--apply", "--plan-id", downloadPlanId);
        assertEquals(ExitCode.OK, downloadApply.getExitCode());
        Map<String, Object> appliedDownload = data(downloadApply);
        assertEquals(7, appliedDownload.get("appliedOperations"));
        List<Map<String, Object>> changedFiles =
                (List<Map<String, Object>>) appliedDownload.get("changedFiles");
        assertEquals(2, changedFiles.size());

        Path backupDir = Paths.get((String) appliedDownload.get("backupDir"));
        assertEquals("# Demo messages\ncode_0=OK\ncode_100=Old error\n",
                read(backupDir.resolve("demo/domain/src/main/resources/i18n/" + CODE_FILE)));
        assertEquals("# Enum messages\nSampleStatusEnum_1=Active\n",
                read(backupDir.resolve("demo/domain/src/main/resources/i18n/" + ENUM_FILE)));

        Path i18nDir = i18nDir();
        assertEquals("# Demo messages\n"
                + "code_0=OK\n"
                + "code_100=\\u7CFB\\u7EDF\\u9519\\u8BEF\n"
                + "#code_7=\n"
                + "code_9=\\u7CFB\\u7EDF\\u63D0\\u793A\n", read(i18nDir.resolve(CODE_FILE)));
        assertEquals("# Enum messages\n"
                + "SampleStatusEnum_1=Active\n"
                + "#MixedEnum_9=\n"
                + "SampleStatusEnum_2=\\u5DF2\\u505C\\u7528\n"
                + "#StringModeEnum_F=\n"
                + "#StringModeEnum_S=\n", read(i18nDir.resolve(ENUM_FILE)));

        assertEquals(" M domain/src/main/resources/i18n/" + CODE_FILE + "\n"
                        + " M domain/src/main/resources/i18n/" + ENUM_FILE + "\n",
                git(repo, "status", "--porcelain", "--untracked-files=normal"));
        assertEquals("domain/src/main/resources/i18n/" + CODE_FILE + "\n"
                        + "domain/src/main/resources/i18n/" + ENUM_FILE + "\n",
                git(repo, "diff", "--name-only"));

        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "download");

        CommandResult downloadSecondRun = download(emptyEnv(), "download", "--config",
                config.toString(), "--languages", "en");
        Map<String, Object> secondDownloadData = data(downloadSecondRun);
        Map<String, Object> secondDownloadPlan =
                (Map<String, Object>) secondDownloadData.get("plan");
        assertEquals(0, secondDownloadPlan.get("fileCount"));
        assertEquals(0, secondDownloadPlan.get("operationCount"));

        CommandResult emptyDownloadApply = download(emptyEnv(), "download", "--config",
                config.toString(), "--apply", "--plan-id",
                (String) secondDownloadData.get("planId"));
        assertEquals(ExitCode.OK, emptyDownloadApply.getExitCode());
        assertEquals(0, ((List<?>) data(emptyDownloadApply).get("changedFiles")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void uploadRejectsDirtyRepositoryUnlessForced() throws Exception {
        prepareWorkspace();
        write(repo.resolve("dirty.txt"), "dirty\n");

        CommandResult dryRun = upload(emptyEnv(), "upload", "--config", config.toString(),
                "--languages", "en");
        assertEquals(1, ((Map<String, Object>) data(dryRun).get("plan")).get("entryCount"));
        String planId = (String) data(dryRun).get("planId");

        CommandResult blocked = upload(envWithToken(), "upload", "--config", config.toString(),
                "--apply", "--plan-id", planId, "--rate-ms", "0");
        assertEquals(ExitCode.WRITE_REJECTED, blocked.getExitCode());
        assertEquals(GitStatus.GIT_DIRTY, blocked.getEnvelope().getError().getCode());
        assertTrue(saved.isEmpty());

        CommandResult forced = upload(envWithToken(), "upload", "--config", config.toString(),
                "--apply", "--plan-id", planId, "--rate-ms", "0", "--force");
        assertEquals(ExitCode.OK, forced.getExitCode());
        assertEquals(1, saved.size());
        boolean warned = false;
        for (String warning : forced.getEnvelope().getWarnings()) {
            if (warning.startsWith("GIT_FORCE_OVERRIDE")) {
                warned = true;
            }
        }
        assertTrue(warned);
    }

    private void prepareWorkspace() throws Exception {
        workspace = temp.newFolder("workspace").toPath().toRealPath();
        repo = workspace.resolve("repo");
        Files.createDirectories(repo.resolve("domain/target/classes"));
        Files.createDirectories(i18nDir());
        write(i18nDir().resolve(CODE_FILE), "# Demo messages\ncode_0=OK\ncode_100=Old error\n");
        write(i18nDir().resolve(ENUM_FILE), "# Enum messages\nSampleStatusEnum_1=Active\n");
        config = writeConfig();

        dictionary("flp-code", "en").put("code_0", "OK");
        dictionary("flp-code", "en").put("code_100", "");
        dictionary("flp-code", "en").put("code_7", "");
        dictionary("flp-code", "en").put("code_9", "");
        dictionary("flp-enum", "en").put("SampleStatusEnum_1", "Active");
        dictionary("flp-enum", "en").put("SampleStatusEnum_2", "");

        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "init");
    }

    private Path i18nDir() {
        return repo.resolve("domain/src/main/resources/i18n");
    }

    private Path writeConfig() throws Exception {
        String yaml = "version: 1\n"
                + "workspaceRoot: .\n"
                + "languages: [en]\n"
                + "platform:\n"
                + "  saveUrl: https://example.com/save\n"
                + "  dictionaryBase: https://example.com\n"
                + "modules:\n"
                + "  demo:\n"
                + "    repoRoot: repo\n"
                + "    domainPath: domain\n"
                + "    os: flp-code\n"
                + "    packages: [com.flashfin.i18n.tool.fixtures]\n"
                + "    codeFile: DemoCodeMessages\n"
                + "    enumFile: DemoEnumerableMessages\n";
        Path file = workspace.resolve("modules.yaml");
        write(file, yaml);
        return file;
    }

    private CommandResult upload(Map<String, String> env, String... args) throws Exception {
        return new UploadCommand(moduleScanner(), new LocalMessagesLoader(),
                dictionaryFactory(), platformFactory(), new GitGuard(), env)
                .execute(CliArguments.parse(args));
    }

    private CommandResult download(Map<String, String> env, String... args) throws Exception {
        return new DownloadCommand(moduleScanner(), dictionaryFactory(),
                new PropertiesFileStore(), new GitGuard(), env)
                .execute(CliArguments.parse(args));
    }

    private ModuleScanner moduleScanner() throws Exception {
        final List<Path> classpath = Collections.singletonList(testClasses());
        ModuleClasspathBuilder builder = new ModuleClasspathBuilder() {
            @Override
            public ClasspathResult build(ResolvedModule module) throws ScanException {
                return new ClasspathResult(module.getClassesDir(), classpath, false);
            }
        };
        KeyGenerator generator = new KeyGenerator(KeyRules.of(
                "com.flashfin.i18n.tool.fixtures.FakeEnumerable",
                "com.flashfin.i18n.tool.fixtures.FakeResultCode",
                "com.flashfin.i18n.tool.fixtures.FakeStringEnumerable"));
        return new ModuleScanner(builder, new ClassScanner(), generator);
    }

    private DictionaryClientFactory dictionaryFactory() {
        return new DictionaryClientFactory() {
            @Override
            public DictionaryClient create(PlatformConfig platform) {
                return new DictionaryClient() {
                    @Override
                    public RemoteDictionary fetch(String os, String language) {
                        return RemoteDictionary.available(language, os + "/" + language,
                                dictionary(os, language));
                    }
                };
            }
        };
    }

    private PlatformClientFactory platformFactory() {
        return new PlatformClientFactory() {
            @Override
            public PlatformClient create(PlatformConfig platform, String token) {
                tokens.add(token);
                return new PlatformClient() {
                    @Override
                    public PlatformSaveResult save(UploadEntry entry) {
                        saved.add(entry);
                        dictionary(entry.getOs(), entry.getLanguage())
                                .put(entry.getKey(), entry.getValue());
                        return PlatformSaveResult.success(200);
                    }
                };
            }
        };
    }

    private Map<String, String> dictionary(String os, String language) {
        String id = os + "|" + language;
        Map<String, String> values = remote.get(id);
        if (values == null) {
            values = new LinkedHashMap<String, String>();
            remote.put(id, values);
        }
        return values;
    }

    private Path testClasses() throws Exception {
        return Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(CommandResult result) {
        return (Map<String, Object>) result.getEnvelope().getData();
    }

    private static Map<String, String> envWithToken() {
        Map<String, String> env = new HashMap<String, String>();
        env.put(TokenInspector.ENV_TOKEN, "test-token");
        return env;
    }

    private static Map<String, String> emptyEnv() {
        return Collections.emptyMap();
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream input = process.getInputStream();
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, count);
        }
        int exitCode = process.waitFor();
        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        assertEquals("git " + Arrays.toString(args) + " failed: " + output, 0, exitCode);
        return output;
    }
}
