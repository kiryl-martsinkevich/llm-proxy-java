package com.llmproxy.client;

import com.llmproxy.config.ProviderConfig;
import com.llmproxy.config.RouteConfig;
import com.llmproxy.transformer.ContentTransformer;
import com.llmproxy.transformer.FormatConverter;
import com.llmproxy.transformer.HeaderTransformer;
import com.llmproxy.transformer.JsonPathTransformer;
import com.llmproxy.util.RetryHandler;
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
import java.util.Set;
import java.util.List;

public class ProxyClient {
    private static final Logger logger = LoggerFactory.getLogger(ProxyClient.class);

    // Common tracing headers that should be preserved and returned in responses
    private static final Set<String> TRACING_HEADERS = Set.of(
            "x-request-id",
            "x-correlation-id",
            "x-trace-id",
            "traceparent",      // W3C Trace Context
            "tracestate",       // W3C Trace Context
            "x-b3-traceid",     // Zipkin B3
            "x-b3-spanid",      // Zipkin B3
            "x-b3-parentspanid",// Zipkin B3
            "x-b3-sampled",     // Zipkin B3
            "x-b3-flags",       // Zipkin B3
            "x-cloud-trace-context", // Google Cloud
            "x-amzn-trace-id"   // AWS
    );

    // Hop-by-hop headers that should never be forwarded by a proxy (RFC 2616 Section 13.5.1)
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "host",
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "content-length"  // Will be recalculated by the HTTP client
    );

    private final Vertx vertx;
    private final Map<Boolean, WebClient> webClientCache;
    private final HeaderTransformer headerTransformer;
    private final ContentTransformer contentTransformer;
    private final JsonPathTransformer jsonPathTransformer;
    private final FormatConverter formatConverter;
    private final RetryHandler retryHandler;

    public ProxyClient(Vertx vertx) {
        this.vertx = vertx;
        this.webClientCache = new HashMap<>();
        this.headerTransformer = new HeaderTransformer();
        this.contentTransformer = new ContentTransformer();
        this.jsonPathTransformer = new JsonPathTransformer();
        this.formatConverter = new FormatConverter();
        this.retryHandler = new RetryHandler(vertx);
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

    /**
     * Applies tracing headers from the incoming request to the outgoing response.
     * This ensures distributed tracing works correctly across the proxy.
     *
     * @param ctx The routing context containing the original request and response
     */
    private void applyTracingHeaders(RoutingContext ctx) {
        MultiMap incomingHeaders = ctx.request().headers();
        int headersApplied = 0;

        for (String tracingHeader : TRACING_HEADERS) {
            String value = incomingHeaders.get(tracingHeader);
            if (value != null && !value.isEmpty()) {
                ctx.response().putHeader(tracingHeader, value);
                headersApplied++;
                logger.debug("Applied tracing header: {} = {}", tracingHeader, value);
            }
        }

        if (headersApplied > 0) {
            logger.debug("Applied {} tracing headers to response", headersApplied);
        }
    }

    /**
     * Forward request with same source and target API format.
     */
    public Future<Void> forwardRequest(RoutingContext ctx, JsonObject requestBody, RouteConfig route, boolean stream) {
        return forwardRequest(ctx, requestBody, route, stream, null);
    }

    /**
     * Forward request with optional format conversion.
     * @param sourceApiType The API type of the incoming request (null means same as target)
     */
    public Future<Void> forwardRequest(RoutingContext ctx, JsonObject requestBody, RouteConfig route,
                                       boolean stream, ProviderConfig.Type sourceApiType) {
        try {
            String originalModel = requestBody.getString("model");
            ProviderConfig.Type targetApiType = route.getProvider().getType();

            // Convert request format if needed
            JsonObject convertedBody = requestBody;
            boolean needsResponseConversion = false;

            if (sourceApiType != null && sourceApiType != targetApiType) {
                if (sourceApiType == ProviderConfig.Type.ANTHROPIC && targetApiType == ProviderConfig.Type.OPENAI) {
                    logger.debug("Converting request from Anthropic to OpenAI format");
                    convertedBody = formatConverter.anthropicToOpenAIRequest(requestBody);
                    needsResponseConversion = true;
                }
                // Add other conversions as needed (OpenAI->Anthropic, etc.)
            }

            // Transform request (model replacement, JSONPath ops, regex)
            JsonObject transformedBody = transformRequest(convertedBody, route);

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

            // Apply headers to request, filtering out hop-by-hop headers
            // (Host header is set automatically by WebClient based on target URI)
            headers.forEach(entry -> {
                if (!HOP_BY_HOP_HEADERS.contains(entry.getKey().toLowerCase())) {
                    request.putHeader(entry.getKey(), entry.getValue());
                }
            });

            // Send request
            if (stream) {
                return handleStreamingRequestRealTime(ctx, request, transformedBody,
                        needsResponseConversion ? sourceApiType : null, originalModel);
            } else {
                return handleNonStreamingRequest(ctx, request, transformedBody, route,
                        needsResponseConversion ? sourceApiType : null, originalModel);
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
                                                     JsonObject body, RouteConfig route,
                                                     ProviderConfig.Type convertResponseTo, String originalModel) {
        // Wrap request in retry logic
        int maxRetries = route.getClient().getRetries();
        String context = String.format("Request to %s (model: %s)",
                route.getProvider().getBaseUrl(), route.getIncomingModel());

        return retryHandler.executeWithRetry(
                () -> request.sendJsonObject(body).compose(response -> {
                    // Check if response status is retryable
                    if (RetryHandler.isRetryableStatusCode(response.statusCode())) {
                        return Future.failedFuture(
                                new RuntimeException("Retryable HTTP status: " + response.statusCode()));
                    }
                    return Future.succeededFuture(response);
                }),
                maxRetries,
                context
        ).compose(response -> {
                    // Forward response
                    ctx.response()
                            .setStatusCode(response.statusCode())
                            .putHeader("Content-Type", "application/json");

                    // Apply tracing headers from original request
                    applyTracingHeaders(ctx);

                    // Copy response headers from provider
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

                    // Convert response format if needed (e.g., OpenAI -> Anthropic)
                    if (responseBody != null && convertResponseTo != null && response.statusCode() < 400) {
                        if (convertResponseTo == ProviderConfig.Type.ANTHROPIC &&
                            route.getProvider().getType() == ProviderConfig.Type.OPENAI) {
                            logger.debug("Converting response from OpenAI to Anthropic format");
                            responseBody = formatConverter.openAIToAnthropicResponse(responseBody, originalModel);
                        }
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
     * Implements streaming by forwarding chunks from the provider.
     * Handles format conversion for cross-API streaming (e.g., OpenAI SSE -> Anthropic SSE).
     */
    private Future<Void> handleStreamingRequestRealTime(RoutingContext ctx, HttpRequest<Buffer> request,
                                                        JsonObject body, ProviderConfig.Type convertResponseTo,
                                                        String originalModel) {
        io.vertx.core.Promise<Void> promise = io.vertx.core.Promise.promise();

        request.sendJsonObject(body, ar -> {
            if (ar.failed()) {
                logger.error("Streaming request failed to connect", ar.cause());
                if (!ctx.response().ended()) {
                    ctx.response()
                            .setStatusCode(502)
                            .putHeader("Content-Type", "application/json")
                            .end("{\"error\":{\"message\":\"Failed to connect to upstream: " +
                                    ar.cause().getMessage().replace("\"", "'") + "\",\"type\":\"proxy_error\"}}");
                }
                promise.complete();
                return;
            }

            HttpResponse<Buffer> response = ar.result();
            int statusCode = response.statusCode();
            Buffer responseBody = response.body();

            logger.debug("Upstream response: status={}, bodyLength={}", statusCode,
                    responseBody != null ? responseBody.length() : 0);

            // Set response status code from upstream
            ctx.response().setStatusCode(statusCode);

            // Apply tracing headers from original request
            applyTracingHeaders(ctx);

            // Handle error responses
            if (statusCode >= 400) {
                // Copy error response headers
                response.headers().forEach(entry -> {
                    String key = entry.getKey().toLowerCase();
                    if (!HOP_BY_HOP_HEADERS.contains(key)) {
                        ctx.response().putHeader(entry.getKey(), entry.getValue());
                    }
                });

                if (responseBody != null && responseBody.length() > 0) {
                    ctx.response().end(responseBody);
                } else {
                    ctx.response().end();
                }

                logger.error("Streaming request failed with status: {} - {}",
                        statusCode, responseBody != null ? responseBody.toString() : "no body");
                promise.complete();
                return;
            }

            // Success - set up streaming response
            ctx.response().setChunked(true);
            ctx.response().putHeader("X-Accel-Buffering", "no");

            // Convert streaming response if needed
            if (convertResponseTo == ProviderConfig.Type.ANTHROPIC && responseBody != null) {
                // Convert OpenAI SSE to Anthropic SSE format
                ctx.response().putHeader("Content-Type", "text/event-stream");
                String convertedStream = convertOpenAIStreamToAnthropic(responseBody.toString(), originalModel);
                ctx.response().end(convertedStream);
            } else {
                // No conversion needed - forward as-is
                response.headers().forEach(entry -> {
                    String key = entry.getKey().toLowerCase();
                    if (!HOP_BY_HOP_HEADERS.contains(key)) {
                        ctx.response().putHeader(entry.getKey(), entry.getValue());
                    }
                });

                if (responseBody != null && responseBody.length() > 0) {
                    ctx.response().end(responseBody);
                } else {
                    ctx.response().end();
                }
            }

            logger.debug("Streaming response completed successfully");
            promise.complete();
        });

        return promise.future();
    }

    /**
     * Converts an OpenAI SSE stream to Anthropic SSE format.
     */
    private String convertOpenAIStreamToAnthropic(String openaiStream, String originalModel) {
        StringBuilder result = new StringBuilder();
        FormatConverter.StreamingState state = new FormatConverter.StreamingState();

        // Parse SSE events
        String[] lines = openaiStream.split("\n");
        for (String line : lines) {
            if (line.startsWith("data: ")) {
                String data = line.substring(6).trim();
                if (!data.isEmpty()) {
                    String converted = formatConverter.openAIStreamChunkToAnthropic(data, originalModel, state);
                    result.append(converted);
                }
            }
        }

        return result.toString();
    }
}
