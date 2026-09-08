package bank.cardissuing.thirdparty;

import bank.cardissuing.thirdparty.merchants.MerchantDirectoryClient;
import bank.cardissuing.thirdparty.merchants.MerchantDirectorySettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MerchantDirectoryClientTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> keySeen = new AtomicReference<>();
    private volatile boolean down;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/merchants", ex -> {
            hits.incrementAndGet();
            keySeen.set(ex.getRequestHeaders().getFirst("X-API-Key"));
            if (down) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            byte[] body = ("{\"statusCode\":200,\"success\":true,\"data\":[" +
                    "{\"id\":7,\"codigoComercio\":\"MX-0007\",\"nombreComercio\":\"Tienda Centro\",\"rfc\":\"TCE010101AAA\",\"estatus\":\"Activo\",\"bloqueado\":false,\"tipoComercio\":\"INDEPENDIENTE\"}," +
                    "{\"id\":8,\"codigoComercio\":\"MX-0008\",\"nombreComercio\":\"Abarrotes Luna\",\"rfc\":\"ALU020202BBB\",\"estatus\":\"Activo\",\"bloqueado\":true}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private MerchantDirectoryClient client(String key) {
        MerchantDirectorySettings s = new MerchantDirectorySettings();
        s.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
        s.setApiKey(key);
        s.setCacheSeconds(60);
        return new MerchantDirectoryClient(s, new ObjectMapper());
    }

    @Test
    void listsMerchantsWithTheIntegrationKey_sortedAndFiltered_andCaches() {
        MerchantDirectoryClient c = client("agg_live_test");
        MerchantDirectoryClient.Listing l = c.list(null);
        assertTrue(l.available());
        assertEquals("agg_live_test", keySeen.get(), "the portal receives the credential in X-API-Key");
        assertEquals(2, l.merchants().size());
        assertEquals("Abarrotes Luna", l.merchants().get(0).name(), "sorted by name");
        assertTrue(l.merchants().get(0).blocked());
        assertEquals("MX-0007", c.list("centro").merchants().get(0).code(), "search by name");
        assertEquals(1, c.list("ALU02").merchants().size(), "search by RFC");
        assertEquals(1, hits.get(), "the second and third listings came from the cache");
    }

    @Test
    void withoutCredential_theSelectorFallsBackToManualEntry() {
        MerchantDirectoryClient c = client("");
        MerchantDirectoryClient.Listing l = c.list(null);
        assertFalse(l.available());
        assertTrue(l.merchants().isEmpty());
        assertTrue(l.detail().contains("sin configurar"));
        assertEquals(0, hits.get());
    }

    @Test
    void whenThePortalStopsAnswering_theLastGoodListingIsServedAndFlagged() {
        MerchantDirectoryClient c = client("k");
        MerchantDirectorySettings s = new MerchantDirectorySettings();
        assertTrue(c.list(null).available());
        down = true;
        // force a refresh by using a client whose cache is already stale
        MerchantDirectorySettings stale = new MerchantDirectorySettings();
        stale.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"); stale.setApiKey("k"); stale.setCacheSeconds(0);
        MerchantDirectoryClient c2 = new MerchantDirectoryClient(stale, new ObjectMapper());
        down = false;
        assertTrue(c2.all().available());
        down = true;
        MerchantDirectoryClient.Listing l = c2.all();
        assertFalse(l.available());
        assertTrue(l.fromCache());
        assertEquals(2, l.merchants().size(), "the last good listing is still offered");
        assertTrue(l.detail().contains("no responde"));
        assertFalse(c2.ping());
        assertNotNull(s);
    }
}
