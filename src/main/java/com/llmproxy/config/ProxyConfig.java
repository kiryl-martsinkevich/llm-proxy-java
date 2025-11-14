package com.llmproxy.config;

import java.util.ArrayList;
import java.util.List;

public class ProxyConfig {
    private ServerConfig server = new ServerConfig();
    private LoggingConfig logging = new LoggingConfig();
    private List<RouteConfig> routes = new ArrayList<>();

    public ServerConfig getServer() {
        return server;
    }

    public void setServer(ServerConfig server) {
        this.server = server;
    }

    public LoggingConfig getLogging() {
        return logging;
    }

    public void setLogging(LoggingConfig logging) {
        this.logging = logging;
    }

    public List<RouteConfig> getRoutes() {
        return routes;
    }

    public void setRoutes(List<RouteConfig> routes) {
        this.routes = routes;
    }

    public RouteConfig findRouteByModel(String modelName) {
        return routes.stream()
                .filter(r -> r.getIncomingModel().equals(modelName))
                .findFirst()
                .orElse(null);
    }
}
