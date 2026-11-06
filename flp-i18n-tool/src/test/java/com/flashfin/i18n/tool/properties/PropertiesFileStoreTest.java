package com.flashfin.i18n.tool.properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PropertiesFileStoreTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writesAtomicallyAndBacksUpPreviousContent() throws Exception {
        Path repo = temp.newFolder("repo").toPath();
        Path file = repo.resolve("src/main/resources/i18n/Demo_en.properties");
        Files.createDirectories(file.getParent());
        String original = "# header\ncode_100=old\n";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));

        PropertiesFileStore store = new PropertiesFileStore();
        PropertiesDocument document = store.read(file);
        document.setValue("code_100", "系统错误");

        BackupSession backup = BackupSession.create(temp.newFolder("data").toPath());
        PropertiesWriteResult result = store.write(file, document, backup, "demo", repo);

        assertTrue(result.isChanged());
        assertNotNull(result.getBackup());
        assertEquals(original, new String(Files.readAllBytes(result.getBackup()),
                StandardCharsets.UTF_8));
        assertEquals("# header\ncode_100=\\u7CFB\\u7EDF\\u9519\\u8BEF\n",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    public void unchangedContentIsNotRewritten() throws Exception {
        Path repo = temp.newFolder("repo").toPath();
        Path file = repo.resolve("Demo.properties");
        Files.write(file, "key=value\n".getBytes(StandardCharsets.UTF_8));

        PropertiesFileStore store = new PropertiesFileStore();
        PropertiesDocument document = store.read(file);
        BackupSession backup = BackupSession.create(temp.newFolder("data").toPath());
        PropertiesWriteResult result = store.write(file, document, backup, "demo", repo);

        assertFalse(result.isChanged());
    }

    @Test
    public void createsMissingFileWithParentDirectories() throws Exception {
        Path repo = temp.newFolder("repo").toPath();
        Path file = repo.resolve("src/main/resources/i18n/Demo_en.properties");

        PropertiesFileStore store = new PropertiesFileStore();
        PropertiesDocument document = PropertiesDocument.empty();
        document.setValue("code_100", "done");
        BackupSession backup = BackupSession.create(temp.newFolder("data").toPath());
        PropertiesWriteResult result = store.write(file, document, backup, "demo", repo);

        assertTrue(result.isChanged());
        assertEquals("code_100=done\n",
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
}
