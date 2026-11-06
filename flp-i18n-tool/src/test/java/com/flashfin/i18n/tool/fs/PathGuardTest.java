package com.flashfin.i18n.tool.fs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PathGuardTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void resolvesRepoAndDomainWithinWorkspace() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        Files.createDirectories(workspace.resolve("repo/domain"));
        PathGuard guard = PathGuard.create(workspace, Collections.<String, String>emptyMap());

        Path repo = guard.resolveRepoRoot("repo");
        Path domain = guard.resolveDomainPath(repo, "domain");

        assertTrue(domain.startsWith(workspace.toRealPath()));
        assertEquals(workspace.toRealPath().resolve("repo").resolve("domain"), domain);
    }

    @Test
    public void rejectsTraversalSegments() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        temp.newFolder("outside");
        PathGuard guard = PathGuard.create(workspace, Collections.<String, String>emptyMap());

        assertCode(PathGuardException.PATH_NOT_ALLOWED, guard, "../outside");
    }

    @Test
    public void rejectsAbsolutePathOutsideWorkspace() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        Path outside = temp.newFolder("outside").toPath();
        PathGuard guard = PathGuard.create(workspace, Collections.<String, String>emptyMap());

        assertCode(PathGuardException.PATH_NOT_ALLOWED, guard, outside.toString());
    }

    @Test
    public void rejectsSymlinkEscape() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        Path outside = temp.newFolder("outside").toPath();
        Path link = workspace.resolve("link");
        Files.createSymbolicLink(link, outside);
        PathGuard guard = PathGuard.create(workspace, Collections.<String, String>emptyMap());

        assertCode(PathGuardException.PATH_NOT_ALLOWED, guard, "link");
    }

    @Test
    public void rejectsMissingDirectory() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        PathGuard guard = PathGuard.create(workspace, Collections.<String, String>emptyMap());

        assertCode(PathGuardException.PATH_NOT_FOUND, guard, "missing");
    }

    @Test
    public void allowedRootsMustContainWorkspace() throws Exception {
        Path workspace = temp.newFolder("workspace").toPath();
        Path other = temp.newFolder("other").toPath();
        Map<String, String> env = new HashMap<String, String>();
        env.put(PathGuard.ENV_ALLOWED_ROOTS, other.toString());

        try {
            PathGuard.create(workspace, env);
            fail("expected PathGuardException");
        } catch (PathGuardException e) {
            assertEquals(PathGuardException.PATH_NOT_ALLOWED, e.getCode());
        }
    }

    @Test
    public void allowedRootsCanWidenScope() throws Exception {
        Path base = temp.newFolder("base").toPath();
        Path workspace = Files.createDirectories(base.resolve("workspace"));
        Files.createDirectories(workspace.resolve("repo"));
        Map<String, String> env = new HashMap<String, String>();
        env.put(PathGuard.ENV_ALLOWED_ROOTS, base.toString());

        PathGuard guard = PathGuard.create(workspace, env);
        assertEquals(workspace.toRealPath().resolve("repo"), guard.resolveRepoRoot("repo"));
    }

    private void assertCode(String code, PathGuard guard, String relative) {
        try {
            guard.resolveRepoRoot(relative);
            fail("expected PathGuardException");
        } catch (PathGuardException e) {
            assertEquals(code, e.getCode());
        }
    }
}
