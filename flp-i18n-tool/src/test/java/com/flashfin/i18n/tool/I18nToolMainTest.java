package com.flashfin.i18n.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class I18nToolMainTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void statusWithoutTokenEmitsJsonAndClearError() throws Exception {
        Path config = configWithClosedPorts();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int exitCode = new I18nToolMain().run(
                new String[]{"status", "--config", config.toAbsolutePath().toString(), "--json"},
                new HashMap<String, String>(), new PrintStream(buffer, true, "UTF-8"));

        JsonNode node = mapper.readTree(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(2, exitCode);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("status", node.get("command").asText());
        assertNotNull(node.get("data"));
        assertEquals("TOKEN_MISSING", node.get("error").get("code").asText());
    }

    @Test
    public void unknownArgumentEmitsInvalidArgument() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int exitCode = new I18nToolMain().run(new String[]{"--bogus"},
                new HashMap<String, String>(), new PrintStream(buffer, true, "UTF-8"));

        JsonNode node = mapper.readTree(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(2, exitCode);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("INVALID_ARGUMENT", node.get("error").get("code").asText());
    }

    @Test
    public void unimplementedCommandFailsClearly() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int exitCode = new I18nToolMain().run(new String[]{"publish", "--json"},
                new HashMap<String, String>(), new PrintStream(buffer, true, "UTF-8"));

        JsonNode node = mapper.readTree(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(2, exitCode);
        assertEquals("COMMAND_NOT_IMPLEMENTED", node.get("error").get("code").asText());
    }

    @Test
    public void uploadDispatchesToConfigLoading() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Path missing = Paths.get("/tmp/does-not-exist-" + System.nanoTime() + ".yaml");

        int exitCode = new I18nToolMain().run(
                new String[]{"upload", "--config", missing.toString(), "--json"},
                new HashMap<String, String>(), new PrintStream(buffer, true, "UTF-8"));

        JsonNode node = mapper.readTree(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(2, exitCode);
        assertEquals("upload", node.get("command").asText());
        assertEquals("CONFIG_NOT_FOUND", node.get("error").get("code").asText());
    }

    @Test
    public void downloadDispatchesToConfigLoading() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Path missing = Paths.get("/tmp/does-not-exist-" + System.nanoTime() + ".yaml");

        int exitCode = new I18nToolMain().run(
                new String[]{"download", "--config", missing.toString(), "--json"},
                new HashMap<String, String>(), new PrintStream(buffer, true, "UTF-8"));

        JsonNode node = mapper.readTree(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(2, exitCode);
        assertEquals("download", node.get("command").asText());
        assertEquals("CONFIG_NOT_FOUND", node.get("error").get("code").asText());
    }

    @Test
    public void diffDispatchesToConfigLoading() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Path missing = Paths.get("/tmp/does-not-exist-" + System.nanoTime() + ".yaml");

        int exitCode = new I18nToolMain().run(
                new String[]{"diff", "--config", missing.toString(), "--json"},
                new HashMap<String, String>(), new PrintStream(buffer, true, "UTF-8"));

        JsonNode node = mapper.readTree(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(2, exitCode);
        assertEquals("diff", node.get("command").asText());
        assertEquals("CONFIG_NOT_FOUND", node.get("error").get("code").asText());
    }

    @Test
    public void validateDispatchesToConfigLoading() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Path missing = Paths.get("/tmp/does-not-exist-" + System.nanoTime() + ".yaml");

        int exitCode = new I18nToolMain().run(
                new String[]{"validate", "--config", missing.toString(), "--json"},
                new HashMap<String, String>(), new PrintStream(buffer, true, "UTF-8"));

        JsonNode node = mapper.readTree(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(2, exitCode);
        assertEquals("validate", node.get("command").asText());
        assertEquals("CONFIG_NOT_FOUND", node.get("error").get("code").asText());
    }

    @Test
    public void helpEmitsJson() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int exitCode = new I18nToolMain().run(new String[]{"--help"},
                new HashMap<String, String>(), new PrintStream(buffer, true, "UTF-8"));

        JsonNode node = mapper.readTree(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(0, exitCode);
        assertTrue(node.get("ok").asBoolean());
        assertEquals("help", node.get("command").asText());
        assertTrue(node.get("data").get("usage").asText().contains("status"));
    }

    private Path configWithClosedPorts() throws IOException {
        int port = closedPort();
        String yaml = "version: 1\n"
                + "platform:\n"
                + "  saveUrl: http://127.0.0.1:" + port + "/save\n"
                + "  dictionaryBase: http://127.0.0.1:" + port + "/cdn\n"
                + "  timeoutMs: 300\n"
                + "modules:\n"
                + "  demo:\n"
                + "    repoRoot: repo\n"
                + "    domainPath: repo/domain\n"
                + "    os: flp-code\n"
                + "    packages: [com.example.type]\n"
                + "    codeFile: DemoCodeMessages\n"
                + "    enumFile: DemoEnumerableMessages\n";
        Path file = Files.createTempFile("cli-config", ".yaml");
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        return file;
    }

    private static int closedPort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }
}
