package com.llmproxy.config;

public class LoggingConfig {
    public enum Level {
        OFF, HEADERS_ONLY, FULL
    }

    private Level level = Level.HEADERS_ONLY;
    private boolean logHeaders = true;
    private boolean logBodies = false;

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public boolean isLogHeaders() {
        return logHeaders;
    }

    public void setLogHeaders(boolean logHeaders) {
        this.logHeaders = logHeaders;
    }

    public boolean isLogBodies() {
        return logBodies;
    }

    public void setLogBodies(boolean logBodies) {
        this.logBodies = logBodies;
    }
}
