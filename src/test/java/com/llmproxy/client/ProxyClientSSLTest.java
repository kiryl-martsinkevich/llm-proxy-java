package com.llmproxy.client;

import com.llmproxy.config.*;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
public class ProxyClientSSLTest {

    private Vertx vertx;
    private ProxyClient proxyClient;

    @BeforeEach
    public void setUp(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
        this.proxyClient = new ProxyClient(vertx);
        testContext.completeNow();
    }

    @AfterEach
    public void tearDown(VertxTestContext testContext) {
        if (vertx != null) {
            vertx.close(testContext.succeedingThenComplete());
        } else {
            testContext.completeNow();
        }
    }

    @Test
    public void testSSLVerificationEnabled() throws Exception {
        // Get the web client cache via reflection
        Field cacheField = ProxyClient.class.getDeclaredField("webClientCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Boolean, WebClient> cache = (Map<Boolean, WebClient>) cacheField.get(proxyClient);

        // Access via reflection to trigger client creation
        java.lang.reflect.Method getWebClientMethod = ProxyClient.class.getDeclaredMethod("getWebClient", boolean.class);
        getWebClientMethod.setAccessible(true);

        // Get client with SSL verification enabled
        WebClient clientWithSSL = (WebClient) getWebClientMethod.invoke(proxyClient, true);

        assertNotNull(clientWithSSL, "WebClient with SSL verification should be created");
        assertTrue(cache.containsKey(true), "Cache should contain entry for SSL verification=true");
        assertEquals(1, cache.size(), "Cache should have exactly one entry");
    }

    @Test
    public void testSSLVerificationDisabled() throws Exception {
        // Get the web client cache via reflection
        Field cacheField = ProxyClient.class.getDeclaredField("webClientCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Boolean, WebClient> cache = (Map<Boolean, WebClient>) cacheField.get(proxyClient);

        // Access via reflection to trigger client creation
        java.lang.reflect.Method getWebClientMethod = ProxyClient.class.getDeclaredMethod("getWebClient", boolean.class);
        getWebClientMethod.setAccessible(true);

        // Get client with SSL verification disabled
        WebClient clientWithoutSSL = (WebClient) getWebClientMethod.invoke(proxyClient, false);

        assertNotNull(clientWithoutSSL, "WebClient without SSL verification should be created");
        assertTrue(cache.containsKey(false), "Cache should contain entry for SSL verification=false");
        assertEquals(1, cache.size(), "Cache should have exactly one entry");
    }

    @Test
    public void testWebClientCaching() throws Exception {
        // Access via reflection to trigger client creation
        java.lang.reflect.Method getWebClientMethod = ProxyClient.class.getDeclaredMethod("getWebClient", boolean.class);
        getWebClientMethod.setAccessible(true);

        // Get client twice with same SSL setting
        WebClient client1 = (WebClient) getWebClientMethod.invoke(proxyClient, true);
        WebClient client2 = (WebClient) getWebClientMethod.invoke(proxyClient, true);

        assertSame(client1, client2, "WebClient instances should be cached and reused");
    }

    @Test
    public void testDifferentSSLSettingsCreateDifferentClients() throws Exception {
        // Access via reflection to trigger client creation
        java.lang.reflect.Method getWebClientMethod = ProxyClient.class.getDeclaredMethod("getWebClient", boolean.class);
        getWebClientMethod.setAccessible(true);

        // Get clients with different SSL settings
        WebClient clientWithSSL = (WebClient) getWebClientMethod.invoke(proxyClient, true);
        WebClient clientWithoutSSL = (WebClient) getWebClientMethod.invoke(proxyClient, false);

        assertNotSame(clientWithSSL, clientWithoutSSL,
            "Different SSL settings should create different WebClient instances");

        // Verify cache has both entries
        Field cacheField = ProxyClient.class.getDeclaredField("webClientCache");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Boolean, WebClient> cache = (Map<Boolean, WebClient>) cacheField.get(proxyClient);

        assertEquals(2, cache.size(), "Cache should have two entries for different SSL settings");
        assertTrue(cache.containsKey(true), "Cache should have entry for SSL verification=true");
        assertTrue(cache.containsKey(false), "Cache should have entry for SSL verification=false");
    }

    @Test
    public void testRouteConfigurationRespected() {
        // Create route config with SSL verification disabled
        RouteConfig route = createTestRoute(false);

        assertFalse(route.getClient().isVerifySsl(),
            "Route should have SSL verification disabled");
    }

    @Test
    public void testDefaultSSLVerificationIsEnabled() {
        ClientConfig clientConfig = new ClientConfig();
        assertTrue(clientConfig.isVerifySsl(),
            "Default SSL verification should be enabled");
    }

    private RouteConfig createTestRoute(boolean verifySsl) {
        RouteConfig route = new RouteConfig();
        route.setIncomingModel("test-model");

        ProviderConfig provider = new ProviderConfig();
        provider.setType(ProviderConfig.Type.OPENAI);
        provider.setBaseUrl("https://api.example.com");
        provider.setTargetModel("target-model");
        route.setProvider(provider);

        HeaderConfig headers = new HeaderConfig();
        route.setHeaders(headers);

        TransformationConfig transformations = new TransformationConfig();
        route.setTransformations(transformations);

        ClientConfig client = new ClientConfig();
        client.setVerifySsl(verifySsl);
        client.setTimeout(30000);
        route.setClient(client);

        return route;
    }
}
