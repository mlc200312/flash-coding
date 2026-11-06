package com.flashfin.i18n.tool.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HttpReachabilityProbeTest {

    private HttpServer server;
    private int port;
    private final HttpReachabilityProbe probe = new HttpReachabilityProbe();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200));
        server.createContext("/unhealthy", exchange -> respond(exchange, 503));
        server.start();
        port = server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void treatsHttpResponseBelow500AsReachable() {
        ProbeResult result = probe.probe("http://127.0.0.1:" + port + "/ok", 1000);

        assertTrue(result.isReachable());
        assertEquals(Integer.valueOf(200), result.getStatusCode());
        assertNull(result.getError());
    }

    @Test
    public void treatsServerErrorAsUnreachable() {
        ProbeResult result = probe.probe("http://127.0.0.1:" + port + "/unhealthy", 1000);

        assertFalse(result.isReachable());
        assertEquals(Integer.valueOf(503), result.getStatusCode());
    }

    @Test
    public void reportsConnectionFailure() throws Exception {
        int closedPort = closedPort();
        ProbeResult result = probe.probe("http://127.0.0.1:" + closedPort + "/", 300);

        assertFalse(result.isReachable());
        assertNull(result.getStatusCode());
        assertNotNull(result.getError());
        assertTrue(result.getLatencyMs() >= 0);
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
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
