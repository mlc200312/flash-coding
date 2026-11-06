package com.flashfin.i18n.tool.dictionary;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HttpDictionaryClientTest {

    private HttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void fetchesDictionaryWithLanguageMapping() throws Exception {
        final AtomicReference<String> path = new AtomicReference<String>();
        server.createContext("/flp-code/lang/zh.json", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"code_0\":\"成功\",\"code_100\":null,\"flag\":true}");
        });

        HttpDictionaryClient client = new HttpDictionaryClient(baseUrl + "/", 1000, 0);
        RemoteDictionary dictionary = client.fetch("flp-code", "zh_CN");

        assertTrue(dictionary.isAvailable());
        assertEquals("/flp-code/lang/zh.json", path.get());
        assertEquals("成功", dictionary.getValues().get("code_0"));
        assertEquals("", dictionary.getValues().get("code_100"));
        assertEquals("true", dictionary.getValues().get("flag"));
        assertNull(dictionary.getErrorCode());
    }

    @Test
    public void mapsEnIdToCdnPath() throws Exception {
        final AtomicReference<String> path = new AtomicReference<String>();
        server.createContext("/flp-code/lang/en_id.json", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{}");
        });

        HttpDictionaryClient client = new HttpDictionaryClient(baseUrl, 1000, 0);
        RemoteDictionary dictionary = client.fetch("flp-code", "en_ID");

        assertTrue(dictionary.isAvailable());
        assertEquals("/flp-code/lang/en_id.json", path.get());
    }

    @Test
    public void retriesServerErrors() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        server.createContext("/retry/lang/zh.json", exchange -> {
            if (requests.incrementAndGet() == 1) {
                respond(exchange, 500, "{}");
            } else {
                respond(exchange, 200, "{\"code_0\":\"ok\"}");
            }
        });

        HttpDictionaryClient client = new HttpDictionaryClient(baseUrl, 1000, 2);
        RemoteDictionary dictionary = client.fetch("retry", "zh_CN");

        assertTrue(dictionary.isAvailable());
        assertEquals(2, requests.get());
        assertEquals("ok", dictionary.getValues().get("code_0"));
    }

    @Test
    public void doesNotRetryNotFound() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        server.createContext("/missing/lang/zh.json", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 404, "{}");
        });

        HttpDictionaryClient client = new HttpDictionaryClient(baseUrl, 1000, 2);
        RemoteDictionary dictionary = client.fetch("missing", "zh_CN");

        assertFalse(dictionary.isAvailable());
        assertEquals(DictionaryException.NOT_FOUND, dictionary.getErrorCode());
        assertEquals(1, requests.get());
    }

    @Test
    public void rejectsMalformedJson() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        server.createContext("/invalid/lang/zh.json", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "not-json");
        });

        HttpDictionaryClient client = new HttpDictionaryClient(baseUrl, 1000, 2);
        RemoteDictionary dictionary = client.fetch("invalid", "zh_CN");

        assertFalse(dictionary.isAvailable());
        assertEquals(DictionaryException.RESPONSE_INVALID, dictionary.getErrorCode());
        assertEquals(1, requests.get());
    }

    @Test
    public void rejectsNonObjectJson() throws Exception {
        server.createContext("/array/lang/zh.json",
                exchange -> respond(exchange, 200, "[1,2]"));

        RemoteDictionary dictionary = new HttpDictionaryClient(baseUrl, 1000, 0)
                .fetch("array", "zh_CN");

        assertFalse(dictionary.isAvailable());
        assertEquals(DictionaryException.RESPONSE_INVALID, dictionary.getErrorCode());
    }

    @Test
    public void rejectsNonScalarValues() throws Exception {
        server.createContext("/object/lang/zh.json",
                exchange -> respond(exchange, 200, "{\"nested\":{}}"));

        RemoteDictionary dictionary = new HttpDictionaryClient(baseUrl, 1000, 0)
                .fetch("object", "zh_CN");

        assertFalse(dictionary.isAvailable());
        assertEquals(DictionaryException.RESPONSE_INVALID, dictionary.getErrorCode());
    }

    @Test
    public void reportsFetchFailureForClosedPort() throws Exception {
        int closedPort = closedPort();
        RemoteDictionary dictionary = new HttpDictionaryClient(
                "http://127.0.0.1:" + closedPort, 300, 0).fetch("flp-code", "zh_CN");

        assertFalse(dictionary.isAvailable());
        assertEquals(DictionaryException.FETCH_FAILED, dictionary.getErrorCode());
        assertNotNull(dictionary.getErrorMessage());
    }

    @Test
    public void rejectsUnsafeOs() {
        RemoteDictionary dictionary = new HttpDictionaryClient(baseUrl, 1000, 0)
                .fetch("../etc", "zh_CN");

        assertFalse(dictionary.isAvailable());
        assertEquals(DictionaryException.INVALID_LANGUAGE, dictionary.getErrorCode());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream output = exchange.getResponseBody();
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
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
