package com.llmproxy.server;

import com.llmproxy.config.ProxyConfig;
import com.llmproxy.server.handlers.OpenAIHandler;
import com.llmproxy.server.handlers.AnthropicHandler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.TimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyServer {
    private static final Logger logger = LoggerFactory.getLogger(ProxyServer.class);

    private final Vertx vertx;
    private final ProxyConfig config;
    private HttpServer server;

    public ProxyServer(Vertx vertx, ProxyConfig config) {
        this.vertx = vertx;
        this.config = config;
    }

    public Future<Void> start() {
        Router router = createRouter();

        HttpServerOptions options = new HttpServerOptions()
                .setCompressionSupported(true)
                .setHandle100ContinueAutomatically(true);

        server = vertx.createHttpServer(options);

        return server.requestHandler(router)
                .listen(config.getServer().getPort(), config.getServer().getHost())
                .map(httpServer -> {
                    logger.info("HTTP server listening on {}:{}",
                            config.getServer().getHost(),
                            config.getServer().getPort());
                    return null;
                });
    }

    private Router createRouter() {
        Router router = Router.router(vertx);

        // Global handlers
        router.route().handler(BodyHandler.create());
        router.route().handler(TimeoutHandler.create(120000)); // 2 minutes timeout

        // Request logging
        router.route().handler(ctx -> {
            logger.info("Incoming request: {} {}", ctx.request().method(), ctx.request().uri());
            ctx.next();
        });

        // Health check endpoint
        router.get("/health").handler(ctx -> {
            ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end("{\"status\":\"ok\"}");
        });

        // OpenAI compatible endpoint
        OpenAIHandler openAIHandler = new OpenAIHandler(vertx, config);
        router.post("/v1/chat/completions").handler(openAIHandler::handle);

        // Anthropic compatible endpoint
        AnthropicHandler anthropicHandler = new AnthropicHandler(vertx, config);
        router.post("/v1/messages").handler(anthropicHandler::handle);

        // Error handler
        router.errorHandler(500, ctx -> {
            logger.error("Internal server error", ctx.failure());
            ctx.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "application/json")
                    .end("{\"error\":\"Internal server error\"}");
        });

        return router;
    }

    public Future<Void> stop() {
        if (server != null) {
            return server.close();
        }
        return Future.succeededFuture();
    }
}
