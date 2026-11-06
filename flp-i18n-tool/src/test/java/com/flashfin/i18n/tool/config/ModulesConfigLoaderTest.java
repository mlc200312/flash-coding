package com.flashfin.i18n.tool.config;

import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ModulesConfigLoaderTest {

    private final ModulesConfigLoader loader = new ModulesConfigLoader();

    @Test
    public void loadsValidConfig() throws Exception {
        ModulesConfig config = loader.load(resourcePath("modules-valid.yaml"));

        assertEquals(1, config.getVersion());
        assertEquals(4, config.getLanguages().size());
        assertEquals("zh_CN", config.getLanguages().get(0));
        assertEquals("http://127.0.0.1:18080/v1/translations/save", config.getPlatform().getSaveUrl());
        assertEquals(1000, config.getPlatform().getTimeoutMs());
        assertEquals(2, config.getPlatform().getMaxRetries());

        assertEquals(1, config.getModules().size());
        ModuleConfig module = config.getModules().get("demo");
        assertNotNull(module);
        assertEquals("flp-enum", module.getOs());
        assertEquals(1, module.getPackages().size());
        assertEquals("DemoCodeMessages", module.getCodeFile());
        assertEquals("DemoEnumerableMessages", module.getEnumFile());
    }

    @Test
    public void defaultsLanguagesWhenAbsent() throws Exception {
        Path file = write("version: 1\n"
                + "platform:\n"
                + "  saveUrl: https://example.com/save\n"
                + "  dictionaryBase: https://example.com\n"
                + "modules:\n"
                + "  demo:\n"
                + "    repoRoot: repo\n"
                + "    domainPath: repo/domain\n"
                + "    os: flp-code\n"
                + "    packages: [com.example.type]\n"
                + "    codeFile: DemoCodeMessages\n"
                + "    enumFile: DemoEnumerableMessages\n");

        assertEquals(4, loader.load(file).getLanguages().size());
    }

    @Test
    public void rejectsMissingFile() {
        assertConfigError(Paths.get("/tmp/does-not-exist-" + System.nanoTime() + ".yaml"),
                ConfigException.CONFIG_NOT_FOUND);
    }

    @Test
    public void rejectsMalformedYaml() throws Exception {
        assertConfigError(write("version: [\n"), ConfigException.CONFIG_PARSE_ERROR);
    }

    @Test
    public void rejectsDuplicateKeys() throws Exception {
        assertConfigError(write("version: 1\nversion: 1\nplatform: {}\nmodules: {}\n"),
                ConfigException.CONFIG_PARSE_ERROR);
    }

    @Test
    public void rejectsMissingPlatform() throws Exception {
        assertInvalid(write("version: 1\nmodules:\n  demo:\n    repoRoot: r\n    domainPath: r/d\n"
                + "    os: flp-code\n    packages: [a]\n    codeFile: C\n    enumFile: E\n"), "platform");
    }

    @Test
    public void rejectsUnsupportedOs() throws Exception {
        assertInvalid(validYaml("bad-os"), "os");
    }

    @Test
    public void rejectsEmptyModules() throws Exception {
        assertInvalid("version: 1\n"
                + "platform:\n"
                + "  saveUrl: https://example.com/save\n"
                + "  dictionaryBase: https://example.com\n"
                + "modules: {}\n", "modules");
    }

    @Test
    public void rejectsInvalidUrl() throws Exception {
        Path file = write("version: 1\n"
                + "platform:\n"
                + "  saveUrl: ftp://example.com/save\n"
                + "  dictionaryBase: https://example.com\n"
                + "modules:\n"
                + "  demo:\n"
                + "    repoRoot: repo\n"
                + "    domainPath: repo/domain\n"
                + "    os: flp-code\n"
                + "    packages: [com.example.type]\n"
                + "    codeFile: DemoCodeMessages\n"
                + "    enumFile: DemoEnumerableMessages\n");
        assertInvalid(file, "saveUrl");
    }

    @Test
    public void rejectsInvalidTimeout() throws Exception {
        Path file = write("version: 1\n"
                + "platform:\n"
                + "  saveUrl: https://example.com/save\n"
                + "  dictionaryBase: https://example.com\n"
                + "  timeoutMs: 10\n"
                + "modules:\n"
                + "  demo:\n"
                + "    repoRoot: repo\n"
                + "    domainPath: repo/domain\n"
                + "    os: flp-code\n"
                + "    packages: [com.example.type]\n"
                + "    codeFile: DemoCodeMessages\n"
                + "    enumFile: DemoEnumerableMessages\n");
        assertInvalid(file, "timeoutMs");
    }

    private String validYaml(String os) {
        return "version: 1\n"
                + "platform:\n"
                + "  saveUrl: https://example.com/save\n"
                + "  dictionaryBase: https://example.com\n"
                + "modules:\n"
                + "  demo:\n"
                + "    repoRoot: repo\n"
                + "    domainPath: repo/domain\n"
                + "    os: " + os + "\n"
                + "    packages: [com.example.type]\n"
                + "    codeFile: DemoCodeMessages\n"
                + "    enumFile: DemoEnumerableMessages\n";
    }

    private void assertConfigError(Path file, String expectedCode) {
        try {
            loader.load(file);
            fail("expected ConfigException");
        } catch (ConfigException e) {
            assertEquals(expectedCode, e.getCode());
        }
    }

    private void assertInvalid(Path file, String expectedFragment) {
        try {
            loader.load(file);
            fail("expected ConfigException");
        } catch (ConfigException e) {
            assertEquals(ConfigException.CONFIG_INVALID, e.getCode());
            assertTrue(e.getMessage(), e.getMessage().contains(expectedFragment));
        }
    }

    private void assertInvalid(String yaml, String expectedFragment) throws IOException {
        assertInvalid(write(yaml), expectedFragment);
    }

    private Path write(String content) throws IOException {
        Path file = Files.createTempFile("modules-test", ".yaml");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        return file;
    }

    private Path resourcePath(String name) throws URISyntaxException {
        return Paths.get(getClass().getClassLoader().getResource(name).toURI());
    }
}
