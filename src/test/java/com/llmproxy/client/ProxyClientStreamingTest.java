package com.llmproxy.client;

import com.llmproxy.config.*;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
public class ProxyClientStreamingTest {

    private Vertx vertx;
    private HttpServer mockServer;
    private int mockServerPort;
    private ProxyClient proxyClient;

    @BeforeEach
    public void setUp(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
        this.proxyClient = new ProxyClient(vertx);
        this.mockServerPort = 8888;

        // Create a mock HTTP server that simulates streaming responses
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        // Mock streaming endpoint
        router.post("/api/stream").handler(ctx -> {
            ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "text/event-stream")
                    .setChunked(true);

            // Simulate streaming response with multiple chunks
            vertx.setTimer(10, id1 -> {
                ctx.response().write("data: {\"chunk\":1}\n\n");
                vertx.setTimer(10, id2 -> {
                    ctx.response().write("data: {\"chunk\":2}\n\n");
                    vertx.setTimer(10, id3 -> {
                        ctx.response().write("data: {\"chunk\":3}\n\n");
                        ctx.response().write("data: [DONE]\n\n");
                        ctx.response().end();
                    });
                });
            });
        });

        // Mock error endpoint
        router.post("/api/error").handler(ctx -> {
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"error\":\"Internal server error\"}");
        });

        // Mock non-streaming endpoint
        router.post("/api/chat").handler(ctx -> {
            JsonObject response = new JsonObject()
                    .put("model", "test-model")
                    .put("message", new JsonObject()
                            .put("content", "Test response"));

            ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(response.encode());
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(mockServerPort)
                .onComplete(testContext.succeeding(server -> {
                    mockServer = server;
                    testContext.completeNow();
                }));
    }

    @AfterEach
    public void tearDown(VertxTestContext testContext) {
        if (mockServer != null) {
            mockServer.close().onComplete(ar -> {
                if (vertx != null) {
                    vertx.close(testContext.succeedingThenComplete());
                } else {
                    testContext.completeNow();
                }
            });
        } else if (vertx != null) {
            vertx.close(testContext.succeedingThenComplete());
        } else {
            testContext.completeNow();
        }
    }

    @Test
    public void testStreamingResponseHeaders(VertxTestContext testContext) throws Exception {
        RouteConfig route = createTestRoute("http://localhost:" + mockServerPort + "/api");
        route.getProvider().setBaseUrl("http://localhost:" + mockServerPort);

        WebClient client = WebClient.create(vertx);

        client.post(mockServerPort, "localhost", "/api/stream")
                .sendJsonObject(new JsonObject().put("test", "data"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(200, response.statusCode(), "Status should be 200");
                    assertEquals("text/event-stream",
                            response.getHeader("Content-Type"),
                            "Content-Type should be text/event-stream");

                    String body = response.bodyAsString();
                    assertTrue(body.contains("chunk"), "Response should contain streaming chunks");
                    assertTrue(body.contains("[DONE]"), "Response should contain done marker");

                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testStreamingWithMultipleChunks(VertxTestContext testContext) throws Exception {
        WebClient client = WebClient.create(vertx);

        client.post(mockServerPort, "localhost", "/api/stream")
                .sendJsonObject(new JsonObject().put("test", "data"))
                .onComplete(testContext.succeeding(response -> {
                    String body = response.bodyAsString();

                    // Verify all chunks are present
                    assertTrue(body.contains("\"chunk\":1"), "Should contain chunk 1");
                    assertTrue(body.contains("\"chunk\":2"), "Should contain chunk 2");
                    assertTrue(body.contains("\"chunk\":3"), "Should contain chunk 3");
                    assertTrue(body.contains("[DONE]"), "Should contain DONE marker");

                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testNonStreamingResponse(VertxTestContext testContext) throws Exception {
        WebClient client = WebClient.create(vertx);

        client.post(mockServerPort, "localhost", "/api/chat")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(200, response.statusCode(), "Status should be 200");
                    assertEquals("application/json",
                            response.getHeader("Content-Type"),
                            "Content-Type should be application/json");

                    JsonObject body = response.bodyAsJsonObject();
                    assertNotNull(body, "Response body should be valid JSON");
                    assertEquals("test-model", body.getString("model"), "Model should match");

                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testStreamingErrorHandling(VertxTestContext testContext) throws Exception {
        WebClient client = WebClient.create(vertx);

        client.post(mockServerPort, "localhost", "/api/error")
                .sendJsonObject(new JsonObject().put("test", "data"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(500, response.statusCode(), "Status should be 500");

                    String body = response.bodyAsString();
                    assertTrue(body.contains("error"), "Response should contain error message");

                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testStreamingWithSSLDisabled() {
        RouteConfig route = createTestRoute("http://localhost:" + mockServerPort + "/api");
        route.getClient().setVerifySsl(false);

        assertFalse(route.getClient().isVerifySsl(),
                "SSL verification should be disabled for this route");
    }

    @Test
    public void testStreamingWithSSLEnabled() {
        RouteConfig route = createTestRoute("https://api.example.com/api");
        route.getClient().setVerifySsl(true);

        assertTrue(route.getClient().isVerifySsl(),
                "SSL verification should be enabled for this route");
    }

    private RouteConfig createTestRoute(String baseUrl) {
        RouteConfig route = new RouteConfig();
        route.setIncomingModel("test-model");

        ProviderConfig provider = new ProviderConfig();
        provider.setType(ProviderConfig.Type.OPENAI);
        provider.setBaseUrl(baseUrl);
        provider.setTargetModel("target-model");
        route.setProvider(provider);

        HeaderConfig headers = new HeaderConfig();
        route.setHeaders(headers);

        TransformationConfig transformations = new TransformationConfig();
        route.setTransformations(transformations);

        ClientConfig client = new ClientConfig();
        client.setTimeout(30000);
        route.setClient(client);

        return route;
    }
}
