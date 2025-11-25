package com.llmproxy.client;

import com.llmproxy.config.ProviderConfig;
import com.llmproxy.config.RouteConfig;
import com.llmproxy.transformer.ContentTransformer;
import com.llmproxy.transformer.HeaderTransformer;
import com.llmproxy.transformer.JsonPathTransformer;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.core.streams.WriteStream;
import io.vertx.core.streams.Pump;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.codec.BodyCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class ProxyClient {
    private static final Logger logger = LoggerFactory.getLogger(ProxyClient.class);

    private final Vertx vertx;
    private final Map<Boolean, WebClient> webClientCache;
    private final HeaderTransformer headerTransformer;
    private final ContentTransformer contentTransformer;
    private final JsonPathTransformer jsonPathTransformer;

    public ProxyClient(Vertx vertx) {
        this.vertx = vertx;
        this.webClientCache = new HashMap<>();
        this.headerTransformer = new HeaderTransformer();
        this.contentTransformer = new ContentTransformer();
        this.jsonPathTransformer = new JsonPathTransformer();
    }

    private WebClient getWebClient(boolean verifySsl) {
        return webClientCache.computeIfAbsent(verifySsl, verify -> {
            WebClientOptions options = new WebClientOptions()
                    .setKeepAlive(true)
                    .setMaxPoolSize(100)
                    .setConnectTimeout(10000)
                    .setIdleTimeout(120)
                    .setVerifyHost(verify)
                    .setTrustAll(!verify);

            logger.debug("Created WebClient with SSL verification: {}", verify);
            return WebClient.create(vertx, options);
        });
    }

    public Future<Void> forwardRequest(RoutingContext ctx, JsonObject requestBody, RouteConfig route, boolean stream) {
        try {
            // Transform request
            JsonObject transformedBody = transformRequest(requestBody, route);

            // Build target URL
            String targetUrl = buildTargetUrl(route.getProvider());
            URI uri = URI.create(targetUrl);

            logger.info("Forwarding request to: {}", targetUrl);

            // Get WebClient with appropriate SSL configuration
            WebClient client = getWebClient(route.getClient().isVerifySsl());

            // Create HTTP request
            HttpRequest<Buffer> request = client
                    .post(uri.getPort() > 0 ? uri.getPort() : (uri.getScheme().equals("https") ? 443 : 80),
                            uri.getHost(),
                            uri.getPath())
                    .ssl(uri.getScheme().equals("https"))
                    .timeout(route.getClient().getTimeout());

            // Transform and set headers
            MultiMap headers = headerTransformer.transform(ctx.request().headers(), route.getHeaders());

            // Add provider-specific headers
            if (route.getProvider().getApiKey() != null && !route.getProvider().getApiKey().isBlank()) {
                headers.set("Authorization", "Bearer " + route.getProvider().getApiKey());
            }
            headers.set("Content-Type", "application/json");

            // Apply headers to request
            headers.forEach(entry -> request.putHeader(entry.getKey(), entry.getValue()));

            // Send request
            if (stream) {
                return handleStreamingRequestRealTime(ctx, request, transformedBody);
            } else {
                return handleNonStreamingRequest(ctx, request, transformedBody, route);
            }

        } catch (Exception e) {
            logger.error("Error forwarding request", e);
            return Future.failedFuture(e);
        }
    }

    private JsonObject transformRequest(JsonObject body, RouteConfig route) {
        JsonObject result = body.copy();

        // Replace model name
        if (route.getProvider().getTargetModel() != null) {
            result = contentTransformer.replaceModel(result, route.getProvider().getTargetModel());
        }

        // For Ollama, ensure stream parameter matches the request
        // If stream is not set or false, explicitly set it to false
        if (route.getProvider().getType() == ProviderConfig.Type.OLLAMA) {
            if (!result.containsKey("stream")) {
                result.put("stream", false);
            }
        }

        // Apply JSONPath transformations
        if (!route.getTransformations().getRequest().getJsonPathOps().isEmpty()) {
            result = jsonPathTransformer.transform(result, route.getTransformations().getRequest());
        }

        // Apply regex transformations to the JSON string
        if (!route.getTransformations().getRequest().getRegexReplacements().isEmpty()) {
            String jsonString = result.encode();
            jsonString = contentTransformer.transform(jsonString, route.getTransformations().getRequest());
            result = new JsonObject(jsonString);
        }

        return result;
    }

    private String buildTargetUrl(ProviderConfig provider) {
        String baseUrl = provider.getBaseUrl();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }

        return switch (provider.getType()) {
            case OPENAI -> baseUrl + "v1/chat/completions";
            case ANTHROPIC -> baseUrl + "v1/messages";
            case OLLAMA -> baseUrl + "api/chat";
        };
    }

    private Future<Void> handleNonStreamingRequest(RoutingContext ctx, HttpRequest<Buffer> request,
                                                     JsonObject body, RouteConfig route) {
        return request.sendJsonObject(body)
                .compose(response -> {
                    // Forward response
                    ctx.response()
                            .setStatusCode(response.statusCode())
                            .putHeader("Content-Type", "application/json");

                    // Copy response headers
                    response.headers().forEach(entry -> {
                        if (!entry.getKey().equalsIgnoreCase("content-length") &&
                            !entry.getKey().equalsIgnoreCase("transfer-encoding")) {
                            ctx.response().putHeader(entry.getKey(), entry.getValue());
                        }
                    });

                    // Handle response based on provider type
                    JsonObject responseBody;

                    if (route.getProvider().getType() == ProviderConfig.Type.OLLAMA) {
                        // Ollama returns NDJSON (newline-delimited JSON), parse the last complete message
                        responseBody = parseOllamaResponse(response.bodyAsString());
                    } else {
                        // OpenAI and Anthropic return standard JSON
                        try {
                            responseBody = response.bodyAsJsonObject();
                        } catch (Exception e) {
                            logger.error("Failed to parse response as JSON", e);
                            responseBody = null;
                        }
                    }

                    // Transform response if needed
                    if (responseBody != null && !route.getTransformations().getResponse().getJsonPathOps().isEmpty()) {
                        responseBody = jsonPathTransformer.transform(responseBody, route.getTransformations().getResponse());
                    }

                    ctx.response().end(responseBody != null ? responseBody.encode() : response.bodyAsString());
                    return Future.succeededFuture();
                })
                .recover(err -> {
                    logger.error("Request failed", err);
                    if (!ctx.response().ended()) {
                        ctx.response()
                                .setStatusCode(502)
                                .putHeader("Content-Type", "application/json")
                                .end(new JsonObject()
                                        .put("error", new JsonObject()
                                                .put("message", "Failed to forward request: " + err.getMessage())
                                                .put("type", "proxy_error"))
                                        .encode());
                    }
                    return Future.succeededFuture();
                })
                .mapEmpty();
    }

    private JsonObject parseOllamaResponse(String ndjson) {
        // Parse NDJSON and return the last message (which has "done": true)
        String[] lines = ndjson.split("\n");
        JsonObject lastMessage = null;

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JsonObject obj = new JsonObject(line);
                lastMessage = obj;

                // If this is the done message, we can stop
                if (obj.getBoolean("done", false)) {
                    break;
                }
            } catch (Exception e) {
                logger.warn("Failed to parse NDJSON line: {}", line);
            }
        }

        return lastMessage;
    }

    private Future<Void> handleStreamingRequest(RoutingContext ctx, HttpRequest<Buffer> request, JsonObject body) {
        // Set up SSE response headers before streaming
        ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .setChunked(true);

        return request
                .sendJsonObject(body)
                .compose(response -> {
                    // Check if the response was successful
                    if (response.statusCode() >= 400) {
                        logger.error("Streaming request failed with status: {}", response.statusCode());
                        if (!ctx.response().ended()) {
                            ctx.response()
                                    .setStatusCode(response.statusCode())
                                    .end("data: {\"error\":\"Stream failed\"}\n\n");
                        }
                        return Future.succeededFuture();
                    }

                    // For Vert.x WebClient, we need to use sendStream for real streaming
                    // However, the response is already received, so we forward it
                    // In a real implementation, we'd use .as(BodyCodec.pipe(pipe)) for true streaming
                    String responseBody = response.bodyAsString();

                    // Forward the entire stream content
                    ctx.response().write(responseBody);
                    ctx.response().end();

                    logger.debug("Streaming response forwarded successfully");
                    return Future.succeededFuture();
                })
                .recover(err -> {
                    logger.error("Streaming request failed", err);
                    if (!ctx.response().ended()) {
                        if (!ctx.response().headWritten()) {
                            ctx.response().setStatusCode(502);
                        }
                        ctx.response().end("data: {\"error\":\"Stream failed\"}\n\n");
                    }
                    return Future.succeededFuture();
                })
                .mapEmpty();
    }

    /**
     * Implements true real-time streaming by forwarding chunks as they arrive from the provider.
     * Uses Vert.x Pipe to stream data with proper backpressure handling.
     */
    private Future<Void> handleStreamingRequestRealTime(RoutingContext ctx, HttpRequest<Buffer> request, JsonObject body) {
        // Set up SSE response headers
        ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "text/event-stream")
                .putHeader("Cache-Control", "no-cache")
                .putHeader("Connection", "keep-alive")
                .putHeader("X-Accel-Buffering", "no") // Disable nginx buffering
                .setChunked(true);

        // Use BodyCodec.pipe() to stream the response directly to the client
        // This forwards chunks as they arrive without buffering the entire response
        return request
                .as(BodyCodec.pipe(ctx.response()))
                .sendJsonObject(body)
                .compose(response -> {
                    if (response.statusCode() >= 400) {
                        logger.error("Streaming request failed with status: {}", response.statusCode());
                    } else {
                        logger.debug("Streaming response completed successfully");
                    }
                    return Future.succeededFuture();
                })
                .recover(err -> {
                    logger.error("Streaming request failed", err);
                    if (!ctx.response().ended()) {
                        ctx.response().end();
                    }
                    return Future.succeededFuture();
                })
                .mapEmpty();
    }
}
