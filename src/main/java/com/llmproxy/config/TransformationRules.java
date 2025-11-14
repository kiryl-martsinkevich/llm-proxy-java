package com.llmproxy.config;

import java.util.ArrayList;
import java.util.List;

public class TransformationRules {
    private List<RegexReplacement> regexReplacements = new ArrayList<>();
    private List<JsonPathOperation> jsonPathOps = new ArrayList<>();

    public List<RegexReplacement> getRegexReplacements() {
        return regexReplacements;
    }

    public void setRegexReplacements(List<RegexReplacement> regexReplacements) {
        this.regexReplacements = regexReplacements;
    }

    public List<JsonPathOperation> getJsonPathOps() {
        return jsonPathOps;
    }

    public void setJsonPathOps(List<JsonPathOperation> jsonPathOps) {
        this.jsonPathOps = jsonPathOps;
    }
}
