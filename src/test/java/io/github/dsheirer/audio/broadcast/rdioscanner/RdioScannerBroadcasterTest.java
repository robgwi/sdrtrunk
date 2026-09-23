package io.github.dsheirer.audio.broadcast.rdioscanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RdioScannerBroadcasterTest
{
    @Test
    void sendsConfiguredHeartbeatWithAuthenticationAndSystemIdentity() throws Exception
    {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> systemId = new AtomicReference<>();
        AtomicReference<String> event = new AtomicReference<>();
        CountDownLatch heartbeatReceived = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/call-upload", exchange ->
        {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "Incomplete call data: no talkgroup".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/scanner-heartbeat", exchange ->
        {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            systemId.set(exchange.getRequestHeaders().getFirst("X-RdioScanner-System-Id"));
            event.set(exchange.getRequestHeaders().getFirst("X-SDRTrunk-Event"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            heartbeatReceived.countDown();
        });
        server.start();

        RdioScannerConfiguration configuration = new RdioScannerConfiguration();
        configuration.setName("County Rdio");
        configuration.setHost("http://127.0.0.1:" + server.getAddress().getPort() + "/api/call-upload");
        configuration.setApiKey("rdio-secret");
        configuration.setSystemID(42);
        configuration.setHeartbeatEnabled(true);
        configuration.setHeartbeatIntervalSeconds(5);
        configuration.setHeartbeatUrl("http://127.0.0.1:" + server.getAddress().getPort() +
            "/scanner-heartbeat");
        RdioScannerBroadcaster broadcaster = new RdioScannerBroadcaster(configuration, null, null, null);

        try
        {
            broadcaster.start();
            assertTrue(heartbeatReceived.await(5, TimeUnit.SECONDS));
            for(int attempt = 0; attempt < 100 && broadcaster.getLastHeartbeatSuccess() == 0; attempt++)
            {
                Thread.sleep(10);
            }

            assertEquals("rdio-secret", apiKey.get());
            assertEquals("42", systemId.get());
            assertEquals("heartbeat", event.get());
            assertTrue(body.get().contains("\"event\":\"heartbeat\""));
            assertTrue(body.get().contains("\"destination\":\"County Rdio\""));
            assertTrue(body.get().contains("\"systemId\":42"));
            assertTrue(broadcaster.getLastHeartbeatSuccess() > 0);
        }
        finally
        {
            broadcaster.stop();
            server.stop(0);
        }
    }

    @Test
    void derivesHeartbeatUrlAndCopiesSettings()
    {
        RdioScannerConfiguration configuration = new RdioScannerConfiguration();
        configuration.setName("Rdio");
        configuration.setHost("https://scanner.example/api/call-upload");
        configuration.setApiKey("secret");
        configuration.setSystemID(7);
        configuration.setHeartbeatEnabled(true);
        configuration.setHeartbeatIntervalSeconds(45);

        assertEquals("https://scanner.example/api/heartbeat", configuration.resolveHeartbeatUrl());
        RdioScannerConfiguration copy = (RdioScannerConfiguration)configuration.copyOf();
        assertEquals("https://scanner.example/api/call-upload", copy.getHost());
        assertEquals("secret", copy.getApiKey());
        assertEquals(7, copy.getSystemID());
        assertTrue(copy.isHeartbeatEnabled());
        assertEquals(45, copy.getHeartbeatIntervalSeconds());

        configuration.setHeartbeatUrl("https://status.example/scanner/online");
        assertEquals("https://status.example/scanner/online", configuration.resolveHeartbeatUrl());
        configuration.setHeartbeatEnabled(false);
        assertFalse(configuration.isHeartbeatEnabled());
    }
}
