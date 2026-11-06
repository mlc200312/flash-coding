package com.flashfin.i18n.tool.scan;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClassScannerTest {

    private static final String FIXTURE_PACKAGE = "com.flashfin.i18n.tool.fixtures";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final ClassScanner scanner = new ClassScanner();

    @Test
    public void scansDirectoriesRecursively() throws Exception {
        TreeSet<String> names = scanner.scanClassNames(testClasses(),
                Collections.singletonList(FIXTURE_PACKAGE));

        assertTrue(names.contains(FIXTURE_PACKAGE + ".SampleStatusEnum"));
        assertTrue(names.contains(FIXTURE_PACKAGE + ".DuplicateEnums$DupOne"));
        assertFalse(names.contains(FIXTURE_PACKAGE + ".package-info"));
        for (String name : names) {
            assertTrue(name, name.startsWith(FIXTURE_PACKAGE + "."));
        }
    }

    @Test
    public void loadsClassesWithoutInitialization() throws Exception {
        List<Path> classpath = testClasses();
        TreeSet<String> names = new TreeSet<String>();
        names.add(FIXTURE_PACKAGE + ".SampleStatusEnum");
        names.add(FIXTURE_PACKAGE + ".DoesNotExist");

        ClassScanner.LoadedClasses loaded = scanner.load(classpath, names);
        try {
            assertEquals(1, loaded.getClasses().size());
            assertEquals(FIXTURE_PACKAGE + ".SampleStatusEnum", loaded.getClasses().get(0).getName());
            assertEquals(1, loaded.getFailures().size());
        } finally {
            loaded.close();
        }
    }

    @Test
    public void scansJarEntriesByPackagePrefix() throws Exception {
        Path jar = temp.newFile("fixtures.jar").toPath();
        writeJar(jar, new String[]{
                "com/example/demo/A.class",
                "com/example/demo/A$Inner.class",
                "com/example/demo/sub/C.class",
                "com/example/demo/package-info.class",
                "com/example/other/D.class",
                "com/example/demo/readme.txt"
        });

        TreeSet<String> names = scanner.scanClassNames(Collections.singletonList(jar),
                Collections.singletonList("com.example.demo"));

        assertEquals(3, names.size());
        assertTrue(names.contains("com.example.demo.A"));
        assertTrue(names.contains("com.example.demo.A$Inner"));
        assertTrue(names.contains("com.example.demo.sub.C"));
    }

    @Test
    public void skipsMissingPackageDirectories() throws Exception {
        TreeSet<String> names = scanner.scanClassNames(testClasses(),
                Collections.singletonList("com.example.absent"));

        assertTrue(names.isEmpty());
    }

    private void writeJar(Path jar, String[] entries) throws Exception {
        OutputStream output = Files.newOutputStream(jar);
        JarOutputStream jarOutput = new JarOutputStream(output);
        try {
            for (String entry : entries) {
                jarOutput.putNextEntry(new JarEntry(entry));
                jarOutput.write(new byte[]{0});
                jarOutput.closeEntry();
            }
        } finally {
            jarOutput.close();
        }
    }

    private List<Path> testClasses() throws Exception {
        Path location = Paths.get(ClassScannerTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        return Collections.singletonList(location);
    }
}
