package com.llmproxy;

import com.llmproxy.config.ConfigLoader;
import com.llmproxy.config.ProxyConfig;
import com.llmproxy.server.ProxyServer;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String DEFAULT_CONFIG_PATH = "config.json";

    public static void main(String[] args) {
        logger.info("Starting LLM Proxy Service...");

        String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG_PATH;

        try {
            // Load configuration
            ConfigLoader configLoader = new ConfigLoader();
            ProxyConfig config = configLoader.loadConfig(configPath);

            // Create Vert.x instance
            VertxOptions options = new VertxOptions()
                    .setEventLoopPoolSize(Runtime.getRuntime().availableProcessors() * 2);
            Vertx vertx = Vertx.vertx(options);

            // Create and start the proxy server
            ProxyServer server = new ProxyServer(vertx, config);
            server.start()
                    .onSuccess(v -> {
                        logger.info("LLM Proxy Service started successfully");
                        logger.info("Listening on {}:{}", config.getServer().getHost(), config.getServer().getPort());
                    })
                    .onFailure(throwable -> {
                        logger.error("Failed to start LLM Proxy Service", throwable);
                        System.exit(1);
                    });

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down LLM Proxy Service...");
                vertx.close();
            }));

        } catch (IOException e) {
            logger.error("Failed to load configuration", e);
            System.exit(1);
        } catch (Exception e) {
            logger.error("Unexpected error during startup", e);
            System.exit(1);
        }
    }
}
