package com.llmproxy.config;

public class RouteConfig {
    private String incomingModel;
    private ProviderConfig provider;
    private HeaderConfig headers = new HeaderConfig();
    private TransformationConfig transformations = new TransformationConfig();
    private ClientConfig client = new ClientConfig();
    private LoggingConfig logging;

    public String getIncomingModel() {
        return incomingModel;
    }

    public void setIncomingModel(String incomingModel) {
        this.incomingModel = incomingModel;
    }

    public ProviderConfig getProvider() {
        return provider;
    }

    public void setProvider(ProviderConfig provider) {
        this.provider = provider;
    }

    public HeaderConfig getHeaders() {
        return headers;
    }

    public void setHeaders(HeaderConfig headers) {
        this.headers = headers;
    }

    public TransformationConfig getTransformations() {
        return transformations;
    }

    public void setTransformations(TransformationConfig transformations) {
        this.transformations = transformations;
    }

    public ClientConfig getClient() {
        return client;
    }

    public void setClient(ClientConfig client) {
        this.client = client;
    }

    public LoggingConfig getLogging() {
        return logging;
    }

    public void setLogging(LoggingConfig logging) {
        this.logging = logging;
    }
}
