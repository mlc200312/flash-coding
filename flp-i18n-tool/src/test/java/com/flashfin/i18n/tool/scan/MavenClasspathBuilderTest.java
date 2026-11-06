package com.flashfin.i18n.tool.scan;

import com.flashfin.i18n.tool.config.ModuleConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MavenClasspathBuilderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void parsesExistingEntriesAndDropsMissingOrDuplicates() throws Exception {
        Path first = temp.newFolder("first").toPath();
        Path second = temp.newFolder("second").toPath();
        Path missing = temp.getRoot().toPath().resolve("missing");
        String content = first + File.pathSeparator + second + File.pathSeparator
                + first + File.pathSeparator + missing + File.pathSeparator + "  ";

        List<Path> entries = MavenClasspathBuilder.parseClasspathContent(content);

        assertEquals(2, entries.size());
        assertEquals(first.toAbsolutePath().normalize(), entries.get(0));
        assertEquals(second.toAbsolutePath().normalize(), entries.get(1));
    }

    @Test
    public void returnsEmptyForBlankContent() {
        assertTrue(MavenClasspathBuilder.parseClasspathContent(null).isEmpty());
        assertTrue(MavenClasspathBuilder.parseClasspathContent("   ").isEmpty());
    }

    @Test
    public void reportsMissingPom() throws Exception {
        Path domain = temp.newFolder("domain").toPath();
        Files.createDirectories(domain.resolve("target/classes"));
        ResolvedModule module = new ResolvedModule(new ModuleConfig("demo", "repo", "domain",
                "flp-code", Collections.singletonList("com.example.type"),
                "DemoCodeMessages", "DemoEnumerableMessages"),
                domain.getParent(), domain, domain.resolve("target/classes"));

        try {
            new MavenClasspathBuilder().build(module);
        } catch (ScanException e) {
            assertEquals(ScanException.CLASSPATH_BUILD_FAILED, e.getCode());
            assertTrue(e.getMessage().contains("pom.xml"));
        }
    }
}
