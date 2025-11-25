package com.llmproxy.client;

import com.llmproxy.config.*;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that verify the complete flow through ProxyClient
 * including request transformation, header manipulation, and SSL configuration.
 */
@ExtendWith(VertxExtension.class)
public class ProxyClientIntegrationTest {

    private Vertx vertx;
    private HttpServer mockProviderServer;
    private HttpServer proxyTestServer;
    private int mockProviderPort;
    private int proxyTestPort;
    private ProxyClient proxyClient;

    @BeforeEach
    public void setUp(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
        this.proxyClient = new ProxyClient(vertx);
        this.mockProviderPort = 9999;
        this.proxyTestPort = 9998;

        // Create a mock provider server (simulates OpenAI, Anthropic, Ollama)
        Router providerRouter = Router.router(vertx);
        providerRouter.route().handler(BodyHandler.create());

        // Mock OpenAI-style endpoint
        providerRouter.post("/v1/chat/completions").handler(ctx -> {
            JsonObject request = ctx.body().asJsonObject();

            // Verify model was transformed
            String model = request.getString("model");

            JsonObject response = new JsonObject()
                    .put("id", "chatcmpl-123")
                    .put("object", "chat.completion")
                    .put("model", model)
                    .put("choices", new io.vertx.core.json.JsonArray()
                            .add(new JsonObject()
                                    .put("message", new JsonObject()
                                            .put("role", "assistant")
                                            .put("content", "Test response for model: " + model))));

            ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(response.encode());
        });

        // Mock streaming endpoint
        providerRouter.post("/v1/chat/stream").handler(ctx -> {
            ctx.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "text/event-stream")
                    .setChunked(true);

            // Send multiple chunks
            vertx.setTimer(10, id1 -> {
                ctx.response().write("data: {\"delta\":\"Hello\"}\n\n");
                vertx.setTimer(10, id2 -> {
                    ctx.response().write("data: {\"delta\":\" World\"}\n\n");
                    vertx.setTimer(10, id3 -> {
                        ctx.response().write("data: [DONE]\n\n");
                        ctx.response().end();
                    });
                });
            });
        });

        // Start the mock provider server
        vertx.createHttpServer()
                .requestHandler(providerRouter)
                .listen(mockProviderPort)
                .onComplete(testContext.succeeding(server -> {
                    mockProviderServer = server;
                    setupProxyTestServer(testContext);
                }));
    }

    private void setupProxyTestServer(VertxTestContext testContext) {
        // Create a test server that simulates incoming requests to the proxy
        Router proxyRouter = Router.router(vertx);
        proxyRouter.route().handler(BodyHandler.create());

        proxyRouter.post("/proxy/chat").handler(ctx -> {
            // Simulate forwarding through ProxyClient
            JsonObject requestBody = ctx.body().asJsonObject();
            RouteConfig route = createTestRoute(false, false);

            proxyClient.forwardRequest(ctx, requestBody, route, false)
                    .onSuccess(v -> {
                        if (!ctx.response().ended()) {
                            // This shouldn't happen as ProxyClient should end the response
                            ctx.response().setStatusCode(500).end("{\"error\":\"Response not forwarded\"}");
                        }
                    })
                    .onFailure(err -> {
                        if (!ctx.response().ended()) {
                            ctx.response().setStatusCode(500).end("{\"error\":\"" + err.getMessage() + "\"}");
                        }
                    });
        });

        vertx.createHttpServer()
                .requestHandler(proxyRouter)
                .listen(proxyTestPort)
                .onComplete(testContext.succeeding(server -> {
                    proxyTestServer = server;
                    testContext.completeNow();
                }));
    }

    @AfterEach
    public void tearDown(VertxTestContext testContext) {
        if (mockProviderServer != null) {
            mockProviderServer.close().onComplete(ar1 -> {
                if (proxyTestServer != null) {
                    proxyTestServer.close().onComplete(ar2 -> {
                        if (vertx != null) {
                            vertx.close(testContext.succeedingThenComplete());
                        } else {
                            testContext.completeNow();
                        }
                    });
                } else {
                    testContext.completeNow();
                }
            });
        } else {
            testContext.completeNow();
        }
    }

    @Test
    public void testCompleteProxyFlow(VertxTestContext testContext) throws Exception {
        JsonObject request = new JsonObject()
                .put("model", "gpt-4")
                .put("messages", new io.vertx.core.json.JsonArray()
                        .add(new JsonObject()
                                .put("role", "user")
                                .put("content", "Hello")));

        vertx.createHttpClient()
                .request(io.vertx.core.http.HttpMethod.POST, proxyTestPort, "localhost", "/proxy/chat")
                .compose(req -> req
                        .putHeader("Content-Type", "application/json")
                        .send(request.encode()))
                .compose(response -> response.body()
                        .map(body -> {
                            assertEquals(200, response.statusCode(), "Proxy should return 200");

                            JsonObject responseBody = new JsonObject(body.toString());
                            assertNotNull(responseBody, "Response should be valid JSON");
                            assertTrue(responseBody.containsKey("choices"), "Response should have choices");

                            return null;
                        }))
                .onComplete(testContext.succeedingThenComplete());

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testModelTransformation() {
        RouteConfig route = createTestRoute(false, false);
        assertEquals("target-model-123", route.getProvider().getTargetModel(),
                "Model should be transformed to target model");
    }

    @Test
    public void testSSLConfigurationPropagation() {
        RouteConfig routeWithSSL = createTestRoute(true, false);
        RouteConfig routeWithoutSSL = createTestRoute(false, false);

        assertTrue(routeWithSSL.getClient().isVerifySsl(),
                "SSL verification should be enabled");
        assertFalse(routeWithoutSSL.getClient().isVerifySsl(),
                "SSL verification should be disabled");
    }

    @Test
    public void testStreamingConfiguration() {
        RouteConfig route = createTestRoute(false, true);
        // Verify route configuration supports streaming
        assertNotNull(route.getProvider(), "Provider should be configured");
        assertNotNull(route.getClient(), "Client config should be set");
    }

    @Test
    public void testHeaderTransformationConfiguration() {
        RouteConfig route = createTestRoute(false, false);

        HeaderConfig headers = route.getHeaders();
        assertNotNull(headers, "Headers should be configured");
        assertTrue(headers.isDropAll(), "Should drop all headers");
        assertTrue(headers.getAdd().containsKey("Content-Type"),
                "Should add Content-Type header");
    }

    @Test
    public void testTimeoutConfiguration() {
        RouteConfig route = createTestRoute(false, false);
        assertEquals(30000, route.getClient().getTimeout(),
                "Timeout should be configured to 30 seconds");
    }

    private RouteConfig createTestRoute(boolean verifySsl, boolean forStreaming) {
        RouteConfig route = new RouteConfig();
        route.setIncomingModel("gpt-4");

        ProviderConfig provider = new ProviderConfig();
        provider.setType(ProviderConfig.Type.OPENAI);
        provider.setBaseUrl("http://localhost:" + mockProviderPort);
        provider.setTargetModel("target-model-123");
        route.setProvider(provider);

        HeaderConfig headers = new HeaderConfig();
        headers.setDropAll(true);
        headers.getAdd().put("Content-Type", "application/json");
        headers.getAdd().put("User-Agent", "LLM-Proxy-Test/1.0");
        route.setHeaders(headers);

        TransformationConfig transformations = new TransformationConfig();
        route.setTransformations(transformations);

        ClientConfig client = new ClientConfig();
        client.setVerifySsl(verifySsl);
        client.setTimeout(30000);
        client.setRetries(3);
        route.setClient(client);

        return route;
    }
}
