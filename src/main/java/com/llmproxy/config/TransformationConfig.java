package com.llmproxy.config;

public class TransformationConfig {
    private TransformationRules request = new TransformationRules();
    private TransformationRules response = new TransformationRules();

    public TransformationRules getRequest() {
        return request;
    }

    public void setRequest(TransformationRules request) {
        this.request = request;
    }

    public TransformationRules getResponse() {
        return response;
    }

    public void setResponse(TransformationRules response) {
        this.response = response;
    }
}
