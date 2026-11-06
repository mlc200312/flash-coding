package com.flashfin.i18n.tool.git;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GitGuardTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void rejectsDirectoryThatIsNotAGitRepository() throws Exception {
        Path directory = temp.newFolder("plain").toPath();
        GitStatus status = new GitGuard().check(directory);
        assertFalse(status.isClean());
        assertEquals(GitStatus.NOT_A_GIT_REPO, status.getCode());
    }

    @Test
    public void cleanRepositoryHasNoEntries() throws Exception {
        Path repo = temp.newFolder("repo").toPath();
        initRepository(repo);
        GitStatus status = new GitGuard().check(repo);
        assertTrue(status.isClean());
    }

    @Test
    public void reportsDirtyFiles() throws Exception {
        Path repo = temp.newFolder("repo").toPath();
        initRepository(repo);
        Files.write(repo.resolve("tracked.txt"), "changed\n".getBytes(StandardCharsets.UTF_8));

        GitStatus status = new GitGuard().check(repo);
        assertFalse(status.isClean());
        assertEquals(GitStatus.GIT_DIRTY, status.getCode());
        assertTrue(status.getEntries().size() > 0);
    }

    @Test
    public void ignoresToolDataDirectory() throws Exception {
        Path repo = temp.newFolder("repo").toPath();
        initRepository(repo);
        Files.createDirectories(repo.resolve(".i18n-tool/journal"));
        Files.write(repo.resolve(".i18n-tool/journal/upload.json"), "{}".getBytes(
                StandardCharsets.UTF_8));

        GitStatus status = new GitGuard().check(repo);
        assertTrue(status.isClean());
    }

    private void initRepository(Path repo) throws Exception {
        run(repo, "init");
        Files.write(repo.resolve("tracked.txt"), "initial\n".getBytes(StandardCharsets.UTF_8));
        run(repo, "add", ".");
        run(repo, "-c", "user.email=test@example.com", "-c", "user.name=Test",
                "commit", "-m", "init");
    }

    private void run(Path repo, String... arguments) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        for (String argument : arguments) {
            command.add(argument);
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = process.getInputStream().read(chunk)) != -1) {
            output.write(chunk, 0, read);
        }
        int exit = process.waitFor();
        assertEquals(new String(output.toByteArray(), StandardCharsets.UTF_8), 0, exit);
    }
}
