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

/**
 * Tests to verify that tracing headers from the original request
 * are properly propagated to responses in both streaming and non-streaming modes.
 */
@ExtendWith(VertxExtension.class)
public class TracingHeadersTest {

    private Vertx vertx;
    private HttpServer mockProviderServer;
    private HttpServer proxyServer;
    private int mockProviderPort;
    private int proxyPort;
    private ProxyClient proxyClient;

    @BeforeEach
    public void setUp(Vertx vertx, VertxTestContext testContext) {
        this.vertx = vertx;
        this.proxyClient = new ProxyClient(vertx);
        this.mockProviderPort = 9997;
        this.proxyPort = 9996;

        setupMockProvider(testContext);
    }

    private void setupMockProvider(VertxTestContext testContext) {
        Router providerRouter = Router.router(vertx);
        providerRouter.route().handler(BodyHandler.create());

        // Mock non-streaming endpoint
        providerRouter.post("/v1/chat/completions").handler(ctx -> {
            JsonObject response = new JsonObject()
                    .put("id", "test-123")
                    .put("model", "test-model")
                    .put("choices", new io.vertx.core.json.JsonArray()
                            .add(new JsonObject()
                                    .put("message", new JsonObject()
                                            .put("content", "Test response"))));

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

            vertx.setTimer(10, id -> {
                ctx.response().write("data: {\"delta\":\"Hello\"}\n\n");
                vertx.setTimer(10, id2 -> {
                    ctx.response().write("data: [DONE]\n\n");
                    ctx.response().end();
                });
            });
        });

        vertx.createHttpServer()
                .requestHandler(providerRouter)
                .listen(mockProviderPort)
                .onComplete(testContext.succeeding(server -> {
                    mockProviderServer = server;
                    setupProxyServer(testContext);
                }));
    }

    private void setupProxyServer(VertxTestContext testContext) {
        Router proxyRouter = Router.router(vertx);
        proxyRouter.route().handler(BodyHandler.create());

        // Non-streaming endpoint
        proxyRouter.post("/proxy/chat").handler(ctx -> {
            JsonObject requestBody = ctx.body().asJsonObject();
            RouteConfig route = createTestRoute(false);

            proxyClient.forwardRequest(ctx, requestBody, route, false)
                    .onFailure(err -> {
                        if (!ctx.response().ended()) {
                            ctx.response().setStatusCode(500).end("{\"error\":\"" + err.getMessage() + "\"}");
                        }
                    });
        });

        // Streaming endpoint
        proxyRouter.post("/proxy/stream").handler(ctx -> {
            JsonObject requestBody = ctx.body().asJsonObject();
            RouteConfig route = createTestRoute(true);

            proxyClient.forwardRequest(ctx, requestBody, route, true)
                    .onFailure(err -> {
                        if (!ctx.response().ended()) {
                            ctx.response().setStatusCode(500).end("{\"error\":\"" + err.getMessage() + "\"}");
                        }
                    });
        });

        vertx.createHttpServer()
                .requestHandler(proxyRouter)
                .listen(proxyPort)
                .onComplete(testContext.succeeding(server -> {
                    proxyServer = server;
                    testContext.completeNow();
                }));
    }

    @AfterEach
    public void tearDown(VertxTestContext testContext) {
        if (mockProviderServer != null) {
            mockProviderServer.close().onComplete(ar1 -> {
                if (proxyServer != null) {
                    proxyServer.close().onComplete(ar2 -> {
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
    public void testRequestIdPropagationNonStreaming(VertxTestContext testContext) throws Exception {
        String requestId = "test-request-123";
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/chat")
                .putHeader("X-Request-ID", requestId)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(200, response.statusCode());
                    assertEquals(requestId, response.getHeader("X-Request-ID"),
                            "X-Request-ID should be propagated to response");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestIdPropagationStreaming(VertxTestContext testContext) throws Exception {
        String requestId = "test-stream-456";
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/stream")
                .putHeader("X-Request-ID", requestId)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test").put("stream", true))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(200, response.statusCode());
                    assertEquals(requestId, response.getHeader("X-Request-ID"),
                            "X-Request-ID should be propagated to streaming response");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCorrelationIdPropagation(VertxTestContext testContext) throws Exception {
        String correlationId = "correlation-789";
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/chat")
                .putHeader("X-Correlation-ID", correlationId)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(correlationId, response.getHeader("X-Correlation-ID"),
                            "X-Correlation-ID should be propagated");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testW3CTraceparentPropagation(VertxTestContext testContext) throws Exception {
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/chat")
                .putHeader("traceparent", traceparent)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(traceparent, response.getHeader("traceparent"),
                            "W3C traceparent should be propagated");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testZipkinB3HeadersPropagation(VertxTestContext testContext) throws Exception {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String spanId = "b7ad6b7169203331";
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/chat")
                .putHeader("X-B3-TraceId", traceId)
                .putHeader("X-B3-SpanId", spanId)
                .putHeader("X-B3-Sampled", "1")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(traceId, response.getHeader("X-B3-TraceId"),
                            "Zipkin B3 TraceId should be propagated");
                    assertEquals(spanId, response.getHeader("X-B3-SpanId"),
                            "Zipkin B3 SpanId should be propagated");
                    assertEquals("1", response.getHeader("X-B3-Sampled"),
                            "Zipkin B3 Sampled should be propagated");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMultipleTracingHeadersPropagation(VertxTestContext testContext) throws Exception {
        String requestId = "multi-test-123";
        String correlationId = "multi-corr-456";
        String traceId = "multi-trace-789";
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/chat")
                .putHeader("X-Request-ID", requestId)
                .putHeader("X-Correlation-ID", correlationId)
                .putHeader("X-Trace-ID", traceId)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(requestId, response.getHeader("X-Request-ID"));
                    assertEquals(correlationId, response.getHeader("X-Correlation-ID"));
                    assertEquals(traceId, response.getHeader("X-Trace-ID"));
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testNoTracingHeadersWhenAbsent(VertxTestContext testContext) throws Exception {
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/chat")
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(200, response.statusCode());
                    assertNull(response.getHeader("X-Request-ID"),
                            "X-Request-ID should not be present when not sent");
                    assertNull(response.getHeader("X-Correlation-ID"),
                            "X-Correlation-ID should not be present when not sent");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGoogleCloudTracePropagation(VertxTestContext testContext) throws Exception {
        String cloudTrace = "105445aa7843bc8bf206b120001000/1;o=1";
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/chat")
                .putHeader("X-Cloud-Trace-Context", cloudTrace)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(cloudTrace, response.getHeader("X-Cloud-Trace-Context"),
                            "Google Cloud trace context should be propagated");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAWSTraceIdPropagation(VertxTestContext testContext) throws Exception {
        String awsTraceId = "Root=1-67891233-abcdef012345678912345678";
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/chat")
                .putHeader("X-Amzn-Trace-Id", awsTraceId)
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    assertEquals(awsTraceId, response.getHeader("X-Amzn-Trace-Id"),
                            "AWS trace ID should be propagated");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCaseInsensitiveTracingHeaders(VertxTestContext testContext) throws Exception {
        String requestId = "case-test-123";
        WebClient client = WebClient.create(vertx);

        client.post(proxyPort, "localhost", "/proxy/chat")
                .putHeader("x-request-id", requestId) // lowercase
                .putHeader("Content-Type", "application/json")
                .sendJsonObject(new JsonObject().put("model", "test"))
                .onComplete(testContext.succeeding(response -> {
                    // Vert.x handles header case-insensitivity
                    String returnedHeader = response.getHeader("X-Request-ID");
                    if (returnedHeader == null) {
                        returnedHeader = response.getHeader("x-request-id");
                    }
                    assertEquals(requestId, returnedHeader,
                            "Tracing headers should be case-insensitive");
                    testContext.completeNow();
                }));

        assertTrue(testContext.awaitCompletion(5, TimeUnit.SECONDS));
    }

    private RouteConfig createTestRoute(boolean forStreaming) {
        RouteConfig route = new RouteConfig();
        route.setIncomingModel("test-model");

        ProviderConfig provider = new ProviderConfig();
        provider.setType(ProviderConfig.Type.OPENAI);
        provider.setBaseUrl("http://localhost:" + mockProviderPort);
        provider.setTargetModel("provider-model");
        route.setProvider(provider);

        HeaderConfig headers = new HeaderConfig();
        route.setHeaders(headers);

        TransformationConfig transformations = new TransformationConfig();
        route.setTransformations(transformations);

        ClientConfig client = new ClientConfig();
        client.setTimeout(30000);
        client.setRetries(1);
        route.setClient(client);

        return route;
    }
}
