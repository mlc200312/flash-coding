package com.flashfin.i18n.tool.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashfin.i18n.tool.sync.UploadEntry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpPlatformClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> requests = new ArrayList<String>();
    private HttpServer server;

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void postsLegacyRequestShapeWithBearerToken() throws Exception {
        String url = startServer(200, "");
        HttpPlatformClient client = new HttpPlatformClient(url, "test-token", 1000, 0);

        PlatformSaveResult result = client.save(new UploadEntry("demo", "flp-code", "code_100",
                "code", "en_id", "Message"));

        assertTrue(result.isOk());
        assertEquals(200, result.getStatus());
        JsonNode body = mapper.readTree(requests.get(0));
        assertEquals("flp-code", body.get("os").asText());
        assertEquals("code_100", body.get("t_key").asText());
        assertEquals("id", body.get("langs").fieldNames().next());
        assertEquals("Message", body.get("langs").get("id").asText());
        assertTrue(lastAuthorization.startsWith("Bearer "));
        assertEquals("Bearer test-token", lastAuthorization);
    }

    @Test
    public void unauthorizedIsNotRetried() throws Exception {
        String url = startServer(401, "denied");
        HttpPlatformClient client = new HttpPlatformClient(url, "test-token", 1000, 3);

        PlatformSaveResult result = client.save(new UploadEntry("demo", "flp-code", "code_1",
                "code", "en", "M"));

        assertEquals("UPLOAD_UNAUTHORIZED", result.getCode());
        assertEquals(1, requests.size());
    }

    @Test
    public void serverErrorsAreRetried() throws Exception {
        String url = startServer(500, "boom");
        HttpPlatformClient client = new HttpPlatformClient(url, "test-token", 1000, 2);

        PlatformSaveResult result = client.save(new UploadEntry("demo", "flp-code", "code_1",
                "code", "en", "M"));

        assertEquals("UPLOAD_FAILED", result.getCode());
        assertEquals(3, requests.size());
    }

    private String lastAuthorization;

    private String startServer(final int status, final String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/save", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                requests.add(read(exchange.getRequestBody()));
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                OutputStream output = exchange.getResponseBody();
                output.write(body);
                output.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/save";
    }

    private String read(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
