package com.llmproxy.server.handlers;

import com.llmproxy.client.ProxyClient;
import com.llmproxy.config.ProxyConfig;
import com.llmproxy.config.RouteConfig;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnthropicHandler {
    private static final Logger logger = LoggerFactory.getLogger(AnthropicHandler.class);

    private final Vertx vertx;
    private final ProxyConfig config;
    private final ProxyClient client;

    public AnthropicHandler(Vertx vertx, ProxyConfig config) {
        this.vertx = vertx;
        this.config = config;
        this.client = new ProxyClient(vertx);
    }

    public void handle(RoutingContext ctx) {
        try {
            // Parse request body
            JsonObject requestBody = ctx.body().asJsonObject();
            if (requestBody == null) {
                sendError(ctx, 400, "Invalid JSON body");
                return;
            }

            // Extract model name
            String modelName = requestBody.getString("model");
            if (modelName == null || modelName.isBlank()) {
                sendError(ctx, 400, "Missing 'model' field in request");
                return;
            }

            logger.info("Request for model: {}", modelName);

            // Find route configuration
            RouteConfig route = config.findRouteByModel(modelName);
            if (route == null) {
                sendError(ctx, 404, "Model not found: " + modelName);
                return;
            }

            // Check if streaming is requested
            Boolean stream = requestBody.getBoolean("stream", false);

            // Forward request to provider
            client.forwardRequest(ctx, requestBody, route, stream)
                    .onSuccess(v -> logger.info("Request completed successfully"))
                    .onFailure(err -> {
                        logger.error("Request failed", err);
                        if (!ctx.response().ended()) {
                            sendError(ctx, 500, "Failed to process request: " + err.getMessage());
                        }
                    });

        } catch (Exception e) {
            logger.error("Error handling request", e);
            sendError(ctx, 500, "Internal server error");
        }
    }

    private void sendError(RoutingContext ctx, int statusCode, String message) {
        if (!ctx.response().ended()) {
            ctx.response()
                    .setStatusCode(statusCode)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                            .put("error", new JsonObject()
                                    .put("message", message)
                                    .put("type", "invalid_request_error"))
                            .encode());
        }
    }
}
