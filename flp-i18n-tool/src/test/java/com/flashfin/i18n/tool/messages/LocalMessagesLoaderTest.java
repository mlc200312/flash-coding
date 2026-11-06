package com.flashfin.i18n.tool.messages;

import com.flashfin.i18n.tool.config.ModuleConfig;
import com.flashfin.i18n.tool.scan.ResolvedModule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LocalMessagesLoaderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final LocalMessagesLoader loader = new LocalMessagesLoader();

    @Test
    public void loadsSuffixedAndPlainChineseFilesWithSuffixPrecedence() throws Exception {
        ResolvedModule module = module();
        write(module, "DemoCodeMessages_zh_CN.properties", "code_0=成功\n");
        write(module, "DemoEnumerableMessages_zh_CN.properties", "Demo_1=启用\n");
        write(module, "DemoCodeMessages.properties", "code_0=默认\ncode_100=默认错误\n");
        write(module, "DemoEnumerableMessages.properties", "Demo_1=默认枚举\nDemo_2=默认2\n");

        LocalMessages messages = loader.load(module, "zh_CN");

        assertEquals(4, messages.getLoadedFiles().size());
        assertTrue(messages.getMissingFiles().isEmpty());
        assertEquals("成功", messages.get("code_0"));
        assertEquals("默认错误", messages.get("code_100"));
        assertEquals("启用", messages.get("Demo_1"));
        assertEquals("默认2", messages.get("Demo_2"));
    }

    @Test
    public void usesEnIdPropertiesSuffix() throws Exception {
        ResolvedModule module = module();
        write(module, "DemoCodeMessages_en_ID.properties", "code_0=success\n");
        write(module, "DemoEnumerableMessages_en_ID.properties", "Demo_1=enabled\n");

        LocalMessages messages = loader.load(module, "en_id");

        assertEquals(2, messages.getLoadedFiles().size());
        assertTrue(messages.getMissingFiles().isEmpty());
        assertEquals("success", messages.get("code_0"));
        assertEquals("enabled", messages.get("Demo_1"));
    }

    @Test
    public void recordsMissingFiles() throws Exception {
        ResolvedModule module = module();

        LocalMessages messages = loader.load(module, "th");

        assertEquals(2, messages.getMissingFiles().size());
        assertTrue(messages.getMissingFiles().get(0).endsWith("DemoCodeMessages_th.properties"));
        assertTrue(messages.getMissingFiles().get(1).endsWith("DemoEnumerableMessages_th.properties"));
        assertTrue(messages.getValues().isEmpty());
    }

    @Test
    public void recordsMissingDirectory() throws Exception {
        Path repo = temp.newFolder("repo").toPath();
        ModuleConfig config = config();
        ResolvedModule module = new ResolvedModule(config, repo, repo.resolve("domain"),
                repo.resolve("domain/target/classes"));

        LocalMessages messages = loader.load(module, "en");

        assertEquals(1, messages.getMissingFiles().size());
        assertTrue(messages.getMissingFiles().get(0).endsWith("i18n"));
        assertTrue(messages.getValues().isEmpty());
    }

    private ResolvedModule module() throws IOException {
        Path repo = temp.newFolder("repo").toPath();
        Path domain = repo.resolve("domain");
        Files.createDirectories(domain.resolve("src/main/resources/i18n"));
        return new ResolvedModule(config(), repo, domain, domain.resolve("target/classes"));
    }

    private ModuleConfig config() {
        return new ModuleConfig("demo", "repo", "domain", "flp-code",
                Collections.singletonList("com.example.type"),
                "DemoCodeMessages", "DemoEnumerableMessages");
    }

    private void write(ResolvedModule module, String name, String content) throws IOException {
        Path dir = module.getDomainRoot().resolve("src/main/resources/i18n");
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }
}
