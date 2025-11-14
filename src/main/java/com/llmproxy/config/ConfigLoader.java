package com.llmproxy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)}");
    private final ObjectMapper objectMapper;

    public ConfigLoader() {
        this.objectMapper = new ObjectMapper();
    }

    public ProxyConfig loadConfig(String configPath) throws IOException {
        logger.info("Loading configuration from: {}", configPath);

        File configFile = new File(configPath);
        if (!configFile.exists()) {
            throw new IOException("Configuration file not found: " + configPath);
        }

        String jsonContent = java.nio.file.Files.readString(configFile.toPath());
        jsonContent = substituteEnvVars(jsonContent);

        ProxyConfig config = objectMapper.readValue(jsonContent, ProxyConfig.class);
        validateConfig(config);

        logger.info("Configuration loaded successfully with {} routes", config.getRoutes().size());
        return config;
    }

    private String substituteEnvVars(String content) {
        Matcher matcher = ENV_VAR_PATTERN.matcher(content);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = System.getenv(varName);
            if (value != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
                logger.debug("Substituted environment variable: {}", varName);
            } else {
                logger.warn("Environment variable not found: {}", varName);
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void validateConfig(ProxyConfig config) {
        if (config.getRoutes().isEmpty()) {
            throw new IllegalStateException("Configuration must have at least one route");
        }

        for (RouteConfig route : config.getRoutes()) {
            if (route.getIncomingModel() == null || route.getIncomingModel().isBlank()) {
                throw new IllegalStateException("Route must have an incomingModel");
            }
            if (route.getProvider() == null) {
                throw new IllegalStateException("Route must have a provider configuration");
            }
            if (route.getProvider().getType() == null) {
                throw new IllegalStateException("Provider must have a type");
            }
            if (route.getProvider().getBaseUrl() == null || route.getProvider().getBaseUrl().isBlank()) {
                throw new IllegalStateException("Provider must have a baseUrl");
            }
        }

        logger.info("Configuration validation passed");
    }
}
