package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.config.ModulesConfig;
import com.flashfin.i18n.tool.config.ModulesConfigLoader;
import com.flashfin.i18n.tool.fs.PathGuard;
import com.flashfin.i18n.tool.scan.ClassScanner;
import com.flashfin.i18n.tool.scan.ClasspathResult;
import com.flashfin.i18n.tool.scan.KeyGenerator;
import com.flashfin.i18n.tool.scan.KeyRules;
import com.flashfin.i18n.tool.scan.ModuleClasspathBuilder;
import com.flashfin.i18n.tool.scan.ModuleRegistry;
import com.flashfin.i18n.tool.scan.ResolvedModule;
import com.flashfin.i18n.tool.scan.ScanException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScanCommandTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    @SuppressWarnings("unchecked")
    public void scansConfiguredModuleAndGeneratesKeys() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        Files.createDirectories(workspace.resolve("repo/domain/target/classes"));
        Path config = writeConfig(workspace);

        CommandResult result = command(workspace).execute(CliArguments.parse(
                new String[]{"scan", "--config", config.toString()}));

        assertEquals(ExitCode.OK, result.getExitCode());
        assertTrue(result.getEnvelope().isOk());
        Map<String, Object> data = (Map<String, Object>) result.getEnvelope().getData();
        List<Map<String, Object>> modules = (List<Map<String, Object>>) data.get("modules");
        assertEquals(1, modules.size());
        Map<String, Object> module = modules.get(0);
        assertEquals("demo", module.get("module"));
        assertEquals(Boolean.TRUE, module.get("ok"));

        Map<String, Object> counts = (Map<String, Object>) module.get("counts");
        assertEquals(4, counts.get("code"));
        assertEquals(6, counts.get("enum"));
        assertEquals(1, counts.get("duplicate"));
        assertEquals(1, ((List<Object>) module.get("duplicates")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void failsClearlyWhenModuleUnknown() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        Files.createDirectories(workspace.resolve("repo/domain/target/classes"));
        Path config = writeConfig(workspace);

        CommandResult result = command(workspace).execute(CliArguments.parse(
                new String[]{"scan", "--config", config.toString(), "--module", "absent"}));

        assertEquals(ExitCode.INVALID, result.getExitCode());
        assertFalse(result.getEnvelope().isOk());
        assertEquals(ScanException.MODULE_NOT_FOUND, result.getEnvelope().getError().getCode());
    }

    @Test
    public void failsWhenModuleNotCompiled() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        Files.createDirectories(workspace.resolve("repo/domain"));
        Path config = writeConfig(workspace);

        CommandResult result = command(workspace).execute(CliArguments.parse(
                new String[]{"scan", "--config", config.toString()}));

        assertEquals(ExitCode.INVALID, result.getExitCode());
        assertEquals(ScanException.CLASSES_NOT_FOUND, result.getEnvelope().getError().getCode());
    }

    @Test
    public void osFilterWithoutMatchReturnsError() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        Files.createDirectories(workspace.resolve("repo/domain/target/classes"));
        Path config = writeConfig(workspace);

        CommandResult result = command(workspace).execute(CliArguments.parse(
                new String[]{"scan", "--config", config.toString(), "--os", "common-enum"}));

        assertEquals(ExitCode.INVALID, result.getExitCode());
        assertEquals(ScanException.MODULE_NOT_FOUND, result.getEnvelope().getError().getCode());
    }

    private ScanCommand command(Path workspace) throws Exception {
        ModulesConfig config = new ModulesConfigLoader().load(workspace.resolve("modules.yaml"));
        PathGuard guard = PathGuard.create(config.getWorkspaceRoot(), Collections.<String, String>emptyMap());
        final ModuleRegistry registry = ModuleRegistry.create(config, guard);
        final List<Path> classpath = Collections.singletonList(testClasses());

        ModuleClasspathBuilder builder = new ModuleClasspathBuilder() {
            @Override
            public ClasspathResult build(ResolvedModule module) throws ScanException {
                ResolvedModule resolved = registry.resolve(module.getConfig());
                return new ClasspathResult(resolved.getClassesDir(), classpath, false);
            }
        };
        KeyGenerator generator = new KeyGenerator(KeyRules.of(
                "com.flashfin.i18n.tool.fixtures.FakeEnumerable",
                "com.flashfin.i18n.tool.fixtures.FakeResultCode",
                "com.flashfin.i18n.tool.fixtures.FakeStringEnumerable"));
        return new ScanCommand(new ModulesConfigLoader(), builder, new ClassScanner(), generator,
                Collections.<String, String>emptyMap());
    }

    private Path writeConfig(Path workspace) throws IOException {
        String yaml = "version: 1\n"
                + "workspaceRoot: .\n"
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
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private Path testClasses() throws Exception {
        return Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
