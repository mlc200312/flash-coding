package com.flashfin.i18n.tool.cli;

import com.flashfin.i18n.tool.config.ModulesConfigLoader;
import com.flashfin.i18n.tool.platform.HttpReachabilityProbe;
import com.flashfin.i18n.tool.report.JsonEnvelope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StatusCommandTest {

    private HttpServer server;
    private int port;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cdn", exchange -> respond(exchange, 200));
        server.createContext("/save", exchange -> respond(exchange, 405));
        server.start();
        port = server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void reportsOkWithValidTokenAndReachableRemotes() throws Exception {
        Map<String, String> env = new HashMap<String, String>();
        env.put("FLP_I18N_TOKEN", jwt(nowSeconds() + 172800));

        CommandResult result = status(config(defaultCdnUrl(), defaultSaveUrl(), 1000), env);

        assertEquals(ExitCode.OK, result.getExitCode());
        assertTrue(result.getEnvelope().isOk());
        assertNull(result.getEnvelope().getError());

        Map<String, Object> data = asMap(result.getEnvelope().getData());
        assertEquals(Boolean.TRUE, data.get("configOk"));
        assertEquals(1, data.get("moduleCount"));

        Map<String, Object> token = asMap(data.get("token"));
        assertEquals(Boolean.TRUE, token.get("present"));
        assertEquals(Boolean.TRUE, token.get("jwt"));
        assertNotNull(token.get("expiresAt"));

        Map<String, Object> remote = asMap(data.get("remote"));
        assertEquals(Boolean.TRUE, asMap(remote.get("cdn")).get("reachable"));
        assertEquals(Boolean.TRUE, asMap(remote.get("platform")).get("reachable"));
        assertEquals(Integer.valueOf(405), asMap(remote.get("platform")).get("status"));
        assertTrue(result.getEnvelope().getWarnings().isEmpty());
    }

    @Test
    public void reportsClearErrorWhenTokenMissing() throws Exception {
        CommandResult result = status(config(defaultCdnUrl(), defaultSaveUrl(), 1000),
                new HashMap<String, String>());

        assertEquals(ExitCode.INVALID, result.getExitCode());
        assertFalse(result.getEnvelope().isOk());
        assertEquals("TOKEN_MISSING", result.getEnvelope().getError().getCode());
        assertTrue(result.getEnvelope().getError().getHint().contains("FLP_I18N_TOKEN"));

        Map<String, Object> data = asMap(result.getEnvelope().getData());
        assertEquals(Boolean.FALSE, asMap(data.get("token")).get("present"));
        assertEquals(Boolean.TRUE, asMap(asMap(data.get("remote")).get("cdn")).get("reachable"));
    }

    @Test
    public void reportsExpiredToken() throws Exception {
        Map<String, String> env = new HashMap<String, String>();
        env.put("FLP_I18N_TOKEN", jwt(nowSeconds() - 60));

        CommandResult result = status(config(defaultCdnUrl(), defaultSaveUrl(), 1000), env);

        assertEquals(ExitCode.INVALID, result.getExitCode());
        assertEquals("TOKEN_EXPIRED", result.getEnvelope().getError().getCode());
    }

    @Test
    public void warnsWhenTokenIsOpaque() throws Exception {
        Map<String, String> env = new HashMap<String, String>();
        env.put("FLP_I18N_TOKEN", "opaque-token-value");

        CommandResult result = status(config(defaultCdnUrl(), defaultSaveUrl(), 1000), env);

        assertEquals(ExitCode.OK, result.getExitCode());
        assertTrue(result.getEnvelope().isOk());
        assertEquals(Boolean.FALSE, asMap(asMap(result.getEnvelope().getData()).get("token")).get("jwt"));
        assertEquals(1, result.getEnvelope().getWarnings().size());
        assertTrue(result.getEnvelope().getWarnings().get(0).startsWith("TOKEN_UNVERIFIABLE"));
    }

    @Test
    public void reportsRemoteFailureWithExitCodeThree() throws Exception {
        int closedPort = closedPort();
        Map<String, String> env = new HashMap<String, String>();
        env.put("FLP_I18N_TOKEN", jwt(nowSeconds() + 3600));

        CommandResult result = status(
                config(defaultCdnUrl(), "http://127.0.0.1:" + closedPort + "/save", 300), env);

        assertEquals(ExitCode.REMOTE_ERROR, result.getExitCode());
        assertFalse(result.getEnvelope().isOk());
        assertEquals("PLATFORM_UNREACHABLE", result.getEnvelope().getError().getCode());
        assertTrue(result.getEnvelope().getError().isRetryable());
        assertEquals(Boolean.FALSE,
                asMap(asMap(asMap(result.getEnvelope().getData()).get("remote")).get("platform")).get("reachable"));
    }

    @Test
    public void reportsMissingConfig() throws Exception {
        CommandResult result = status(Paths.get("/tmp/missing-" + System.nanoTime() + ".yaml"),
                new HashMap<String, String>());

        assertEquals(ExitCode.INVALID, result.getExitCode());
        assertEquals("CONFIG_NOT_FOUND", result.getEnvelope().getError().getCode());
        assertEquals(Boolean.FALSE, asMap(result.getEnvelope().getData()).get("configOk"));
    }

    @Test
    public void reportsInvalidConfig() throws Exception {
        Path file = write("version: 1\nmodules: {}\n");
        CommandResult result = status(file, new HashMap<String, String>());

        assertEquals(ExitCode.INVALID, result.getExitCode());
        assertEquals("CONFIG_INVALID", result.getEnvelope().getError().getCode());
    }

    @Test
    public void resolvesConfigFromEnvironment() throws Exception {
        Map<String, String> env = new HashMap<String, String>();
        env.put(StatusCommand.ENV_CONFIG,
                config(defaultCdnUrl(), defaultSaveUrl(), 1000).toAbsolutePath().toString());
        env.put("FLP_I18N_TOKEN", jwt(nowSeconds() + 3600));

        CliArguments args = CliArguments.parse(new String[]{"status", "--json"});
        CommandResult result = new StatusCommand(new ModulesConfigLoader(),
                new HttpReachabilityProbe(), env).execute(args);

        assertEquals(ExitCode.OK, result.getExitCode());
    }

    private CommandResult status(Path config, Map<String, String> env) throws Exception {
        CliArguments args = CliArguments.parse(new String[]{
                "status", "--config", config.toAbsolutePath().toString(), "--json"});
        return new StatusCommand(new ModulesConfigLoader(), new HttpReachabilityProbe(), env).execute(args);
    }

    private Path config(String cdnUrl, String saveUrl, int timeoutMs) throws IOException {
        return write("version: 1\n"
                + "languages: [zh_CN, en, th, en_id]\n"
                + "platform:\n"
                + "  saveUrl: " + saveUrl + "\n"
                + "  dictionaryBase: " + cdnUrl + "\n"
                + "  timeoutMs: " + timeoutMs + "\n"
                + "  maxRetries: 1\n"
                + "modules:\n"
                + "  demo:\n"
                + "    repoRoot: repo\n"
                + "    domainPath: repo/domain\n"
                + "    os: flp-code\n"
                + "    packages: [com.example.type]\n"
                + "    codeFile: DemoCodeMessages\n"
                + "    enumFile: DemoEnumerableMessages\n");
    }

    private Path write(String content) throws IOException {
        Path file = Files.createTempFile("status-config", ".yaml");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        file.toFile().deleteOnExit();
        return file;
    }

    private String defaultCdnUrl() {
        return "http://127.0.0.1:" + port + "/cdn";
    }

    private String defaultSaveUrl() {
        return "http://127.0.0.1:" + port + "/save";
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String jwt(long expSeconds) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"UserId\":\"tester\",\"exp\":" + expSeconds + "}");
        return header + "." + payload + ".test-signature";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int closedPort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
